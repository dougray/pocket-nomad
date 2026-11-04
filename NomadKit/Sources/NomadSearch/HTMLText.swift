import Foundation

/// Fast, dependency-free HTML to plain text for indexing.
///
/// Drops script/style/nav/footer/table-of-contents, Wikipedia chrome (edit links,
/// citation markers, navboxes) and trailing boilerplate sections ("References",
/// "External links", ...). Decodes common entities, collapses whitespace and keeps
/// paragraph breaks as "\n\n".
public enum HTMLText {
    public static func extract(_ html: Data) -> (title: String?, text: String) {
        var p = Parser(Array(html))
        p.run()
        let title = p.title.map { String(decoding: $0, as: UTF8.self).collapsedWhitespace } ?? p.firstH1
        let text = String(decoding: p.out, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        return (title?.isEmpty == true ? nil : title, text)
    }

    public static func extract(_ html: String) -> (title: String?, text: String) {
        extract(Data(html.utf8))
    }

    /// Elements whose whole subtree is dropped.
    static let skipTags: Set<String> = [
        "script", "style", "noscript", "template", "nav", "footer", "svg", "math", "button", "head",
        "iframe", "object", "select", "form", "audio", "video", "canvas",
    ]
    /// class/id fragments that mark subtrees to drop.
    static let skipMarkers: [String] = [
        "toc", "navbox", "reference", "reflist", "mw-editsection", "noprint", "catlinks", "printfooter",
        "mw-jump", "metadata", "sistersitebox", "mw-cite-backlink", "hatnote", "navigation-not-searchable",
        "mbox", "ambox", "authority-control", "portal", "shortdescription",
    ]
    /// Section headings after which content is dropped (until a heading of the same or higher level).
    static let boilerplateHeadings: Set<String> = [
        "references", "external links", "see also", "notes", "further reading", "bibliography",
        "sources", "citations", "footnotes", "notes and references", "references and notes",
        "works cited", "general references", "explanatory notes",
    ]
    static let voidTags: Set<String> = [
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source",
        "track", "wbr",
    ]
    static let blockTags: Set<String> = [
        "p", "div", "section", "article", "main", "aside", "blockquote", "pre", "ul", "ol", "dl", "table",
        "figure", "figcaption", "details", "summary", "address", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "body", "header",
    ]
    static let lineTags: Set<String> = ["br", "li", "dt", "dd", "caption", "tr", "tbody", "thead", "tfoot"]
    static let cellTags: Set<String> = ["td", "th"]

    struct Parser {
        let b: [UInt8]
        var i = 0
        var out: [UInt8] = []
        var pendingSpace = false
        var pendingBreak = 0 // 0, 1 ("\n") or 2 ("\n\n")

        var title: [UInt8]?
        var inTitle = false
        var firstH1: String?

        // Subtree skipping: tag name + depth of that tag.
        var skipTag: String?
        var skipDepth = 0
        // Heading tracking for boilerplate sections.
        var headingLevel = 0
        var headingStart = 0
        var skipSectionLevel = 0 // >0 while inside a dropped section
        // Tables: body cells are written "Header: value; Header: value" so a reader
        // (or a small language model) can tell which number belongs to which column.
        var tableHeaders: [String] = []
        var tableRow = -1
        var tableCell = -1
        var rowIsHeader = false
        var cellStart = 0

        init(_ bytes: [UInt8]) { b = bytes }

        mutating func run() {
            while i < b.count {
                let c = b[i]
                if c == UInt8(ascii: "<") {
                    parseMarkup()
                } else if c == UInt8(ascii: "&") {
                    let decoded = parseEntity()
                    emitText(decoded)
                } else {
                    emitByte(c)
                    i += 1
                }
            }
        }

        var suppressed: Bool { skipTag != nil || skipSectionLevel > 0 }

        mutating func emitText(_ bytes: [UInt8]) { for c in bytes { emitByte(c) } }

        mutating func emitByte(_ c: UInt8) {
            if inTitle {
                if title == nil { title = [] }
                title!.append(c)
                return
            }
            if suppressed { return }
            if c == 0x20 || c == 0x0a || c == 0x09 || c == 0x0d || c == 0x0c {
                pendingSpace = true
                return
            }
            if !out.isEmpty {
                if pendingBreak > 0 {
                    while let last = out.last, last == 0x20 { out.removeLast() }
                    let existing = out.reversed().prefix { $0 == 0x0a }.count
                    if existing < pendingBreak { for _ in existing ..< pendingBreak { out.append(0x0a) } }
                } else if pendingSpace, let last = out.last, last != 0x20, last != 0x0a {
                    out.append(0x20)
                }
            }
            pendingBreak = 0
            pendingSpace = false
            out.append(c)
        }

        mutating func requestBreak(_ n: Int) {
            if !suppressed { pendingBreak = max(pendingBreak, n) }
        }

        mutating func parseMarkup() {
            // Comments, doctype, CDATA, processing instructions.
            if starts("<!--") {
                i = find("-->", from: i + 4).map { $0 + 3 } ?? b.count
                return
            }
            if i + 1 < b.count, b[i + 1] == UInt8(ascii: "!") || b[i + 1] == UInt8(ascii: "?") {
                i = find(">", from: i + 2).map { $0 + 1 } ?? b.count
                return
            }
            var j = i + 1
            let closing = j < b.count && b[j] == UInt8(ascii: "/")
            if closing { j += 1 }
            let nameStart = j
            while j < b.count, isNameByte(b[j]) { j += 1 }
            if j == nameStart { // a bare "<" in text
                emitByte(UInt8(ascii: "<"))
                i += 1
                return
            }
            let name = String(decoding: b[nameStart ..< j].map { $0 >= 0x41 && $0 <= 0x5a ? $0 + 0x20 : $0 }, as: UTF8.self)
            // Find the end of the tag, honouring quoted attribute values.
            var quote: UInt8 = 0
            let attrStart = j
            while j < b.count {
                let c = b[j]
                if quote != 0 { if c == quote { quote = 0 } }
                else if c == UInt8(ascii: "\"") || c == UInt8(ascii: "'") { quote = c }
                else if c == UInt8(ascii: ">") { break }
                j += 1
            }
            let attrs = b[attrStart ..< min(j, b.count)]
            let selfClosing = j > attrStart && b[j - 1] == UInt8(ascii: "/")
            i = min(j + 1, b.count)
            handleTag(name, closing: closing, selfClosing: selfClosing, attrs: attrs)
            // Raw-text elements: jump straight to the closing tag.
            if !closing, name == "script" || name == "style" || name == "textarea" {
                if let end = findCaseInsensitive("</" + name, from: i) {
                    i = find(">", from: end).map { $0 + 1 } ?? b.count
                    handleTag(name, closing: true, selfClosing: false, attrs: [])
                } else {
                    i = b.count
                }
            }
        }

        mutating func handleTag(_ name: String, closing: Bool, selfClosing: Bool, attrs: ArraySlice<UInt8>) {
            if name == "title" {
                if closing { inTitle = false }
                else if title == nil, !selfClosing, skipTag == nil || skipTag == "head" { inTitle = true }
                return
            }
            // Inside a skipped subtree: only track nesting of the same tag.
            if let tag = skipTag {
                if name == tag, !HTMLText.voidTags.contains(name), !selfClosing {
                    skipDepth += closing ? -1 : 1
                    if skipDepth == 0 { skipTag = nil }
                }
                return
            }
            let isVoid = HTMLText.voidTags.contains(name) || selfClosing
            if !closing, !isVoid, name != "body", name != "html", shouldSkip(name, attrs) {
                skipTag = name
                skipDepth = 1
                return
            }
            if let level = headingLevelOf(name) {
                if !closing {
                    if skipSectionLevel > 0, level <= skipSectionLevel { skipSectionLevel = 0 }
                    requestBreak(2)
                    if skipSectionLevel == 0 {
                        headingLevel = level
                        flushPending()
                        headingStart = out.count
                    }
                } else if headingLevel == level {
                    let text = String(decoding: out[headingStart...], as: UTF8.self)
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    if level == 1, firstH1 == nil, !text.isEmpty { firstH1 = text }
                    if level >= 2, HTMLText.boilerplateHeadings.contains(text.lowercased()) {
                        out.removeSubrange(headingStart...)
                        skipSectionLevel = level
                    }
                    headingLevel = 0
                    requestBreak(2)
                }
                return
            }
            if !suppressed { handleTableTag(name, closing: closing) }
            if HTMLText.blockTags.contains(name) { requestBreak(2) }
            else if HTMLText.lineTags.contains(name) { requestBreak(1) }
            else if HTMLText.cellTags.contains(name), tableRow < 0 { pendingSpace = true }
        }

        mutating func handleTableTag(_ name: String, closing: Bool) {
            switch (name, closing) {
            case ("table", false):
                tableHeaders = []; tableRow = -1
            case ("table", true):
                tableRow = -1
            case ("tr", false):
                tableRow += 1; tableCell = -1; rowIsHeader = false
            case ("th", false), ("td", false):
                guard tableRow >= 0 else { return }
                tableCell += 1
                if tableCell == 0 { rowIsHeader = name == "th" && tableRow == 0 }
                if tableCell > 0 { emitText(Array("; ".utf8)) }
                if !rowIsHeader, tableCell < tableHeaders.count, !tableHeaders[tableCell].isEmpty {
                    emitText(Array("\(tableHeaders[tableCell]): ".utf8))
                }
                flushPending()
                cellStart = out.count
            case ("th", true), ("td", true):
                guard tableRow >= 0, rowIsHeader else { return }
                let text = cellStart <= out.count ? String(decoding: out[cellStart...], as: UTF8.self) : ""
                tableHeaders.append(text.trimmingCharacters(in: .whitespacesAndNewlines))
            default:
                break
            }
        }

        /// Emits any pending break now, so a heading's text starts at a known offset.
        mutating func flushPending() {
            guard !out.isEmpty, pendingBreak > 0 else { return }
            while let last = out.last, last == 0x20 { out.removeLast() }
            let existing = out.reversed().prefix { $0 == 0x0a }.count
            if existing < pendingBreak { for _ in existing ..< pendingBreak { out.append(0x0a) } }
            pendingBreak = 0
            pendingSpace = false
        }

        func headingLevelOf(_ name: String) -> Int? {
            guard name.count == 2, name.first == "h", let d = name.last?.wholeNumberValue, (1 ... 6).contains(d) else { return nil }
            return d
        }

        func shouldSkip(_ name: String, _ attrs: ArraySlice<UInt8>) -> Bool {
            if HTMLText.skipTags.contains(name) { return true }
            guard !attrs.isEmpty else { return false }
            var skip = false
            Self.scanAttributes(attrs) { attr, value in
                switch attr {
                case .style:
                    let v = value.filter { $0 != 0x20 }
                    if Self.containsASCII(v, Array("display:none".utf8)) { skip = true }
                case .role:
                    if value.elementsEqual("navigation".utf8) || value.elementsEqual("note".utf8) { skip = true }
                case .classOrID:
                    let v = String(decoding: value, as: UTF8.self)
                    for token in v.split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" }) {
                        for m in HTMLText.skipMarkers where token == m || token.hasPrefix(m + "-") || token.hasPrefix(m + "_")
                            || token.hasSuffix("-" + m) || (m.count >= 6 && token.contains(m)) {
                            // "reference" only on citation containers, not e.g. section ids.
                            if m == "reference", name != "sup", name != "ol", name != "span", name != "div" { continue }
                            skip = true
                            return
                        }
                    }
                }
            }
            return skip
        }

        enum Attr { case classOrID, role, style }

        /// Byte-level attribute scan; calls `found` with lowercased values of class/id/role/style.
        static func scanAttributes(_ a: ArraySlice<UInt8>, _ found: (Attr, [UInt8]) -> Void) {
            var k = a.startIndex
            let end = a.endIndex
            @inline(__always) func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d || c == 0x2f }
            while k < end {
                while k < end, isSpace(a[k]) { k += 1 }
                let nameStart = k
                while k < end, a[k] != UInt8(ascii: "="), !isSpace(a[k]) { k += 1 }
                let nameEnd = k
                while k < end, a[k] == 0x20 { k += 1 }
                var valueRange: Range<Int>?
                if k < end, a[k] == UInt8(ascii: "=") {
                    k += 1
                    while k < end, a[k] == 0x20 { k += 1 }
                    if k < end, a[k] == UInt8(ascii: "\"") || a[k] == UInt8(ascii: "'") {
                        let q = a[k]
                        k += 1
                        let vs = k
                        while k < end, a[k] != q { k += 1 }
                        valueRange = vs ..< k
                        k += 1
                    } else {
                        let vs = k
                        while k < end, !isSpace(a[k]) { k += 1 }
                        valueRange = vs ..< k
                    }
                }
                guard let vr = valueRange else { if k == nameStart { k += 1 }; continue }
                let n = nameEnd - nameStart
                guard n >= 2, n <= 5 else { continue }
                var name: [UInt8] = []
                for i in nameStart ..< nameEnd { name.append(a[i] | 0x20) }
                let attr: Attr
                switch name {
                case Array("class".utf8), Array("id".utf8): attr = .classOrID
                case Array("role".utf8): attr = .role
                case Array("style".utf8): attr = .style
                default: continue
                }
                found(attr, a[vr].map { $0 >= 0x41 && $0 <= 0x5a ? $0 + 0x20 : $0 })
            }
        }

