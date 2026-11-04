import Foundation
#if canImport(PDFKit)
import PDFKit

/// Plain text from a PDF stored in a ZIM, in the paragraph form `PassageSplitter` expects.
enum PDFText {
    /// The document's text and the best title it offers (its metadata title, or its
    /// first line), either of which may be missing.
    static func extract(_ data: Data) -> (title: String?, text: String)? {
        guard let doc = PDFDocument(data: data) else { return nil }
        var pages: [String] = []
        for i in 0 ..< doc.pageCount {
            guard let page = doc.page(at: i)?.string else { continue }
            pages.append(paragraphs(page))
        }
        let text = pages.joined(separator: "\n\n").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let meta = (doc.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String).flatMap(usableTitle)
        let firstLine = doc.page(at: 0)?.string?.components(separatedBy: .newlines).lazy.compactMap(usableTitle).first
        return (meta ?? firstLine, text)
    }

    /// Rejects empty, numeric and tool-generated titles ("Microsoft Word - doc1.docx").
    static func usableTitle(_ raw: String) -> String? {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let range = t.range(of: "Microsoft Word - ") { t.removeSubrange(range) }
        let letters = t.unicodeScalars.filter(CharacterSet.letters.contains).count
        let lower = t.lowercased()
        let boilerplate = ["preface", "contents", "table of contents", "foreword", "introduction", "acknowledgments", "acknowledgements"]
        guard letters >= 4, t.count <= 100, !t.contains("\\"), !t.contains("/"),
              ![".doc", ".docx", ".pdf"].contains(where: lower.hasSuffix),
              !lower.hasPrefix("untitled"), !boilerplate.contains(lower) else { return nil }
        return t
    }

    /// PDF text breaks every line. Rejoin lines into paragraphs, splitting on blank
    /// lines and on lines that end a sentence followed by a short line.
    static func paragraphs(_ page: String) -> String {
        var out: [String] = []
        var current = ""
        for raw in page.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                if !current.isEmpty { out.append(current); current = "" }
                continue
            }
            if current.hasSuffix("-") { current.removeLast(); current += line }
            else { current += current.isEmpty ? line : " " + line }
        }
        if !current.isEmpty { out.append(current) }
        return out.joined(separator: "\n\n")
    }

    /// "Water_Treatment-Guide.pdf" → "Water Treatment Guide".
    static func fileTitle(title: String, path: String) -> String {
        var name = title.isEmpty ? (path.split(separator: "/").last.map(String.init) ?? path) : title
        if name.lowercased().hasSuffix(".pdf") { name.removeLast(4) }
        name = name.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        return name.split(separator: " ").joined(separator: " ")
    }
}
#endif
