package org.nomadpocket.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nomadpocket.library.search.HtmlText
import org.nomadpocket.library.search.IndexBuilder
import org.nomadpocket.library.search.IndexDocument
import org.nomadpocket.library.search.IndexException
import org.nomadpocket.library.search.PassageSplitter
import org.nomadpocket.library.search.SearchIndex
import org.nomadpocket.library.zim.ZimArchive

/** A port of NomadKit/Tests/NomadSearchTests/NomadSearchTests.swift. */
class HtmlTextTest {
    @Test fun extractBasics() {
        val html = """
            <!DOCTYPE html><html><head><title>Water &amp; Fire</title>
            <style>body { color: red }</style><script>var x = "<p>no</p>";</script></head>
            <body><nav>Menu Home</nav>
            <h1>Water</h1>
            <div id="toc"><ul><li>1 Boiling</li></ul></div>
            <p>Boil water for   <b>one minute</b>.<sup class="reference">[1]</sup> It&#39;s safe&nbsp;then &#x2014; mostly.</p>
            <!-- comment <p>hidden</p> -->
            <h2>Boiling<span class="mw-editsection">[edit]</span></h2>
            <p>At altitude, boil 3 minutes.<br>Let it cool.</p>
            <h2>References</h2><ol><li>Some cite</li></ol>
            <h2>Storage</h2><p>Keep it covered.</p>
            <h2>External links</h2><ul><li><a href="x">x.org</a></li></ul>
            <footer>Footer text</footer></body></html>
        """.trimIndent()
        val (title, text) = HtmlText.extract(html)
        assertEquals("Water & Fire", title)
        assertTrue(text, text.contains("Boil water for one minute. It's safe then — mostly."))
        assertTrue(text, text.contains("At altitude, boil 3 minutes.\nLet it cool."))
        assertTrue(text, text.contains("\n\nBoiling\n\n"))
        assertTrue(text, text.contains("Keep it covered."))
        for (junk in listOf("color: red", "var x", "no", "Menu", "1 Boiling", "[1]", "[edit]", "hidden", "Some cite",
            "x.org", "Footer", "References", "External links")) {
            assertFalse("should drop $junk: $text", text.contains(junk))
        }
        assertFalse(text.contains("  "))
        assertFalse(text.contains("\n\n\n"))
    }

    @Test fun titleFallsBackToH1AndOddInput() {
        assertEquals("Knots", HtmlText.extract("<h1>Knots</h1><p>Bowline.</p>").title)
        assertEquals("", HtmlText.extract("").text)
        val odd = HtmlText.extract("a < b && c > d &bogus; <p unclosed")
        assertTrue(odd.text, odd.text.startsWith("a < b && c > d &bogus;"))
    }

    @Test fun tablesKeepColumnHeaders() {
        val html = """
            <p>Use this table.</p><table><tr><th>Amount of clear water</th><th>6% bleach</th><th>8.25% bleach</th></tr>
            <tr><td>1 gallon (3.8 L)</td><td>8 drops</td><td>6 drops</td></tr>
            <tr><td>2 gallons</td><td>16 drops</td><td>12 drops</td></tr></table><p>Wait 30 minutes.</p>
        """.trimIndent()
        val text = HtmlText.extract(html).text
        assertTrue(text, text.contains("Amount of clear water: 1 gallon (3.8 L); 6% bleach: 8 drops; 8.25% bleach: 6 drops"))
        assertTrue(text, text.contains("Amount of clear water: 2 gallons; 6% bleach: 16 drops; 8.25% bleach: 12 drops"))
        assertTrue(text, text.endsWith("Wait 30 minutes."))
        // A table without a header row still reads as plain cells.
        assertTrue(HtmlText.extract("<table><tr><td>a</td><td>b</td></tr></table>").text.contains("a; b"))
    }

