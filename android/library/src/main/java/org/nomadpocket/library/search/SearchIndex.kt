package org.nomadpocket.library.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * One search hit. [score] is `-bm25`, so HIGHER IS BETTER.
 * [snippet] marks matched terms with `**bold**`.
 */
data class Passage(val title: String, val path: String, val body: String, val snippet: String, val score: Double)

class IndexException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Read-only access to a `.nomadidx` passage index (SQLite FTS5), built by [IndexBuilder]
 * here or by NomadKit's `nomad-index` on a Mac. A port of NomadKit's `SearchIndex.swift`.
 *
 * Uses the SQLite bundled with androidx.sqlite (`sqlite-bundled`), which is built with
 * FTS5; the SQLite inside Android doesn't promise FTS5. Thread-safe.
 */
class SearchIndex(val file: File) : AutoCloseable {
    private val db: SQLiteConnection

    init {
        db = try {
            BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY)
        } catch (e: SQLiteException) {
            throw IndexException("SQLite: ${e.message}", e)
        }
        // Fail early on files that aren't our index.
        try {
            db.prepare("SELECT count(*) FROM meta").use { it.step() }
        } catch (e: SQLiteException) {
            db.close()
            throw IndexException("not a NOMAD index: ${e.message}", e)
        }
    }

    override fun close() = synchronized(this) { db.close() }

    /** `meta.zim_uuid` (hex), or null if empty/missing. */
    val zimUuid: String? get() = meta("zim_uuid")?.takeIf { it.isNotEmpty() }

    /** True when the build finished (`meta.complete = 1`). */
    val isComplete: Boolean get() = meta("complete") == "1"

    fun meta(key: String): String? = synchronized(this) {
        runCatching {
            db.prepare("SELECT value FROM meta WHERE key = ?").use { s ->
                s.bindText(1, key)
                if (s.step() && !s.isNull(0)) s.getText(0) else null
            }
        }.getOrNull()
    }

    /**
     * Free-text search. Never throws on odd input (quotes, `*`, operators); returns
     * [] when nothing searchable is left. Throws only on database errors.
     */
    fun search(query: String, limit: Int): List<Passage> {
        if (limit <= 0) return emptyList()
        val match = ftsQuery(query) ?: return emptyList()
        val sql = """
            SELECT title, path, body, snippet(passages, 1, '**', '**', '…', 32), bm25(passages, 4.0, 1.0, 0.0) AS rank
            FROM passages WHERE passages MATCH ? ORDER BY rank LIMIT ?
        """.trimIndent()
        return synchronized(this) {
            try {
                db.prepare(sql).use { s ->
                    s.bindText(1, match)
                    s.bindLong(2, minOf(limit, 10_000).toLong())
                    val out = ArrayList<Passage>()
                    while (s.step()) {
                        fun text(i: Int) = if (s.isNull(i)) "" else s.getText(i)
                        out.add(Passage(text(0), text(1), text(2), text(3), -s.getDouble(4)))
                    }
                    out
                }
            } catch (e: SQLiteException) {
                throw IndexException("SQLite: ${e.message}", e)
            }
        }
    }

    companion object {
        val stopwords: Set<String> = setOf(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
            "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
            "near", "not", "of", "on", "or", "our", "should", "so", "than", "that", "the", "their", "them", "then",
            "there", "these", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which", "who",
            "why", "will", "with", "would", "you", "your", "about", "any", "some", "use", "using", "get", "make",
            "tell", "please", "best", "way", "ways", "need", "want", "know", "much", "many", "very", "just",
        )

        /**
         * Turns free text into a safe FTS5 MATCH expression: lowercase word tokens,
         * stopwords and punctuation dropped, each term double-quoted, joined with OR.
         * Returns null when nothing searchable remains. If every word is a stopword the
         * stopwords are kept, so "the who" still searches.
         */
        fun ftsQuery(text: String): String? {
            val tokens = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            val lower = text.lowercase(Locale.ROOT)
            while (i < lower.length) {
                val cp = lower.codePointAt(i)
                if (Character.isLetter(cp) || Character.isDigit(cp)) {
                    current.appendCodePoint(cp)
                } else if (current.isNotEmpty()) {
                    tokens.add(current.toString()); current.setLength(0)
                }
                i += Character.charCount(cp)
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
            val kept = tokens.filter { it.codePointCount(0, it.length) >= 2 || it.all { c -> c.isDigit() } }
            var terms = kept.filter { it !in stopwords }
            if (terms.isEmpty()) terms = kept
            terms = terms.distinct()
            if (terms.isEmpty()) return null
            return terms.take(32).joinToString(" OR ") { "\"$it\"" }
        }
    }
}
