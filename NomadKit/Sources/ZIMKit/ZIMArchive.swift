import Foundation

/// A directory entry in the content namespace. `path` has no namespace prefix on new-layout
/// (6.1+) files and is "<ns>/<path>" (e.g. "A/Foo") on old-layout files.
/// For redirects, `mimeType` is the MIME type of the final target ("" if it can't be resolved).
public struct ZIMEntry: Sendable, Hashable {
    public let index: Int
    public let path: String
    public let title: String
    public let mimeType: String
    public let isRedirect: Bool
}

/// The bytes of an entry, after following redirects. `path` is the final target's path.
public struct ZIMContent: Sendable {
    public let data: Data
    public let mimeType: String
    public let path: String
}

/// Pure-Swift, read-only ZIM reader (major versions 5 and 6).
///
/// Reads with `pread` and keeps only small caches in memory, so it is safe to
/// open very large archives. All methods are safe to call from any thread.
public final class ZIMArchive: @unchecked Sendable {
    public let url: URL

    private let file: FileReader
    private let header: Header
    private let mimeTypes: [String]
    /// True for ZIM 6.1+ ("new namespace scheme": C, M, W, X).
    public let usesNewNamespaceScheme: Bool
    private let contentNamespaces: [UInt8]

    private let clusterCache = Locked(LRUCache<Int, Data>(capacity: 8))
    private let lazyState = Locked(LazyState())

    private struct LazyState {
        var frontListing: Data??   // nil = not loaded yet; .some(nil) = absent
        var articleCount: Int?
    }

    // MARK: Opening

    public init(url: URL) throws {
        self.url = url
        file = try FileReader(url: url)
        header = try Header(file: file)
        usesNewNamespaceScheme = header.major >= 6 && header.minor >= 1
        contentNamespaces = usesNewNamespaceScheme ? [UInt8(ascii: "C")]
            : [UInt8(ascii: "A"), UInt8(ascii: "I"), UInt8(ascii: "-")]
        mimeTypes = try Self.readMimeList(file: file, header: header)
    }

    private static func readMimeList(file: FileReader, header: Header) throws -> [String] {
        let end = header.pathPtrPos > header.mimeListPos ? header.pathPtrPos : file.size
        let length = Int(min(end - header.mimeListPos, 1 << 20))
        let data = try file.cachedRead(at: header.mimeListPos, count: length)
        var list: [String] = []
        var start = 0
        let bytes = [UInt8](data)
        for i in 0 ..< bytes.count where bytes[i] == 0 {
            if i == start { return list } // empty string terminates the list
            list.append(String(decoding: bytes[start ..< i], as: UTF8.self))
            start = i + 1
        }
        throw ZIMError.invalidHeader("unterminated MIME type list")
    }

    // MARK: Basic properties

    public var uuid: String { header.uuid.map { String(format: "%02x", $0) }.joined() }
    public var entryCount: Int { Int(header.entryCount) }
    public var clusterCount: Int { Int(header.clusterCount) }
    public var majorVersion: Int { Int(header.major) }
    public var minorVersion: Int { Int(header.minor) }

    /// Front articles if the archive has a front-article listing, otherwise the
    /// number of non-redirect HTML entries in the content namespace. Cached.
    public var articleCount: Int {
        if let n = lazyState.withLock({ $0.articleCount }) { return n }
        let n: Int
        if let listing = frontListing() {
            n = listing.count / 4
        } else if let c = counterHTML() {
            n = c
        } else {
            let ns = usesNewNamespaceScheme ? UInt8(ascii: "C") : UInt8(ascii: "A")
            var count = 0
            for i in (try? namespaceRange(ns)) ?? 0 ..< 0 {
                if let d = try? dirent(at: i), d.isItem, mimeName(d.mime).hasPrefix("text/html") { count += 1 }
            }
            n = count
        }
        lazyState.withLock { $0.articleCount = n }
        return n
    }

