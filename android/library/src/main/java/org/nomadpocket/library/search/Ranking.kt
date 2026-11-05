package org.nomadpocket.library.search

import java.util.Locale

/**
 * Puts passages from different indexes on one scale. A port of the ranking in the
 * iOS app's `SearchService.swift`.
 *
 * bm25 scores depend on each index's own word statistics, so a big book's scores
 * dwarf the small guide index even when the guide is the better answer. Each
 * index's scores are scaled against its own best hit, then by how much of the
 * question the passage covers (squared), so a passage that only shares one word
 * ("stop" in "Just Stop Oil") can't outrank one that matches it all.
 */
object Ranking {
    /** The built-in guides (short, vetted, written for emergencies) get this boost on top. */
    const val GUIDE_BOOST = 1.3

    /** Each passage with its cross-index score: `score / best * boost * coverage²`. */
    fun normalized(passages: List<Passage>, boost: Double, query: String): List<Pair<Passage, Double>> {
        val top = passages.maxOfOrNull { it.score }
        if (top == null || top <= 0) return passages.map { it to 0.0 }
        val terms = keyTerms(query)
        return passages.map { p ->
            val cover = coverage(terms, p.title + " " + p.body)
            p to p.score / top * boost * cover * cover
        }
    }

    private val stopwords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "can", "do", "does", "for", "from", "how", "i", "if", "in", "is",
        "it", "me", "my", "of", "on", "or", "should", "so", "that", "the", "to", "what", "when", "where", "which",
        "who", "why", "will", "with", "you", "your", "much", "many", "get", "make", "about", "there", "this",
    )

    /** Lowercased content words, cut to a 5-letter stem so "bleeding" matches "bleed". */
    fun keyTerms(query: String): List<String> =
        query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in stopwords }
            .map { it.take(5) }
            .distinct()

    /** Share of key terms present in the text (1 when the query has none). */
    fun coverage(terms: List<String>, text: String): Double {
        if (terms.isEmpty()) return 1.0
        val lower = text.lowercase(Locale.ROOT)
        return terms.count { lower.contains(it) }.toDouble() / terms.size
    }
}
