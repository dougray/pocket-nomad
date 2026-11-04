import Foundation
import SQLite3

/// One search hit. `score` is `-bm25`, so HIGHER IS BETTER.
/// `snippet` marks matched terms with `**bold**` (renders via `AttributedString(markdown:)`).
public struct Passage: Sendable, Hashable {
    public let title: String
    public let path: String
    public let body: String
    public let snippet: String
    public let score: Double
}

/// Read-only access to an index built by `IndexBuilder`. Thread-safe.
public final class SearchIndex: @unchecked Sendable {
    public let url: URL
    private var db: OpaquePointer?
    private let lock = NSLock()

    public init(url: URL) throws {
        self.url = url
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(url.path, &db, flags, nil) == SQLITE_OK else {
            let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(db)
            db = nil
            throw IndexError.sqlite(msg)
        }
        // Fail early on files that aren't our index.
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT count(*) FROM meta", -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW else {
            let msg = String(cString: sqlite3_errmsg(db))
            sqlite3_close(db)
            db = nil
            throw IndexError.sqlite("not a NOMAD index: \(msg)")
        }
    }

    deinit { sqlite3_close(db) }

    /// `meta.zim_uuid` (hex), or nil if empty/missing.
    public var zimUUID: String? {
        guard let v = meta("zim_uuid"), !v.isEmpty else { return nil }
        return v
    }

    /// True when the build finished (`meta.complete = 1`).
    public var isComplete: Bool { meta("complete") == "1" }

    public func meta(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT value FROM meta WHERE key = ?", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, key, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        guard sqlite3_step(stmt) == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: c)
    }

    /// Free-text search. Never throws on odd input (quotes, `*`, operators); returns
    /// [] when nothing searchable is left. Throws only on database errors.
    public func search(_ query: String, limit: Int) throws -> [Passage] {
        guard limit > 0, let match = Self.ftsQuery(query) else { return [] }
        lock.lock(); defer { lock.unlock() }
        let sql = """
            SELECT title, path, body, snippet(passages, 1, '**', '**', '…', 32), bm25(passages, 4.0, 1.0, 0.0) AS rank
            FROM passages WHERE passages MATCH ? ORDER BY rank LIMIT ?
            """
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            throw IndexError.sqlite(String(cString: sqlite3_errmsg(db)))
        }
        sqlite3_bind_text(stmt, 1, match, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        sqlite3_bind_int(stmt, 2, Int32(min(limit, 10_000)))
        var out: [Passage] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw IndexError.sqlite(String(cString: sqlite3_errmsg(db))) }
            func text(_ i: Int32) -> String { sqlite3_column_text(stmt, i).map { String(cString: $0) } ?? "" }
            out.append(Passage(title: text(0), path: text(1), body: text(2), snippet: text(3),
                               score: -sqlite3_column_double(stmt, 4)))
        }
        return out
    }

    // MARK: Query sanitizer

    static let stopwords: Set<String> = [
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
        "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
        "near", "not", "of", "on", "or", "our", "should", "so", "than", "that", "the", "their", "them", "then",
        "there", "these", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which", "who",
        "why", "will", "with", "would", "you", "your", "about", "any", "some", "use", "using", "get", "make",
        "tell", "please", "best", "way", "ways", "need", "want", "know", "much", "many", "very", "just",
    ]

    /// Turns free text into a safe FTS5 MATCH expression: lowercase word tokens,
    /// stopwords and punctuation dropped, each term double-quoted, joined with OR.
    /// Returns nil when nothing searchable remains. If every word is a stopword the
    /// stopwords are kept, so "the who" still searches.
    public static func ftsQuery(_ text: String) -> String? {
        var tokens: [String] = []
        var current = ""
        for ch in text.lowercased() {
            if ch.isLetter || ch.isNumber {
                current.append(ch)
            } else if !current.isEmpty {
                tokens.append(current)
                current = ""
            }
        }
        if !current.isEmpty { tokens.append(current) }
        tokens = tokens.filter { $0.count >= 2 || $0.allSatisfy(\.isNumber) }
        var terms = tokens.filter { !stopwords.contains($0) }
        if terms.isEmpty { terms = tokens }
        var seen = Set<String>()
        terms = terms.filter { seen.insert($0).inserted }
        guard !terms.isEmpty else { return nil }
        return terms.prefix(32).map { "\"\($0)\"" }.joined(separator: " OR ")
    }
}
