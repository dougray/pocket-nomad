package org.nomadpocket.library.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.nomadpocket.library.zim.ZimArchive

/** One HTML document to index (the bundled guides, or anything else). */
class IndexDocument(val path: String, val title: String, val html: ByteArray)

data class IndexStats(val articles: Int, val passages: Int, val seconds: Double)

class IndexCancelled : Exception("index build cancelled")

/**
 * Builds the SQLite FTS5 passage index (schema_version 1), the same format as
 * NomadKit's `IndexBuilder.swift`, so indexes built on a Mac work here and back:
 *
 *     CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
 *     CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61');
 *
 * Writes `<output>.tmp` first and moves it into place only when complete.
 *
 * Not ported: text from PDFs inside a ZIM (iOS uses PDFKit; Android has no PDF
 * text extraction built in), so PDF-only books get title search only.
 */
object IndexBuilder {
    const val SCHEMA_VERSION = 1

    /** Indexes the front articles of a ZIM. [progress] (done, total) is called periodically. */
    fun build(
        archive: ZimArchive,
        output: File,
        maxArticles: Int? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): IndexStats {
        val total = maxArticles?.let { minOf(it, archive.articleCount) } ?: archive.articleCount
        val writer = IndexWriter(output)
        var done = 0
        var lastReport = 0
        var failure: Exception? = null
        // Storage order decompresses each cluster about once instead of once per article.
        archive.forEachArticle(ZimArchive.ArticleOrder.STORAGE) { entry, position ->
            if (maxArticles != null && done >= maxArticles) return@forEachArticle false
            if (isCancelled()) { failure = IndexCancelled(); return@forEachArticle false }
            done++
            if (entry.mimeType.startsWith("text/html")) {
                val content = runCatching { archive.content(entry.path) }.getOrNull()
                if (content != null) {
                    try { writer.add(entry.title, entry.path, content.data) } catch (e: Exception) { failure = e; return@forEachArticle false }
                }
            }
            // With maxArticles, progress counts articles; otherwise listing slots (articleCount
            // can include front-article redirects, which are skipped).
            val shown = minOf(if (maxArticles == null) position else done, total)
            if (shown - lastReport >= 50) { lastReport = shown; progress(shown, total) }
            true
        }
        failure?.let { writer.abandon(); throw it }
        progress(total, total)
        return writer.finish(mapOf("zim_uuid" to archive.uuid))
    }

    /**
     * Indexes arbitrary HTML documents with the same schema and passage splitting.
     * [sourceId] is stored as `zim_uuid` in meta; [total] is only used for progress.
     */
    fun build(
        documents: Sequence<IndexDocument>,
        output: File,
        sourceId: String = "",
        total: Int = 0,
        progress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): IndexStats {
        val writer = IndexWriter(output)
        var done = 0
        try {
            for (doc in documents) {
                if (isCancelled()) throw IndexCancelled()
                writer.add(doc.title, doc.path, doc.html)
                done++
                if (done % 50 == 0) progress(done, maxOf(total, done))
            }
        } catch (e: Exception) {
            writer.abandon()
            throw e
        }
        progress(done, maxOf(total, done))
        return writer.finish(mapOf("zim_uuid" to sourceId))
    }
}

/** Splits extracted text into ~900–1200 character passages on paragraph boundaries. */
object PassageSplitter {
    const val TARGET = 900
    const val MAX_LENGTH = 1200

    fun split(text: String): List<String> {
        val pieces = ArrayList<String>()
        for (para in text.split("\n\n")) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (p.length <= MAX_LENGTH) pieces.add(p) else pieces.addAll(splitLong(p))
        }
        val passages = ArrayList<String>()
        var current = ""
        for (p in pieces) {
            if (current.isEmpty()) {
                current = p
            } else if (current.length + 2 + p.length <= MAX_LENGTH) {
                current += "\n\n" + p
            } else {
                passages.add(current)
                current = p
            }
            if (current.length >= TARGET) {
                passages.add(current)
                current = ""
            }
        }
        if (current.isNotEmpty()) {
            // Fold a short tail into the previous passage when it still fits loosely.
            val last = passages.lastOrNull()
            if (current.length < 200 && last != null && last.length + 2 + current.length <= MAX_LENGTH + 300) {
                passages[passages.size - 1] = last + "\n\n" + current
            } else {
                passages.add(current)
            }
        }
        return passages
    }

    /** Breaks one long paragraph at sentence ends (or spaces) into chunks <= MAX_LENGTH. */
    fun splitLong(p: String): List<String> {
        val chunks = ArrayList<String>()
        var rest = p
        while (rest.length > MAX_LENGTH) {
            val window = rest.substring(0, MAX_LENGTH)
            var cut = -1
            // Prefer the last sentence end after TARGET / 2 characters.
            var idx = window.length
            while (idx > TARGET / 2) {
                idx--
                val ch = window[idx]
                if (ch == '.' || ch == '!' || ch == '?') {
                    val next = idx + 1
                    if (next == window.length || window[next] == ' ') { cut = next; break }
                }
            }
            if (cut < 0) cut = window.lastIndexOf(' ').let { if (it >= 0) it + 1 else -1 }
            val end = if (cut > 0) cut else window.length
            val chunk = rest.substring(0, end).trim(' ')
            if (chunk.isNotEmpty()) chunks.add(chunk)
            rest = rest.substring(end)
        }
        val tail = rest.trim(' ')
        if (tail.isNotEmpty()) chunks.add(tail)
        return chunks
    }
}

