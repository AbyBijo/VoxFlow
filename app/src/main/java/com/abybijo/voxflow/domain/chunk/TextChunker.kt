package com.abybijo.voxflow.domain.chunk

/**
 * Chunking (architecture §5). Pure Kotlin, fully unit-tested.
 *
 * Priority order, strict:
 *   1. Paragraph — never merge across a paragraph if it would exceed target.
 *   2. Sentence — abbreviation/decimal/ellipsis-aware. A naive split on
 *      "." is explicitly forbidden and covered by tests.
 *   3. Clause — commas, semicolons, colons, em dashes, conjunctions.
 *   4. Word — last resort only. Never split inside a word, except the
 *      pathological single-word-longer-than-the-cap case, where the word
 *      itself is hard-split at the cap (the only way to honour it).
 *
 * Sizes: soft target 180–280 characters, hard cap 400.
 * Every chunk is an exact substring of the normalised text, so [charOffset]
 * is exact and resume/highlighting can address chunks by id.
 */
object TextChunker {

    const val TARGET_MIN = 180
    const val TARGET_MAX = 280
    const val HARD_CAP = 400

    /** The pause the player inserts after this chunk. */
    enum class Boundary { SENTENCE, CLAUSE, PARAGRAPH, NONE }

    data class Chunk(
        val id: Int,
        val text: String,
        val charOffset: Int,
        val paragraphIndex: Int,
        val boundaryAfter: Boundary,
    )

    fun chunk(normalizedText: String): List<Chunk> {
        if (normalizedText.isBlank()) return emptyList()
        val paragraphs = paragraphSpans(normalizedText)
        val chunks = mutableListOf<Chunk>()
        for ((pIndex, pSpan) in paragraphs.withIndex()) {
            val sentences = sentenceSpans(normalizedText, pSpan.first, pSpan.second)
            val pieces = if (sentences.isEmpty()) {
                listOf(pSpan.first to pSpan.second)
            } else {
                sentences
            }
            packPieces(normalizedText, pieces, pIndex, chunks)
        }
        // The final chunk ends the text: no trailing pause.
        if (chunks.isNotEmpty()) {
            val last = chunks.last()
            chunks[chunks.lastIndex] = last.copy(boundaryAfter = Boundary.NONE)
        }
        return chunks
    }

    // -- paragraph spans -------------------------------------------------
    // A paragraph break is a blank line (\n\n). Spans exclude separators.