        static func containsASCII(_ hay: [UInt8], _ needle: [UInt8]) -> Bool {
            guard needle.count <= hay.count else { return false }
            for s in 0 ... (hay.count - needle.count) where hay[s] == needle[0] {
                if hay[s ..< s + needle.count].elementsEqual(needle) { return true }
            }
            return false
        }

        mutating func parseEntity() -> [UInt8] {
            // i points at "&"
            var j = i + 1
            while j < b.count, j - i <= 32, b[j] != UInt8(ascii: ";"), isEntityByte(b[j]) { j += 1 }
            guard j < b.count, b[j] == UInt8(ascii: ";"), j > i + 1 else {
                i += 1
                return [UInt8(ascii: "&")]
            }
            let name = String(decoding: b[(i + 1) ..< j], as: UTF8.self)
            i = j + 1
            if name.hasPrefix("#") {
                let num = name.dropFirst()
                let value: UInt32? = num.first == "x" || num.first == "X"
                    ? UInt32(num.dropFirst(), radix: 16) : UInt32(num)
                if let v = value, let s = Unicode.Scalar(v) {
                    if v == 0xad { return [] }
                    if v == 0xa0 || v == 0x2009 || v == 0x200b { return [0x20] }
                    return Array(String(Character(s)).utf8)
                }
                return []
            }
            if let s = HTMLText.entities[name] { return Array(s.utf8) }
            return Array("&\(name);".utf8)
        }