    @Test fun passageSplitter() {
        val para = "Water is life. ".repeat(20) // ~300 chars
        val text = (0 until 12).joinToString("\n\n") { "P$it $para" }
        val passages = PassageSplitter.split(text)
        assertTrue(passages.size > 2)
        for (p in passages.dropLast(1)) assertTrue("${p.length}", p.length in 900..1200)
        assertTrue(passages.all { it.length <= 1500 })

        val long = "Sentence number here. ".repeat(200) // one 4400-char paragraph
        val chunks = PassageSplitter.split(long)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 1500 })
        assertTrue(chunks[0].endsWith("."))

        assertEquals(listOf("Short stub."), PassageSplitter.split("Short stub."))
        assertEquals(emptyList<String>(), PassageSplitter.split(""))
    }
}

class QuerySanitizerTest {
    @Test fun sanitizer() {
        assertEquals("\"purify\" OR \"water\"", SearchIndex.ftsQuery("How do I purify water?"))
        assertEquals("\"water\" OR \"fire\"", SearchIndex.ftsQuery("\"water\" AND fire*"))
        // All stopwords: kept, but quoted, so operators are inert.
        assertEquals("\"near\" OR \"or\" OR \"not\"", SearchIndex.ftsQuery("NEAR(a b) OR NOT x"))
        assertNull(SearchIndex.ftsQuery(""))
        assertNull(SearchIndex.ftsQuery("   \"\" * ** () - ^ :"))
        assertEquals("\"the\" OR \"who\"", SearchIndex.ftsQuery("the who"))
        assertEquals("\"café\" OR \"911\"", SearchIndex.ftsQuery("Café 911 x"))
        assertEquals("\"water\"", SearchIndex.ftsQuery("water water WATER"))
    }
}

class IndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tempIndex() = File(tmp.root, "nomadkit-${System.nanoTime()}.nomadidx")

    /** The SQLite bundled with the app has FTS5 (Android's own SQLite doesn't promise it). */
    @Test fun bundledSqliteHasFts5() {
        BundledSQLiteDriver().open(":memory:").use { db ->
            val options = ArrayList<String>()
            db.prepare("PRAGMA compile_options").use { s -> while (s.step()) options.add(s.getText(0)) }
            assertTrue(options.toString(), "ENABLE_FTS5" in options)
        }
    }

    /** A .nomadidx built elsewhere (Python's SQLite, the Mac tool's schema) opens and searches here. */
    @Test fun opensPrebuiltFixtureIndex() {
        val f = tmp.newFile("mini.nomadidx")
        javaClass.getResourceAsStream("/fixtures/mini.nomadidx")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        SearchIndex(f).use { index ->
            assertTrue(index.isComplete)
            assertEquals("00112233445566778899aabbccddeeff", index.zimUuid)
            assertEquals("1", index.meta("schema_version"))
            val hits = index.search("how long should I boil water", 5)
            assertEquals("guides/water.html", hits.first().path)
            assertTrue(hits.first().snippet.contains("**"))
            assertTrue(hits.first().score > 0)
            // Porter stemming: "bites" finds "bitten".
            assertEquals("guides/snake.html", index.search("snake bites", 5).first().path)
        }
    }

    @Test fun documentIndexAndOddQueries() {
        val docs = listOf(
            IndexDocument("guides/water.html", "Water purification", "<p>Boil water for one minute to kill pathogens. Chlorine tablets also work.</p>".toByteArray()),
            IndexDocument("guides/fire.html", "Starting a fire", "<p>Use a ferro rod and dry tinder. Build a teepee of kindling.</p>".toByteArray()),
            IndexDocument("guides/empty.html", "Empty", "<script>x</script>".toByteArray()),
            IndexDocument("guides/redirect.html", "Moved",
                "<html><head><meta http-equiv=\"refresh\" content=\"0;URL='./x'\"></head><body>Moved</body></html>".toByteArray()),
        )
        val out = tempIndex()
        val progressCalls = AtomicInteger()
        val stats = IndexBuilder.build(docs.asSequence(), out, progress = { _, _ -> progressCalls.incrementAndGet() })
        assertEquals(2, stats.articles)
        assertTrue(progressCalls.get() > 0)
        assertFalse(File(out.path + ".tmp").exists())

        SearchIndex(out).use { index ->
            assertTrue(index.isComplete)
            assertNull(index.zimUuid)
            assertEquals("1", index.meta("schema_version"))
            assertEquals("2", index.meta("articles"))

            val hits = index.search("how do I boil water?", 5)
            assertEquals("guides/water.html", hits.first().path)
            assertEquals("Water purification", hits.first().title)
            assertTrue(hits.first().snippet.contains("**"))
            assertTrue(hits.first().score > 0)
            // Title weighting: "fire" in title.
            assertEquals("guides/fire.html", index.search("fire", 5).first().path)
            // Stemming via porter: "boiling" matches "Boil".
            assertEquals("guides/water.html", index.search("boiling", 5).first().path)

            for (odd in listOf("", "\"", "\"\"\"", "*", "water*", "AND", "OR OR", "NEAR(", "water AND", "(", "-x", "a:b", "^",
                "'; DROP TABLE meta; --", "🔥")) {
                index.search(odd, 5) // must not throw
            }
            assertTrue(index.search("", 5).isEmpty())
            assertEquals("guides/water.html", index.search("\"water\" AND*", 5).first().path)
            assertTrue(index.search("water", 0).isEmpty())
        }
    }

    @Test fun cancelLeavesNoFiles() {
        val docs = (0 until 10).map { IndexDocument("$it", "T$it", "<p>text $it</p>".toByteArray()) }
        val out = tempIndex()
        assertThrows<Exception> { IndexBuilder.build(docs.asSequence(), out, isCancelled = { true }) }
        assertFalse(out.exists())
        assertFalse(File(out.path + ".tmp").exists())
    }

    @Test fun zimIndexBuildAndSearch() {
        val zimFile = fixture("wikipedia_en_ray-charles_maxi.zim")
        ZimArchive(zimFile).use { zim ->
            val out = tempIndex()
            var last = 0 to 0
            val stats = IndexBuilder.build(zim, out, progress = { d, t -> last = d to t })
            assertTrue(stats.articles > 100)
            assertTrue(stats.passages > stats.articles)
            assertEquals(last.second, last.first)

            SearchIndex(out).use { index ->
                assertEquals(zim.uuid, index.zimUuid)
                assertEquals(stats.passages.toString(), index.meta("passages"))

                val hits = index.search("Georgia on my mind state song", 5)
                assertEquals("Georgia_on_My_Mind", hits.first().path)
                assertTrue(hits.all { it.body.length in 900..1600 || it.body.length < 900 })
                // Paths in the index open in the archive.
                for (h in hits) assertNotNull(h.path, zim.content(h.path))
                // Scores are higher-is-better and sorted.
                assertEquals(hits.map { it.score }.sortedDescending(), hits.map { it.score })

                assertFalse(index.search("what instrument did ray charles play", 10).isEmpty())
                // Boilerplate sections are not indexed.
                assertTrue(index.search("\"retrieved\"", 50).size < 10)
            }
        }
    }

    @Test fun zimIndexOldLayoutMaxArticles() {
        ZimArchive(fixture("wikibooks_be_all_nopic_2017-02.zim")).use { zim ->
            val out = tempIndex()
            val stats = IndexBuilder.build(zim, out, maxArticles = 10)
            assertTrue(stats.articles in 1..10)
            SearchIndex(out).use { index ->
                val hits = index.search("кухня", 5)
                assertFalse(hits.isEmpty())
                assertTrue(hits.all { it.path.startsWith("A/") })
                assertNotNull(zim.content(hits[0].path))
            }
        }
    }

    @Test fun openGarbageFails() {
        val f = tempIndex()
        f.writeText("not sqlite")
        assertThrows<IndexException> { SearchIndex(f) }
        assertThrows<IndexException> { SearchIndex(File(tmp.root, "missing.nomadidx")) }
    }
}
