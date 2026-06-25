package com.example.todo

/** A single todo item. */
data class Todo(
    val id: Int,
    val title: String,
    val completed: Boolean = false,
) {
    fun toggled(): Todo = copy(completed = !completed)
}

enum class TodoFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    ;

    fun matches(todo: Todo): Boolean = when (this) {
        ALL -> true
        ACTIVE -> !todo.completed
        COMPLETED -> todo.completed
    }
}