    /// Reads `M/<key>` as UTF-8 text, e.g. "Title", "Description", "Language".
    public func metadata(_ key: String) -> String? {
        guard let data = metadataData(key) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// Raw bytes of `M/<key>`.
    public func metadataData(_ key: String) -> Data? {
        guard let d = try? findDirent(ns: UInt8(ascii: "M"), path: Array(key.utf8)),
              let target = try? resolve(d),
              let data = try? blob(of: target) else { return nil }
        return data
    }

    /// Keys present in the metadata namespace.
    public var metadataKeys: [String] {
        guard let range = try? namespaceRange(UInt8(ascii: "M")) else { return [] }
        return range.compactMap { try? dirent(at: $0).path }
    }

    /// PNG illustration (`M/Illustration_<size>x<size>@1`), falling back to the old favicon.
    public func illustration(size: Int = 48) -> Data? {
        if let d = metadataData("Illustration_\(size)x\(size)@1") { return d }
        for (ns, path) in [("-", "favicon"), ("-", "favicon.png"), ("I", "favicon.png")] {
            if let d = try? findDirent(ns: UInt8(ascii: Unicode.Scalar(ns)!), path: Array(path.utf8)),
               let t = try? resolve(d), let data = try? blob(of: t) { return data }
        }
        return nil
    }

    /// Path (no namespace) of the main page, with redirects resolved.
    public var mainPagePath: String? {
        if header.mainPage != UInt32.max, Int(header.mainPage) < entryCount,
           let d = try? dirent(at: Int(header.mainPage)), let t = try? resolve(d) {
            return publicPath(t)
        }
        if let d = try? findDirent(ns: UInt8(ascii: "W"), path: Array("mainPage".utf8)), let t = try? resolve(d) {
            return publicPath(t)
        }
        return nil
    }

    // MARK: Lookup

    /// Looks up a content entry by path.
    ///
    /// New layout (6.1+): paths have no namespace prefix ("Ray_Charles").
    /// Old layout: returned paths include the namespace ("A/Foo", "I/foo.png", "-/style.css")
    /// so relative links in the HTML resolve; such paths are accepted here, and a bare
    /// path is tried in `A`, `I`, then `-`. Percent-encoded paths are decoded as a fallback.
    public func entry(path: String) -> ZIMEntry? {
        guard let d = lookupDirent(path: path) else { return nil }
        return makeEntry(d)
    }

    /// Entry bytes for a path, following redirects (max depth 10).
    /// Returns nil if the path doesn't exist; throws on corrupt data.
    public func content(path: String) throws -> ZIMContent? {
        guard let d = lookupDirent(path: path) else { return nil }
        let target = try resolve(d)
        guard target.isItem else { return nil }
        return ZIMContent(data: try blob(of: target), mimeType: mimeName(target.mime), path: publicPath(target))
    }

    /// Content entry by its index in the path-ordered directory (nil for other namespaces).
    public func entry(at index: Int) -> ZIMEntry? {
        guard let d = try? dirent(at: index), contentNamespaces.contains(d.ns) else { return nil }
        return makeEntry(d)
    }

    private func lookupDirent(path rawPath: String) -> Dirent? {
        var candidates = [rawPath.hasPrefix("/") ? String(rawPath.dropFirst()) : rawPath]
        if let decoded = candidates[0].removingPercentEncoding, decoded != candidates[0] { candidates.append(decoded) }
        for path in candidates {
            let bytes = Array(path.utf8)
            // "X/rest" with a single-char content namespace: old-layout public paths
            // ("A/Foo", "I/foo.png", "-/style.css"); also "C/foo" on new files.
            let explicit = bytes.count > 2 && bytes[1] == UInt8(ascii: "/") && contentNamespaces.contains(bytes[0])
            if explicit, let d = try? findDirent(ns: bytes[0], path: Array(bytes[2...])) { return d }
            for ns in contentNamespaces {
                if let d = try? findDirent(ns: ns, path: bytes) { return d }
            }
        }
        return nil
    }

    // MARK: Title search

    /// Case-insensitive title-prefix search over articles. Binary-searches the
    /// title-ordered lists using several capitalisations of the query, merges and
    /// dedupes; exact title matches come first.
    public func titleSearch(_ query: String, limit: Int) -> [ZIMEntry] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, limit > 0 else { return [] }
        let lowerQ = q.lowercased()
        var variants: [String] = []
        for v in [q, q.prefix(1).uppercased() + q.dropFirst(), q.prefix(1).uppercased() + q.dropFirst().lowercased(),
                  q.capitalized, lowerQ, q.uppercased(),
                  q.replacingOccurrences(of: " ", with: "_")] where !variants.contains(v) {
            variants.append(v)
        }
        let ns = usesNewNamespaceScheme ? UInt8(ascii: "C") : UInt8(ascii: "A")

        var results: [ZIMEntry] = []
        var seen = Set<Int>()
        let scanCap = limit * 4 + 64

        func search(count: Int, indexAt: (Int) throws -> Int) {
            for variant in variants {
                guard results.count < limit else { return }
                let key = Array(variant.utf8)
                // lower bound of (ns, key) in title order
                var lo = 0, hi = count
                while lo < hi {
                    let mid = (lo + hi) / 2
                    guard let d = try? dirent(at: indexAt(mid)) else { return }
                    if Self.compare(d.ns, d.sortTitle, ns, key) < 0 { lo = mid + 1 } else { hi = mid }
                }
                var i = lo, scanned = 0
                while i < count, scanned < scanCap, results.count < limit {
                    defer { i += 1; scanned += 1 }
                    guard let idx = try? indexAt(i), let d = try? dirent(at: idx) else { break }
                    guard d.ns == ns, d.sortTitle.starts(with: key) else { break }
                    // Dedupe on the resolved target so a redirect and its article appear once.
                    let target = d.isRedirect ? ((try? resolve(d))?.index ?? idx) : idx
                    guard seen.insert(target).inserted else { continue }
                    let e = makeEntry(d)
                    guard e.mimeType.hasPrefix("text/html"),
                          e.title.lowercased().hasPrefix(lowerQ) || d.path.lowercased().hasPrefix(lowerQ)
                    else { continue }
                    results.append(e)
                }
            }
        }

        if header.hasTitleList(fileSize: file.size) {
            let base = header.titlePtrPos
            search(count: entryCount) { Int(try self.file.u32(at: base + 4 * UInt64($0))) }
        }
        if results.count < limit, let listing = frontListing() {
            search(count: listing.count / 4) { Int(listing.le(UInt32.self, at: 4 * $0)) }
        }

        let exact = results.filter { $0.title.lowercased() == lowerQ }
        let rest = results.filter { $0.title.lowercased() != lowerQ }
        return Array((exact + rest).prefix(limit))
    }

