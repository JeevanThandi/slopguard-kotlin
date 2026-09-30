package dev.slopguard.core.mutation

import dev.slopguard.core.errors.SlopguardError

/**
 * Mutation operator ids. Every slopguard port uses the same ids — in
 * `--operators`, in the JSON `operator` field and in ignore markers — so keep
 * them in step with the siblings.
 *
 * - `arithmetic` — `+`↔`-`, `*`↔`/`, `%`→`*` and the compound assignments.
 * - `boolean_literal` — `true`↔`false`.
 * - `boundary` — `<`↔`<=`, `>`↔`>=`.
 * - `increment` — `++`↔`--`.
 * - `invert_negative` — `-x` → `x`.
 * - `logical` — `&&`↔`||`.
 * - `negate_conditional` — `==`↔`!=`, `===`↔`!==`, `<`→`>=`, `<=`→`>`, `>`→`<=`, `>=`→`<`.
 * - `remove_call` — drop a call statement whose result is discarded.
 * - `remove_not` — `!x` → `x`.
 */
enum class MutationOperator(val wire: String) {
    ARITHMETIC("arithmetic"),
    BOOLEAN_LITERAL("boolean_literal"),
    BOUNDARY("boundary"),
    INCREMENT("increment"),
    INVERT_NEGATIVE("invert_negative"),
    LOGICAL("logical"),
    NEGATE_CONDITIONAL("negate_conditional"),
    REMOVE_CALL("remove_call"),
    REMOVE_NOT("remove_not"),
    ;

    companion object {
        /** Every operator, sorted by id. The default when `--operators` is not given. */
        val ALL: List<MutationOperator> = entries.sortedBy { it.wire }

        fun fromWire(id: String): MutationOperator? = entries.firstOrNull { it.wire == id }

        /**
         * Resolve `--operators` values (comma-separated, and the flag may repeat)
         * into a sorted, de-duplicated list. No ids at all means every operator.
         * An unknown id is an `invalid_argument` error.
         */
        fun parseList(values: List<String>): List<MutationOperator> {
            val ids = values.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) return ALL
            val unknown = ids.filter { fromWire(it) == null }
            if (unknown.isNotEmpty()) {
                throw SlopguardError.invalidArgument(
                    "--operators",
                    "unknown operator(s): ${unknown.joinToString(", ")} (expected: ${ALL.joinToString(", ") { it.wire }})",
                )
            }
            return ALL.filter { it.wire in ids }
        }
    }
}
