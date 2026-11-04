import Foundation
import SQLite3
import ZIMKit
#if canImport(PDFKit)
import PDFKit
#endif

/// One HTML document to index (for the app's bundled guides, or anything else).
public struct IndexDocument: Sendable, Identifiable {
    public let path: String
    public let title: String
    public let html: Data
    public var id: String { path }

    public init(path: String, title: String, html: Data) {
        self.path = path
        self.title = title
        self.html = html
    }
}

public enum IndexError: Error, Sendable, CustomStringConvertible {
    case sqlite(String)
    case cancelled

    public var description: String {
        switch self {
        case let .sqlite(m): return "SQLite: \(m)"
        case .cancelled: return "index build cancelled"
        }
    }
}

public struct IndexStats: Sendable {
    public let articles: Int
    public let passages: Int
    public let seconds: Double
    public var articlesPerSecond: Double { seconds > 0 ? Double(articles) / seconds : 0 }
}

/// Builds the SQLite FTS5 passage index (schema_version 1):
///
///     CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
///     CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61');
///
/// Writes `<output>.tmp` first and moves it into place only when complete.
public enum IndexBuilder {
    public static let schemaVersion = 1

    /// Indexes the front articles of a ZIM. `progress(done, total)` is called periodically.
    @discardableResult
    public static func build(
        archive: ZIMArchive,
        output: URL,
        maxArticles: Int? = nil,
        progress: @Sendable (Int, Int) -> Void = { _, _ in },
        isCancelled: @Sendable () -> Bool = { false }
    ) throws -> IndexStats {
        let total = maxArticles.map { min($0, archive.articleCount) } ?? archive.articleCount
        let writer = try IndexWriter(output: output)
        var done = 0
        var lastReport = 0
        var failure: Error?
        // Storage order decompresses each cluster about once instead of once per article.
        archive.forEachArticle(order: .storage) { entry, position in
            if let maxArticles, done >= maxArticles { return false }
            if isCancelled() { failure = IndexError.cancelled; return false }
            done += 1
            if entry.mimeType.hasPrefix("text/html"), let content = try? archive.content(path: entry.path) {
                do { try writer.add(title: entry.title, path: entry.path, html: content.data) }
                catch { failure = error; return false }
            }
            // With --max, progress counts articles; otherwise listing slots (articleCount
            // can include front-article redirects, which are skipped).
            let shown = min(maxArticles == nil ? position : done, total)
            if shown - lastReport >= 50 { lastReport = shown; progress(shown, total) }
            return true
        }
        if let failure { writer.abandon(); throw failure }
        if maxArticles == nil {
            do { try indexPDFs(in: archive, writer: writer, isCancelled: isCancelled) }
            catch { writer.abandon(); throw error }
        }
        progress(total, total)
        return try writer.finish(meta: ["zim_uuid": archive.uuid])
    }

    /// Books such as the zimgit collections are mostly PDFs behind a single HTML
    /// page, so their text is indexed too. PDFs aren't front articles, so this walks
    /// every entry.
    static func indexPDFs(in archive: ZIMArchive, writer: IndexWriter, isCancelled: () -> Bool) throws {
        #if canImport(PDFKit)
        for index in 0 ..< archive.entryCount {
            if isCancelled() { throw IndexError.cancelled }
            guard let entry = archive.entry(at: index), !entry.isRedirect,
                  entry.mimeType == "application/pdf",
                  let content = try? archive.content(path: entry.path) else { continue }
            guard let pdf = autoreleasepool(invoking: { PDFText.extract(content.data) }) else { continue }
            try writer.add(title: pdf.title ?? PDFText.fileTitle(title: entry.title, path: entry.path), path: entry.path, text: pdf.text)
        }
        #endif
    }