    // MARK: Iteration

    /// Calls `body` for each front article (the `X/listing/titleOrdered/v1` list when
    /// present, skipping redirects in it; otherwise every non-redirect HTML entry in the
    /// content namespace, in path order). Return false to stop. Entries that fail to read are skipped.
    public func forEachArticle(_ body: (ZIMEntry) throws -> Bool) rethrows {
        try forEachArticle(order: .listing) { entry, _ in try body(entry) }
    }

    /// Order for `forEachArticle(order:_:)`.
    public enum ArticleOrder: Sendable {
        /// Title order (front-article listing) or path order (fallback).
        case listing
        /// Same articles, re-sorted in chunks by cluster and blob so each compressed
        /// cluster is decompressed about once. Much faster for bulk reads such as indexing.
        case storage
    }

    /// Like `forEachArticle(_:)`, also passing `position`: how many listing slots have
    /// been consumed so far (1...articleCount), for progress reporting.
    public func forEachArticle(order: ArticleOrder,
                               _ body: (_ entry: ZIMEntry, _ position: Int) throws -> Bool) rethrows {
        let listing = frontListing()
        let slotCount: Int
        let range: Range<Int>
        if let listing {
            slotCount = listing.count / 4
            range = 0 ..< 0
        } else {
            let ns = usesNewNamespaceScheme ? UInt8(ascii: "C") : UInt8(ascii: "A")
            range = (try? namespaceRange(ns)) ?? 0 ..< 0
            slotCount = range.count
        }
        func direntForSlot(_ slot: Int) -> Dirent? {
            let idx = listing.map { Int($0.le(UInt32.self, at: 4 * slot)) } ?? (range.lowerBound + slot)
            guard let d = try? dirent(at: idx), d.isItem else { return nil }
            if listing == nil, !mimeName(d.mime).hasPrefix("text/html") { return nil }
            return d
        }

        switch order {
        case .listing:
            var produced = 0
            for slot in 0 ..< slotCount {
                guard let d = direntForSlot(slot) else { continue }
                produced += 1
                // Position counts listing slots; without a listing it counts articles (matches articleCount).
                if try !body(makeEntry(d), listing == nil ? produced : slot + 1) { return }
            }
        case .storage:
            let chunk = 50_000
            var chunkStart = 0
            var produced = 0
            while chunkStart < slotCount {
                let chunkEnd = min(slotCount, chunkStart + chunk)
                var items: [Dirent] = []
                items.reserveCapacity(chunkEnd - chunkStart)
                for slot in chunkStart ..< chunkEnd { if let d = direntForSlot(slot) { items.append(d) } }
                items.sort { ($0.cluster, $0.blob) < ($1.cluster, $1.blob) }
                for (k, d) in items.enumerated() {
                    produced += 1
                    let pos = listing == nil ? produced
                        : chunkStart + Int((Double(k + 1) / Double(items.count)) * Double(chunkEnd - chunkStart))
                    if try !body(makeEntry(d), pos) { return }
                }
                chunkStart = chunkEnd
            }
        }
    }

