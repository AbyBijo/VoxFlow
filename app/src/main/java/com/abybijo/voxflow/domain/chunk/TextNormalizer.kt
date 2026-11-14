package com.abybijo.voxflow.domain.chunk

/**
 * Light text normalisation before chunking (architecture §5).
 *
 * S1 scope is deliberately small: line endings, quotes, dashes and
 * whitespace. Numbers, currency, dates and URLs are NOT expanded here —
 * Kokoro's Misaki G2P reads them, and a wrong expansion is worse than
 * none. A fuller normaliser (number/currency/date rules) is a later
 * refinement, added per-rule with tests, not by guessing.
 *
 * The normalised text is the source of truth for chunk offsets: every
 * chunk is an exact substring of it.
 */
object TextNormalizer {

    fun normalize(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        // Curly quotes/dashes to their plain forms (G2P handles plain best).
        s = s
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace("…", "...")
        // One space around em/en dashes so they read as pauses, not joins.
        s = s.replace("—", " — ").replace("–", " – ")
        // Collapse horizontal whitespace per line, trim lines, drop empty
        // lines beyond paragraph breaks (3+ newlines -> exactly 2).
        val lines = s.split('\n')
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        s = lines.joinToString("\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }
}
