package org.nomadpocket.app

import android.app.Application
import kotlinx.coroutines.MainScope
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
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.Mesh
import org.nomadpocket.mesh.NearbyMeshCore

class NomadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key: the map style and tiles come from OpenFreeMap.
        MapLibre.getInstance(this)
        Mesh.init(this)
        KnownRadioStore.init(this)
        connectNearbyRadios()
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

    /** Radio settings reads the downloaded MeshCore radios' settings, and can start a download. */
    private fun connectNearbyRadios() {
        MainScope().launch {
            KnownRadioStore.state.collect { s ->
                Mesh.nearbyMeshCore.value = NearbyMeshCore(
                    downloaded = s.updated != null,
                    params = s.radios.filter { it.firmware == Firmware.MESHCORE }.map { it.params },
                )
            }
        }
        Mesh.downloadNearby = {
            val fix = Mesh.location.currentLocation()
            if (fix == null) {
                "No GPS fix yet. Allow location, step outside, and try again, or download from Map › Known radios."
            } else {
                KnownRadioStore.download(fix.latitude, fix.longitude, 200, null)
                KnownRadioStore.state.value.lastError
            }
        }
    }
}
