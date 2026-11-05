package org.nomadpocket.library

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nomadpocket.library.search.IndexBuilder
import org.nomadpocket.library.search.IndexCancelled
import org.nomadpocket.library.search.Passage
import org.nomadpocket.library.search.Ranking
import org.nomadpocket.library.search.SearchIndex
import org.nomadpocket.library.zim.ZimArchive

/** An offline library book (a Kiwix ZIM file). [id] is the ZIM's UUID. */
data class Book(
    val id: String,
    val file: File,
    val title: String,
    val summary: String,
    val size: Long,
    val articleCount: Int,
    /** The book's full-text index, or null for "title search only". */
    val indexFile: File?,
)

/**
 * The books on this phone, their search indexes and the on-phone indexer. Like the
 * iOS app's ContentStore, books larger than [AUTO_INDEX_LIMIT] articles (all of
 * Wikipedia) wait for the user to ask, because they take hours to index on a phone;
 * a prebuilt `.nomadidx` from the Mac pack script can be added instead.
 *
 * - Books: `<app-specific external storage>/Books/<name>.zim` (falls back to internal
 *   storage when there is no external storage). Removed with the app; never backed up.
 * - Indexes built here: `noBackupFilesDir/Indexes/<book id>.nomadidx`.
 * - Prebuilt indexes: any `.nomadidx` in the Books folder whose `zim_uuid` matches a
 *   book, or with the same name as the book.
 *
 * Thread-safe. [refresh] does file I/O, so call it off the main thread.
 */
class BookShelf(val booksDir: File, val indexDir: File) {
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    /** Book id to indexing progress (0..1) while it is queued or being indexed. */
    private val _indexProgress = MutableStateFlow<Map<String, Double>>(emptyMap())
    val indexProgress: StateFlow<Map<String, Double>> = _indexProgress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Open archives and indexes, keyed by file path and reused across refreshes. */
    private class Opened(val archive: ZimArchive, val length: Long, val modified: Long)
    private val archives = HashMap<String, Opened>()
    private val indexes = HashMap<String, SearchIndex>()
    private val archiveById = HashMap<String, ZimArchive>()
    private var loaded = false

