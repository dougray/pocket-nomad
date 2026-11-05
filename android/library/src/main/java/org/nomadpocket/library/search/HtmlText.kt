package org.nomadpocket.library.search

/**
 * Fast, dependency-free HTML to plain text for indexing. A port of NomadKit's
 * `HTMLText.swift`.
 *
 * Drops script/style/nav/footer/table-of-contents, Wikipedia chrome (edit links,
 * citation markers, navboxes) and trailing boilerplate sections ("References",
 * "External links", ...). Decodes common entities, collapses whitespace and keeps
 * paragraph breaks as "\n\n".
 */
object HtmlText {
    data class Extracted(val title: String?, val text: String)

    fun extract(html: ByteArray): Extracted {
        val p = Parser(html)
        p.run()
        val title = p.title?.let { collapseWhitespace(String(it.toByteArray(), Charsets.UTF_8)) } ?: p.firstH1
        val text = String(p.out.toByteArray(), Charsets.UTF_8).trim()
        return Extracted(title?.takeIf { it.isNotEmpty() }, text)
    }

    fun extract(html: String): Extracted = extract(html.toByteArray())

    fun collapseWhitespace(s: String): String = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Elements whose whole subtree is dropped. */
    private val skipTags = setOf(
        "script", "style", "noscript", "template", "nav", "footer", "svg", "math", "button", "head",
        "iframe", "object", "select", "form", "audio", "video", "canvas",
    )

    /** class/id fragments that mark subtrees to drop. */
    private val skipMarkers = listOf(
        "toc", "navbox", "reference", "reflist", "mw-editsection", "noprint", "catlinks", "printfooter",
        "mw-jump", "metadata", "sistersitebox", "mw-cite-backlink", "hatnote", "navigation-not-searchable",
        "mbox", "ambox", "authority-control", "portal", "shortdescription",
    )

