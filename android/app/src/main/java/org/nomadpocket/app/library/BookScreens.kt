package org.nomadpocket.app.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.nomadpocket.app.ui.SubScreen
import org.nomadpocket.library.BookShelf

/** A book: its main page, or a title search when the search icon is on. */
@Composable
fun BookScreen(bookId: String, onBack: () -> Unit, onArticle: (String) -> Unit, onPdf: (String, String) -> Unit) {
    val context = LocalContext.current
    val shelf = remember { BookShelf.get(context) }
    val books by shelf.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId } ?: remember(bookId) { shelf.ensureLoaded(); shelf.book(bookId) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    SubScreen(
        title = book?.title ?: "Book",
        onBack = onBack,
        actions = {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Filled.SearchOff else Icons.Filled.Search, contentDescription = if (searching) "Close search" else "Find an article by title")
            }
            if (book != null) BookMenu(book)
        },
    ) { m ->
        if (book == null) {
            Text("Book not found", Modifier.padding(24.dp))
        } else {
            Column(m) {
                if (searching) LibrarySearchField(query, { query = it }, "Find an article by title")
                BookHome(Modifier.fillMaxSize(), book, if (searching) query else "", onArticle, onPdf) { pageTitle = it }
            }
        }
    }
}

/** One page in a book, opened from search or a title match. */
@Composable
fun ArticleScreen(bookId: String, path: String, onBack: () -> Unit, onPdf: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    SubScreen(title, onBack) { m -> BookReader(m, bookId, path, { title = it }, onPdf) }
}

@Composable
fun PdfViewerScreen(bookId: String, path: String, onBack: () -> Unit) {
    SubScreen(path.substringAfterLast('/').removeSuffix(".pdf").replace('_', ' '), onBack) { m -> PdfScreen(m, bookId, path) }
}
