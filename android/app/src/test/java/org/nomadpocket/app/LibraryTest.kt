package org.nomadpocket.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nomadpocket.app.library.BookUrls
import org.nomadpocket.app.library.GuideIndex
import org.nomadpocket.app.library.Importer
import org.nomadpocket.app.library.LibrarySearch
import org.nomadpocket.app.library.OpdsParser
import org.nomadpocket.app.library.SourceRef
import org.nomadpocket.app.library.byteString
import org.nomadpocket.app.library.snippetText
import org.nomadpocket.library.BookSearch
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun opdsEntriesBecomeCatalogItems() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="https://specs.opds.io/opds-1.2">
              <title>Kiwix</title>
              <entry>
                <id>urn:uuid:1</id><title>Water</title><summary>Water treatment manuals</summary>
                <name>zimgit-water_en</name><flavour></flavour>
                <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim" length="21000000"
                      href="https://download.kiwix.org/zim/other/zimgit-water_en_2024-08.zim.meta4" />
                <link rel="http://opds-spec.org/image/thumbnail" type="image/png" href="/catalog/v2/illustration/1" />
              </entry>
              <entry>
                <id>urn:uuid:2</id><title>WikiMed</title><summary>Medicine</summary><name>wikipedia_en_medicine</name><flavour>nopic</flavour>
                <link type="application/x-zim" length="1000" href="https://download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-01.zim.meta4" />
              </entry>
              <entry><id>urn:uuid:3</id><title>No file</title></entry>
            </feed>
        """.trimIndent()
        val items = OpdsParser.parse(xml.byteInputStream())
        assertEquals(2, items.size)
        val (water, flavour) = items[0]
        assertEquals("zimgit-water_en_2024-08.zim", water.fileName)
        assertEquals("https://download.kiwix.org/zim/other/zimgit-water_en_2024-08.zim", water.url)
        assertEquals("Water", water.title)
        assertEquals("Water treatment manuals", water.summary)
        assertEquals(21_000_000L, water.size)
        assertNull(flavour)
        assertEquals("nopic", items[1].second)
    }

    @Test fun bookUrlsRoundTrip() {
        for (path in listOf("A/Water purification.html", "www.nhs.uk/medicines/", "C/50%_rule", "Ünïcode?x")) {
            val web = Uri.parse(BookUrls.web("abc123", path))
            assertEquals("nomad.invalid", web.host)
            assertEquals("abc123" to path, BookUrls.parse(web))
            assertEquals("abc123" to path, BookUrls.parse(Uri.parse(BookUrls.nomad("abc123", path))))
        }
        // A relative link resolved by the WebView still parses.
        assertEquals("abc123" to "I/x.png", BookUrls.parse(Uri.parse("https://nomad.invalid/book/abc123/I/x.png")))
        assertNull(BookUrls.parse(Uri.parse("https://example.com/book/abc/x")))
        assertNull(BookUrls.parse(Uri.parse("https://nomad.invalid/other/abc/x")))
    }

    @Test fun importerChecksMagicBytes() {
        assertEquals("zim", Importer.kindOf(byteArrayOf(0x5A, 0x49, 0x4D, 0x04) + ByteArray(12), 16))
        assertEquals("nomadidx", Importer.kindOf("SQLite format 3\u0000".toByteArray(), 16))
        assertNull(Importer.kindOf("PK\u0003\u0004 not a book".toByteArray(), 16))
        assertNull(Importer.kindOf(ByteArray(0), -1))
    }

    @Test fun formatting() {
        assertEquals("1.2 GB", byteString(1_234_000_000))
        assertEquals("55 MB", byteString(55_000_000))
        val s = snippetText("Boil **water** for **one** minute")
        assertEquals("Boil water for one minute", s.text)
        assertEquals(2, s.spanStyles.size)
    }

    /** The guides' full-text index is built from the bundled guides and searched with the same FTS5 code as books. */
    @Test fun guideFullTextSearch() {
        val index = GuideIndex.get(context)!!
        assertEquals("69", index.meta("articles"))
        val hits = LibrarySearch.articles(context, "how do I purify water", limit = 5)
        assertTrue(hits.isNotEmpty())
        val top = hits.first()
        assertEquals("Survival Guide", top.sourceName)
        assertTrue(top.ref.toString(), (top.ref as SourceRef.Guide).file.contains("water"))
        // Body text, not only titles and keywords: a phrase from inside a guide.
        assertTrue(LibrarySearch.articles(context, "tourniquet", limit = 5).any { it.ref is SourceRef.Guide })
    }

    /** The interface Ask uses: no books installed means no passages, never an error. */
    @Test fun bookSearchWithNoBooksIsEmpty() {
        assertTrue(BookSearch.passages(context, "how do I treat a snake bite", 8).isEmpty())
    }
}
