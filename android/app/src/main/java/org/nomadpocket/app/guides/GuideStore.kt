package org.nomadpocket.app.guides

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Guide(
    val id: String,
    val title: String,
    val summary: String,
    val file: String,
    val urgent: Boolean,
    val keywords: List<String>,
)

data class GuideCategory(
    val id: String,
    val title: String,
    val symbol: String,
    val summary: String?,
    val guides: List<Guide>,
)

data class Checklist(val id: String, val title: String, val symbol: String, val items: List<String>)

/**
 * The survival guides bundled with the app (copied from the iOS app's
 * App/Resources/Guides at build time). They work with nothing downloaded.
 */
class GuideLibrary(val categories: List<GuideCategory>, val checklists: List<Checklist>) {
    val allGuides: List<Guide> = categories.flatMap { it.guides }
    val urgent: List<Guide> = allGuides.filter { it.urgent }

    fun guide(file: String): Guide? = allGuides.firstOrNull { it.file == file }
    fun guideById(id: String): Guide? = allGuides.firstOrNull { it.id == id }
    fun category(id: String): GuideCategory? = categories.firstOrNull { it.id == id }

    /** The most common life-threatening first-aid topics, kept to one screen. */
    val emergencies: List<Guide>
        get() = urgent.filter { g -> categories.firstOrNull()?.guides?.contains(g) == true }.take(10)

    /**
     * Matches every word of [query] against a guide's title, summary and keywords.
     * Urgent guides come first, then title matches, then the order in the index.
     */
    fun search(query: String, limit: Int = 25): List<Guide> {
        val terms = query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        data class Hit(val guide: Guide, val order: Int, val titleHits: Int)
        return allGuides.mapIndexedNotNull { i, g ->
            val title = g.title.lowercase()
            val text = (listOf(title, g.summary.lowercase()) + g.keywords.map { it.lowercase() }).joinToString(" \u0000 ")
            if (terms.all { text.contains(it) }) Hit(g, i, terms.count { title.contains(it) }) else null
        }
            .sortedWith(compareByDescending<Hit> { it.guide.urgent }.thenByDescending { it.titleHits }.thenBy { it.order })
            .take(limit)
            .map { it.guide }
    }

    companion object {
        const val ROOT = "Guides"

        fun parseIndex(json: String): List<GuideCategory> {
            val cats = JSONObject(json).getJSONArray("categories")
            return (0 until cats.length()).map { i ->
                val c = cats.getJSONObject(i)
                val guides = c.getJSONArray("guides")
                GuideCategory(
                    id = c.getString("id"),
                    title = c.getString("title"),
                    symbol = c.optString("symbol"),
                    summary = if (c.has("summary") && !c.isNull("summary")) c.getString("summary") else null,
                    guides = (0 until guides.length()).map { j ->
                        val g = guides.getJSONObject(j)
                        Guide(
                            id = g.getString("id"),
                            title = g.getString("title"),
                            summary = g.optString("summary"),
                            file = g.getString("file"),
                            urgent = g.optBoolean("urgent", false),
                            keywords = g.optJSONArray("keywords")?.strings() ?: emptyList(),
                        )
                    },
                )
            }
        }

        fun parseChecklists(json: String): List<Checklist> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Checklist(c.getString("id"), c.getString("title"), c.optString("symbol"), c.getJSONArray("items").strings())
            }
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

        @Volatile private var cached: GuideLibrary? = null

        fun get(context: Context): GuideLibrary = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        private fun load(context: Context): GuideLibrary {
            val assets = context.assets
            fun read(name: String) = runCatching { assets.open("$ROOT/$name").bufferedReader().use { it.readText() } }.getOrNull()
            val categories = read("index.json")?.let { runCatching { parseIndex(it) }.getOrNull() } ?: emptyList()
            val checklists = read("checklists.json")?.let { runCatching { parseChecklists(it) }.getOrNull() } ?: emptyList()
            return GuideLibrary(categories, checklists)
        }

        fun html(context: Context, guide: Guide): String =
            runCatching { context.assets.open("$ROOT/${guide.file}").bufferedReader().use { it.readText() } }
                .getOrDefault("<p>This guide is missing.</p>")
    }
}