        func isNameByte(_ c: UInt8) -> Bool {
            (c >= 0x61 && c <= 0x7a) || (c >= 0x41 && c <= 0x5a) || (c >= 0x30 && c <= 0x39) || c == 0x2d || c == 0x3a
        }

        func isEntityByte(_ c: UInt8) -> Bool {
            isNameByte(c) || c == UInt8(ascii: "#")
        }

        func starts(_ s: String) -> Bool {
            let u = Array(s.utf8)
            return i + u.count <= b.count && Array(b[i ..< i + u.count]) == u
        }

        func find(_ s: String, from: Int) -> Int? {
            let u = Array(s.utf8)
            guard !u.isEmpty, from < b.count else { return nil }
            var k = from
            while k + u.count <= b.count {
                if b[k] == u[0], b[k ..< k + u.count].elementsEqual(u) { return k }
                k += 1
            }
            return nil
        }

        func findCaseInsensitive(_ s: String, from: Int) -> Int? {
            let u = Array(s.lowercased().utf8)
            var k = from
            while k + u.count <= b.count {
                if b[k] == u[0] {
                    var ok = true
                    for (n, ch) in u.enumerated() {
                        var c = b[k + n]
                        if c >= 0x41 && c <= 0x5a { c += 0x20 }
                        if c != ch { ok = false; break }
                    }
                    if ok { return k }
                }
                k += 1
            }
            return nil
        }
    }

    static let entities: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ensp": " ", "emsp": " ",
        "thinsp": " ", "zwnj": "", "zwj": "", "shy": "", "ndash": "–", "mdash": "—", "minus": "−",
        "hellip": "…", "lsquo": "‘", "rsquo": "’", "sbquo": "‚", "ldquo": "“", "rdquo": "”", "bdquo": "„",
        "laquo": "«", "raquo": "»", "copy": "©", "reg": "®", "trade": "™", "deg": "°", "plusmn": "±",
        "times": "×", "divide": "÷", "middot": "·", "bull": "•", "para": "¶", "sect": "§", "micro": "µ",
        "frac12": "½", "frac14": "¼", "frac34": "¾", "sup1": "¹", "sup2": "²", "sup3": "³", "prime": "′",
        "Prime": "″", "euro": "€", "pound": "£", "yen": "¥", "cent": "¢", "larr": "←", "rarr": "→",
        "uarr": "↑", "darr": "↓", "harr": "↔", "le": "≤", "ge": "≥", "ne": "≠", "asymp": "≈", "infin": "∞",
        "alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "mu": "μ", "pi": "π", "sigma": "σ",
        "omega": "ω", "Delta": "Δ", "Omega": "Ω", "eacute": "é", "egrave": "è", "aacute": "á",
        "agrave": "à", "iacute": "í", "oacute": "ó", "uacute": "ú", "ntilde": "ñ", "ccedil": "ç",
        "uuml": "ü", "ouml": "ö", "auml": "ä", "szlig": "ß", "Eacute": "É", "Uuml": "Ü", "Ouml": "Ö",
        "Auml": "Ä", "iexcl": "¡", "iquest": "¿",
    ]
}

extension String {
    var collapsedWhitespace: String {
        split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }
}
