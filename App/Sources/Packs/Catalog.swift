import Foundation

/// A downloadable file: a Kiwix book, a map region or an AI model.
struct CatalogItem: Identifiable, Hashable, Sendable {
    enum Kind: String, Sendable { case book, map, model }
    let id: String
    let kind: Kind
    let title: String
    let summary: String
    let size: Int64
    let url: URL
    var fileName: String { url.lastPathComponent }
}

/// Reads the free Kiwix OPDS catalog (library.kiwix.org) so book links never go stale.
enum KiwixCatalog {
    static let base = URL(string: "https://opds.library.kiwix.org/catalog/v2/entries")!

    /// Survival-focused picks, roughly in order of usefulness per megabyte.
    static let recommended: [(name: String, flavour: String?, note: String)] = [
        ("zimgit-post-disaster_en", nil, "Post-disaster rebuilding: water, power, shelter, sanitation"),
        ("zimgit-water_en", nil, "Water treatment manuals"),
        ("zimgit-medicine_en", nil, "Field medicine manuals"),
        ("irp.fas.org_en_military-medicine", nil, "Military field medicine"),
        ("wikipedia_en_medicine", "nopic", "WikiMed: 70,000+ medical articles, no pictures"),
        ("nhs.uk_en_medicines", nil, "NHS Medicines A to Z: what each is for, doses, side effects"),
        ("zimgit-food-preparation_en", nil, "Food storage and preparation"),
        ("zimgit-knots_en", nil, "Knots, with diagrams"),
    ]

    static let bigger: [(name: String, flavour: String?, note: String)] = [
        ("medlineplus.gov_en_all", nil, "MedlinePlus: plain-language health encyclopedia from the U.S. National Library of Medicine"),
        ("wikipedia_en_medicine", "maxi", "WikiMed with pictures"),
        ("ifixit_en_all", nil, "iFixit repair guides"),
        ("wikipedia_en_100", nil, "Wikipedia's top 100 articles (good for testing)"),
        ("wikipedia_en_all", "mini", "All of Wikipedia, intros only"),
        ("wikipedia_en_all", "nopic", "All of Wikipedia, no pictures"),
    ]

    static func lookup(name: String, flavour: String?) async throws -> CatalogItem? {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "name", value: name), URLQueryItem(name: "count", value: "20")]
        let items = try await fetch(c.url!)
        return items.first { flavour == nil || $0.flavour == flavour }?.item
    }

    static func search(_ query: String) async throws -> [CatalogItem] {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "lang", value: "eng"), URLQueryItem(name: "count", value: "40")]
        return try await fetch(c.url!).map(\.item)
    }

    private static func fetch(_ url: URL) async throws -> [(item: CatalogItem, flavour: String?)] {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        let (data, _) = try await URLSession.shared.data(for: request)
        return OPDSParser.parse(data)
    }
}

/// Minimal Atom/OPDS parser: just the fields the app shows.
final class OPDSParser: NSObject, XMLParserDelegate {
    private var entries: [(CatalogItem, String?)] = []
    private var inEntry = false
    private var text = ""
    private var fields: [String: String] = [:]
    private var link: (url: URL, size: Int64)?

    static func parse(_ data: Data) -> [(item: CatalogItem, flavour: String?)] {
        let p = OPDSParser()
        let parser = XMLParser(data: data)
        parser.delegate = p
        parser.parse()
        return p.entries.map { (item: $0.0, flavour: $0.1) }
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        text = ""
        if name == "entry" { inEntry = true; fields = [:]; link = nil }
        if inEntry, name == "link", attributes["type"] == "application/x-zim",
           var href = attributes["href"] {
            if href.hasSuffix(".meta4") { href.removeLast(6) }
            if let url = URL(string: href) { link = (url, Int64(attributes["length"] ?? "") ?? 0) }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        guard inEntry else { return }
        switch name {
        case "title", "summary", "name", "flavour", "id": fields[name] = text.trimmingCharacters(in: .whitespacesAndNewlines)
        case "entry":
            inEntry = false
            if let link {
                let flavour = fields["flavour"].flatMap { $0.isEmpty ? nil : $0 }
                let item = CatalogItem(id: link.url.lastPathComponent, kind: .book,
                                       title: fields["title"] ?? link.url.lastPathComponent,
                                       summary: fields["summary"] ?? "", size: link.size, url: link.url)
                entries.append((item, flavour))
            }
        default: break
        }
    }
}

enum ModelCatalog {
    /// Apache-2.0 models that run well with llama.cpp on a phone.
    static let items: [CatalogItem] = [
        CatalogItem(id: "Qwen3-1.7B-Q4_K_M.gguf", kind: .model, title: "Qwen3 1.7B (small, fast)",
                    summary: "Works on most iPhones from 2020 on. About 1.1 GB.", size: 1_110_000_000,
                    url: URL(string: "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf")!),
        CatalogItem(id: "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", kind: .model, title: "Qwen3 4B (better answers)",
                    summary: "Needs an iPhone with 6 GB of memory or more (tested on iPhone 15). About 2.5 GB.", size: 2_500_000_000,
                    url: URL(string: "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf")!),
    ]
}
