package com.example.todo

/** A tiny in-memory todo store. Deliberately simple and fully tested. */
class TodoStore {
    private val items = mutableListOf<Todo>()
    private var nextId = 1

    fun add(title: String): Todo {
        val todo = Todo(id = nextId, title = title)
        nextId++
        items.add(todo)
        return todo
    }

    fun remove(id: Int): Boolean = items.removeIf { it.id == id }

    fun toggle(id: Int): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].toggled()
        return true
    }

    fun filtered(filter: TodoFilter): List<Todo> = items.filter { filter.matches(it) }

    fun clearCompleted(): Int {
        val before = items.size
        items.removeIf { it.completed }
        return before - items.size
    }

    fun all(): List<Todo> = items.toList()

    val activeCount: Int
        get() = items.count { !it.completed }
}