    /** Section headings after which content is dropped (until a heading of the same or higher level). */
    private val boilerplateHeadings = setOf(
        "references", "external links", "see also", "notes", "further reading", "bibliography",
        "sources", "citations", "footnotes", "notes and references", "references and notes",
        "works cited", "general references", "explanatory notes",
    )
    private val voidTags = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    )
    private val blockTags = setOf(
        "p", "div", "section", "article", "main", "aside", "blockquote", "pre", "ul", "ol", "dl", "table",
        "figure", "figcaption", "details", "summary", "address", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "body", "header",
    )
    private val lineTags = setOf("br", "li", "dt", "dd", "caption", "tr", "tbody", "thead", "tfoot")
    private val cellTags = setOf("td", "th")

    /** A growable byte buffer with the few operations the parser needs. */
    private class Bytes {
        var a = ByteArray(1024)
        var size = 0
        fun add(c: Byte) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = c
        }
        fun last(): Byte? = if (size > 0) a[size - 1] else null
        fun removeLast() { size-- }
        fun isEmpty() = size == 0
        fun trailingNewlines(): Int {
            var n = 0
            while (n < size && a[size - 1 - n] == NL) n++
            return n
        }
        fun string(from: Int): String = if (from <= size) String(a, from, size - from, Charsets.UTF_8) else ""
        fun truncate(to: Int) { size = to }
        fun toByteArray(): ByteArray = a.copyOf(size)
    }

    private const val SP: Byte = 0x20
    private const val NL: Byte = 0x0a

    private class Parser(val b: ByteArray) {
        var i = 0
        val out = Bytes()
        var pendingSpace = false
        var pendingBreak = 0 // 0, 1 ("\n") or 2 ("\n\n")

        var title: Bytes? = null
        var inTitle = false
        var firstH1: String? = null

        // Subtree skipping: tag name + depth of that tag.
        var skipTag: String? = null
        var skipDepth = 0

        // Heading tracking for boilerplate sections.
        var headingLevel = 0
        var headingStart = 0
        var skipSectionLevel = 0 // >0 while inside a dropped section

        // Tables: body cells are written "Header: value; Header: value" so a reader
        // (or a small language model) can tell which number belongs to which column.
        var tableHeaders = ArrayList<String>()
        var tableRow = -1
        var tableCell = -1
        var rowIsHeader = false
        var cellStart = 0

        fun run() {
            while (i < b.size) {
                val c = b[i]
                when (c) {
                    '<'.code.toByte() -> parseMarkup()
                    '&'.code.toByte() -> emitText(parseEntity())
                    else -> { emitByte(c); i++ }
                }
            }
        }

        val suppressed: Boolean get() = skipTag != null || skipSectionLevel > 0

        fun emitText(bytes: ByteArray) { for (c in bytes) emitByte(c) }

        fun emitByte(c: Byte) {
            if (inTitle) {
                (title ?: Bytes().also { title = it }).add(c)
                return
            }
            if (suppressed) return
            if (c == SP || c == NL || c == 0x09.toByte() || c == 0x0d.toByte() || c == 0x0c.toByte()) {
                pendingSpace = true
                return
            }
            if (!out.isEmpty()) {
                if (pendingBreak > 0) {
                    while (out.last() == SP) out.removeLast()
                    val existing = out.trailingNewlines()
                    for (k in existing until pendingBreak) out.add(NL)
                } else if (pendingSpace && out.last() != SP && out.last() != NL) {
                    out.add(SP)
                }
            }
            pendingBreak = 0
            pendingSpace = false
            out.add(c)
        }

        fun requestBreak(n: Int) {
            if (!suppressed) pendingBreak = maxOf(pendingBreak, n)
        }

        fun parseMarkup() {
            // Comments, doctype, CDATA, processing instructions.
            if (starts("<!--")) {
                i = find("-->", i + 4)?.let { it + 3 } ?: b.size
                return
            }
            if (i + 1 < b.size && (b[i + 1] == '!'.code.toByte() || b[i + 1] == '?'.code.toByte())) {
                i = find(">", i + 2)?.let { it + 1 } ?: b.size
                return
            }
            var j = i + 1
            val closing = j < b.size && b[j] == '/'.code.toByte()
            if (closing) j++
            val nameStart = j
            while (j < b.size && isNameByte(b[j])) j++
            if (j == nameStart) { // a bare "<" in text
                emitByte('<'.code.toByte())
                i++
                return
            }
            val name = String(ByteArray(j - nameStart) { lower(b[nameStart + it]) }, Charsets.UTF_8)
            // Find the end of the tag, honouring quoted attribute values.
            var quote: Byte = 0
            val attrStart = j
            while (j < b.size) {
                val c = b[j]
                if (quote != 0.toByte()) {
                    if (c == quote) quote = 0
                } else if (c == '"'.code.toByte() || c == '\''.code.toByte()) {
                    quote = c
                } else if (c == '>'.code.toByte()) {
                    break
                }
                j++
            }
            val attrEnd = minOf(j, b.size)
            val selfClosing = j > attrStart && b[j - 1] == '/'.code.toByte()
            i = minOf(j + 1, b.size)
            handleTag(name, closing, selfClosing, attrStart, attrEnd)
            // Raw-text elements: jump straight to the closing tag.
            if (!closing && (name == "script" || name == "style" || name == "textarea")) {
                val end = findCaseInsensitive("</$name", i)
                if (end != null) {
                    i = find(">", end)?.let { it + 1 } ?: b.size
                    handleTag(name, closing = true, selfClosing = false, attrStart = 0, attrEnd = 0)
                } else {
                    i = b.size
                }
            }
        }

        fun handleTag(name: String, closing: Boolean, selfClosing: Boolean, attrStart: Int, attrEnd: Int) {
            if (name == "title") {
                if (closing) inTitle = false
                else if (title == null && !selfClosing && (skipTag == null || skipTag == "head")) inTitle = true
                return
            }
            // Inside a skipped subtree: only track nesting of the same tag.
            val tag = skipTag
            if (tag != null) {
                if (name == tag && name !in voidTags && !selfClosing) {
                    skipDepth += if (closing) -1 else 1
                    if (skipDepth == 0) skipTag = null
                }
                return
            }
            val isVoid = name in voidTags || selfClosing
            if (!closing && !isVoid && name != "body" && name != "html" && shouldSkip(name, attrStart, attrEnd)) {
                skipTag = name
                skipDepth = 1
                return
            }
            val level = headingLevelOf(name)
            if (level != null) {
                if (!closing) {
                    if (skipSectionLevel > 0 && level <= skipSectionLevel) skipSectionLevel = 0
                    requestBreak(2)
                    if (skipSectionLevel == 0) {
                        headingLevel = level
                        flushPending()
                        headingStart = out.size
                    }
                } else if (headingLevel == level) {
                    val text = out.string(headingStart).trim()
                    if (level == 1 && firstH1 == null && text.isNotEmpty()) firstH1 = text
                    if (level >= 2 && text.lowercase() in boilerplateHeadings) {
                        out.truncate(headingStart)
                        skipSectionLevel = level
                    }
                    headingLevel = 0
                    requestBreak(2)
                }
                return
            }
            if (!suppressed) handleTableTag(name, closing)
            when {
                name in blockTags -> requestBreak(2)
                name in lineTags -> requestBreak(1)
                name in cellTags && tableRow < 0 -> pendingSpace = true
            }
        }

        fun handleTableTag(name: String, closing: Boolean) {
            when {
                name == "table" && !closing -> { tableHeaders = ArrayList(); tableRow = -1 }
                name == "table" && closing -> tableRow = -1
                name == "tr" && !closing -> { tableRow++; tableCell = -1; rowIsHeader = false }
                (name == "th" || name == "td") && !closing -> {
                    if (tableRow < 0) return
                    tableCell++
                    if (tableCell == 0) rowIsHeader = name == "th" && tableRow == 0
                    if (tableCell > 0) emitText("; ".toByteArray())
                    if (!rowIsHeader && tableCell < tableHeaders.size && tableHeaders[tableCell].isNotEmpty()) {
                        emitText("${tableHeaders[tableCell]}: ".toByteArray())
                    }
                    flushPending()
                    cellStart = out.size
                }
                (name == "th" || name == "td") && closing -> {
                    if (tableRow < 0 || !rowIsHeader) return
                    tableHeaders.add(out.string(cellStart).trim())
                }
            }
        }

        /** Emits any pending break now, so a heading's text starts at a known offset. */
        fun flushPending() {
            if (out.isEmpty() || pendingBreak == 0) return
            while (out.last() == SP) out.removeLast()
            val existing = out.trailingNewlines()
            for (k in existing until pendingBreak) out.add(NL)
            pendingBreak = 0
            pendingSpace = false
        }

        fun headingLevelOf(name: String): Int? {
            if (name.length != 2 || name[0] != 'h') return null
            val d = name[1].digitToIntOrNull() ?: return null
            return if (d in 1..6) d else null
        }

        fun shouldSkip(name: String, attrStart: Int, attrEnd: Int): Boolean {
            if (name in skipTags) return true
            if (attrStart >= attrEnd) return false
            var skip = false
            scanAttributes(attrStart, attrEnd) { attr, value ->
                if (skip) return@scanAttributes
                when (attr) {
                    Attr.STYLE -> {
                        val v = value.filter { it != SP }.toByteArray()
                        if (String(v, Charsets.UTF_8).contains("display:none")) skip = true
                    }
                    Attr.ROLE -> {
                        val v = String(value, Charsets.UTF_8)
                        if (v == "navigation" || v == "note") skip = true
                    }
                    Attr.CLASS_OR_ID -> {
                        val v = String(value, Charsets.UTF_8)
                        outer@ for (token in v.split(' ', '\t', '\n').filter { it.isNotEmpty() }) {
                            for (m in skipMarkers) {
                                val hit = token == m || token.startsWith("$m-") || token.startsWith("${m}_") ||
                                    token.endsWith("-$m") || (m.length >= 6 && token.contains(m))
                                if (!hit) continue
                                // "reference" only on citation containers, not e.g. section ids.
                                if (m == "reference" && name != "sup" && name != "ol" && name != "span" && name != "div") continue
                                skip = true
                                break@outer
                            }
                        }
                    }
                }
            }
            return skip
        }

        enum class Attr { CLASS_OR_ID, ROLE, STYLE }

        /** Byte-level attribute scan; calls [found] with lowercased values of class/id/role/style. */
        fun scanAttributes(start: Int, end: Int, found: (Attr, ByteArray) -> Unit) {
            fun isSpace(c: Byte) = c == SP || c == 0x09.toByte() || c == NL || c == 0x0d.toByte() || c == '/'.code.toByte()
            var k = start
            while (k < end) {
                while (k < end && isSpace(b[k])) k++
                val nameStart = k
                while (k < end && b[k] != '='.code.toByte() && !isSpace(b[k])) k++
                val nameEnd = k
                while (k < end && b[k] == SP) k++
                var vs = -1
                var ve = -1
                if (k < end && b[k] == '='.code.toByte()) {
                    k++
                    while (k < end && b[k] == SP) k++
                    if (k < end && (b[k] == '"'.code.toByte() || b[k] == '\''.code.toByte())) {
                        val q = b[k]
                        k++
                        vs = k
                        while (k < end && b[k] != q) k++
                        ve = k
                        k++
                    } else {
                        vs = k
                        while (k < end && !isSpace(b[k])) k++
                        ve = k
                    }
                }
                if (vs < 0) {
                    if (k == nameStart) k++
                    continue
                }
                val n = nameEnd - nameStart
                if (n < 2 || n > 5) continue
                val attrName = String(ByteArray(n) { (b[nameStart + it].toInt() or 0x20).toByte() }, Charsets.US_ASCII)
                val attr = when (attrName) {
                    "class", "id" -> Attr.CLASS_OR_ID
                    "role" -> Attr.ROLE
                    "style" -> Attr.STYLE
                    else -> continue
                }
                found(attr, ByteArray(ve - vs) { lower(b[vs + it]) })
            }
        }

        fun parseEntity(): ByteArray {
            // i points at "&"
            var j = i + 1
            while (j < b.size && j - i <= 32 && b[j] != ';'.code.toByte() && isEntityByte(b[j])) j++
            if (j >= b.size || b[j] != ';'.code.toByte() || j <= i + 1) {
                i++
                return byteArrayOf('&'.code.toByte())
            }
            val name = String(b, i + 1, j - i - 1, Charsets.UTF_8)
            i = j + 1
            if (name.startsWith("#")) {
                val num = name.drop(1)
                val value = if (num.startsWith("x") || num.startsWith("X")) num.drop(1).toIntOrNull(16) else num.toIntOrNull()
                if (value != null && value in 0..0x10FFFF && value !in 0xD800..0xDFFF) {
                    if (value == 0xad) return ByteArray(0)
                    if (value == 0xa0 || value == 0x2009 || value == 0x200b) return byteArrayOf(SP)
                    return String(Character.toChars(value)).toByteArray()
                }
                return ByteArray(0)
            }
            entities[name]?.let { return it.toByteArray() }
            return "&$name;".toByteArray()
        }

        fun isNameByte(c: Byte): Boolean {
            val x = c.toInt()
            return (x in 0x61..0x7a) || (x in 0x41..0x5a) || (x in 0x30..0x39) || x == 0x2d || x == 0x3a
        }

        fun isEntityByte(c: Byte) = isNameByte(c) || c == '#'.code.toByte()

        fun lower(c: Byte): Byte = if (c.toInt() in 0x41..0x5a) (c + 0x20).toByte() else c

        fun starts(s: String): Boolean {
            val u = s.toByteArray()
            if (i + u.size > b.size) return false
            for (k in u.indices) if (b[i + k] != u[k]) return false
            return true
        }

        fun find(s: String, from: Int): Int? {
            val u = s.toByteArray()
            if (u.isEmpty() || from >= b.size) return null
            var k = maxOf(from, 0)
            while (k + u.size <= b.size) {
                if (b[k] == u[0]) {
                    var ok = true
                    for (n in 1 until u.size) if (b[k + n] != u[n]) { ok = false; break }
                    if (ok) return k
                }
                k++
            }
            return null
        }

        fun findCaseInsensitive(s: String, from: Int): Int? {
            val u = s.lowercase().toByteArray()
            var k = from
            while (k + u.size <= b.size) {
                if (b[k] == u[0]) {
                    var ok = true
                    for (n in u.indices) if (lower(b[k + n]) != u[n]) { ok = false; break }
                    if (ok) return k
                }
                k++
            }
            return null
        }
    }

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "zwnj" to "", "zwj" to "", "shy" to "", "ndash" to "–", "mdash" to "—", "minus" to "−",
        "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
        "times" to "×", "divide" to "÷", "middot" to "·", "bull" to "•", "para" to "¶", "sect" to "§", "micro" to "µ",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³", "prime" to "′",
        "Prime" to "″", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "larr" to "←", "rarr" to "→",
        "uarr" to "↑", "darr" to "↓", "harr" to "↔", "le" to "≤", "ge" to "≥", "ne" to "≠", "asymp" to "≈", "infin" to "∞",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "mu" to "μ", "pi" to "π", "sigma" to "σ",
        "omega" to "ω", "Delta" to "Δ", "Omega" to "Ω", "eacute" to "é", "egrave" to "è", "aacute" to "á",
        "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "ccedil" to "ç",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "Eacute" to "É", "Uuml" to "Ü", "Ouml" to "Ö",
        "Auml" to "Ä", "iexcl" to "¡", "iquest" to "¿",
    )
}
