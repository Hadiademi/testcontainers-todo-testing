# Sistemi i Menaxhimit të Detyrave — Testim me Testcontainers

[![CI](https://github.com/Hadiademi/testcontainers-todo-testing/actions/workflows/maven.yml/badge.svg)](https://github.com/Hadiademi/testcontainers-todo-testing/actions/workflows/maven.yml)

Projekt për lëndën e **Testimit të Softuerit**.
**Tema:** Test Containers — testim i integrimit kundër shërbimeve reale (baza të dhënash, browser) që ngrihen automatikisht në Docker.

**Grupi (2 studentë):** Hadi Ademi & Valton Bekteshi

---

## Çfarë është ky projekt

Një aplikacion **Todo** (listë detyrash) i ndërtuar me **Spring Boot**, që ruan të dhënat në **PostgreSQL** dhe ka një ndërfaqe web të thjeshtë. Aplikacioni vetë është i thjeshtë me qëllim — **fokusi i projektit është mënyra se si testohet**.

Pyetja qendrore që zgjidh Testcontainers:

> *Si i teston aplikacionet që varen nga baza të dhënash ose shërbime të jashtme, pa i instaluar ato në kompjuter, dhe duke u siguruar që testet xhirojnë kundër shërbimeve **reale**, jo imitime (mock)?*

Testcontainers ngre **shërbime reale brenda Docker containers** vetëm sa zgjasin testet, dhe i fshin automatikisht më pas.

## Teknologjitë

| Shtresa | Teknologjia |
|--------|-------------|
| Gjuha | Java 17 |
| Framework | Spring Boot 3.1 (Web, Data JPA, Validation) |
| Baza e të dhënave | PostgreSQL 15 |
| Migrime | Flyway |
| Build | Maven |
| Testim | JUnit 5, Testcontainers, RestAssured, Selenium, Toxiproxy |

## Llojet e testimit në projekt

Projekti demonstron disa nivele testimi, të gjitha me Testcontainers:

| Klasa e testit | Çfarë teston | Lloji |
|----------------|--------------|-------|
| `ApplicationTests` | Konteksti i aplikacionit ngarkohet me një PostgreSQL real (magic JDBC URL) | Smoke |
| `TodoRepositoryTest` | Shtresa e të dhënave (repository) kundër PostgreSQL real | Integration |
| `TodoControllerTests` | Endpoint-et REST `GET/POST/DELETE /todos` përmes RestAssured | Integration / API |
| `SeleniumE2ETests` | Ndërfaqja web me një **browser Chrome real** në container (me regjistrim video) | UI / End‑to‑End |
| `DatabaseResilienceTests` | Sjellja e aplikacionit kur rrjeti drejt bazës prishet | **Recovery / Reliability** |

### 🔌 Recovery & Reliability Testing me Toxiproxy

Pjesa më e avancuar e projektit. Vendosëm një proxy **midis aplikacionit dhe bazës së të dhënave**:

```
Spring Boot app  ──▶  Toxiproxy  ──▶  PostgreSQL
```

[Toxiproxy](https://github.com/Shopify/toxiproxy) na lejon të injektojmë probleme reale rrjeti gjatë testit dhe të verifikojmë se si reagon aplikacioni. Testet provojnë:

- ✅ **Punon normalisht** përmes proxy-t kur gjithçka është në rregull
- 🐌 **Me vonesë rrjeti (latency)** — kërkesa është më e ngadaltë, por rezultati mbetet i saktë
- ❌ **Kur pritet lidhja me bazën** — aplikacioni **dështon shpejt** (jo varet pafund), falë timeout-eve të shkurtra
- 🔄 **Rikuperohet vetvetiu** sapo baza kthehet — pa rinisje, pool-i i lidhjeve i zëvendëson lidhjet e vdekura

Shih: [`DatabaseResilienceTests.java`](src/test/java/com/valtonhadi/todos/DatabaseResilienceTests.java)

## Si ta xhirosh

### Parakushtet
- **Java 17+**
- **Docker** i instaluar dhe duke punuar (Testcontainers e përdor për të ngritur container-at)

### Xhiro të gjitha testet
```shell
./mvnw verify
```

### Nis aplikacionin lokalisht
Aplikacioni mund të nisë me një PostgreSQL që ngrihet automatikisht në një container:
```shell
./mvnw spring-boot:test-run
```
Pastaj hape në shfletues: **http://localhost:8080**

API-ja: `http://localhost:8080/todos`

## CI/CD

Çdo `push` dhe `pull request` nis automatikisht një **GitHub Actions workflow** që ekzekuton të gjithë suitën e testeve (unit + integration + E2E + resilience) në cloud. Testcontainers përdor Docker-in e gatshëm të runner-it — pa nevojë për shërbime me pagesë.

Shih statusin te skeda **[Actions](https://github.com/Hadiademi/testcontainers-todo-testing/actions)** (badge jeshil lart).

## Shënim teknik (Docker i ri)

Versionet e reja të Docker Engine (28+/29) kërkojnë API ≥ 1.44. Klienti që vjen me Testcontainers parazgjedh një version më të vjetër, ndaj te [`pom.xml`](pom.xml) e kemi fiksuar `api.version=1.44` për JVM-në e testeve, që `./mvnw verify` të punojë pa konfigurim shtesë.

## Struktura e projektit

```
src/
├── main/java/com/valtonhadi/todos/
│   ├── entity/Todo.java              # entiteti JPA
│   ├── repository/TodoRepository.java
│   ├── web/TodoController.java       # REST API
│   └── Application.java
├── main/resources/
│   ├── db/migration/                 # migrime Flyway
│   └── public/                       # ndërfaqja web
└── test/java/com/valtonhadi/todos/
    ├── ApplicationTests.java
    ├── TodoControllerTests.java
    ├── repository/TodoRepositoryTest.java
    ├── SeleniumE2ETests.java
    └── DatabaseResilienceTests.java  # testet me Toxiproxy
```
