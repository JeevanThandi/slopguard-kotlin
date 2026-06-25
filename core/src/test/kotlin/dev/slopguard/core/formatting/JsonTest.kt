package dev.slopguard.core.formatting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun keysAreSortedAlphabetically() {
        val json = Json.encode(linkedMapOf("zebra" to 1, "alpha" to 2, "mango" to 3))
        val alpha = json.indexOf("alpha")
        val mango = json.indexOf("mango")
        val zebra = json.indexOf("zebra")
        assertTrue(alpha < mango && mango < zebra)
    }

    @Test
    fun wholeNumberDoublesDropDecimal() {
        assertEquals("30", Json.encode(30.0))
        assertEquals("0", Json.encode(0.0))
    }

    @Test
    fun fractionalDoublesKeepPrecision() {
        assertTrue(Json.encode(22.5).startsWith("22.5"))
    }

    @Test
    fun escapesStrings() {
        assertEquals("\"a\\\"b\\nc\"", Json.encode("a\"b\nc"))
    }

    @Test
    fun emptyCollections() {
        assertEquals("{}", Json.encode(emptyMap<String, Any?>()))
        assertEquals("[]", Json.encode(emptyList<Any?>()))
    }

    @Test
    fun nullsAndNesting() {
        val json = Json.encode(mapOf("a" to null, "b" to listOf(1, 2)))
        assertTrue(json.contains("\"a\": null"))
        assertTrue(json.contains("\"b\": ["))
    }
}
