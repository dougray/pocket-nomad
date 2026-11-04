import Foundation
import NomadSearch

/// Where a search hit came from, so the app can open it.
enum SourceRef: Hashable, Sendable {
    case guide(path: String)                    // e.g. "first-aid/bleeding.html"
    case book(id: String, path: String)
}

struct SearchHit: Identifiable, Hashable, Sendable {
    let id = UUID()
    let ref: SourceRef
    let sourceName: String
    let title: String
    let snippet: String
    let body: String
    let score: Double
}

/// One place to search the built-in guides and every indexed book.
///
/// SQLite handles stay inside this actor; callers only see plain values.
actor SearchService {
    static let shared = SearchService()

    private var guideIndex: SearchIndex?
    private var bookIndexes: [(id: String, index: SearchIndex)] = []
    private var bookNames: [String: String] = [:]

    func reload(indexes: [(String, URL)]) async {
        bookIndexes = indexes.compactMap { id, url in
            (try? SearchIndex(url: url)).map { (id, $0) }
        }
        let names = await MainActor.run {
            Dictionary(uniqueKeysWithValues: ContentStore.shared.books.map { ($0.id, $0.title) })
        }
        bookNames = names
    }

    func setGuideIndex(url: URL) {
        guideIndex = try? SearchIndex(url: url)
    }

    var hasGuideIndex: Bool { guideIndex != nil }

    /// Best passage per article, for result lists.
    func searchArticles(_ query: String, limit: Int = 25) -> [SearchHit] {
        var seen = Set<SourceRef>()
        return search(query, limit: limit * 3).filter { seen.insert($0.ref).inserted }.prefix(limit).map { $0 }
    }

    /// Best passages across all sources.
    ///
    /// bm25 scores depend on each index's own word statistics, so a big book's
    /// scores dwarf the small guide index even when the guide is the better answer.
    /// Each index's scores are scaled against its own best hit, and the built-in
    /// guides (short, vetted, written for emergencies) get a boost on top.
    func search(_ query: String, limit: Int = 8) -> [SearchHit] {
        var hits: [SearchHit] = []
        if let guideIndex, let passages = try? guideIndex.search(query, limit: limit) {
            hits += Self.normalized(passages, boost: Self.guideBoost, query: query).map { p, score in
                SearchHit(ref: .guide(path: p.path), sourceName: "Survival Guide", title: GuideStore.guide(file: p.path)?.title ?? p.title,
                          snippet: p.snippet, body: p.body, score: score)
            }
        }
        for (id, index) in bookIndexes {
            guard let passages = try? index.search(query, limit: limit) else { continue }
            hits += Self.normalized(passages, boost: 1, query: query).map { p, score in
                SearchHit(ref: .book(id: id, path: p.path), sourceName: bookNames[id] ?? "Library", title: p.title,
                          snippet: p.snippet, body: p.body, score: score)
            }
        }
        return Array(hits.sorted { $0.score > $1.score }.prefix(limit))
    }

    static let guideBoost = 1.3

    /// Passage scores are -bm25 (higher is better). Each is scaled against its index's
    /// best hit, then by how much of the question it covers, so a passage that only
    /// shares one word ("stop" in "Just Stop Oil") can't outrank one that matches it all.
    static func normalized(_ passages: [Passage], boost: Double, query: String) -> [(Passage, Double)] {
        guard let top = passages.map(\.score).max(), top > 0 else { return passages.map { ($0, 0) } }
        let terms = keyTerms(query)
        return passages.map { p in
            let cover = coverage(of: terms, in: p.title + " " + p.body)
            return (p, p.score / top * boost * cover * cover)
        }
    }

    private static let stopwords: Set<String> = [
        "a", "an", "and", "are", "as", "at", "be", "can", "do", "does", "for", "from", "how", "i", "if", "in", "is",
        "it", "me", "my", "of", "on", "or", "should", "so", "that", "the", "to", "what", "when", "where", "which",
        "who", "why", "will", "with", "you", "your", "much", "many", "get", "make", "about", "there", "this",
    ]

    /// Lowercased content words, cut to a 5-letter stem so "bleeding" matches "bleed".
    static func keyTerms(_ query: String) -> [String] {
        let words = query.lowercased().split { !$0.isLetter && !$0.isNumber }.map(String.init)
        return Array(Set(words.filter { !stopwords.contains($0) && $0.count > 1 }.map { String($0.prefix(5)) }))
    }

    /// Share of key terms present in the text (1 when the query has none).
    static func coverage(of terms: [String], in text: String) -> Double {
        guard !terms.isEmpty else { return 1 }
        let lower = text.lowercased()
        return Double(terms.filter { lower.contains($0) }.count) / Double(terms.count)
    }
}
