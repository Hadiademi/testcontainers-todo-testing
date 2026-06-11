package com.valtonhadi.todos;

import com.valtonhadi.todos.repository.TodoRepository;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Recovery / Reliability testing with Testcontainers + Toxiproxy.
 *
 * <p>Normally the application talks to PostgreSQL directly. Here we insert a
 * Toxiproxy container <em>between</em> the app and the database:
 *
 * <pre>
 *     Spring Boot app  ->  Toxiproxy  ->  PostgreSQL
 * </pre>
 *
 * Toxiproxy lets us simulate real-world network problems on demand (latency,
 * the database becoming unreachable, etc.) and then assert how the application
 * behaves: does it fail fast instead of hanging forever, and does it recover
 * automatically once the database is reachable again?
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DatabaseResilienceTests {

    @LocalServerPort
    private Integer port;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private DataSource dataSource;

    // A shared Docker network so the app's traffic can be routed
    // postgres <- toxiproxy <- app, with the containers talking to each other.
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    // The proxy we control during the tests (enable/disable, add latency, ...).
    static Proxy dbProxy;

    @DynamicPropertySource
    static void configureDatasourceThroughProxy(DynamicPropertyRegistry registry) throws IOException {
        // Create a proxy that listens inside the toxiproxy container on 8666
        // and forwards to the postgres container on 5432.
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        dbProxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");

        // Point Spring's datasource at the proxy instead of postgres directly.
        String jdbcUrl = "jdbc:postgresql://" + toxiproxy.getHost() + ":"
                + toxiproxy.getMappedPort(8666) + "/" + postgres.getDatabaseName();
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Keep timeouts short so a "database down" situation surfaces quickly
        // instead of the default 30s — this is what lets the app fail fast.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
    }

    @BeforeEach
    void resetToHealthyState() throws IOException {
        // Make sure every test starts with a healthy proxy and a clean table,
        // regardless of what a previous test did to the connection.
        dbProxy.enable();
        for (Toxic toxic : dbProxy.toxics().getAll()) {
            toxic.remove();
        }
        // A previous test may have killed the pooled connections by disabling
        // the proxy. Evict them so the pool hands out fresh, working ones.
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
        deleteAllWithRetry(Duration.ofSeconds(10));
        RestAssured.baseURI = "http://localhost:" + port;
    }

    @Test
    void todoApiWorksNormallyThroughTheProxy() {
        // Baseline: with the proxy healthy, the app behaves exactly as normal.
        String id = createTodo("Buy milk");

        given().contentType(ContentType.JSON)
                .when().get("/todos/{id}", id)
                .then().statusCode(200)
                .body("title", org.hamcrest.Matchers.is("Buy milk"));
    }

    @Test
    void addedNetworkLatencyStillReturnsTheCorrectResult() throws IOException {
        // Inject 1 second of latency on every packet from the database.
        dbProxy.toxics().latency("slow-db", ToxicDirection.DOWNSTREAM, 1000);

        // The request is slower, but the data must still be correct.
        String id = createTodo("Slow but correct");
        given().contentType(ContentType.JSON)
                .when().get("/todos/{id}", id)
                .then().statusCode(200)
                .body("title", org.hamcrest.Matchers.is("Slow but correct"));
    }

    @Test
    void whenTheDatabaseIsUnreachableTheApiFailsFastInsteadOfHanging() throws IOException {
        // Simulate the database going completely down.
        dbProxy.disable();

        // The call must come back with a server error within a few seconds
        // (thanks to the short Hikari timeout) rather than hanging forever.
        assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                given().contentType(ContentType.JSON)
                        .when().get("/todos")
                        .then().statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(500)));
    }

    @Test
    void theApiRecoversAutomaticallyOnceTheDatabaseIsBack() throws IOException, InterruptedException {
        // 1) Database goes down -> requests fail.
        dbProxy.disable();
        given().contentType(ContentType.JSON)
                .when().get("/todos")
                .then().statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(500));

        // 2) Database comes back -> the app must recover on its own, without a
        //    restart. The connection pool replaces the dead connections.
        dbProxy.enable();

        int status = getTodosStatusWithRetry(Duration.ofSeconds(20));
        assertThat(status).isEqualTo(200);
    }

    // --- helpers -----------------------------------------------------------

    private String createTodo(String title) {
        return given().contentType(ContentType.JSON)
                .body("{\"title\":\"" + title + "\",\"completed\":false,\"order\":1}")
                .when().post("/todos")
                .then().statusCode(201)
                .extract().path("id");
    }

    /** Retries deleteAll() until it succeeds, draining any dead connections. */
    private void deleteAllWithRetry(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                todoRepository.deleteAll();
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }

    /** Polls GET /todos until it returns 200 or the timeout elapses. */
    private int getTodosStatusWithRetry(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int status = -1;
        while (System.nanoTime() < deadline) {
            status = given().contentType(ContentType.JSON).when().get("/todos").getStatusCode();
            if (status == 200) {
                return status;
            }
            Thread.sleep(500);
        }
        return status;
    }
}
