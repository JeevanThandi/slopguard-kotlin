package com.example.todo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoStoreTest {
    @Test
    fun addsAndLists() {
        val store = TodoStore()
        val a = store.add("write tests")
        val b = store.add("ship it")
        assertEquals(1, a.id)
        assertEquals(2, b.id)
        assertEquals(2, store.all().size)
        assertEquals(2, store.activeCount)
    }

    @Test
    fun togglesAndFilters() {
        val store = TodoStore()
        store.add("a")
        val b = store.add("b")
        assertTrue(store.toggle(b.id))
        assertFalse(store.toggle(999))
        assertEquals(1, store.filtered(TodoFilter.ACTIVE).size)
        assertEquals(1, store.filtered(TodoFilter.COMPLETED).size)
        assertEquals(2, store.filtered(TodoFilter.ALL).size)
        assertEquals(1, store.activeCount)
    }

    @Test
    fun removesAndClears() {
        val store = TodoStore()
        val a = store.add("a")
        val b = store.add("b")
        store.toggle(b.id)
        assertTrue(store.remove(a.id))
        assertFalse(store.remove(a.id))
        store.add("c")
        store.toggle(store.all().first { it.title == "c" }.id)
        assertEquals(2, store.clearCompleted())
        assertEquals(0, store.all().size)
    }
}