    /// Indexes arbitrary HTML documents with the same schema and passage splitting.
    /// `sourceID` is stored as `zim_uuid` in meta; `total` is only used for progress.
    @discardableResult
    public static func build(
        documents: some Sequence<IndexDocument>,
        output: URL,
        sourceID: String = "",
        total: Int? = nil,
        progress: @Sendable (Int, Int) -> Void = { _, _ in },
        isCancelled: @Sendable () -> Bool = { false }
    ) throws -> IndexStats {
        let writer = try IndexWriter(output: output)
        let total = total ?? documents.underestimatedCount
        var done = 0
        do {
            for doc in documents {
                if isCancelled() { throw IndexError.cancelled }
                try writer.add(title: doc.title, path: doc.path, html: doc.html)
                done += 1
                if done % 50 == 0 { progress(done, max(total, done)) }
            }
        } catch {
            writer.abandon()
            throw error
        }
        progress(done, max(total, done))
        return try writer.finish(meta: ["zim_uuid": sourceID])
    }
}

/// Splits extracted text into ~900–1200 character passages on paragraph boundaries.
public enum PassageSplitter {
    public static let target = 900
    public static let maxLength = 1200

    public static func split(_ text: String) -> [String] {
        var pieces: [String] = []
        for para in text.components(separatedBy: "\n\n") {
            let p = para.trimmingCharacters(in: .whitespacesAndNewlines)
            if p.isEmpty { continue }
            if p.count <= maxLength { pieces.append(p) } else { pieces.append(contentsOf: splitLong(p)) }
        }
        var passages: [String] = []
        var current = ""
        for p in pieces {
            if current.isEmpty {
                current = p
            } else if current.count + 2 + p.count <= maxLength {
                current += "\n\n" + p
            } else {
                passages.append(current)
                current = p
            }
            if current.count >= target {
                passages.append(current)
                current = ""
            }
        }
        if !current.isEmpty {
            // Fold a short tail into the previous passage when it still fits loosely.
            if current.count < 200, let last = passages.last, last.count + 2 + current.count <= maxLength + 300 {
                passages[passages.count - 1] = last + "\n\n" + current
            } else {
                passages.append(current)
            }
        }
        return passages
    }

    /// Breaks one long paragraph at sentence ends (or spaces) into chunks <= maxLength.
    static func splitLong(_ p: String) -> [String] {
        var chunks: [String] = []
        var rest = Substring(p)
        while rest.count > maxLength {
            let window = rest.prefix(maxLength)
            var cut: String.Index?
            // Prefer the last sentence end after `target / 2` characters.
            let minCut = window.index(window.startIndex, offsetBy: target / 2)
            var idx = window.endIndex
            while idx > minCut {
                idx = window.index(before: idx)
                let ch = window[idx]
                if ch == "." || ch == "!" || ch == "?" {
                    let next = window.index(after: idx)
                    if next == window.endIndex || window[next] == " " { cut = next; break }
                }
            }
            if cut == nil { cut = window.lastIndex(of: " ").map { window.index(after: $0) } }
            let end = cut ?? window.endIndex
            let chunk = rest[rest.startIndex ..< end].trimmingCharacters(in: .whitespaces)
            if !chunk.isEmpty { chunks.append(chunk) }
            rest = rest[end...]
        }
        let tail = rest.trimmingCharacters(in: .whitespaces)
        if !tail.isEmpty { chunks.append(tail) }
        return chunks
    }
}

// MARK: - Writer

final class IndexWriter {
    let output: URL
    let tmp: URL
    private var db: OpaquePointer?
    private var insert: OpaquePointer?
    private var articles = 0
    private var passages = 0
    private var inBatch = 0
    private let start = Date()
    private static let batchSize = 500

