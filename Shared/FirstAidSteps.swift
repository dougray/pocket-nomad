import Foundation

/// One urgent guide boiled down to its "Do this now" steps, for the Apple Watch.
struct FirstAidCard: Identifiable, Hashable, Sendable {
    let id: String
    let category: String
    let title: String
    let summary: String
    let steps: [String]
    /// The red and yellow callouts that sit next to the steps ("Call 911…").
    let warnings: [String]
}

/// Turns the bundled guide HTML into short step lists. Both apps ship the same
/// App/Resources/Guides folder, so the watch always shows exactly what the phone
/// shows; nothing is copied by hand.
enum FirstAidSteps {
    /// Every guide that index.json marks `"urgent": true`, in index order.
    static func load(guidesRoot root: URL) -> [FirstAidCard] {
        guard let data = try? Data(contentsOf: root.appending(path: "index.json")) else { return [] }
        return urgentEntries(indexJSON: data).compactMap { entry in
            guard let html = try? String(contentsOf: root.appending(path: entry.file), encoding: .utf8) else { return nil }
            let parts = extract(html: html)
            guard !parts.steps.isEmpty else { return nil }
            return FirstAidCard(id: entry.id, category: entry.category, title: entry.title, summary: entry.summary,
                                steps: parts.steps, warnings: parts.warnings)
        }
    }

    struct Entry: Equatable, Sendable {
        let id: String
        let category: String
        let title: String
        let summary: String
        let file: String
    }

    static func urgentEntries(indexJSON: Data) -> [Entry] {
        struct Index: Decodable {
            struct Category: Decodable { let title: String; let guides: [Guide] }
            struct Guide: Decodable { let id: String; let title: String; let summary: String; let file: String; let urgent: Bool? }
            let categories: [Category]
        }
        guard let index = try? JSONDecoder().decode(Index.self, from: indexJSON) else { return [] }
        return index.categories.flatMap { c in
            c.guides.filter { $0.urgent == true }.map {
                Entry(id: $0.id, category: c.title, title: $0.title, summary: $0.summary, file: $0.file)
            }
        }
    }

    /// The list items of the first numbered list in the guide's first section
    /// ("Do this now"), plus that section's callouts, as plain text.
    static func extract(html: String) -> (steps: [String], warnings: [String]) {
        let section = firstSection(of: html)
        var steps: [String] = []
        if let olStart = section.range(of: "<ol"), let olEnd = section.range(of: "</ol>", range: olStart.upperBound..<section.endIndex) {
            let list = section[olStart.upperBound..<olEnd.lowerBound]
            steps = elements(named: "li", in: list).map(plainText).filter { !$0.isEmpty }
        }
        var warnings: [String] = []
        var cursor = section.startIndex
        while let start = section.range(of: "<div class=\"callout", range: cursor..<section.endIndex),
              let open = section.range(of: ">", range: start.upperBound..<section.endIndex),
              let close = section.range(of: "</div>", range: open.upperBound..<section.endIndex) {
            let text = plainText(section[open.upperBound..<close.lowerBound])
            if !text.isEmpty { warnings.append(text) }
            cursor = close.upperBound
        }
        return (steps, warnings)
    }

    /// From the first <h2> up to (not including) the second, or the whole page
    /// when it has no headings.
    static func firstSection(of html: String) -> Substring {
        guard let first = html.range(of: "<h2") else { return html[...] }
        let afterHeading = html.range(of: "</h2>", range: first.upperBound..<html.endIndex)?.upperBound ?? first.upperBound
        let end = html.range(of: "<h2", range: afterHeading..<html.endIndex)?.lowerBound ?? html.endIndex
        return html[afterHeading..<end]
    }

    private static func elements(named tag: String, in html: Substring) -> [Substring] {
        var out: [Substring] = []
        var cursor = html.startIndex
        while let start = html.range(of: "<\(tag)", range: cursor..<html.endIndex),
              let open = html.range(of: ">", range: start.upperBound..<html.endIndex) {
            let close = html.range(of: "</\(tag)>", range: open.upperBound..<html.endIndex)
            let next = html.range(of: "<\(tag)", range: open.upperBound..<html.endIndex)
            // A missing or later </li> ends the item at the next <li>.
            let endIndex = [close?.lowerBound, next?.lowerBound].compactMap { $0 }.min() ?? html.endIndex
            out.append(html[open.upperBound..<endIndex])
            cursor = endIndex
            if cursor == html.endIndex { break }
        }
        return out
    }

    /// Strips tags, decodes entities and collapses whitespace.
    static func plainText(_ html: Substring) -> String {
        var out = ""
        var inTag = false
        for ch in html {
            if ch == "<" { inTag = true; continue }
            if ch == ">" { inTag = false; continue }
            if !inTag { out.append(ch) }
        }
        return decodeEntities(out)
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static let named: [String: String] = [
        "amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ",
        "rsquo": "\u{2019}", "lsquo": "\u{2018}", "ldquo": "\u{201C}", "rdquo": "\u{201D}",
        "mdash": "\u{2014}", "ndash": "\u{2013}", "deg": "\u{00B0}", "hellip": "\u{2026}",
        "frac12": "\u{00BD}", "times": "\u{00D7}", "plusmn": "\u{00B1}",
    ]

    static func decodeEntities(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var out = ""
        var rest = s[...]
        while let amp = rest.firstIndex(of: "&") {
            out += rest[..<amp]
            let after = rest[rest.index(after: amp)...]
            if let semi = after.firstIndex(of: ";"), after.distance(from: after.startIndex, to: semi) <= 8 {
                let name = String(after[..<semi])
                var decoded: String?
                if name.hasPrefix("#x") || name.hasPrefix("#X") {
                    decoded = UInt32(name.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init).map { String(Character($0)) }
                } else if name.hasPrefix("#") {
                    decoded = UInt32(name.dropFirst()).flatMap(Unicode.Scalar.init).map { String(Character($0)) }
                } else {
                    decoded = named[name]
                }
                if let decoded {
                    out += decoded
                    rest = after[after.index(after: semi)...]
                    continue
                }
            }
            out += "&"
            rest = after
        }
        return out + rest
    }
}
