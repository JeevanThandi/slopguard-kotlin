package dev.slopguard.core.analysis

/**
 * fnmatch-style glob matching, matching the sibling ports: a star matches across
 * path separators (so a double-star segment and a single-star segment behave the
 * same), `?` matches a single character, and `[...]` is a character class. There
 * is no special PATHNAME handling.
 */
object Glob {
    fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    // collapse runs of '*' (handles '**') into a single ".*"
                    while (i + 1 < glob.length && glob[i + 1] == '*') i++
                    sb.append(".*")
                }
                '?' -> sb.append('.')
                '[' -> {
                    val close = glob.indexOf(']', i + 1)
                    if (close < 0) {
                        sb.append("\\[")
                    } else {
                        sb.append(glob.substring(i, close + 1))
                        i = close
                    }
                }
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '\\' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    /** True if [path] (forward-slash separated) matches any of [globs]. */
    fun matchesAny(globs: List<String>, path: String): Boolean {
        if (globs.isEmpty()) return false
        return globs.any { glob ->
            val re = toRegex(glob)
            re.matches(path) || re.matches("/$path")
        }
    }
}