    init(output: URL) throws {
        self.output = output
        tmp = output.appendingPathExtension("tmp")
        let fm = FileManager.default
        for suffix in ["", "-journal", "-wal", "-shm"] {
            try? fm.removeItem(atPath: tmp.path + suffix)
        }
        guard sqlite3_open_v2(tmp.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK else {
            let msg = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(db)
            throw IndexError.sqlite(msg)
        }
        try exec("""
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            PRAGMA temp_store=MEMORY;
            PRAGMA cache_size=-32768;
            CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
            CREATE VIRTUAL TABLE passages USING fts5(title, body, path UNINDEXED, tokenize='porter unicode61');
            """)
        guard sqlite3_prepare_v2(db, "INSERT INTO passages(title, body, path) VALUES (?, ?, ?)", -1, &insert, nil) == SQLITE_OK else {
            throw IndexError.sqlite(errmsg)
        }
        try exec("BEGIN")
    }

    deinit {
        sqlite3_finalize(insert)
        sqlite3_close(db)
    }

    private var errmsg: String { db.map { String(cString: sqlite3_errmsg($0)) } ?? "no database" }

    func exec(_ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? errmsg
            sqlite3_free(err)
            throw IndexError.sqlite(msg)
        }
    }

    func add(title docTitle: String, path: String, html: Data) throws {
        if Self.isMetaRefreshStub(html) { return } // HTML redirect page, nothing to index
        let (htmlTitle, text) = HTMLText.extract(html)
        try add(title: !docTitle.isEmpty ? docTitle : (htmlTitle ?? path), path: path, text: text)
    }

    func add(title: String, path: String, text: String) throws {
        let chunks = PassageSplitter.split(text)
        guard !chunks.isEmpty else { return }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for body in chunks {
            sqlite3_reset(insert)
            sqlite3_bind_text(insert, 1, title, -1, transient)
            sqlite3_bind_text(insert, 2, body, -1, transient)
            sqlite3_bind_text(insert, 3, path, -1, transient)
            guard sqlite3_step(insert) == SQLITE_DONE else { throw IndexError.sqlite(errmsg) }
            passages += 1
        }
        articles += 1
        inBatch += 1
        if inBatch >= Self.batchSize {
            try exec("COMMIT; BEGIN")
            inBatch = 0
        }
    }

    /// Small pages whose only job is `<meta http-equiv="refresh">` (mwoffliner section redirects).
    static func isMetaRefreshStub(_ html: Data) -> Bool {
        guard html.count < 4096 else { return false }
        let s = String(decoding: html, as: UTF8.self).lowercased()
        return s.contains("http-equiv") && s.contains("refresh")
    }

    func finish(meta: [String: String]) throws -> IndexStats {
        try exec("COMMIT")
        var all = meta
        all["schema_version"] = String(IndexBuilder.schemaVersion)
        all["articles"] = String(articles)
        all["passages"] = String(passages)
        all["built_at"] = ISO8601DateFormatter().string(from: Date())
        all["complete"] = "1"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", -1, &stmt, nil) == SQLITE_OK else {
            throw IndexError.sqlite(errmsg)
        }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        try exec("BEGIN")
        for (k, v) in all.sorted(by: { $0.key < $1.key }) where k != "complete" {
            sqlite3_reset(stmt)
            sqlite3_bind_text(stmt, 1, k, -1, transient)
            sqlite3_bind_text(stmt, 2, v, -1, transient)
            guard sqlite3_step(stmt) == SQLITE_DONE else { throw IndexError.sqlite(errmsg) }
        }
        try exec("COMMIT")
        try exec("INSERT INTO passages(passages) VALUES('optimize')")
        // `complete` goes in last, after optimize, so a crash never leaves a "complete" partial file.
        try exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('complete', '1')")
        try exec("PRAGMA journal_mode=DELETE")
        sqlite3_finalize(insert); insert = nil
        sqlite3_close(db); db = nil

        let fm = FileManager.default
        if fm.fileExists(atPath: output.path) { try fm.removeItem(at: output) }
        try fm.moveItem(at: tmp, to: output)
        return IndexStats(articles: articles, passages: passages, seconds: Date().timeIntervalSince(start))
    }

    func abandon() {
        sqlite3_finalize(insert); insert = nil
        sqlite3_close(db); db = nil
        try? FileManager.default.removeItem(at: tmp)
    }
}
