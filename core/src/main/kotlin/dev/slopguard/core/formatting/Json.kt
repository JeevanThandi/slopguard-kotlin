package dev.slopguard.core.formatting

/**
 * A tiny, dependency-free JSON writer that emits **alphabetically sorted keys**
 * and pretty-prints with two-space indentation — matching the diff-stable,
 * sorted-key output of the sibling ports (Swift's `.sortedKeys`, Go's
 * alphabetical struct fields, TypeScript's stable stringify). Whole-number
 * doubles render without a trailing `.0`.
 */
object Json {
    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb, 0)
        return sb.toString()
    }

    private fun write(value: Any?, sb: StringBuilder, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value.toString())
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(formatDouble(value))
            is Float -> sb.append(formatDouble(value.toDouble()))
            is Map<*, *> -> writeObject(value, sb, indent)
            is List<*> -> writeArray(value, sb, indent)
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeObject(map: Map<*, *>, sb: StringBuilder, indent: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        val keys = map.keys.map { it.toString() }.sorted()
        sb.append("{\n")
        val childIndent = indent + 1
        keys.forEachIndexed { i, key ->
            pad(sb, childIndent)
            writeString(key, sb)
            sb.append(": ")
            write(map[key], sb, childIndent)
            if (i < keys.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent)
        sb.append('}')
    }

    private fun writeArray(list: List<*>, sb: StringBuilder, indent: Int) {
        if (list.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append("[\n")
        val childIndent = indent + 1
        list.forEachIndexed { i, item ->
            pad(sb, childIndent)
            write(item, sb, childIndent)
            if (i < list.size - 1) sb.append(',')
            sb.append('\n')
        }
        pad(sb, indent)
        sb.append(']')
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        if (d == d.toLong().toDouble()) return d.toLong().toString()
        return d.toString()
    }

    private fun pad(sb: StringBuilder, indent: Int) {
        repeat(indent) { sb.append("  ") }
    }
}
