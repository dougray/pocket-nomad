package org.nomadpocket.app.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.LabeledRow
import org.nomadpocket.app.ui.accent
import org.nomadpocket.library.BookShelf

/** Get Content while there's still Wi-Fi: books from the free Kiwix library (PacksView on iOS). */
@Composable
fun GetContentScreen(modifier: Modifier) {
    val context = LocalContext.current
    val shelf = remember { BookShelf.get(context) }
    val books by shelf.books.collectAsState()
    val active by Downloads.active.collectAsState()
    val downloadError by Downloads.lastError.collectAsState()
    val importing by Importer.importing.collectAsState()
    val shelfError by shelf.lastError.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var recommended by remember { mutableStateOf<List<Pair<CatalogItem, String>>>(emptyList()) }
    var bigger by remember { mutableStateOf<List<Pair<CatalogItem, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Importer.import(context, uris)
    }
    KeepLibraryFresh()

    LaunchedEffect(Unit) {
        suspend fun resolve(list: List<KiwixCatalog.Pick>) = coroutineScope {
            list.map { pick -> async(Dispatchers.IO) { runCatching { KiwixCatalog.lookup(pick) }.getOrNull()?.let { it to pick.note } } }
                .awaitAll().filterNotNull()
        }
        recommended = resolve(KiwixCatalog.recommended)
        offline = recommended.isEmpty()
        bigger = resolve(KiwixCatalog.bigger)
        loading = false
    }
    val results by produceState<List<CatalogItem>>(emptyList(), query) {
        if (query.isBlank()) { value = emptyList(); return@produceState }
        delay(400)
        value = withContext(Dispatchers.IO) { runCatching { KiwixCatalog.search(query) }.getOrDefault(emptyList()) }
    }
    // `active` and `books` are read so rows update when a download starts or finishes.
    fun installed(item: CatalogItem) = books.size >= 0 && Downloads.installed(context, item)
    fun downloading(item: CatalogItem) = active.any { it.fileName == item.fileName }
    val bulk = recommended.map { it.first }.filter { !installed(it) && !downloading(it) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        LibrarySearchField(query, { query = it }, "Search the Kiwix library")
        FormSection {
            LabeledRow("Free space on phone", byteString(Downloads.freeSpace(context)))
            LabeledRow("Books", "${books.size} · ${byteString(books.sumOf { it.size })}")
            (downloadError ?: shelfError)?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Brand.danger)
                    TextButton(onClick = { Downloads.clearError(); shelf.clearError() }) { Text("OK") }
                }
            }
        }
        importing?.let { ImportingRow(it) }
        if (active.isNotEmpty()) {
            FormSection(header = "Downloading", footer = "Downloads keep going with the app closed, and pick up where they left off if the connection drops.") {
                active.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { Downloads.cancel(context, a.id) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel ${a.title}") }
                        }
                        LinearProgressIndicator(progress = { a.fraction.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text(
                            "${byteString(a.received)} of ${if (a.expected > 0) byteString(a.expected) else "?"}" + (a.note?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (offline && !loading) {
            FormSection {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, contentDescription = null)
                    Text("No internet right now. You can still add files you already have with Import from Files.")
                }
            }
        }
        if (query.isNotBlank()) {
            FormSection(header = "Kiwix catalog") {
                if (results.isEmpty()) Text("No books match “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                results.forEach { CatalogRow(it, it.summary, installed(it), downloading(it)) { Downloads.start(context, it) } }
            }
        } else {
            FormSection(header = "Survival essentials", footer = "From the free Kiwix library. Downloads continue in the background.") {
                if (loading && recommended.isEmpty()) CircularProgressIndicator(Modifier.size(24.dp))
                if (bulk.isNotEmpty()) {
                    TextButton(onClick = { confirmBulk = true }) {
                        Text("Download all essentials (${byteString(bulk.sumOf { it.size })})", fontWeight = FontWeight.SemiBold)
                    }
                }
                recommended.forEach { (item, note) -> CatalogRow(item, note, installed(item), downloading(item)) { Downloads.start(context, item) } }
            }
            FormSection(
                header = "Research",
                footer = "Build it on a Mac with tools/nomad-packs.sh pubmed, then copy the .zim and its .nomadidx to the phone and open them " +
                    "with Pocket Nomad. It is made for your own use, because publishers may own the abstracts.",
            ) {
                val pubmed = books.firstOrNull { it.file.name.startsWith("pocketnomad_en_pubmed-survival") }
                if (pubmed != null) {
                    LabeledRow("PubMed: Survival Medicine", byteString(pubmed.size))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("PubMed: Survival Medicine", fontWeight = FontWeight.SemiBold)
                        Text(
                            "About 9,600 guideline and systematic-review abstracts on emergency, wilderness and disaster medicine (about 55 MB with its search index).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (bigger.isNotEmpty()) {
                FormSection(header = "Bigger references") {
                    bigger.forEach { (item, note) -> CatalogRow(item, note, installed(item), downloading(item)) { Downloads.start(context, item) } }
                }
            }
        }
        FormSection(footer = "Offline map areas are saved from the Map tab.") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = accent)
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import from Files…") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Download ${byteString(bulk.sumOf { it.size })} of survival references?") },
            text = { Text("Use Wi-Fi if you can. Make sure the phone has room to spare.") },
            confirmButton = { TextButton(onClick = { confirmBulk = false; bulk.forEach { Downloads.start(context, it) } }) { Text("Download all") } },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogRow(item: CatalogItem, note: String, installed: Boolean, downloading: Boolean, onDownload: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            Text(byteString(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            installed -> Icon(Icons.Filled.CheckCircle, contentDescription = "Installed", tint = Brand.green)
            downloading -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, contentDescription = "Download ${item.title}", tint = accent) }
        }
    }
}
