package org.nomadpocket.app.library

import android.net.Uri
import android.util.Xml
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser

/** A downloadable Kiwix book. [id] is the file name. */
data class CatalogItem(val id: String, val title: String, val summary: String, val size: Long, val url: String) {
    val fileName: String get() = id
}

/**
 * Reads the free Kiwix OPDS catalog (library.kiwix.org) so book links never go
 * stale. Same picks, order and notes as the iOS app (`App/Sources/Packs/Catalog.swift`).
 */
object KiwixCatalog {
    const val BASE = "https://opds.library.kiwix.org/catalog/v2/entries"

    data class Pick(val name: String, val flavour: String?, val note: String)

    /** Survival-focused picks, roughly in order of usefulness per megabyte. */
    val recommended = listOf(
        Pick("zimgit-post-disaster_en", null, "Post-disaster rebuilding: water, power, shelter, sanitation"),
        Pick("zimgit-water_en", null, "Water treatment manuals"),
        Pick("zimgit-medicine_en", null, "Field medicine manuals"),
        Pick("irp.fas.org_en_military-medicine", null, "Military field medicine"),
        Pick("wikipedia_en_medicine", "nopic", "WikiMed: 70,000+ medical articles, no pictures"),
        Pick("nhs.uk_en_medicines", null, "NHS Medicines A to Z: what each is for, doses, side effects"),
        Pick("zimgit-food-preparation_en", null, "Food storage and preparation"),
        Pick("zimgit-knots_en", null, "Knots, with diagrams"),
    )

    val bigger = listOf(
        Pick("medlineplus.gov_en_all", null, "MedlinePlus: plain-language health encyclopedia from the U.S. National Library of Medicine"),
        Pick("wikipedia_en_medicine", "maxi", "WikiMed with pictures"),
        Pick("ifixit_en_all", null, "iFixit repair guides"),
        Pick("lrnselfreliance_en_all", null, "Learning Self-Reliance videos and articles"),
        Pick("wikipedia_en_100", null, "Wikipedia's top 100 articles (good for testing)"),
        Pick("wikipedia_en_all", "mini", "All of Wikipedia, intros only"),
        Pick("wikipedia_en_all", "nopic", "All of Wikipedia, no pictures"),
    )

    /** The newest edition of a named book (and flavour). Blocking network call. */
    fun lookup(pick: Pick): CatalogItem? {
        val url = Uri.parse(BASE).buildUpon().appendQueryParameter("name", pick.name).appendQueryParameter("count", "20").build()
        return fetch(url.toString()).firstOrNull { pick.flavour == null || it.second == pick.flavour }?.first
    }

    /** English books matching [query]. Blocking network call. */
    fun search(query: String): List<CatalogItem> {
        val url = Uri.parse(BASE).buildUpon().appendQueryParameter("q", query).appendQueryParameter("lang", "eng")
            .appendQueryParameter("count", "40").build()
        return fetch(url.toString()).map { it.first }
    }

    private fun fetch(url: String): List<Pair<CatalogItem, String?>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        return try {
            if (conn.responseCode !in 200..299) emptyList() else conn.inputStream.use { OpdsParser.parse(it) }
        } finally {
            conn.disconnect()
        }
    }
}

/** Minimal Atom/OPDS parser: just the fields the app shows. Each item with its flavour ("nopic", "maxi"...). */
object OpdsParser {
    fun parse(input: InputStream): List<Pair<CatalogItem, String?>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val entries = ArrayList<Pair<CatalogItem, String?>>()
        var inEntry = false
        val fields = HashMap<String, String>()
        var link: Pair<String, Long>? = null
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return entries
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    val name = parser.name.substringAfter(':')
                    if (name == "entry") { inEntry = true; fields.clear(); link = null }
                    if (inEntry && name == "link" && parser.getAttributeValue(null, "type") == "application/x-zim") {
                        val href = parser.getAttributeValue(null, "href")?.removeSuffix(".meta4")
                        if (href != null) link = href to (parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L)
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (!inEntry) continue
                    when (val name = parser.name.substringAfter(':')) {
                        "title", "summary", "name", "flavour", "id" -> fields[name] = text.toString().trim()
                        "entry" -> {
                            inEntry = false
                            link?.let { (href, size) ->
                                val file = href.substringAfterLast('/')
                                val item = CatalogItem(file, fields["title"] ?: file, fields["summary"] ?: "", size, href)
                                entries.add(item to fields["flavour"]?.takeIf { it.isNotEmpty() })
                            }
                        }
                    }
                    text.setLength(0)
                }
            }
        }
    }
}
