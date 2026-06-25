package com.example.todo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoTest {
    @Test
    fun togglesCompletion() {
        val todo = Todo(id = 1, title = "x")
        assertFalse(todo.completed)
        assertTrue(todo.toggled().completed)
    }

    @Test
    fun filterMatching() {
        val active = Todo(id = 1, title = "a", completed = false)
        val done = Todo(id = 2, title = "b", completed = true)
        assertTrue(TodoFilter.ALL.matches(active))
        assertTrue(TodoFilter.ALL.matches(done))
        assertTrue(TodoFilter.ACTIVE.matches(active))
        assertFalse(TodoFilter.ACTIVE.matches(done))
        assertTrue(TodoFilter.COMPLETED.matches(done))
        assertFalse(TodoFilter.COMPLETED.matches(active))
        assertEquals(3, TodoFilter.entries.size)
    }
}
