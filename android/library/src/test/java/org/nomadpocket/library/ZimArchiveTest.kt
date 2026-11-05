package org.nomadpocket.library

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nomadpocket.library.zim.LruCache
import org.nomadpocket.library.zim.ZimArchive
import org.nomadpocket.library.zim.ZimException

/** The small ZIM files NomadKit's tests use (NomadKit/Tests/Fixtures/fetch-fixtures.sh); skipped when missing. */
fun fixture(name: String): File {
    val f = File(System.getProperty("fixtures") ?: "../../NomadKit/Tests/Fixtures", name)
    assumeTrue("fixture $name missing; run NomadKit/Tests/Fixtures/fetch-fixtures.sh", f.isFile)
    return f
}

/** A port of NomadKit/Tests/ZIMKitTests/ZIMArchiveTests.swift, plus written archives for each compression. */
class ZimArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    // small-nons.zim: openzim test suite, ZIM 6.1 new namespaces, zstd cluster.
    @Test fun newLayoutSmall() {
        ZimArchive(fixture("small-nons.zim")).use { zim ->
            assertEquals(6, zim.majorVersion)
            assertEquals(1, zim.minorVersion)
            assertTrue(zim.usesNewNamespaceScheme)
            assertEquals(16, zim.entryCount)
            assertEquals(32, zim.uuid.length)
            assertTrue(zim.uuid.all { it in "0123456789abcdef" })
            assertEquals("Test ZIM file", zim.metadata("Title"))
            assertEquals("en", zim.metadata("Language"))
            assertNull(zim.metadata("NoSuchKey"))
            assertEquals("main.html", zim.mainPagePath)
            assertEquals(1, zim.articleCount)

            val page = zim.content("main.html")!!
            assertEquals("text/html", page.mimeType)
            assertTrue(String(page.data).contains("Test ZIM file"))

            val png = zim.illustration()!!
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), png.copyOfRange(0, 4))
            assertEquals("image/png", zim.content("favicon.png")!!.mimeType)

            assertNull(zim.entry("does-not-exist.html"))
            assertNull(zim.content("does-not-exist.html"))
            // Metadata namespace isn't reachable as content.
            assertNull(zim.entry("Title"))

            assertEquals(listOf("main.html"), zim.titleSearch("test z", 5).map { it.path })
            assertEquals("Test ZIM file", zim.titleSearch("TEST", 5).firstOrNull()?.title)
            assertTrue(zim.titleSearch("", 5).isEmpty())
            assertTrue(zim.titleSearch("zzz", 5).isEmpty())

            val paths = ArrayList<String>()
            zim.forEachArticle { paths.add(it.path); true }
            assertEquals(listOf("main.html"), paths)
        }
    }

    // small-withns.zim: ZIM 5.0 old namespaces (A/I/-/M), xz cluster.
    @Test fun oldLayoutSmall() {
        ZimArchive(fixture("small-withns.zim")).use { zim ->
            assertEquals(5, zim.majorVersion)
            assertFalse(zim.usesNewNamespaceScheme)
            assertEquals(17, zim.entryCount)
            assertEquals("A/main.html", zim.mainPagePath)
            assertEquals("2020-11-15", zim.metadata("Date"))

            // Old layout returns namespaced paths and accepts both forms.
            val main = zim.content("A/main.html")!!
            assertEquals("A/main.html", main.path)
            assertEquals("text/html", main.mimeType)
            assertArrayEquals(main.data, zim.content("main.html")!!.data)
            assertEquals("A/main.html", zim.entry("main.html")?.path)
            assertEquals("image/png", zim.entry("I/favicon.png")?.mimeType)
            assertEquals("I/favicon.png", zim.entry("favicon.png")?.path)

            // -/favicon is a redirect to I/favicon.png.
            val fav = zim.entry("-/favicon")!!
            assertTrue(fav.isRedirect)
            assertEquals("image/png", fav.mimeType)
            assertEquals("I/favicon.png", zim.content("-/favicon")?.path)
            assertNotNull(zim.illustration())

            assertEquals(listOf("A/main.html"), zim.titleSearch("test", 5).map { it.path })
            val paths = ArrayList<String>()
            zim.forEachArticle { paths.add(it.path); true }
            assertEquals(listOf("A/main.html"), paths)
        }
    }

    // wikibooks_be: real 2017 Kiwix file, ZIM 5.0 old namespaces, xz, Cyrillic titles, redirects.
    @Test fun oldLayoutWikibooksXZ() {
        ZimArchive(fixture("wikibooks_be_all_nopic_2017-02.zim")).use { zim ->
            assertEquals("bel", zim.metadata("Language"))
            assertEquals("Wikibooks", zim.metadata("Creator"))
            val mainPath = zim.mainPagePath!!
            assertTrue(mainPath.startsWith("A/"))
            assertTrue(zim.content(mainPath)!!.data.size > 1000)

            // A/index.htm is a redirect to the main page.
            assertTrue(zim.entry("A/index.htm")!!.isRedirect)
            assertEquals(mainPath, zim.content("A/index.htm")?.path)

            // Assets in the "-" namespace.
            assertEquals("text/css", zim.content("-/s/style.css")!!.mimeType)

            // Case-insensitive Cyrillic prefix search.
            val hits = zim.titleSearch("італьянская мова", 20)
            assertTrue(hits.any { it.title == "Італьянская мова" })
            assertEquals("Італьянская мова", hits.first().title) // exact match first
            assertTrue(hits.size >= 5)

            var count = 0
            zim.forEachArticle { e ->
                assertFalse(e.isRedirect)
                assertTrue(e.mimeType.startsWith("text/html"))
                assertTrue(e.path.startsWith("A/"))
                count++
                true
            }
            assertEquals(zim.articleCount, count)
            assertTrue(count > 30)

            // Early stop.
            var seen = 0
            zim.forEachArticle { seen++; seen < 3 }
            assertEquals(3, seen)
        }
    }

    // Climate change mini: ZIM 6.2, zstd, ~3800 articles, header title list.
    @Test fun newLayoutClimateZstd() {
        ZimArchive(fixture("wikipedia_en_climate_change_mini_2024-06.zim")).use { zim ->
            assertEquals(2, zim.minorVersion)
            assertEquals("mini", zim.metadata("Flavour"))
            assertEquals(3821, zim.articleCount)
            assertEquals("index", zim.mainPagePath)

            val article = zim.content("Sea_level_rise")!!
            assertEquals("text/html", article.mimeType)
            assertTrue(String(article.data).contains("Sea level rise"))

            // Redirect resolution.
            val redirect = zim.entry("10-year_flood")!!
            assertTrue(redirect.isRedirect)
            assertEquals("text/html", redirect.mimeType)
            assertEquals("100-year_flood", zim.content("10-year_flood")?.path)

            val hits = zim.titleSearch("global warming", 10)
            assertEquals(10, hits.size)
            assertEquals("Global warming", hits.first().title)
            assertTrue(hits.all { it.title.lowercase().startsWith("global warming") })
            assertFalse(zim.titleSearch("sea LEVEL r", 3).isEmpty())
        }
    }

    // Ray Charles maxi: ZIM 6.3, zstd, no header title list -> uses X/listing/titleOrdered/v1.
    @Test fun newLayoutRayCharlesListingFallback() {
        ZimArchive(fixture("wikipedia_en_ray-charles_maxi.zim")).use { zim ->
            assertEquals(3, zim.minorVersion)
            assertEquals("Ray Charles", zim.metadata("Title"))
            assertEquals("index", zim.mainPagePath)
            assertNotNull(zim.content("index"))

            val hits = zim.titleSearch("georgia on", 5)
            assertFalse(hits.isEmpty())
            assertTrue(hits.all { it.title.lowercase().startsWith("georgia on") })
            assertEquals("Georgia_on_My_Mind", zim.content(hits[0].path)!!.path)

            // The two orders yield the same set of articles.
            val listing = HashSet<String>()
            val storage = HashSet<String>()
            var lastPos = 0
            zim.forEachArticle { listing.add(it.path); true }
            zim.forEachArticle(ZimArchive.ArticleOrder.STORAGE) { e, pos ->
                storage.add(e.path)
                assertTrue(pos <= zim.articleCount)
                lastPos = pos
                true
            }
            assertEquals(listing, storage)
            assertEquals(zim.articleCount, lastPos)
            assertTrue(listing.size > 100)
        }
    }

    @Test fun rejectsNonZim() {
        val f = tmp.newFile("notzim.zim")
        f.writeBytes(ByteArray(200) { 0x41 })
        assertThrows<ZimException.NotAZimFile> { ZimArchive(f) }
        assertThrows<ZimException> { ZimArchive(File("/nonexistent/x.zim")) }
        val short = tmp.newFile("short.zim").apply { writeBytes(ByteArray(10)) }
        assertThrows<ZimException.NotAZimFile> { ZimArchive(short) }
    }

    @Test fun concurrentReads() {
        ZimArchive(fixture("wikipedia_en_climate_change_mini_2024-06.zim")).use { zim ->
            val paths = ArrayList<String>()
            zim.forEachArticle { paths.add(it.path); paths.size < 200 }
            val expected = paths.map { zim.content(it)!!.data.size }
            val failures = AtomicInteger()
            val pool = Executors.newFixedThreadPool(8)
            for (worker in 0 until 8) pool.execute {
                for ((i, p) in paths.withIndex()) {
                    if (i % 8 != worker && i % 3 != 0) continue
                    if (runCatching { zim.content(p)?.data?.size }.getOrNull() != expected[i]) failures.incrementAndGet()
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
        }
    }

    @Test fun lruCache() {
        val c = LruCache<Int, String>(2)
        c.set(1, "a"); c.set(2, "b")
        c.get(1)
        c.set(3, "c") // evicts 2
        assertEquals("a", c.get(1))
        assertNull(c.get(2))
        assertEquals("c", c.get(3))
    }

    /** A port of ZIMWriterTests: written archives round-trip through the reader, for each compression. */
    @Test fun writtenArchivesRoundTrip() {
        for (compression in TestZimWriter.Compression.entries) {
            val w = TestZimWriter()
            w.compression = compression
            w.clusterSize = 64 // force several clusters
            w.padding = 8 // stray bytes after each compressed stream must be ignored
            w.setMetadata("Title", "Test Pack")
            w.setMetadata("Language", "eng")
            w.add("index", "Home", "text/html", "<h1>Home</h1>", false)
            for (n in 1..20) w.add("pmid/$n", "Snake bite review $n", "text/html", "<p>Keep the limb still. Paper $n.</p>", true)
            w.add(TestZimWriter.Item("logo.png", "logo.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), false))
            w.mainPath = "index"
            val f = tmp.newFile("writer-$compression.zim")
            w.write(f)

            ZimArchive(f).use { zim ->
                assertTrue(zim.usesNewNamespaceScheme)
                assertEquals("Test Pack", zim.metadata("Title"))
                assertEquals("index", zim.mainPagePath)
                assertEquals(20, zim.articleCount)
                assertTrue(zim.clusterCount > 2)
                val page = zim.content("pmid/7")!!
                assertEquals("<p>Keep the limb still. Paper 7.</p>", String(page.data))
                assertEquals("text/html", page.mimeType)
                assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), zim.content("logo.png")!!.data)
                // Percent-encoded paths, as a WebView sends them, are decoded as a fallback.
                assertEquals("pmid/7", zim.content("pmid%2F7")?.path)
                assertEquals("Snake bite review 1", zim.titleSearch("snake bite review 1", 5).first().title)
                var seen = 0
                zim.forEachArticle { seen++; true }
                assertEquals(20, seen)
            }
        }
    }

    @Test fun writerRejectsDuplicatePaths() {
        val w = TestZimWriter()
        w.add("a", "A", "text/html", "", true)
        w.add("a", "A", "text/html", "", true)
        assertThrows<IllegalArgumentException> { w.write(tmp.newFile("dup.zim")) }
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return
        throw AssertionError("expected ${T::class.simpleName}, got $t", t)
    }
    throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
}
