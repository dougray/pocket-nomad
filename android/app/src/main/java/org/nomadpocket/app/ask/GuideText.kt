package org.nomadpocket.app.ask

/**
 * Plain text from a guide's HTML fragment, for searching and for the prompt.
 *
 * The guides are small, clean HTML written for this app (headings, lists, tables,
 * callouts), so this is a much smaller cousin of HTMLText in NomadKit: block tags
 * become paragraph breaks, list items and table rows become lines, and table cells
 * are written "Header: value; Header: value" so a reader (or a small model) can
 * tell which number belongs to which column. A trailing "Sources" section is dropped.
 */
object GuideText {
    private val blockTags = setOf(
        "p", "div", "section", "article", "blockquote", "pre", "ul", "ol", "dl", "table", "figure",
        "figcaption", "details", "summary", "h1", "h2", "h3", "h4", "h5", "h6", "hr",
    )
    private val lineTags = setOf("br", "li", "dt", "dd", "caption", "tr")
    private val skipTags = setOf("script", "style", "svg", "button", "nav", "footer")
    private val boilerplateHeadings = setOf("sources", "references", "see also", "further reading")
    private val tag = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>""")
    private val entity = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "minus" to "−", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "deg" to "°", "plusmn" to "±", "times" to "×", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "le" to "≤", "ge" to "≥", "micro" to "µ", "bull" to "•", "middot" to "·",
    )

    fun extract(html: String): String {
        val out = StringBuilder()
        var pendingBreak = 0
        var skipping: String? = null
        var skipSection = 0       // heading level of a dropped section, 0 when not dropping
        var heading = 0           // level of the heading being written
        var headingStart = 0
        // Tables: header cells of the first row, then the current row and cell.
        val headers = mutableListOf<String>()
        var row = -1
        var cell = -1
        var headerRow = false
        var cellStart = 0

        fun emit(text: String) {
            if (skipping != null || skipSection > 0) return
            for (c in text) {
                if (c.isWhitespace()) {
                    if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n' && pendingBreak == 0) out.append(' ')
                    continue
                }
                if (pendingBreak > 0 && out.isNotEmpty()) {
                    while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                    val existing = out.takeLastWhile { it == '\n' }.length
                    repeat(pendingBreak - existing.coerceAtMost(pendingBreak)) { out.append('\n') }
                }
                pendingBreak = 0
                out.append(c)
            }
        }
        fun requestBreak(n: Int) { if (skipping == null && skipSection == 0) pendingBreak = maxOf(pendingBreak, n) }
        fun flush() {
            if (out.isEmpty() || pendingBreak == 0) return
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            val existing = out.takeLastWhile { it == '\n' }.length
            repeat(pendingBreak - existing.coerceAtMost(pendingBreak)) { out.append('\n') }
            pendingBreak = 0
        }

        var i = 0
        for (m in tag.findAll(html)) {
            emit(decode(html.substring(i, m.range.first)))
            i = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            if (skipping != null) {
                if (closing && name == skipping) skipping = null
                continue
            }
            if (!closing && name in skipTags) { skipping = name; continue }
            val level = if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') name[1] - '0' else 0
            if (level > 0) {
                if (!closing) {
                    if (skipSection > 0 && level <= skipSection) skipSection = 0
                    requestBreak(2)
                    if (skipSection == 0) { heading = level; flush(); headingStart = out.length }
                } else if (heading == level) {
                    val text = out.substring(headingStart).trim()
                    if (level >= 2 && text.lowercase() in boilerplateHeadings) {
                        out.setLength(headingStart)
                        skipSection = level
                    }
                    heading = 0
                    requestBreak(2)
                }
                continue
            }
            if (skipSection == 0) when {
                name == "table" -> { headers.clear(); row = -1 }
                name == "tr" && !closing -> { row++; cell = -1; headerRow = false }
                (name == "td" || name == "th") && !closing && row >= 0 -> {
                    cell++
                    if (cell == 0) headerRow = name == "th" && row == 0
                    if (cell > 0) emit("; ")
                    if (!headerRow && cell < headers.size && headers[cell].isNotEmpty()) emit("${headers[cell]}: ")
                    flush()
                    cellStart = out.length
                }
                (name == "td" || name == "th") && closing && row >= 0 && headerRow ->
                    headers += out.substring(cellStart.coerceAtMost(out.length)).trim()
            }
            when (name) {
                in blockTags -> requestBreak(2)
                in lineTags -> requestBreak(1)
            }
        }
        emit(decode(html.substring(i)))
        return out.toString().trim()
    }

    private fun decode(text: String): String = entity.replace(text) { m ->
        val name = m.groupValues[1]
        if (name.startsWith("#")) {
            val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
            when (code) {
                null -> ""
                0xad -> ""
                0xa0, 0x2009, 0x200b -> " "
                else -> runCatching { String(Character.toChars(code)) }.getOrDefault("")
            }
        } else entities[name] ?: m.value
    }
}

/** Splits text into ~900–1200 character passages on paragraph boundaries. A port of PassageSplitter in NomadKit. */
object PassageSplitter {
    const val TARGET = 900
    const val MAX_LENGTH = 1200

    fun split(text: String): List<String> {
        val pieces = mutableListOf<String>()
        for (para in text.split("\n\n")) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (p.length <= MAX_LENGTH) pieces += p else pieces += splitLong(p)
        }
        val passages = mutableListOf<String>()
        var current = ""
        for (p in pieces) {
            current = when {
                current.isEmpty() -> p
                current.length + 2 + p.length <= MAX_LENGTH -> current + "\n\n" + p
                else -> { passages += current; p }
            }
            if (current.length >= TARGET) { passages += current; current = "" }
        }
        if (current.isNotEmpty()) {
            // Fold a short tail into the previous passage when it still fits loosely.
            val last = passages.lastOrNull()
            if (current.length < 200 && last != null && last.length + 2 + current.length <= MAX_LENGTH + 300) {
                passages[passages.size - 1] = last + "\n\n" + current
            } else {
                passages += current
            }
        }
        return passages
    }

    /** Breaks one long paragraph at sentence ends (or spaces) into chunks no longer than [MAX_LENGTH]. */
    private fun splitLong(p: String): List<String> {
        val chunks = mutableListOf<String>()
        var rest = p
        while (rest.length > MAX_LENGTH) {
            val window = rest.substring(0, MAX_LENGTH)
            // Prefer the last sentence end after TARGET / 2 characters.
            var cut = -1
            for (idx in window.length - 1 downTo TARGET / 2 + 1) {
                val ch = window[idx]
                if ((ch == '.' || ch == '!' || ch == '?') && (idx + 1 == window.length || window[idx + 1] == ' ')) { cut = idx + 1; break }
            }
            if (cut < 0) cut = window.lastIndexOf(' ').let { if (it >= 0) it + 1 else window.length }
            val chunk = rest.substring(0, cut).trim(' ')
            if (chunk.isNotEmpty()) chunks += chunk
            rest = rest.substring(cut)
        }
        val tail = rest.trim(' ')
        if (tail.isNotEmpty()) chunks += tail
        return chunks
    }
}
