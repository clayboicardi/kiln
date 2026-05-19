// FTS5 query sanitization per schema sketch §4 + scaffold prep §3.2.
// User-typed query strings → safe FTS5 MATCH expressions with type-ahead
// prefix matching.

package com.clayworks.kiln.library.source.internal

private val FTS5_OPERATORS = setOf('"', '(', ')', '*', ':', '-', '+', '^', '~')

/**
 * Sanitize a user-typed query for SQLite FTS5 MATCH.
 *
 * - Strip operator chars that would change FTS5 semantics
 * - Wrap whitespace-split tokens in quotes
 * - Append `*` to the last token for type-ahead prefix matching
 *
 * Returns `""` (empty quoted string) for blank input, which FTS5 treats
 * as a no-match query.
 */
internal fun sanitizeFtsQuery(raw: String): String {
    val cleaned = raw.filter { it !in FTS5_OPERATORS }
    val tokens = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "\"\""
    val quoted = tokens.dropLast(1).map { "\"$it\"" }
    val last = "\"${tokens.last()}\"*"
    return (quoted + last).joinToString(" ")
}
