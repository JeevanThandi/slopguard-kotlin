package dev.slopguard.core.mutation

/**
 * Maps offsets in the parsed text back to the file's original text. The parser
 * sees `\n` line separators only (see `KotlinParser.normalizeLineSeparators`):
 * every `\r\n` lost its `\r`, and a lone `\r` became `\n` one-for-one.
 */
internal class OffsetMap(original: String) {
    /** Parsed-text positions of the `\n` that followed each dropped `\r`, ascending. */
    private val dropped: IntArray

    init {
        val positions = ArrayList<Int>()
        for (i in 0 until original.length - 1) {
            if (original[i] == '\r' && original[i + 1] == '\n') positions.add(i - positions.size)
        }
        dropped = positions.toIntArray()
    }

    /**
     * The original offset of parsed-text [offset]. A span that ends right before
     * a line break maps to an end right before the original `\r`.
     */
    fun toOriginal(offset: Int): Int {
        var lo = 0
        var hi = dropped.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dropped[mid] < offset) lo = mid + 1 else hi = mid
        }
        return offset + lo
    }
}
