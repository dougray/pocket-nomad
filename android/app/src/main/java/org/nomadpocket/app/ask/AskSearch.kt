package org.nomadpocket.app.ask

/**
 * Passages from downloaded books, for Ask. The Library module provides the real one;
 * until it is wired in (see [AskSources]) there are no books and this returns nothing.
 * Scores must already be normalised the way [AskSearch.normalized] does it (scaled
 * against each book index's best hit × question coverage², boost 1), which is what
 * the Library's BookSearch returns; they are used as they are. Called off the main thread.
 */
fun interface BookPassageSource {
    fun passages(question: String, limit: Int): List<AskPassage>

    companion object {
        val NONE = BookPassageSource { _, _ -> emptyList() }
    }
}

/**
 * Where Ask finds book passages and how it opens one. Set once at startup in
 * [org.nomadpocket.app.NomadApp.onCreate].
 */
object AskSources {
    @Volatile var books: BookPassageSource = BookPassageSource.NONE

    /**
     * The navigation route that opens a book article in the Library reader, or null to
     * show the passage itself (the default until the Library reader exists).
     */
    @Volatile var bookRoute: ((bookId: String, path: String) -> String)? = null
}

/**
 * One place to search the built-in guides and every indexed book. A port of
 * SearchService in App/Sources/Ask/SearchService.swift.
 *
 * bm25 scores depend on each index's own word statistics, so a big book's scores
 * dwarf the small guide index even when the guide is the better answer. Each index's
 * scores are scaled against its own best hit (the guides here, each book by the
 * Library's BookSearch), and the built-in guides (short, vetted, written for
 * emergencies) get a boost on top.
 */
class AskSearch(private val guides: GuideIndex?, private val books: BookPassageSource) {
    /** Best passages across all sources, best first. */
    fun search(question: String, limit: Int = 8): List<AskPassage> {
        val hits = mutableListOf<AskPassage>()
        guides?.let { hits += normalized(it.search(question, limit), GUIDE_BOOST, question) }
        // Book scores arrive already normalised per index (see BookPassageSource).
        hits += runCatching { books.passages(question, limit) }.getOrDefault(emptyList())
        return hits.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        const val GUIDE_BOOST = 1.3

        /**
         * Scales each passage's score against its index's best hit, then by how much of
         * the question it covers (squared), so a passage that only shares one word
         * ("stop" in "Just Stop Oil") can't outrank one that matches it all.
         */
        fun normalized(passages: List<AskPassage>, boost: Double, query: String): List<AskPassage> {
            val top = passages.maxOfOrNull { it.score }
            if (top == null || top <= 0) return passages.map { it.copy(score = 0.0) }
            val terms = keyTerms(query)
            return passages.map { p ->
                val cover = coverage(terms, p.title + " " + p.body)
                p.copy(score = p.score / top * boost * cover * cover)
            }
        }

        private val stopwords = setOf(
            "a", "an", "and", "are", "as", "at", "be", "can", "do", "does", "for", "from", "how", "i", "if", "in", "is",
            "it", "me", "my", "of", "on", "or", "should", "so", "that", "the", "to", "what", "when", "where", "which",
            "who", "why", "will", "with", "you", "your", "much", "many", "get", "make", "about", "there", "this",
        )

        /** Lowercased content words, cut to a 5-letter stem so "bleeding" matches "bleed". */
        fun keyTerms(query: String): List<String> =
            query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 1 && it !in stopwords }
                .map { it.take(5) }
                .distinct()

        /** Share of key terms present in the text (1 when the query has none). */
        fun coverage(terms: List<String>, text: String): Double {
            if (terms.isEmpty()) return 1.0
            val lower = text.lowercase()
            return terms.count { lower.contains(it) }.toDouble() / terms.size
        }
    }
}