internal class IndexWriter(private val output: File) {
    private val tmp = File(output.path + ".tmp")
    private var db: SQLiteConnection?
    private var insert: SQLiteStatement?
    private var articles = 0
    private var passages = 0
    private var inBatch = 0
    private val start = System.nanoTime()

    init {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(tmp.path + suffix).delete()
        val conn = try {
            BundledSQLiteDriver().open(tmp.path)
        } catch (e: SQLiteException) {
            throw IndexException("SQLite: ${e.message}", e)
        }
        db = conn
        try {
            for (sql in listOf(
                "PRAGMA journal_mode=OFF",
                "PRAGMA synchronous=OFF",
                "PRAGMA temp_store=MEMORY",
                "PRAGMA cache_size=-32768",
                "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
                "CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61')",
            )) exec(sql)
            insert = conn.prepare("INSERT INTO passages(title, body, path) VALUES (?, ?, ?)")
            exec("BEGIN")
        } catch (e: Exception) {
            abandon()
            throw e
        }
    }

    /** Runs a statement, stepping through any rows (PRAGMAs return one). */
    private fun exec(sql: String) {
        val conn = db ?: throw IndexException("no database")
        try {
            conn.prepare(sql).use { s -> while (s.step()) { /* drain */ } }
        } catch (e: SQLiteException) {
            throw IndexException("SQLite: ${e.message}", e)
        }
    }

    fun add(title: String, path: String, html: ByteArray) {
        if (isMetaRefreshStub(html)) return // HTML redirect page, nothing to index
        val (htmlTitle, text) = HtmlText.extract(html)
        addText(if (title.isNotEmpty()) title else (htmlTitle ?: path), path, text)
    }

    fun addText(title: String, path: String, text: String) {
        val chunks = PassageSplitter.split(text)
        if (chunks.isEmpty()) return
        val stmt = insert ?: throw IndexException("index closed")
        try {
            for (body in chunks) {
                stmt.reset()
                stmt.clearBindings()
                stmt.bindText(1, title)
                stmt.bindText(2, body)
                stmt.bindText(3, path)
                stmt.step()
                passages++
            }
        } catch (e: SQLiteException) {
            throw IndexException("SQLite: ${e.message}", e)
        }
        articles++
        inBatch++
        if (inBatch >= BATCH_SIZE) {
            exec("COMMIT")
            exec("BEGIN")
            inBatch = 0
        }
    }

    fun finish(meta: Map<String, String>): IndexStats {
        exec("COMMIT")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val all = meta.toMutableMap()
        all["schema_version"] = IndexBuilder.SCHEMA_VERSION.toString()
        all["articles"] = articles.toString()
        all["passages"] = passages.toString()
        all["built_at"] = iso.format(Date())
        val conn = db ?: throw IndexException("no database")
        exec("BEGIN")
        try {
            conn.prepare("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { s ->
                for ((k, v) in all.toSortedMap()) {
                    s.reset()
                    s.clearBindings()
                    s.bindText(1, k)
                    s.bindText(2, v)
                    s.step()
                }
            }
        } catch (e: SQLiteException) {
            throw IndexException("SQLite: ${e.message}", e)
        }
        exec("COMMIT")
        exec("INSERT INTO passages(passages) VALUES('optimize')")
        // `complete` goes in last, after optimize, so a crash never leaves a "complete" partial file.
        exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('complete', '1')")
        exec("PRAGMA journal_mode=DELETE")
        insert?.close(); insert = null
        conn.close(); db = null

        output.delete()
        if (!tmp.renameTo(output)) throw IndexException("couldn't move the index into place")
        return IndexStats(articles, passages, (System.nanoTime() - start) / 1e9)
    }

    fun abandon() {
        runCatching { insert?.close() }; insert = null
        runCatching { db?.close() }; db = null
        tmp.delete()
    }

    companion object {
        private const val BATCH_SIZE = 500

        /** Small pages whose only job is `<meta http-equiv="refresh">` (mwoffliner section redirects). */
        fun isMetaRefreshStub(html: ByteArray): Boolean {
            if (html.size >= 4096) return false
            val s = String(html, Charsets.UTF_8).lowercase(Locale.ROOT)
            return s.contains("http-equiv") && s.contains("refresh")
        }
    }
}
