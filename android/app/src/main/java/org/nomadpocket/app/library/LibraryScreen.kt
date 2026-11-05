package org.nomadpocket.app.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.accent
import org.nomadpocket.app.ui.cardColor
import org.nomadpocket.library.Book
import org.nomadpocket.library.BookShelf
import java.text.NumberFormat

/** Bytes as "1.2 GB", like ByteCountFormatter on iOS (decimal units). */
fun byteString(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes bytes"
}

/** Polls downloads and rescans the shelf while a Library screen is showing. */
@Composable
fun KeepLibraryFresh() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { BookShelf.get(context).refresh() }
        while (true) {
            withContext(Dispatchers.IO) { Downloads.poll(context) }
            delay(1000)
        }
    }
}

/** The search field used by the Library screens. */
@Composable
fun LibrarySearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = cardColor, focusedContainerColor = cardColor, unfocusedBorderColor = cardColor),
    )
}

/** The Library tab: installed books, search across every book and guide, and Get Content. */
@Composable
fun LibraryScreen(onBook: (String) -> Unit, onHit: (SourceRef) -> Unit, onGetContent: () -> Unit) {
    val context = LocalContext.current
    val shelf = remember { BookShelf.get(context) }
    val books by shelf.books.collectAsState()
    val progress by shelf.indexProgress.collectAsState()
    val error by shelf.lastError.collectAsState()
    val importing by Importer.importing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    KeepLibraryFresh()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onGetContent) { Icon(Icons.Filled.Download, contentDescription = "Get content") }
        }
        LibrarySearchField(query, { query = it }, "Search every book")
        importing?.let { ImportingRow(it) }
        when {
            query.isNotBlank() -> {
                val hits by produceState<List<SearchHit>?>(null, query, books) {
                    delay(250)
                    value = withContext(Dispatchers.IO) { LibrarySearch.articles(context, query, limit = 30) }
                }
                SearchResultsList(hits, "No matches for “$query”.", onHit)
            }
            books.isEmpty() -> NoBooks(onGetContent)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(books, key = { it.id }) { book ->
                    BookRow(book, progress[book.id], onOpen = { onBook(book.id) }, onDelete = { shelf.delete(book) })
                    HorizontalDivider()
                }
                item {
                    Footnote(
                        "Add .zim files from Kiwix, or a .nomadidx search index from the Mac pack script, by opening them with Pocket Nomad " +
                            "from your file manager or browser downloads, or with Get Content › Import from Files. Long-press a book to delete it.",
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
        error?.let { msg ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, Modifier.weight(1f), color = Brand.danger, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { shelf.clearError() }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun NoBooks(onGetContent: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text("No books yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Download offline references like WikiMed and the Post-Disaster Library while you have Wi-Fi. They keep working with no signal.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGetContent) { Text("Get content") }
    }
}

@Composable
fun ImportingRow(state: Importer.State) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Importing ${state.name}…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (state.total > 0) LinearProgressIndicator(progress = { (state.copied.toFloat() / state.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: Book, indexProgress: Double?, onOpen: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { confirm = true }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookIcon(book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, fontWeight = FontWeight.SemiBold)
            if (book.summary.isNotEmpty()) {
                Text(book.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val small = MaterialTheme.typography.labelSmall
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(byteString(book.size), style = small, color = muted)
                Text(if (book.articleCount == 1) "1 article" else "${NumberFormat.getIntegerInstance().format(book.articleCount)} articles", style = small, color = muted)
                when {
                    indexProgress != null -> Text("Indexing ${(indexProgress * 100).toInt()}%", style = small, color = accent)
                    book.indexFile == null -> Text("Title search only", style = small, color = Brand.orange)
                }
            }
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${book.title}", tint = MaterialTheme.colorScheme.outline) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${book.title}?") },
            text = { Text("This frees ${byteString(book.size)}. You can download it again later.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = Brand.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookIcon(book: Book) {
    val context = LocalContext.current
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, book.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BookShelf.get(context).archive(book.id)?.illustration(48)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription = null, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(44.dp).background(cardColor, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = accent)
        }
    }
}

/** `**bold**` snippets as styled text. */
fun snippetText(snippet: String): AnnotatedString = buildAnnotatedString {
    val parts = snippet.split("**")
    for ((i, part) in parts.withIndex()) {
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

/** Search hits as cards: source, title, snippet. Null hits means still searching. */
@Composable
fun SearchResultsList(hits: List<SearchHit>?, emptyText: String, onHit: (SourceRef) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hits != null && hits.isEmpty()) {
            item { Text(emptyText, Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(hits ?: emptyList()) { hit -> SearchHitCard(hit) { onHit(hit.ref) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SearchHitCard(hit: SearchHit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(hit.sourceName.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = accent)
        Text(hit.title, fontWeight = FontWeight.SemiBold)
        Text(
            snippetText(hit.snippet.ifEmpty { hit.body }),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A book's main page, with a title search for that book (BookHomeView on iOS). */
@Composable
fun BookHome(
    modifier: Modifier,
    book: Book,
    query: String,
    onArticle: (String) -> Unit,
    onPdf: (String, String) -> Unit,
    onTitle: (String) -> Unit,
) {
    val context = LocalContext.current
    val archive = remember(book.id) { BookShelf.get(context).archive(book.id) }
    val mainPath = remember(book.id) { archive?.mainPagePath }
    if (query.isNotBlank()) {
        val results by produceState<List<Pair<String, String>>?>(null, query) {
            value = withContext(Dispatchers.IO) { archive?.titleSearch(query, 50)?.map { it.path to it.title } ?: emptyList() }
        }
        LazyColumn(modifier) {
            if (results?.isEmpty() == true) item { Text("No article titles match “$query”.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results ?: emptyList()) { (path, title) ->
                Text(title, Modifier.fillMaxWidth().clickable { onArticle(path) }.padding(horizontal = 16.dp, vertical = 14.dp))
                HorizontalDivider()
            }
        }
    } else if (mainPath != null) {
        BookReader(modifier, book.id, mainPath, onTitle, onPdf)
    } else {
        BookArticleList(modifier, book, onArticle)
    }
}

/** For books without a main page: the first articles in title order. */
@Composable
private fun BookArticleList(modifier: Modifier, book: Book, onArticle: (String) -> Unit) {
    val context = LocalContext.current
    val list by produceState<List<Pair<String, String>>>(emptyList(), book.id) {
        value = withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            BookShelf.get(context).archive(book.id)?.forEachArticle { e -> if (e.title.isNotEmpty()) out.add(e.path to e.title); out.size < 200 }
            out
        }
    }
    LazyColumn(modifier) {
        item { Footnote("This book has no main page. Its first articles:", Modifier.padding(16.dp)) }
        items(list) { (path, title) ->
            Text(title, Modifier.fillMaxWidth().clickable { onArticle(path) }.padding(horizontal = 16.dp, vertical = 14.dp))
            HorizontalDivider()
        }
    }
}

/** The menu on a book: build its index when it has none. */
@Composable
fun BookMenu(book: Book) {
    val context = LocalContext.current
    val shelf = remember { BookShelf.get(context) }
    val progress by shelf.indexProgress.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (book.indexFile != null && progress[book.id] == null) return
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (progress[book.id] != null) {
            DropdownMenuItem(text = { Text("Indexing ${(progress[book.id]!! * 100).toInt()}% · Stop") }, onClick = { open = false; shelf.cancelIndexing() })
        } else {
            DropdownMenuItem(text = { Text("Build full-text search index") }, onClick = { open = false; shelf.enqueueIndex(book.id) })
        }
    }
}
