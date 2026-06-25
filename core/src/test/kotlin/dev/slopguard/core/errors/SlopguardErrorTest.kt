package dev.slopguard.core.errors

import kotlin.test.Test
import kotlin.test.assertEquals

class SlopguardErrorTest {
    @Test
    fun envelopeCarriesStableCode() {
        val e = SlopguardError.fileNotFound("/x")
        val env = envelopeFor(e)
        assertEquals("file_not_found", env.code)
        assertEquals("File not found: /x", env.message)
    }

    @Test
    fun nonSlopguardErrorBecomesInternal() {
        val env = envelopeFor(IllegalStateException("boom"))
        assertEquals("internal_error", env.code)
        assertEquals("boom", env.message)
    }

    @Test
    fun invalidArgumentCode() {
        assertEquals("invalid_argument", envelopeFor(SlopguardError.invalidArgument("--threshold", "nan")).code)
    }

    @Test
    fun toStringIncludesCode() {
        assertEquals("[unsupported] nope", SlopguardError.unsupported("nope").toString())
    }
}
