// CoverageAutoBackup.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Keeps a copy of the coverage data (CoverageBackup's one file) in a folder ON
// THIS PHONE that the user picked with Android's folder picker, such as
// Documents › Pocket Nomad Backups. Only local storage (internal storage or a
// memory card) is accepted, never Google Drive or another cloud service: the
// backup is location history and stays on the device. The folder is outside
// the app's own storage, so it survives uninstalling and reinstalling. The app
// gets access to that one folder only, and the file is overwritten in place
// after drives and when the app goes to the background. Export (the user's own
// choice where a copy goes) and restore (merge) work with any file.
// There is no account and no server.

package org.nomadpocket.mesh

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CoverageAutoBackup(
    private val context: Context,
    private val prefs: MeshPrefs,
    private val scope: CoroutineScope,
    private val coverage: CoverageStore,
    private val drive: () -> DriveMode?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Status(
        /** The chosen folder's name, or null when automatic backup is off. */
        val folderName: String? = null,
        /** False when Android no longer lets the app write there (e.g. after a reinstall). */
        val folderUsable: Boolean = true,
        val lastMillis: Long? = null,
        val working: Boolean = false,
        val error: String? = null,
        /** What happened when the folder was chosen, e.g. a backup found there and restored. */
        val note: String? = null,
    ) {
        val on: Boolean get() = folderName != null
    }

    private object Keys {
        const val FOLDER = "backup.folderUri"
        const val FOLDER_NAME = "backup.folderName"
        const val LAST = "backup.lastMillis"
        const val FINGERPRINT = "backup.fingerprint"
    }

    private val _status = MutableStateFlow(Status())
    val statusFlow: StateFlow<Status> = _status.asStateFlow()
    val status: Status get() = _status.value
    private val lock = Mutex()

    private val folder: Uri? get() = prefs.getString(Keys.FOLDER)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    init { refreshStatus() }

    private fun refreshStatus(working: Boolean = status.working, error: String? = status.error, note: String? = status.note) {
        val uri = folder
        _status.value = Status(
            folderName = uri?.let { prefs.getString(Keys.FOLDER_NAME) ?: "the chosen folder" },
            folderUsable = uri == null || canWrite(uri),
            lastMillis = prefs.getLong(Keys.LAST, 0).takeIf { it > 0 },
            working = working,
            error = error,
            note = note,
        )
    }

    private fun canWrite(uri: Uri): Boolean =
        runCatching { context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }.getOrDefault(false)

    /** Everything to back up, right now. */
    fun contents(): CoverageBackup.Contents {
        val d = drive()
        return CoverageBackup.Contents(
            app = appName(),
            createdAtMillis = now(),
            samples = coverage.samples,
            pings = coverage.pings,
            zones = coverage.zones,
            settings = coverage.settings,
            drive = d?.let { dm ->
                CoverageBackup.DriveSettings(dm.pinging, dm.keepScreenOn,
                    Firmware.entries.mapNotNull { fw -> dm.target(fw)?.let { fw to it } }.toMap())
            } ?: CoverageBackup.DriveSettings(),
        )
    }

    private fun appName(): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "Pocket Nomad Android" + (version?.let { " $it" } ?: "")
    }

    /**
     * The folder picker's answer: keep access to it and back up there now. If the
     * folder already holds a backup (say, from before a reinstall) it is merged in
     * first, so choosing the old folder brings everything back instead of overwriting it.
     */
    fun chooseFolder(uri: Uri) {
        if (!CoverageBackup.isLocalFolder(uri.authority)) {
            refreshStatus(error = CoverageBackup.NOT_LOCAL, note = null)
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        folder?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
        prefs.putString(Keys.FOLDER, uri.toString())
        prefs.putString(Keys.FOLDER_NAME, runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull())
        prefs.remove(Keys.FINGERPRINT)
        refreshStatus(error = null, note = null)
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val file = DocumentFile.fromTreeUri(context, uri)?.findFile(CoverageBackup.FILE_NAME) ?: return@runCatching null
                    context.contentResolver.openInputStream(file.uri)?.use { CoverageBackup.decode(it.readBytes().toString(Charsets.UTF_8)) }
                }.getOrNull()
            }
            if (found != null && !found.isEmpty) {
                val added = coverage.restore(found)
                refreshStatus(note = if (added.total == 0) "That folder's backup matches this phone."
                    else "Found a backup in that folder and added ${added.samples} recordings, ${added.pings} pings and ${added.zones} private zones from it.")
            }
            backUp(CoverageBackup.Reason.MANUAL)
        }
    }

    /** Stops automatic backups and gives up access to the folder. The file there stays. */
    fun turnOff() {
        folder?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.remove(Keys.FOLDER)
        prefs.remove(Keys.FOLDER_NAME)
        prefs.remove(Keys.FINGERPRINT)
        refreshStatus(error = null, note = null)
    }

    /** Writes the backup into the chosen folder when [CoverageBackup.shouldBackUp] says so. */
    fun backUp(reason: CoverageBackup.Reason) {
        val uri = folder
        val fingerprint = coverage.fingerprint
        val changed = prefs.getString(Keys.FINGERPRINT) != fingerprint
        val last = prefs.getLong(Keys.LAST, 0).takeIf { it > 0 }
        if (!CoverageBackup.shouldBackUp(reason, uri != null, changed, last, now()) || uri == null) return
        if (!CoverageBackup.isLocalFolder(uri.authority)) { refreshStatus(error = CoverageBackup.NOT_LOCAL); return }
        // Never replace a backup with an empty one (a fresh install pointed at its old folder, say).
        if (coverage.isEmpty) return
        val text = CoverageBackup.encode(contents())
        scope.launch {
            lock.withLock {
                refreshStatus(working = true)
                val error = withContext(Dispatchers.IO) { runCatching { writeToFolder(uri, text) }.exceptionOrNull() }
                if (error == null) {
                    prefs.putLong(Keys.LAST, now())
                    prefs.putString(Keys.FINGERPRINT, fingerprint)
                }
                refreshStatus(working = false, error = error?.let { problem(it) })
            }
        }
    }

    private fun problem(e: Throwable): String =
        if (e is SecurityException) "Pocket Nomad can't write to that folder any more. Choose it again."
        else "Couldn't back up: ${e.message ?: "the folder didn't accept the file"}."

    private fun writeToFolder(tree: Uri, text: String) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: error("the folder is gone")
        if (!canWrite(tree)) throw SecurityException("no write permission")
        val file = dir.findFile(CoverageBackup.FILE_NAME)
            ?: dir.createFile(CoverageBackup.MIME_TYPE, CoverageBackup.FILE_NAME)
            ?: error("the folder didn't let Pocket Nomad create a file")
        writeDocument(file.uri, text)
    }

    /** Overwrites a document. "wt" truncates; a few providers only take "w". */
    private fun writeDocument(uri: Uri, text: String) {
        val resolver = context.contentResolver
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("couldn't open the file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Export: writes the backup to a file the user created with the save dialog. */
    suspend fun exportTo(uri: Uri): String? {
        val text = CoverageBackup.encode(contents())
        return withContext(Dispatchers.IO) { runCatching { writeDocument(uri, text) }.exceptionOrNull() }
            ?.let { "Couldn't save the backup: ${it.message ?: "the file couldn't be written"}." }
    }

    /** What a restore did, in words for the screen. */
    data class RestoreResult(val added: CoverageBackup.MergeResult?, val message: String)

    /** Restore: merges a backup file into what's on the phone. Never removes anything. */
    suspend fun restoreFrom(uri: Uri): RestoreResult {
        val contents = try {
            withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("couldn't open the file")
                CoverageBackup.decode(text)
            }
        } catch (e: CoverageBackup.FormatException) {
            return RestoreResult(null, e.message ?: "This isn't a Pocket Nomad backup.")
        } catch (e: Exception) {
            return RestoreResult(null, "Couldn't read the file: ${e.message ?: "unknown problem"}.")
        }
        val wasEmpty = coverage.isEmpty
        val added = coverage.restore(contents)
        drive()?.let { d ->
            // Drive settings come back on a fresh install; otherwise the phone's own choices stay.
            if (wasEmpty) {
                d.pinging = contents.drive.pinging
                d.keepScreenOn = contents.drive.keepScreenOn
            }
            contents.drive.targets.forEach { (fw, node) -> if (d.target(fw) == null) d.setTarget(fw, node) }
        }
        val parts = listOf(
            "${added.samples} recording${if (added.samples == 1) "" else "s"}",
            "${added.pings} ping${if (added.pings == 1) "" else "s"}",
            "${added.zones} private zone${if (added.zones == 1) "" else "s"}",
        )
        var message = if (added.total == 0) "Everything in that backup is already on this phone."
        else "Added ${parts.joinToString(", ")}. Nothing on this phone was removed."
        if (contents.settings.recording && !coverage.recording) message += " Turn Record coverage back on to keep mapping."
        // A restore counts as new data for the next automatic backup.
        backUp(CoverageBackup.Reason.BACKGROUND)
        return RestoreResult(added, message)
    }
}
