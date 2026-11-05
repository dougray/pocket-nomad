package org.nomadpocket.core

import org.json.JSONObject

/** One urgent guide boiled down to its "Do this now" steps, for the watch. */
data class FirstAidCard(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    /** The red and yellow callouts that sit next to the steps ("Call 911…"). */
    val warnings: List<String>,
)

/**
 * Turns the bundled guide HTML into short step lists. A port of
 * Shared/FirstAidSteps.swift: every app ships the same App/Resources/Guides
 * folder, so the watch always shows exactly what the phone shows.
 */
object FirstAidSteps {
    data class Entry(val id: String, val category: String, val title: String, val summary: String, val file: String)

    /**
     * Every guide that index.json marks `"urgent": true`, in index order.
     * [read] returns a file under the Guides folder by relative path, or null.
     */
    fun load(read: (String) -> String?): List<FirstAidCard> {
        val index = read("index.json") ?: return emptyList()
        return urgentEntries(index).mapNotNull { entry ->
            val html = read(entry.file) ?: return@mapNotNull null
            val (steps, warnings) = extract(html)
            if (steps.isEmpty()) null
            else FirstAidCard(entry.id, entry.category, entry.title, entry.summary, steps, warnings)
        }
    }

    fun urgentEntries(indexJSON: String): List<Entry> {
        val root = runCatching { JSONObject(indexJSON) }.getOrNull() ?: return emptyList()
        val categories = root.optJSONArray("categories") ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (c in 0 until categories.length()) {
            val category = categories.optJSONObject(c) ?: continue
            val guides = category.optJSONArray("guides") ?: continue
            for (g in 0 until guides.length()) {
                val guide = guides.optJSONObject(g) ?: continue
                if (!guide.optBoolean("urgent", false)) continue
                out += Entry(guide.optString("id"), category.optString("title"), guide.optString("title"),
                    guide.optString("summary"), guide.optString("file"))
            }
        }
        return out
    }

    /**
     * The list items of the first numbered list in the guide's first section
     * ("Do this now"), plus that section's callouts, as plain text.
     */
    fun extract(html: String): Pair<List<String>, List<String>> {
        val section = firstSection(html)
        var steps = emptyList<String>()
        val olStart = section.indexOf("<ol")
        if (olStart >= 0) {
            val olEnd = section.indexOf("</ol>", olStart + 3)
            if (olEnd >= 0) {
                steps = elements("li", section.substring(olStart + 3, olEnd)).map(::plainText).filter { it.isNotEmpty() }
            }
        }
        val warnings = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = section.indexOf("<div class=\"callout", cursor).takeIf { it >= 0 } ?: break
            val open = section.indexOf(">", start + 1).takeIf { it >= 0 } ?: break
            val close = section.indexOf("</div>", open + 1).takeIf { it >= 0 } ?: break
            val text = plainText(section.substring(open + 1, close))
            if (text.isNotEmpty()) warnings += text
            cursor = close + 6
        }
        return steps to warnings
    }

    /** From the first <h2> up to (not including) the second, or the whole page when it has no headings. */
    fun firstSection(html: String): String {
        val first = html.indexOf("<h2")
        if (first < 0) return html
        val closeHeading = html.indexOf("</h2>", first + 3)
        val afterHeading = if (closeHeading >= 0) closeHeading + 5 else first + 3
        val end = html.indexOf("<h2", afterHeading).takeIf { it >= 0 } ?: html.length
        return html.substring(afterHeading, end)
    }

    private fun elements(tag: String, html: String): List<String> {
        val out = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = html.indexOf("<$tag", cursor).takeIf { it >= 0 } ?: break
            val open = html.indexOf(">", start + 1).takeIf { it >= 0 } ?: break
            val close = html.indexOf("</$tag>", open + 1).takeIf { it >= 0 }
            val next = html.indexOf("<$tag", open + 1).takeIf { it >= 0 }
            // A missing or later </li> ends the item at the next <li>.
            val end = listOfNotNull(close, next).minOrNull() ?: html.length
            out += html.substring(open + 1, end)
            cursor = end
            if (cursor == html.length) break
        }
        return out
    }

    /** Strips tags, decodes entities and collapses whitespace. */
    fun plainText(html: String): String {
        val out = StringBuilder()
        var inTag = false
        for (ch in html) {
            when {
                ch == '<' -> inTag = true
                ch == '>' -> inTag = false
                !inTag -> out.append(ch)
            }
        }
        return decodeEntities(out.toString()).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "mdash" to "—", "ndash" to "–", "deg" to "°", "hellip" to "…",
        "frac12" to "½", "times" to "×", "plusmn" to "±",
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val amp = s.indexOf('&', i)
            if (amp < 0) break
            out.append(s, i, amp)
            val semi = s.indexOf(';', amp + 1)
            // Like the Swift version: a name of at most 8 characters before the ';'.
            if (semi >= 0 && semi - (amp + 1) <= 8) {
                val name = s.substring(amp + 1, semi)
                val decoded = when {
                    name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
                    name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
                    else -> named[name]
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
            out.append('&')
            i = amp + 1
        }
        out.append(s, i, s.length)
        return out.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (Character.isValidCodePoint(cp) && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}
