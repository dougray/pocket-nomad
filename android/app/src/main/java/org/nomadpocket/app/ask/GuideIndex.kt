package org.nomadpocket.app.ask

import android.content.Context
import org.nomadpocket.app.guides.Guide
import org.nomadpocket.app.guides.GuideLibrary
import kotlin.math.ln
import kotlin.math.max

/**
 * Full-text passage search over the built-in guides, in memory.
 *
 * iOS builds a SQLite FTS5 index of the guides on first launch. Android's own SQLite
 * doesn't promise FTS5, and 69 short guides make only a few hundred passages, so this
 * scores them with the same formula FTS5 uses (bm25, k1 = 1.2, b = 0.75, the title
 * column weighted 4x) without a database. Words are cut to a rough stem ([stem]) the
 * way FTS5's porter tokenizer would, so "bleeding" finds "bleed".
 */
class GuideIndex(private val passages: List<Entry>) {
    /** One passage of one guide. [title] includes the guide's keywords, as on iOS. */
    class Entry(val guide: Guide, val title: String, val body: String) {
        internal val titleTerms: Map<String, Int> = counts(tokens(title).map(::stem))
        internal val bodyTerms: Map<String, Int> = counts(tokens(body).map(::stem))
        internal val titleLength = titleTerms.values.sum()
        internal val bodyLength = bodyTerms.values.sum()
    }

    private val avgTitle = passages.map { it.titleLength }.average().takeIf { it > 0 } ?: 1.0
    private val avgBody = passages.map { it.bodyLength }.average().takeIf { it > 0 } ?: 1.0

    val size: Int get() = passages.size

    /** The best passages for [query], best first. Score is -bm25: HIGHER IS BETTER. */
    fun search(query: String, limit: Int): List<AskPassage> {
        val terms = queryTerms(query).map(::stem).distinct()
        if (terms.isEmpty() || limit <= 0) return emptyList()
        val n = passages.size.toDouble()
        val idf = terms.associateWith { t ->
            val df = passages.count { t in it.titleTerms || t in it.bodyTerms }
            // FTS5 clamps idf so common words never count against a passage.
            max(1e-6, ln((n - df + 0.5) / (df + 0.5)))
        }
        return passages.mapNotNull { e ->
            var score = 0.0
            var matched = false
            for (t in terms) {
                val ft = e.titleTerms[t] ?: 0
                val fb = e.bodyTerms[t] ?: 0
                if (ft == 0 && fb == 0) continue
                matched = true
                score += idf.getValue(t) * (TITLE_WEIGHT * part(ft, e.titleLength, avgTitle) + part(fb, e.bodyLength, avgBody))
            }
            if (matched) AskPassage(SourceRef.Guide(e.guide.file), GUIDE_SOURCE, e.guide.title, e.body, score) else null
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun part(tf: Int, length: Int, avg: Double): Double =
        if (tf == 0) 0.0 else tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / avg))

    companion object {
        const val GUIDE_SOURCE = "Survival Guide"
        private const val K1 = 1.2
        private const val B = 0.75
        private const val TITLE_WEIGHT = 4.0

        /** Splits every guide into passages. Keywords go in the title field, which counts 4x. */
        fun build(guides: List<Guide>, html: (Guide) -> String?): GuideIndex = GuideIndex(
            guides.flatMap { g ->
                val text = html(g)?.let(GuideText::extract) ?: return@flatMap emptyList()
                val title = (listOf(g.title) + g.keywords).joinToString(" · ")
                PassageSplitter.split(text).map { Entry(g, title, it) }
            },
        )

        @Volatile private var cached: GuideIndex? = null

        /** Built once per process from the guides in the APK (a fraction of a second). */
        fun get(context: Context): GuideIndex = cached ?: synchronized(this) {
            cached ?: run {
                val library = GuideLibrary.get(context)
                build(library.allGuides) { g ->
                    runCatching { context.assets.open("${GuideLibrary.ROOT}/${g.file}").bufferedReader().use { it.readText() } }.getOrNull()
                }
            }.also { cached = it }
        }

        /** Lowercased letter and digit runs. */
        fun tokens(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        /** The words worth searching for, like SearchIndex.ftsQuery on iOS: stopwords dropped unless nothing else is left. */
        fun queryTerms(query: String): List<String> {
            val words = tokens(query).filter { it.length >= 2 || it.all(Char::isDigit) }
            val terms = words.filter { it !in STOPWORDS }.ifEmpty { words }
            return terms.distinct().take(32)
        }

        /**
         * A rough English stem: enough that "bleeding", "bleeds" and "bled" mostly meet
         * at "bleed", "burns" at "burn" and "purifying" at "purifi". Not the full Porter
         * algorithm; both the passages and the question go through the same function.
         */
        fun stem(word: String): String {
            if (word.length <= 3 || word.any { it.isDigit() }) return word
            val w = word
            fun cut(suffix: String, keep: Int = 3, add: String = ""): String? =
                if (w.endsWith(suffix) && w.length - suffix.length >= keep) w.dropLast(suffix.length) + add else null
            val s = cut("ies", add = "i") ?: cut("ied", add = "i") ?: cut("ying", add = "i")
                ?: cut("ing") ?: cut("edly") ?: (if (w.endsWith("eed")) w else null) ?: cut("ed") ?: cut("ly") ?: cut("es", keep = 4) ?: cut("ss")?.let { w }
                ?: cut("s") ?: w
            // Undo the doubled consonant left by "-ing"/"-ed" ("stopping" -> "stop").
            return if (s.length >= 4 && s != w && s[s.length - 1] == s[s.length - 2] && s.last() !in "lsz") s.dropLast(1) else s
        }

        private fun counts(words: List<String>): Map<String, Int> = words.groupingBy { it }.eachCount()

        /** SearchIndex.stopwords in NomadKit. */
        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
            "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
            "near", "not", "of", "on", "or", "our", "should", "so", "than", "that", "the", "their", "them", "then",
            "there", "these", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which", "who",
            "why", "will", "with", "would", "you", "your", "about", "any", "some", "use", "using", "get", "make",
            "tell", "please", "best", "way", "ways", "need", "want", "know", "much", "many", "very", "just",
        )
    }
}