    /** Set to false when the user stops indexing, until they start it again. */
    var autoIndex = true
    private val indexer = Executors.newSingleThreadExecutor { r -> Thread(r, "book-indexer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val queue = LinkedHashSet<String>()
    private val cancelCurrent = AtomicBoolean(false)
    /** Books whose indexing failed this session, so a refresh doesn't retry them in a loop. */
    private val failed = HashSet<String>()

    init {
        booksDir.mkdirs()
        indexDir.mkdirs()
    }

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** The open archive for a book id, for the reader. */
    fun archive(id: String): ZimArchive? {
        ensureLoaded()
        return synchronized(this) { archiveById[id] }
    }

    fun clearError() { _lastError.value = null }
    fun reportError(message: String) { _lastError.value = message }

    fun ensureLoaded() {
        if (!synchronized(this) { loaded }) refresh()
    }

    /** Rescans the Books folder, opens new books, finds their indexes and queues indexing. */
    fun refresh() {
        val list = synchronized(this) {
            val zims = booksDir.listFiles { f -> f.isFile && f.extension.lowercase(Locale.ROOT) == "zim" }?.toList() ?: emptyList()
            val seen = HashSet<String>()
            for (f in zims) {
                seen.add(f.path)
                val old = archives[f.path]
                if (old != null && old.length == f.length() && old.modified == f.lastModified()) continue
                old?.archive?.close()
                archives.remove(f.path)
                runCatching { ZimArchive(f) }.onSuccess { archives[f.path] = Opened(it, f.length(), f.lastModified()) }
            }
            for (path in archives.keys.filter { it !in seen }) archives.remove(path)?.archive?.close()

            // Indexes: the phone's own, plus any prebuilt ones in the Books folder.
            val idxFiles = (indexDir.listFiles()?.toList() ?: emptyList()) + (booksDir.listFiles()?.toList() ?: emptyList())
            val wantIdx = idxFiles.filter { it.isFile && it.extension.lowercase(Locale.ROOT) == "nomadidx" }.map { it.path }.toSet()
            for (path in indexes.keys.filter { it !in wantIdx }) indexes.remove(path)?.close()
            for (path in wantIdx) if (path !in indexes) runCatching { SearchIndex(File(path)) }.onSuccess { indexes[path] = it }

            archiveById.clear()
            val out = ArrayList<Book>()
            for ((path, opened) in archives) {
                val a = opened.archive
                val id = a.uuid
                if (archiveById.containsKey(id)) continue // the same book twice: keep one
                archiveById[id] = a
                val file = File(path)
                val index = findIndex(id, file)
                var articles = a.articleCount
                // Books that are mostly PDFs list one HTML page; the index counts every document.
                index?.let { indexes[it.path]?.meta("articles")?.toIntOrNull() }?.let { if (it > articles) articles = it }
                out.add(
                    Book(
                        id = id, file = file,
                        title = a.metadata("Title")?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                        summary = a.metadata("Description") ?: "",
                        size = file.length(), articleCount = articles, indexFile = index,
                    ),
                )
            }
            loaded = true
            out.sortedBy { it.title.lowercase(Locale.ROOT) }
        }
        _books.value = list
        if (autoIndex) {
            val skip = synchronized(this) { failed.toSet() }
            for (b in list) if (b.indexFile == null && b.articleCount <= AUTO_INDEX_LIMIT && b.id !in skip) enqueueIndex(b.id)
        }
    }

    /** The sidecar `<book>.nomadidx`, the phone's own `<id>.nomadidx`, or any prebuilt index for this book. */
    private fun findIndex(id: String, zim: File): File? {
        val sidecar = File(zim.parentFile, zim.nameWithoutExtension + ".nomadidx")
        val own = File(indexDir, "$id.nomadidx")
        for (candidate in listOf(sidecar, own)) {
            val idx = indexes[candidate.path] ?: continue
            if (idx.zimUuid == null || idx.zimUuid == id) return candidate
        }
        return indexes.entries.firstOrNull { it.key.startsWith(booksDir.path) && it.value.zimUuid == id }?.let { File(it.key) }
    }

    /** Deletes a book, its own index and its sidecar index. */
    fun delete(book: Book) {
        synchronized(this) {
            queue.remove(book.id)
            archives.remove(book.file.path)?.archive?.close()
            archiveById.remove(book.id)
            for (f in listOf(File(indexDir, "${book.id}.nomadidx"), File(book.file.parentFile, book.file.nameWithoutExtension + ".nomadidx"), book.indexFile)) {
                if (f != null) { indexes.remove(f.path)?.close(); f.delete() }
            }
            book.file.delete()
        }
        _indexProgress.update { it - book.id }
        refresh()
    }

    // Search

    /** Best passages across every indexed book, ranked with [Ranking.normalized], with their snippets. */
    fun search(question: String, limit: Int): List<BookHit> {
        ensureLoaded()
        if (limit <= 0) return emptyList()
        val shelf = _books.value
        val open = synchronized(this) { shelf.mapNotNull { b -> b.indexFile?.let { f -> indexes[f.path]?.let { b to it } } } }
        val hits = ArrayList<BookHit>()
        for ((book, index) in open) {
            val found: List<Passage> = runCatching { index.search(question, limit) }.getOrDefault(emptyList())
            for ((p, score) in Ranking.normalized(found, 1.0, question)) {
                hits.add(BookHit(BookPassage(book.id, book.title, p.title, p.path, p.body, score), p.snippet))
            }
        }
        return hits.sortedByDescending { it.passage.score }.take(limit)
    }

    /** Best passage per article, for result lists. */
    fun articles(query: String, limit: Int): List<BookHit> {
        val seen = HashSet<Pair<String, String>>()
        return search(query, limit * 3).filter { seen.add(it.passage.bookId to it.passage.path) }.take(limit)
    }

    // Indexing

    fun enqueueIndex(bookId: String) {
        autoIndex = true
        synchronized(this) {
            failed.remove(bookId)
            if (bookId in queue) return
            queue.add(bookId)
        }
        _indexProgress.update { it + (bookId to (it[bookId] ?: 0.0)) }
        indexer.execute { runIndex(bookId) }
    }

    /** Stops the current build and clears the queue; nothing is indexed automatically until [enqueueIndex]. */
    fun cancelIndexing() {
        autoIndex = false
        synchronized(this) { queue.clear() }
        cancelCurrent.set(true)
        _indexProgress.value = emptyMap()
    }

    private fun runIndex(bookId: String) {
        val book = book(bookId)
        val archive = archive(bookId)
        if (book == null || archive == null || !synchronized(this) { bookId in queue }) {
            synchronized(this) { queue.remove(bookId) }
            _indexProgress.update { it - bookId }
            return
        }
        cancelCurrent.set(false)
        try {
            IndexBuilder.build(
                archive, File(indexDir, "$bookId.nomadidx"),
                progress = { done, total -> _indexProgress.update { it + (bookId to if (total > 0) done.toDouble() / total else 0.0) } },
                isCancelled = { cancelCurrent.get() || !synchronized(this) { bookId in queue } },
            )
        } catch (_: IndexCancelled) {
        } catch (e: Exception) {
            synchronized(this) { failed.add(bookId) }
            _lastError.value = "Indexing ${book.title} failed: ${e.message}"
        } finally {
            synchronized(this) { queue.remove(bookId) }
            _indexProgress.update { it - bookId }
        }
        refresh()
    }

    companion object {
        const val AUTO_INDEX_LIMIT = 200_000

        @Volatile private var shared: BookShelf? = null

        /** The app's one shelf. */
        fun get(context: Context): BookShelf = shared ?: synchronized(this) {
            shared ?: BookShelf(booksDir(context), File(context.noBackupFilesDir, "Indexes")).also { shared = it }
        }

        /** App-specific storage, so no storage permission is needed and books go with the app. */
        fun booksDir(context: Context): File {
            val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            return File(external ?: context.filesDir, "Books")
        }
    }
}
