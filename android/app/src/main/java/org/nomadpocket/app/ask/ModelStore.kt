package org.nomadpocket.app.ask

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A download or import in progress, by file name. */
data class Transfer(
    val name: String,
    val received: Long,
    val expected: Long,
    val state: State = State.RUNNING,
    val error: String? = null,
) {
    enum class State { RUNNING, CHECKING, PAUSED, FAILED }
    val fraction: Float get() = if (expected > 0) (received.toDouble() / expected).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * The AI model files on this phone, in the app's private files folder (`models/`).
 * Downloads resume from a `.part` file after a pause, a dropped connection or the app
 * being closed, and a file only becomes a model once its SHA-256 matches the catalog.
 * Model files are never in git or the APK.
 */
object ModelStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    /** Downloads and imports, by file name. */
    val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()
    private val _models = MutableStateFlow<List<ModelFile>>(emptyList())
    /** Finished model files, biggest first. */
    val models: StateFlow<List<ModelFile>> = _models.asStateFlow()
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "models").apply { mkdirs() }
        refresh()
        // A download cut off by the app closing can be resumed from where it stopped.
        val paused = dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().mapNotNull { f ->
            val name = f.name.removeSuffix(".part")
            ModelCatalog.byId(name)?.let { Transfer(name, f.length(), it.size, Transfer.State.PAUSED) }
        }
        _transfers.value = paused.associateBy { it.name }
    }

    fun refresh() {
        _models.value = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty()
            .map { ModelFile(it.name, it.absolutePath, it.length()) }
            .sortedByDescending { it.size }
    }

    fun file(name: String): File = File(dir, name)

    /** Starts or resumes downloading [model]. */
    fun download(model: CatalogModel) {
        if (jobs[model.id]?.isActive == true) return
        setTransfer(Transfer(model.id, partFile(model.id).length(), model.size))
        jobs[model.id] = scope.launch {
            try {
                fetch(model)
                setTransfer(Transfer(model.id, model.size, model.size, Transfer.State.CHECKING))
                verifyAndInstall(model)
                _transfers.update { it - model.id }
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(model.id, model.size, e.message ?: "The download stopped.")
            }
        }
    }

    /** Stops a download, keeping what has arrived so it can resume. */
    fun pause(name: String) {
        jobs.remove(name)?.cancel()
        _transfers.update { map -> map[name]?.let { map + (name to it.copy(state = Transfer.State.PAUSED)) } ?: map }
    }

    /** Deletes a model, or a partial download of one. */
    fun delete(name: String) {
        jobs.remove(name)?.cancel()
        partFile(name).delete()
        file(name).delete()
        _transfers.update { it - name }
        refresh()
    }

    /** Free memory now, as Android reports it. */
    fun freeMemory(context: Context): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem
    }

    private fun partFile(name: String) = File(dir, "$name.part")

    private fun setTransfer(t: Transfer) = _transfers.update { it + (t.name to t) }

    private fun fail(name: String, expected: Long, message: String) =
        setTransfer(Transfer(name, partFile(name).length(), expected, Transfer.State.FAILED, message))

    private suspend fun fetch(model: CatalogModel) {
        val part = partFile(model.id)
        var url = URL(model.url)
        // Hugging Face answers with a redirect to its file servers; follow it by hand
        // so the Range header goes with it.
        repeat(6) {
            val have = part.length()
            if (have >= model.size) return
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
                setRequestProperty("User-Agent", "PocketNomad-Android")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val next = conn.getHeaderField("Location") ?: throw IOException("Download of ${model.id} failed (redirect with no address).")
                        url = URL(url, next)
                        return@repeat
                    }
                    200, 206 -> {
                        // 200 means the server ignored the range: start again from zero.
                        val append = code == 206 && have > 0
                        conn.inputStream.use { input -> copy(input, FileOutputStream(part, append), model.id, if (append) have else 0, model.size) }
                        return
                    }
                    416 -> return   // already have every byte; the checksum decides
                    else -> throw IOException("Download of ${model.id} failed (HTTP $code).")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Download of ${model.id} failed (too many redirects).")
    }

    private suspend fun copy(input: InputStream, output: FileOutputStream, name: String, start: Long, expected: Long) {
        output.use { out ->
            val buffer = ByteArray(256 * 1024)
            var received = start
            var lastReport = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                received += n
                if (received - lastReport >= 4_000_000) {
                    lastReport = received
                    setTransfer(Transfer(name, received, expected))
                }
            }
        }
    }

    private suspend fun verifyAndInstall(model: CatalogModel) {
        val part = partFile(model.id)
        if (part.length() != model.size) {
            throw IOException("The download of ${model.title} is incomplete. Tap Resume to continue it.")
        }
        val sum = sha256(part)
        if (!sum.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The download of ${model.title} was damaged (its checksum didn't match), so it was deleted. Try again.")
        }
        if (!part.renameTo(file(model.id))) throw IOException("Couldn't save ${model.id}.")
    }

    /**
     * Copies a .gguf file the user opened with Pocket Nomad (from Files, a browser
     * download or a USB stick) into the models folder. A file named like a catalog
     * model must match its checksum; any other file must at least start with "GGUF".
     */
    fun import(context: Context, uri: Uri) {
        val name = displayName(context, uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model.gguf"
        if (jobs[name]?.isActive == true) return
        val expected = size(context, uri)
        setTransfer(Transfer(name, 0, expected))
        jobs[name] = scope.launch {
            val part = partFile(name)
            try {
                if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("Pocket Nomad can only open AI models (.gguf files) here.")
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open $name.")
                input.use { copy(it, FileOutputStream(part, false), name, 0, expected) }
                if (!isGguf(part)) throw IOException("$name isn't a GGUF model file.")
                ModelCatalog.byId(name)?.let { model ->
                    setTransfer(Transfer(name, expected, expected, Transfer.State.CHECKING))
                    if (!sha256(part).equals(model.sha256, ignoreCase = true)) {
                        throw IOException("$name doesn't match the checksum of ${model.title}; it may be damaged or a different file.")
                    }
                }
                if (!part.renameTo(file(name))) throw IOException("Couldn't save $name.")
                _transfers.update { it - name }
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                setTransfer(Transfer(name, 0, expected, Transfer.State.FAILED, e.message ?: "Couldn't add $name."))
            }
        }
    }

    /** Clears a failed transfer from the list. */
    fun dismiss(name: String) {
        if (_transfers.value[name]?.state == Transfer.State.FAILED && jobs[name]?.isActive != true) {
            if (ModelCatalog.byId(name) == null) partFile(name).delete()
            _transfers.update { it - name }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun size(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L

    /** True when the file starts with the GGUF magic bytes. */
    fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
    }.getOrDefault(false)

    /** Hex SHA-256 of a file, read in 1 MB pieces (models are gigabytes). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
