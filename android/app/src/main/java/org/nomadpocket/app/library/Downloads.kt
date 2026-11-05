package org.nomadpocket.app.library

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.nomadpocket.library.BookShelf
import org.nomadpocket.library.search.SearchIndex

/** Background work for the Library that should outlive a screen. */
internal val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Book downloads, through Android's own DownloadManager: they keep going with the
 * app closed, show a notification, and resume by themselves after the connection
 * drops (the Kiwix mirrors support ranged requests). Files go to the Books folder
 * as `<name>.zim.part` and are renamed to `.zim` only when complete, so a partial
 * file is never opened as a book.
 */
object Downloads {
    data class Active(
        val id: Long,
        val fileName: String,
        val title: String,
        val received: Long,
        val expected: Long,
        /** "Waiting for network", "Paused", or null while running. */
        val note: String?,
    ) {
        val fraction: Double get() = if (expected > 0) received.toDouble() / expected else 0.0
    }

    private val _active = MutableStateFlow<List<Active>>(emptyList())
    val active: StateFlow<List<Active>> = _active.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** Download id to (file name, title), kept so downloads are found again after a restart. */
    private fun tracked(context: Context): Map<Long, Pair<String, String>> =
        prefs(context).all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: return@mapNotNull null
            id to (o.getString("file") to o.getString("title"))
        }.toMap()

    fun isDownloading(item: CatalogItem): Boolean = _active.value.any { it.fileName == item.fileName }

    fun start(context: Context, item: CatalogItem) {
        if (isDownloading(item)) return
        val dm = context.getSystemService(DownloadManager::class.java)
        val books = BookShelf.get(context).booksDir
        val external = context.getExternalFilesDir(null)
        if (dm == null || external == null || !books.path.startsWith(external.path)) {
            _lastError.value = "Downloads need the phone's app storage, which isn't available right now."
            return
        }
        File(books, item.fileName + ".part").delete()
        val request = DownloadManager.Request(Uri.parse(item.url))
            .setTitle(item.title)
            .setDescription("Pocket Nomad book")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "Books/${item.fileName}.part")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = runCatching { dm.enqueue(request) }.getOrElse {
            _lastError.value = "Couldn't start ${item.title}: ${it.message}"
            return
        }
        prefs(context).edit().putString(id.toString(), JSONObject().put("file", item.fileName).put("title", item.title).toString()).apply()
        _active.update { it + Active(id, item.fileName, item.title, 0, item.size, null) }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(DownloadManager::class.java)?.remove(id) // also deletes the partial file
        prefs(context).edit().remove(id.toString()).apply()
        _active.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * Reads every tracked download's state, renames finished files into place and
     * refreshes the shelf. Cheap; the Library screens call it about once a second.
     */
    fun poll(context: Context) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return
        val tracked = tracked(context)
        if (tracked.isEmpty()) { _active.value = emptyList(); return }
        val now = ArrayList<Active>()
        var finished = false
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(*tracked.keys.toLongArray())) }.getOrNull()
        val seen = HashSet<Long>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                seen.add(id)
                val (fileName, title) = tracked[id] ?: continue
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val received = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = File(BookShelf.get(context).booksDir, "$fileName.part")
                        val dest = File(part.parentFile, fileName)
                        dest.delete()
                        if (!part.renameTo(dest)) _lastError.value = "Couldn't save $fileName."
                        forget(context, id)
                        finished = true
                    }
                    DownloadManager.STATUS_FAILED -> {
                        _lastError.value = "Download of $fileName failed${if (reason in 400..599) " (HTTP $reason)" else ""}."
                        dm.remove(id)
                        forget(context, id)
                    }
                    else -> {
                        val note = when {
                            status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
                            status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
                            status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped, retrying"
                            status == DownloadManager.STATUS_PAUSED -> "Paused"
                            status == DownloadManager.STATUS_PENDING -> "Starting"
                            else -> null
                        }
                        now.add(Active(id, fileName, title, received, total, note))
                    }
                }
            }
        }
        // Downloads removed from the system's list (cleared by the user) are forgotten too.
        if (cursor != null) for (id in tracked.keys - seen) forget(context, id)
        _active.value = now.sortedBy { it.title }
        if (finished) BookShelf.get(context).refresh()
    }

    private fun forget(context: Context, id: Long) = prefs(context).edit().remove(id.toString()).apply()

    /** True when the book (or another edition of it) is on the phone. */
    fun installed(context: Context, item: CatalogItem): Boolean {
        val shelf = BookShelf.get(context)
        if (File(shelf.booksDir, item.fileName).exists()) return true
        // A newer or older edition of the same book counts as installed.
        val stem = item.fileName.replace(Regex("_\\d{4}-\\d{2}\\.zim$"), "")
        return shelf.books.value.any { it.file.name.startsWith(stem + "_") }
    }

    fun freeSpace(context: Context): Long = runCatching { StatFs(BookShelf.get(context).booksDir.path).availableBytes }.getOrDefault(0)

    fun clearError() { _lastError.value = null }
}