    private fun paragraphSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
                if (i > start) spans.add(start to i)
                i += 2
                while (i < text.length && text[i] == '\n') i++
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) spans.add(start to text.length)
        return spans
    }

    // -- sentence spans --------------------------------------------------

    private val ABBREVIATIONS = setOf(
        "dr", "mr", "mrs", "ms", "st", "vs", "e.g", "i.e", "etc",
        "fig", "no", "vol", "pp", "capt", "lt", "col", "gen",
        "sr", "jr", "u.s", "u.k", "a.m", "p.m",
    )

    /**
     * Sentence end = '.', '!' or '?' (or "..." as one terminator) followed
     * by end-of-text, or by whitespace + a capital / opening quote.
     * Not a boundary when the token before it is an abbreviation, a single
     * initial ("J. R. R. Tolkien"), or the dot sits inside a decimal.
     */
    private fun sentenceSpans(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = from
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                // Ellipsis: consume the whole run as one terminator.
                var end = i
                if (c == '.') {
                    while (end + 1 < to && text[end + 1] == '.') end++
                }
                if (isSentenceEnd(text, from, to, start, end)) {
                    // Include trailing closing quotes/parens in the sentence.
                    var close = end + 1
                    while (close < to && (text[close] == '"' || text[close] == '\'' ||
                            text[close] == ')' || text[close] == ']' || text[close] == '”')
                    ) close++
                    spans.add(start to close)
                    // Skip following whitespace; next sentence starts after it.
                    var next = close
                    while (next < to && text[next].isWhitespace()) next++
                    start = next
                    i = next
                    continue
                }
                i = end + 1
            } else {
                i++
            }
        }
        if (start < to) spans.add(start to to)
        return spans.filter { (s, e) -> text.substring(s, e).isNotBlank() }
    }

    private fun isSentenceEnd(
        text: String, from: Int, to: Int, sentenceStart: Int, termEnd: Int,
    ): Boolean {
        val c = text[termEnd]
        // Decimal: digit . digit is never a boundary.
        if (c == '.' && termEnd > from && termEnd + 1 < to &&
            text[termEnd - 1].isDigit() && text[termEnd + 1].isDigit()
        ) return false
        // Token before the terminator (letters/dots only).
        var t = termEnd - 1
        while (t >= from && (text[t].isLetter() || text[t] == '.')) t--
        val token = text.substring(t + 1, termEnd).lowercase()
        // Single initial ("J.") is never a boundary.
        if (token.length == 1 && token[0].isLetter()) return false
        if (token in ABBREVIATIONS) return false
        // Look past closing quotes/parens, then require end or
        // whitespace + capital/opening quote.
        var j = termEnd + 1
        while (j < to && (text[j] == '"' || text[j] == '\'' || text[j] == ')' ||
                text[j] == ']' || text[j] == '”')
        ) j++
        if (j >= to) return true
        if (!text[j].isWhitespace()) return false
        var k = j
        while (k < to && text[k].isWhitespace()) k++
        if (k >= to) return true
        val next = text[k]
        return next.isUpperCase() || next == '"' || next == '“' || next == '('
    }

    // -- packing ---------------------------------------------------------

    private fun packPieces(
        text: String,
        pieces: List<Pair<Int, Int>>,
        paragraphIndex: Int,
        out: MutableList<Chunk>,
    ) {
        var currentStart = -1
        var currentEnd = -1
        var currentBoundary = Boundary.SENTENCE

        fun flush(boundary: Boundary) {
            if (currentStart < 0) return
            out.add(
                Chunk(
                    id = out.size,
                    text = text.substring(currentStart, currentEnd),
                    charOffset = currentStart,
                    paragraphIndex = paragraphIndex,
                    boundaryAfter = boundary,
                )
            )
            currentStart = -1
            currentEnd = -1
        }

        for ((s, e) in pieces) {
            if (e - s > HARD_CAP) {
                // Oversized sentence: flush what we hold, then clause-split.
                flush(currentBoundary)
                splitLongSentence(text, s, e, paragraphIndex, out)
                currentBoundary = Boundary.SENTENCE
                continue
            }
            if (currentStart < 0) {
                currentStart = s
                currentEnd = e
                currentBoundary = Boundary.SENTENCE
                continue
            }
            val joinedLen = e - currentStart
            if (joinedLen > TARGET_MAX && currentEnd - currentStart >= TARGET_MIN) {
                flush(currentBoundary)
                currentStart = s
                currentEnd = e
            } else if (joinedLen > HARD_CAP) {
                // Would exceed the cap: flush, start fresh with this piece.
                flush(currentBoundary)
                currentStart = s
                currentEnd = e
            } else {
                currentEnd = e
            }
            currentBoundary = Boundary.SENTENCE
        }
        // Last piece of the paragraph ends at a paragraph boundary.
        if (currentStart >= 0) flush(Boundary.PARAGRAPH)
    }

    /** Split one over-long sentence at clause boundaries, then words. */
    private fun splitLongSentence(
        text: String,
        from: Int,
        to: Int,
        paragraphIndex: Int,
        out: MutableList<Chunk>,
    ) {
        val clauses = clauseSpans(text, from, to)
        var currentStart = -1
        var currentEnd = -1
        fun flush(boundary: Boundary) {
            if (currentStart < 0) return
            out.add(
                Chunk(
                    id = out.size,
                    text = text.substring(currentStart, currentEnd),
                    charOffset = currentStart,
                    paragraphIndex = paragraphIndex,
                    boundaryAfter = boundary,
                )
            )
            currentStart = -1
            currentEnd = -1
        }
        for ((s, e) in clauses) {
            if (e - s > HARD_CAP) {
                flush(Boundary.CLAUSE)
                splitByWords(text, s, e, paragraphIndex, out)
                continue
            }
            if (currentStart < 0) {
                currentStart = s
                currentEnd = e
                continue
            }
            val joinedLen = e - currentStart
            if (joinedLen > TARGET_MAX && currentEnd - currentStart >= TARGET_MIN) {
                flush(Boundary.CLAUSE)
                currentStart = s
                currentEnd = e
            } else if (joinedLen > HARD_CAP) {
                flush(Boundary.CLAUSE)
                currentStart = s
                currentEnd = e
            } else {
                currentEnd = e
            }
        }
        if (currentStart >= 0) flush(Boundary.CLAUSE)
    }

    private val CLAUSE_ENDS = setOf(',', ';', ':', '—', '–')
    private val CONJUNCTIONS = listOf(" and ", " but ", " or ", " so ", " yet ", " for ", " nor ")

    /** Split at clause punctuation / conjunctions, keeping the delimiter. */
    private fun clauseSpans(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val cuts = mutableListOf<Int>() // indices just AFTER a clause delimiter
        var i = from
        while (i < to) {
            if (text[i] in CLAUSE_ENDS) {
                cuts.add(i + 1)
                i++
                continue
            }
            var matched = false
            for (conj in CONJUNCTIONS) {
                if (i + conj.length <= to &&
                    text.regionMatches(i, conj, 0, conj.length, ignoreCase = true)
                ) {
                    cuts.add(i) // cut BEFORE the conjunction; it opens the next clause
                    matched = true
                    break
                }
            }
            i++
            if (matched) continue
        }
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = from
        for (cut in cuts.sorted()) {
            if (cut > start) spans.add(start to cut)
            start = cut
        }
        if (start < to) spans.add(start to to)
        return spans.filter { (s, e) -> text.substring(s, e).isNotBlank() }
    }

    /** Pack words into <= HARD_CAP chunks, cutting nearest the target. */
    private fun splitByWords(
        text: String,
        from: Int,
        to: Int,
        paragraphIndex: Int,
        out: MutableList<Chunk>,
    ) {
        var start = from
        while (start < to) {
            // Skip leading spaces.
            while (start < to && text[start] == ' ') start++
            if (start >= to) break
            var end = minOf(start + HARD_CAP, to)
            if (end < to) {
                // Back off to the last space at/before TARGET_MAX..HARD_CAP.
                var cut = -1
                var i = minOf(start + TARGET_MAX, to - 1)
                while (i > start) {
                    if (text[i] == ' ') { cut = i; break }
                    i--
                }
                end = if (cut > start) cut else minOf(start + HARD_CAP, to)
            }
            out.add(
                Chunk(
                    id = out.size,
                    text = text.substring(start, end),
                    charOffset = start,
                    paragraphIndex = paragraphIndex,
                    boundaryAfter = Boundary.CLAUSE,
                )
            )
            start = end
        }
    }
}
