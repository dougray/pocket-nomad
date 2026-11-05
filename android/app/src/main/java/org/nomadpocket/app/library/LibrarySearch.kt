package org.nomadpocket.app.library

import android.content.Context
import java.io.File
import org.nomadpocket.app.BuildConfig
import org.nomadpocket.app.guides.GuideLibrary
import org.nomadpocket.library.BookShelf
import org.nomadpocket.library.search.IndexBuilder
import org.nomadpocket.library.search.IndexDocument
import org.nomadpocket.library.search.Ranking
import org.nomadpocket.library.search.SearchIndex

/** Where a search hit came from, so the app can open it. */
sealed interface SourceRef {
    data class Guide(val file: String) : SourceRef          // e.g. "first-aid/bleeding.html"
    data class Book(val id: String, val path: String) : SourceRef
}

data class SearchHit(
    val ref: SourceRef,
    val sourceName: String,
    val title: String,
    /** Matched words marked `**bold**`. */
    val snippet: String,
    val body: String,
    val score: Double,
)

/**
 * The full-text index of the 69 built-in guides, built on the phone once per app
 * version (a second or two), like `GuideStore.prepareIndex` on iOS. Keywords go in
 * the title field, which search weights 4x, so "purify" finds Making Water Safe
 * before the surface-cleaning guide.
 */
object GuideIndex {
    @Volatile private var index: SearchIndex? = null

    fun file(context: Context): File =
        File(context.cacheDir, "guides-${BuildConfig.VERSION_NAME}-${BuildConfig.VERSION_CODE}-v3.nomadidx")

    /** The index, building it first if needed. Null if it can't be built. Does file I/O. */
    fun get(context: Context): SearchIndex? = index ?: synchronized(this) {
        index ?: runCatching { open(context) }.getOrNull().also { index = it }
    }

    private fun open(context: Context): SearchIndex {
        val out = file(context)
        if (!out.exists()) {
            // Old versions' indexes are left behind in the cache; Android clears it when space runs low.
            context.cacheDir.listFiles { f -> f.name.startsWith("guides-") && f.extension == "nomadidx" }?.forEach { it.delete() }
            val library = GuideLibrary.get(context)
            val docs = library.allGuides.asSequence().mapNotNull { guide ->
                val html = runCatching { context.assets.open("${GuideLibrary.ROOT}/${guide.file}").use { it.readBytes() } }.getOrNull()
                    ?: return@mapNotNull null
                IndexDocument(guide.file, (listOf(guide.title) + guide.keywords).joinToString(" · "), html)
            }
            IndexBuilder.build(docs, out, sourceId = "guides", total = library.allGuides.size)
        }
        return SearchIndex(out)
    }
}

/**
 * One place to search the built-in guides and every indexed book, a port of the
 * iOS app's `SearchService`. Each index's scores are scaled against its own best
 * hit and by question coverage (see [Ranking]); guides get [Ranking.GUIDE_BOOST].
 */
object LibrarySearch {
    /** Best passages across all sources. Does file I/O: call off the main thread. */
    fun search(context: Context, query: String, limit: Int = 8, includeGuides: Boolean = true): List<SearchHit> {
        val hits = ArrayList<SearchHit>()
        if (includeGuides) {
            val guides = GuideLibrary.get(context)
            val passages = GuideIndex.get(context)?.let { runCatching { it.search(query, limit) }.getOrNull() } ?: emptyList()
            for ((p, score) in Ranking.normalized(passages, Ranking.GUIDE_BOOST, query)) {
                hits.add(SearchHit(SourceRef.Guide(p.path), "Survival Guide", guides.guide(p.path)?.title ?: p.title, p.snippet, p.body, score))
            }
        }
        for (h in BookShelf.get(context).search(query, limit)) {
            val p = h.passage
            hits.add(SearchHit(SourceRef.Book(p.bookId, p.path), p.bookTitle, p.articleTitle, h.snippet, p.text, p.score))
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    /** Best passage per article, for result lists. */
    fun articles(context: Context, query: String, limit: Int = 25, includeGuides: Boolean = true): List<SearchHit> {
        val seen = HashSet<SourceRef>()
        return search(context, query, limit * 3, includeGuides).filter { seen.add(it.ref) }.take(limit)
    }
}