/** DownloadManager says a download ended; finish it even if no screen is open. */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        libraryScope.launch {
            try { Downloads.poll(context.applicationContext) } finally { pending.finish() }
        }
    }
}

/**
 * Adds files the user opens with or shares to Pocket Nomad, or picks with
 * "Import from Files…": `.zim` books and `.nomadidx` search indexes. The type is
 * checked from the file's first bytes, because shared files often have no name.
 */
object Importer {
    data class State(val name: String, val copied: Long, val total: Long)

    private val _importing = MutableStateFlow<State?>(null)
    val importing: StateFlow<State?> = _importing.asStateFlow()

    fun import(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        libraryScope.launch { for (uri in uris) importOne(app, uri) }
    }

    private fun importOne(context: Context, uri: Uri) {
        val shelf = BookShelf.get(context)
        var name = "book"
        var total = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) total = c.getLong(1)
                }
            }
        }
        if (uri.scheme == "file") uri.lastPathSegment?.let { name = it }
        name = name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "book" }
        var part: File? = null
        try {
            val head = ByteArray(16)
            val n = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: -1
            val ext = kindOf(head, n) ?: run {
                shelf.reportError("Pocket Nomad can open .zim books and .nomadidx search indexes.")
                return
            }
            val base = name.substringBeforeLast('.', name).takeIf { name.lowercase(Locale.ROOT).endsWith(".$ext") } ?: name
            val dest = File(shelf.booksDir, "$base.$ext")
            val tmp = File(shelf.booksDir, "$base.$ext.part").also { part = it }
            shelf.booksDir.mkdirs()
            _importing.value = State(dest.name, 0, total)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastShown = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        copied += r
                        if (copied - lastShown > 8L shl 20) { lastShown = copied; _importing.value = State(dest.name, copied, total) }
                    }
                }
            }
            if (ext == "nomadidx") {
                // Any SQLite file has the same first bytes; only keep real search indexes.
                runCatching { SearchIndex(tmp).close() }.onFailure { throw java.io.IOException("it isn't a Pocket Nomad search index") }
            }
            dest.delete()
            if (!tmp.renameTo(dest)) throw java.io.IOException("couldn't move the file into place")
            shelf.refresh()
        } catch (e: Exception) {
            part?.delete()
            shelf.reportError("Couldn't import $name: ${e.message}")
        } finally {
            _importing.value = null
        }
    }

    /** "zim" or "nomadidx" from the magic bytes, or null. */
    fun kindOf(head: ByteArray, count: Int): String? = when {
        count >= 4 && head[0] == 0x5A.toByte() && head[1] == 0x49.toByte() && head[2] == 0x4D.toByte() && head[3] == 0x04.toByte() -> "zim"
        count >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3" -> "nomadidx"
        else -> null
    }
}