    // MARK: Internals: listings

    private func frontListing() -> Data? {
        if let cached = lazyState.withLock({ $0.frontListing }) { return cached }
        var listing: Data?
        if usesNewNamespaceScheme,
           let d = try? findDirent(ns: UInt8(ascii: "X"), path: Array("listing/titleOrdered/v1".utf8)),
           let t = try? resolve(d), let data = try? blob(of: t) {
            listing = data
        }
        lazyState.withLock { $0.frontListing = .some(listing) }
        return listing
    }

    /// Sum of "text/html" items from the `M/Counter` metadata ("text/html=12;image/png=3").
    private func counterHTML() -> Int? {
        guard !usesNewNamespaceScheme, let counter = metadata("Counter") else { return nil }
        var total: Int?
        for part in counter.split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1)
            if kv.count == 2, kv[0].hasPrefix("text/html"), let n = Int(kv[1]) { total = (total ?? 0) + n }
        }
        return total
    }

    // MARK: Internals: dirents

    struct Dirent {
        let index: Int
        let mime: UInt16
        let ns: UInt8
        let cluster: UInt32
        let blob: UInt32
        let redirectIndex: UInt32
        let pathBytes: [UInt8]
        let titleBytes: [UInt8]

        var isRedirect: Bool { mime == 0xffff }
        /// A real item with data (not redirect / linktarget / deleted).
        var isItem: Bool { mime < 0xfffd }
        var path: String { String(decoding: pathBytes, as: UTF8.self) }
        /// Title as used for sorting and display: the path when the title is empty.
        var sortTitle: [UInt8] { titleBytes.isEmpty ? pathBytes : titleBytes }
        var title: String { String(decoding: sortTitle, as: UTF8.self) }
    }

    func dirent(at index: Int) throws -> Dirent {
        guard index >= 0, index < entryCount else { throw ZIMError.indexOutOfRange(index) }
        let offset = try file.u64(at: header.pathPtrPos + 8 * UInt64(index))
        guard offset < file.size else { throw ZIMError.invalidDirent(index: index) }
        var want = 256
        while true {
            let data = try file.cachedRead(at: offset, count: want)
            if let d = Self.parseDirent(data, index: index) { return d }
            if data.count < want || want >= 1 << 17 { throw ZIMError.invalidDirent(index: index) }
            want *= 4
        }
    }

    private static func parseDirent(_ data: Data, index: Int) -> Dirent? {
        guard data.count >= 8 else { return nil }
        let mime = data.le(UInt16.self, at: 0)
        let ns = data[data.startIndex + 3]
        var pos = 8
        var cluster: UInt32 = 0, blob: UInt32 = 0, redirect: UInt32 = 0
        if mime == 0xffff {
            guard data.count >= pos + 4 else { return nil }
            redirect = data.le(UInt32.self, at: pos); pos += 4
        } else if mime == 0xfffe || mime == 0xfffd {
            // linktarget / deleted: no cluster or redirect fields
        } else {
            guard data.count >= pos + 8 else { return nil }
            cluster = data.le(UInt32.self, at: pos)
            blob = data.le(UInt32.self, at: pos + 4)
            pos += 8
        }
        return data.withUnsafeBytes { raw -> Dirent? in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard let pathEnd = bytes[pos...].firstIndex(of: 0) else { return nil }
            guard let titleEnd = bytes[(pathEnd + 1)...].firstIndex(of: 0) else { return nil }
            return Dirent(index: index, mime: mime, ns: ns, cluster: cluster, blob: blob, redirectIndex: redirect,
                          pathBytes: Array(bytes[pos ..< pathEnd]), titleBytes: Array(bytes[(pathEnd + 1) ..< titleEnd]))
        }
    }

    /// Follows redirects (max depth 10).
    func resolve(_ start: Dirent) throws -> Dirent {
        var d = start
        var depth = 0
        while d.isRedirect {
            depth += 1
            if depth > 10 { throw ZIMError.redirectLoop(path: start.path) }
            d = try dirent(at: Int(d.redirectIndex))
        }
        return d
    }

    private func makeEntry(_ d: Dirent) -> ZIMEntry {
        let mime: String
        if d.isRedirect {
            mime = (try? resolve(d)).flatMap { $0.isItem ? mimeName($0.mime) : nil } ?? ""
        } else {
            mime = d.isItem ? mimeName(d.mime) : ""
        }
        return ZIMEntry(index: d.index, path: publicPath(d), title: d.title, mimeType: mime, isRedirect: d.isRedirect)
    }

    /// New layout: bare path. Old layout: "<ns>/<path>" so relative links like "../I/x.png" resolve.
    private func publicPath(_ d: Dirent) -> String {
        usesNewNamespaceScheme ? d.path : String(UnicodeScalar(d.ns)) + "/" + d.path
    }

    private func mimeName(_ m: UInt16) -> String {
        Int(m) < mimeTypes.count ? mimeTypes[Int(m)] : ""
    }

    static func compare(_ ns1: UInt8, _ a: [UInt8], _ ns2: UInt8, _ b: [UInt8]) -> Int {
        if ns1 != ns2 { return ns1 < ns2 ? -1 : 1 }
        let n = min(a.count, b.count)
        let c = a.withUnsafeBufferPointer { pa in
            b.withUnsafeBufferPointer { pb in n == 0 ? 0 : memcmp(pa.baseAddress!, pb.baseAddress!, n) }
        }
        if c != 0 { return c < 0 ? -1 : 1 }
        return a.count == b.count ? 0 : (a.count < b.count ? -1 : 1)
    }

    /// Index of the first entry >= (ns, path) in path order.
    private func lowerBound(ns: UInt8, path: [UInt8]) throws -> Int {
        var lo = 0, hi = entryCount
        while lo < hi {
            let mid = (lo + hi) / 2
            let d = try dirent(at: mid)
            if Self.compare(d.ns, d.pathBytes, ns, path) < 0 { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    private func findDirent(ns: UInt8, path: [UInt8]) throws -> Dirent? {
        let i = try lowerBound(ns: ns, path: path)
        guard i < entryCount else { return nil }
        let d = try dirent(at: i)
        return d.ns == ns && d.pathBytes == path ? d : nil
    }

    private func namespaceRange(_ ns: UInt8) throws -> Range<Int> {
        let lo = try lowerBound(ns: ns, path: [])
        let hi = ns == 255 ? entryCount : try lowerBound(ns: ns + 1, path: [])
        return lo ..< max(lo, hi)
    }

    // MARK: Internals: clusters

    private func blob(of d: Dirent) throws -> Data {
        guard d.isItem else { throw ZIMError.invalidDirent(index: d.index) }
        return try readBlob(cluster: Int(d.cluster), blob: Int(d.blob))
    }

    private func clusterBounds(_ c: Int) throws -> (start: UInt64, end: UInt64) {
        guard c >= 0, c < clusterCount else { throw ZIMError.invalidCluster(index: c, reason: "index out of range") }
        let start = try file.u64(at: header.clusterPtrPos + 8 * UInt64(c))
        var end: UInt64
        if c + 1 < clusterCount {
            end = try file.u64(at: header.clusterPtrPos + 8 * UInt64(c + 1))
        } else {
            end = header.checksumPos > start && header.checksumPos <= file.size ? header.checksumPos : file.size
        }
        if end > file.size { end = file.size }
        guard start < end else { throw ZIMError.invalidCluster(index: c, reason: "bad bounds") }
        return (start, end)
    }

    private func readBlob(cluster c: Int, blob b: Int) throws -> Data {
        if let data = clusterCache.withLock({ $0.get(c) }) {
            return try Self.extractBlob(from: data, cluster: c, blob: b)
        }
        let (start, end) = try clusterBounds(c)
        let info = try file.cachedRead(at: start, count: 1)[0]
        let compression = Int(info & 0x0f)
        let extended = info & 0x10 != 0
        let offSize: UInt64 = extended ? 8 : 4
        let base = start + 1

        func readOffset(_ i: Int) throws -> UInt64 {
            extended ? try file.u64(at: base + UInt64(i) * offSize) : UInt64(try file.u32(at: base + UInt64(i) * offSize))
        }

        switch compression {
        case 0, 1:
            // Uncompressed: read only the offsets we need and the blob itself.
            let first = try readOffset(0)
            guard first >= offSize, first % offSize == 0 else {
                throw ZIMError.invalidCluster(index: c, reason: "bad first offset")
            }
            let blobCount = Int(first / offSize) - 1
            guard b < blobCount else { throw ZIMError.invalidCluster(index: c, reason: "blob \(b) out of range") }
            let o1 = try readOffset(b), o2 = try readOffset(b + 1)
            guard o1 <= o2, base + o2 <= end else { throw ZIMError.invalidCluster(index: c, reason: "bad blob offsets") }
            return try file.read(at: base + o1, count: Int(o2 - o1))
        case 2, 4, 5:
            let raw = try file.read(at: base, count: Int(end - base))
            var data: Data
            switch compression {
            case 4: data = try Decompress.xz(raw)
            case 5: data = try Decompress.zstd(raw)
            default: data = try Decompress.zlib(raw)
            }
            // Remember the extended flag by prefixing it to the cached bytes.
            data.insert(extended ? 1 : 0, at: 0)
            clusterCache.withLock { $0.set(c, data) }
            return try Self.extractBlob(from: data, cluster: c, blob: b)
        default:
            throw ZIMError.unsupportedCompression(compression)
        }
    }

    /// `data` is a decompressed cluster prefixed with one flag byte (1 = extended offsets).
    private static func extractBlob(from data: Data, cluster c: Int, blob b: Int) throws -> Data {
        guard data.count > 1 else { throw ZIMError.invalidCluster(index: c, reason: "empty") }
        let extended = data[data.startIndex] == 1
        let offSize = extended ? 8 : 4
        let body = 1
        let total = data.count - body
        func offset(_ i: Int) throws -> Int {
            let at = body + i * offSize
            guard at + offSize <= data.count else { throw ZIMError.invalidCluster(index: c, reason: "offset table") }
            let v = extended ? data.le(UInt64.self, at: at) : UInt64(data.le(UInt32.self, at: at))
            guard v <= UInt64(total) else { throw ZIMError.invalidCluster(index: c, reason: "offset past end") }
            return Int(v)
        }
        let first = try offset(0)
        guard first >= offSize, first % offSize == 0 else { throw ZIMError.invalidCluster(index: c, reason: "bad first offset") }
        guard b < first / offSize - 1 else { throw ZIMError.invalidCluster(index: c, reason: "blob \(b) out of range") }
        let o1 = try offset(b), o2 = try offset(b + 1)
        guard o1 <= o2 else { throw ZIMError.invalidCluster(index: c, reason: "bad blob offsets") }
        return data.subdata(in: (data.startIndex + body + o1) ..< (data.startIndex + body + o2))
    }
}

// MARK: - Header

struct Header {
    static let magic: UInt32 = 72_173_914 // "ZIM\x04"

    let major: UInt16
    let minor: UInt16
    let uuid: [UInt8]
    let entryCount: UInt32
    let clusterCount: UInt32
    let pathPtrPos: UInt64
    let titlePtrPos: UInt64
    let clusterPtrPos: UInt64
    let mimeListPos: UInt64
    let mainPage: UInt32
    let layoutPage: UInt32
    let checksumPos: UInt64

    init(file: FileReader) throws {
        guard file.size >= 80 else { throw ZIMError.notAZIMFile }
        let h = try file.read(at: 0, count: 80)
        guard h.le(UInt32.self, at: 0) == Self.magic else { throw ZIMError.notAZIMFile }
        major = h.le(UInt16.self, at: 4)
        minor = h.le(UInt16.self, at: 6)
        guard major == 5 || major == 6 else { throw ZIMError.unsupportedVersion(major: Int(major), minor: Int(minor)) }
        uuid = Array(h[8 ..< 24])
        entryCount = h.le(UInt32.self, at: 24)
        clusterCount = h.le(UInt32.self, at: 28)
        pathPtrPos = h.le(UInt64.self, at: 32)
        titlePtrPos = h.le(UInt64.self, at: 40)
        clusterPtrPos = h.le(UInt64.self, at: 48)
        mimeListPos = h.le(UInt64.self, at: 56)
        mainPage = h.le(UInt32.self, at: 64)
        layoutPage = h.le(UInt32.self, at: 68)
        checksumPos = h.le(UInt64.self, at: 72)

        let size = file.size
        guard mimeListPos >= 80, mimeListPos < size else { throw ZIMError.invalidHeader("mimeListPos out of range") }
        guard pathPtrPos <= size, UInt64(entryCount) * 8 <= size - pathPtrPos else {
            throw ZIMError.invalidHeader("path pointer list out of range")
        }
        guard clusterPtrPos <= size, UInt64(clusterCount) * 8 <= size - clusterPtrPos else {
            throw ZIMError.invalidHeader("cluster pointer list out of range")
        }
    }

    func hasTitleList(fileSize: UInt64) -> Bool {
        titlePtrPos != UInt64.max && titlePtrPos >= 80 && titlePtrPos <= fileSize
            && UInt64(entryCount) * 4 <= fileSize - titlePtrPos
    }
}
