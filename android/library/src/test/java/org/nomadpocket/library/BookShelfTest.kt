package org.nomadpocket.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nomadpocket.library.search.Passage
import org.nomadpocket.library.search.Ranking

class RankingTest {
    private fun p(score: Double, title: String, body: String) = Passage(title, title, body, "", score)

    @Test fun keyTermsAndCoverage() {
        assertEquals(setOf("bleed", "arm"), Ranking.keyTerms("How do I stop bleeding from my arm?").toSet() - "stop")
        assertTrue("stop" in Ranking.keyTerms("stop bleeding"))
        assertEquals(1.0, Ranking.coverage(emptyList(), "anything"), 0.0)
        assertEquals(0.5, Ranking.coverage(listOf("bleed", "arm"), "Bleeding control"), 1e-9)
    }

    @Test fun scoresAreScaledPerIndexThenByCoverage() {
        // A huge book's raw bm25 is much larger, but scaling makes each index's best hit 1.0.
        val book = Ranking.normalized(listOf(p(40.0, "Just Stop Oil", "A protest group."), p(20.0, "Bleeding", "Stop bleeding with pressure.")), 1.0, "stop bleeding")
        val guide = Ranking.normalized(listOf(p(2.0, "Severe Bleeding", "Press hard to stop the bleeding.")), Ranking.GUIDE_BOOST, "stop bleeding")
        // "Just Stop Oil" covers half the question: 1.0 * 0.5² = 0.25.
        assertEquals(0.25, book[0].second, 1e-9)
        assertEquals(0.5, book[1].second, 1e-9)
        assertEquals(1.3, guide[0].second, 1e-9)
        assertTrue(Ranking.normalized(listOf(p(0.0, "x", "y")), 1.0, "x").all { it.second == 0.0 })
        assertTrue(Ranking.normalized(emptyList(), 1.0, "x").isEmpty())
    }
}

class BookShelfTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeBook(dir: File, name: String, title: String, pages: Map<String, String>) {
        val w = TestZimWriter()
        w.setMetadata("Title", title)
        w.setMetadata("Description", "$title for tests")
        pages.forEach { (path, html) -> w.add(path, path.replace('_', ' '), "text/html", html, true) }
        w.mainPath = pages.keys.first()
        w.write(File(dir, name))
    }

    private fun waitForIndexes(shelf: BookShelf) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (shelf.indexProgress.value.isEmpty() && shelf.books.value.all { it.indexFile != null }) return
            Thread.sleep(50)
        }
        throw AssertionError("indexing didn't finish: ${shelf.indexProgress.value} ${shelf.lastError.value}")
    }

    @Test fun emptyShelfHasNoPassages() {
        val shelf = BookShelf(File(tmp.root, "Books"), File(tmp.root, "Indexes"))
        assertTrue(shelf.search("water", 5).isEmpty())
        assertTrue(shelf.books.value.isEmpty())
    }

    @Test fun findsIndexesAndSearchesAcrossBooks() {
        val books = File(tmp.root, "Books").apply { mkdirs() }
        writeBook(books, "medicine_en_2026-01.zim", "Field Medicine", mapOf(
            "Snake_bite" to "<p>Keep the limb still and below the heart. Get to a hospital for antivenom.</p>",
            "Bleeding" to "<p>Press firmly on the wound to stop bleeding. Use a tourniquet only for limbs.</p>",
        ))
        writeBook(books, "water_en_2026-01.zim", "Water Manual", mapOf(
            "Boiling" to "<p>Boil water for one minute; three minutes above 5,000 feet.</p>",
        ))
        // A download still in progress is not a book.
        File(books, "partial.zim.part").writeBytes(ByteArray(100))
        val shelf = BookShelf(books, File(tmp.root, "Indexes"))
        shelf.refresh()
        assertEquals(listOf("Field Medicine", "Water Manual"), shelf.books.value.map { it.title })
        waitForIndexes(shelf)
        assertNull(shelf.lastError.value)
        assertTrue(shelf.books.value.all { it.indexFile!!.parentFile == File(tmp.root, "Indexes") })

        val hits = shelf.search("how do I stop bleeding", 5)
        assertEquals("Bleeding", hits.first().passage.path)
        assertEquals("Field Medicine", hits.first().passage.bookTitle)
        assertTrue(hits.first().snippet.contains("**"))
        val water = shelf.search("boil water", 5).first().passage
        assertEquals("Water Manual", water.bookTitle)
        assertEquals("Boiling", water.articleTitle)
        assertTrue(water.text.contains("one minute"))
        assertNotNull(shelf.archive(water.bookId)?.content(water.path))
        assertTrue(shelf.search("water", 0).isEmpty())

        // Deleting a book removes its index too.
        val medicine = shelf.books.value.first { it.title == "Field Medicine" }
        val idx = medicine.indexFile!!
        shelf.delete(medicine)
        assertFalse(medicine.file.exists())
        assertFalse(idx.exists())
        assertEquals(listOf("Water Manual"), shelf.books.value.map { it.title })
        assertTrue(shelf.search("bleeding tourniquet", 5).none { it.passage.bookTitle == "Field Medicine" })
    }

    @Test fun prebuiltIndexInBooksFolderIsUsed() {
        val books = File(tmp.root, "Books").apply { mkdirs() }
        writeBook(books, "pack.zim", "Pack", mapOf("a" to "<p>Alpha text about knots.</p>"))
        val shelf = BookShelf(books, File(tmp.root, "Indexes"))
        shelf.autoIndex = false
        shelf.refresh()
        val book = shelf.books.value.single()
        assertNull(book.indexFile)
        assertTrue(shelf.indexProgress.value.isEmpty())
        // A sidecar index with the same name, built elsewhere for this book.
        val other = BookShelf(File(tmp.root, "Other"), File(tmp.root, "OtherIdx"))
        org.nomadpocket.library.search.IndexBuilder.build(
            org.nomadpocket.library.zim.ZimArchive(book.file), File(books, "pack.nomadidx"),
        )
        shelf.refresh()
        assertEquals(File(books, "pack.nomadidx"), shelf.books.value.single().indexFile)
        assertEquals("a", shelf.search("knots", 3).first().passage.path)
        assertTrue(other.books.value.isEmpty())
    }
}
