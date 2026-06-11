package com.valtonhadi.todos.repository;

import com.valtonhadi.todos.entity.Todo;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;

import java.util.List;

public interface TodoRepository extends ListCrudRepository<Todo, String>, ListPagingAndSortingRepository<Todo, String> {
    @Query("select t from Todo t where t.completed = false")
    List<Todo> getPendingTodos();
}
