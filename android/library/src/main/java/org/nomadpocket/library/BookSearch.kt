package org.nomadpocket.library

import android.content.Context

/**
 * A passage from a book's full-text index. [score] is comparable across books
 * (see [org.nomadpocket.library.search.Ranking]); higher is better. [path] opens
 * in the book's reader (`nomad://book/<bookId>/<path>`).
 */
data class BookPassage(
    val bookId: String,
    val bookTitle: String,
    val articleTitle: String,
    val path: String,
    val text: String,
    val score: Double,
)

/** A [BookPassage] with its `**bold**`-marked snippet, for result lists. */
data class BookHit(val passage: BookPassage, val snippet: String)

/** Full-text search over every installed book, for Ask and the Library search. */
object BookSearch {
    /**
     * The best passages for [question] across all installed books' indexes: each
     * index's bm25 scores scaled against its own best hit, times question coverage².
     * Empty when there are no books or none is indexed yet. Does file I/O: call it
     * off the main thread.
     */
    fun passages(context: Context, question: String, limit: Int): List<BookPassage> =
        BookShelf.get(context.applicationContext).search(question, limit).map { it.passage }
}
