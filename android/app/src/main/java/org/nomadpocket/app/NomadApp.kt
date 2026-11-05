package org.nomadpocket.app

import android.app.Application
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import android.net.Uri
import org.nomadpocket.app.ask.AskPassage
import org.nomadpocket.app.ask.AskSources
import org.nomadpocket.app.ask.BookPassageSource
import org.nomadpocket.app.ask.ModelStore
import org.nomadpocket.app.library.Downloads
import org.nomadpocket.app.library.GuideIndex
import org.nomadpocket.app.library.libraryScope
import org.nomadpocket.library.BookSearch
import org.nomadpocket.library.BookShelf
import org.nomadpocket.app.map.KnownRadioStore
import org.nomadpocket.mesh.Mesh

class NomadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key: the map style and tiles come from OpenFreeMap.
        MapLibre.getInstance(this)
        Mesh.init(this)
        KnownRadioStore.init(this)
        // Off the main thread: build the guides' full-text index (once per app version),
        // open the books and finish any downloads that completed while the app was closed.
        libraryScope.launch {
            GuideIndex.get(this@NomadApp)
            BookShelf.get(this@NomadApp).refresh()
            Downloads.poll(this@NomadApp)
        }
        ModelStore.init(this)
        // Ask searches the guides and the books. Book passages come from the Library
        // module's BookSearch (already ranked per index), and a tapped book source opens
        // the article in the Library reader.
        AskSources.books = BookPassageSource { q, n ->
            BookSearch.passages(this, q, n).map {
                AskPassage.fromBook(it.bookId, it.bookTitle, it.articleTitle, it.path, it.text, it.score)
            }
        }
        AskSources.bookRoute = { bookId, path -> "article/$bookId?path=${Uri.encode(path)}" }
    }
}
