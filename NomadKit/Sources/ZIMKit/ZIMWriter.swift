import CryptoKit
import Foundation
import libzstd

/// Writes small ZIM 6.1 archives (new namespace scheme) that `ZIMArchive` and Kiwix
/// can read: content in `C`, metadata in `M`, the main page as `W/mainPage`, and a
/// front-article listing in `X/listing/titleOrdered/v1`. Everything is built in
/// memory, so it suits packs of tens of megabytes, not Wikipedia.
public struct ZIMWriter {
    public struct Item: Sendable {
        public let path: String
        public let title: String
        public let mimeType: String
        public let data: Data
        /// Listed as an article (shown in title search and indexed for full-text search).
        public let isArticle: Bool

        public init(path: String, title: String, mimeType: String, data: Data, isArticle: Bool) {
            self.path = path
            self.title = title
            self.mimeType = mimeType
            self.data = data
            self.isArticle = isArticle
        }
    }

    public var metadata: [String: Data] = [:]
    public var mainPath: String?
    /// Uncompressed bytes per cluster before compressing it.
    public var clusterSize = 1 << 20
    public var zstdLevel: Int32 = 15

    private var items: [Item] = []

    public init() {}

    public mutating func setMetadata(_ key: String, _ value: String) { metadata[key] = Data(value.utf8) }

    public mutating func add(_ item: Item) { items.append(item) }

    public enum WriteError: Error, CustomStringConvertible {
        case duplicatePath(String)
        case missingMainPage(String)
        case compression(String)

        public var description: String {
            switch self {
            case let .duplicatePath(p): "duplicate path: \(p)"
            case let .missingMainPage(p): "main page not found: \(p)"
            case let .compression(m): "zstd: \(m)"
            }
        }
    }

    private struct Entry {
        let ns: UInt8
        let path: [UInt8]
        let title: [UInt8]
        var mime: UInt16 = 0
        var cluster: UInt32 = 0
        var blob: UInt32 = 0
        var redirectTo: [UInt8]? // path in `C`
        var data = Data()
        var isArticle = false
        var compressible = true
    }

    public func write(to url: URL) throws {
        let C = UInt8(ascii: "C"), M = UInt8(ascii: "M"), W = UInt8(ascii: "W"), X = UInt8(ascii: "X")

        var entries: [Entry] = []
        var seen = Set<[UInt8]>()
        for item in items {
            let path = Array(item.path.utf8)
            guard seen.insert(path).inserted else { throw WriteError.duplicatePath(item.path) }
            // An empty title means "same as path"; skip storing it when they match.
            let title = item.title == item.path ? [] : Array(item.title.utf8)
            entries.append(Entry(ns: C, path: path, title: title, data: item.data, isArticle: item.isArticle,
                                 compressible: Self.isCompressible(item.mimeType)))
        }
        if let mainPath {
            guard seen.contains(Array(mainPath.utf8)) else { throw WriteError.missingMainPage(mainPath) }
            entries.append(Entry(ns: W, path: Array("mainPage".utf8), title: [], redirectTo: Array(mainPath.utf8)))
        }
        var meta = metadata
        let counts = Dictionary(grouping: items, by: \.mimeType).map { "\($0.key)=\($0.value.count)" }.sorted()
        meta["Counter"] = Data(counts.joined(separator: ";").utf8)
        for (key, value) in meta {
            entries.append(Entry(ns: M, path: Array(key.utf8), title: [], data: value, compressible: true))
        }
        // The listing's bytes are filled in once the final entry order is known.
        entries.append(Entry(ns: X, path: Array("listing/titleOrdered/v1".utf8), title: [], data: Data(), compressible: false))

        // Path order defines entry indexes.
        entries.sort { Self.less($0.ns, $0.path, $1.ns, $1.path) }
        let indexOfPath = Dictionary(uniqueKeysWithValues: entries.enumerated().map { ([$1.ns] + $1.path, $0) })

        // Title order: all entries for the header list, articles for the listing.
        func titleKey(_ e: Entry) -> [UInt8] { e.title.isEmpty ? e.path : e.title }
        let titleOrder = entries.indices.sorted {
            Self.less(entries[$0].ns, titleKey(entries[$0]), entries[$1].ns, titleKey(entries[$1]))
        }
        var listing = Data()
        for i in titleOrder where entries[i].isArticle { listing.appendLE(UInt32(i)) }
        let listingIndex = indexOfPath[[X] + Array("listing/titleOrdered/v1".utf8)]!
        entries[listingIndex].data = listing

        // MIME types, in first-seen order.
        var mimeTypes: [String] = []
        var mimeIndex: [String: UInt16] = [:]
        func mime(_ m: String) -> UInt16 {
            if let i = mimeIndex[m] { return i }
            mimeTypes.append(m)
            mimeIndex[m] = UInt16(mimeTypes.count - 1)
            return UInt16(mimeTypes.count - 1)
        }
        let mimeOfPath = Dictionary(uniqueKeysWithValues: items.map { (Array($0.path.utf8), $0.mimeType) })
        for i in entries.indices where entries[i].redirectTo == nil {
            let e = entries[i]
            entries[i].mime = mime(e.ns == C ? mimeOfPath[e.path]! : (e.ns == X ? "application/octet-stream+zimlisting" : "text/plain"))
        }

        // Clusters: compressible blobs packed together, others in uncompressed clusters.
        var clusters: [Data] = []
        for compressible in [true, false] {
            var blobs: [Data] = []
            var size = 0
            var members: [Int] = []
            func flush() throws {
                guard !blobs.isEmpty else { return }
                let number = UInt32(clusters.count)
                for (k, i) in members.enumerated() {
                    entries[i].cluster = number
                    entries[i].blob = UInt32(k)
                }
                clusters.append(try Self.cluster(blobs, compress: compressible ? zstdLevel : nil))
                blobs = []; members = []; size = 0
            }
            for i in entries.indices where entries[i].redirectTo == nil && entries[i].compressible == compressible {
                blobs.append(entries[i].data)
                members.append(i)
                size += entries[i].data.count
                if size >= clusterSize { try flush() }
            }
            try flush()
        }

        // Serialise dirents.
        var dirents: [Data] = []
        for e in entries {
            var d = Data()
            if let target = e.redirectTo {
                d.appendLE(UInt16(0xffff))
                d.append(0) // parameter length
                d.append(e.ns)
                d.appendLE(UInt32(0)) // revision
                d.appendLE(UInt32(indexOfPath[[C] + target]!))
            } else {
                d.appendLE(e.mime)
                d.append(0)
                d.append(e.ns)
                d.appendLE(UInt32(0))
                d.appendLE(e.cluster)
                d.appendLE(e.blob)
            }
            d.append(contentsOf: e.path); d.append(0)
            d.append(contentsOf: e.title); d.append(0)
            dirents.append(d)
        }

        // Layout: header, MIME list, path pointers, title pointers, cluster pointers, dirents, clusters, MD5.
        var mimeList = Data()
        for m in mimeTypes { mimeList.append(contentsOf: Array(m.utf8)); mimeList.append(0) }
        mimeList.append(0)

        let mimeListPos = UInt64(80)
        let pathPtrPos = mimeListPos + UInt64(mimeList.count)
        let titlePtrPos = pathPtrPos + 8 * UInt64(entries.count)
        let clusterPtrPos = titlePtrPos + 4 * UInt64(entries.count)
        var direntPos = clusterPtrPos + 8 * UInt64(clusters.count)
        var direntOffsets: [UInt64] = []
        for d in dirents { direntOffsets.append(direntPos); direntPos += UInt64(d.count) }
        var clusterPos = direntPos
        var clusterOffsets: [UInt64] = []
        for c in clusters { clusterOffsets.append(clusterPos); clusterPos += UInt64(c.count) }
        let checksumPos = clusterPos

        var out = Data()
        out.appendLE(UInt32(72_173_914)) // "ZIM\x04"
        out.appendLE(UInt16(6))
        out.appendLE(UInt16(1))
        out.append(contentsOf: (0 ..< 16).map { _ in UInt8.random(in: 0 ... 255) })
        out.appendLE(UInt32(entries.count))
        out.appendLE(UInt32(clusters.count))
        out.appendLE(pathPtrPos)
        out.appendLE(titlePtrPos)
        out.appendLE(clusterPtrPos)
        out.appendLE(mimeListPos)
        let main = mainPath.map { _ in UInt32(indexOfPath[[W] + Array("mainPage".utf8)]!) } ?? UInt32.max
        out.appendLE(main)
        out.appendLE(UInt32.max) // layout page
        out.appendLE(checksumPos)
        out.append(mimeList)
        for o in direntOffsets { out.appendLE(o) }
        for i in titleOrder { out.appendLE(UInt32(i)) }
        for o in clusterOffsets { out.appendLE(o) }
        for d in dirents { out.append(d) }
        for c in clusters { out.append(c) }
        out.append(contentsOf: Insecure.MD5.hash(data: out))
        try out.write(to: url, options: .atomic)
    }

    /// One cluster: info byte, then (possibly compressed) offset table and blobs.
    static func cluster(_ blobs: [Data], compress level: Int32?) throws -> Data {
        var body = Data()
        var offset = UInt32(4 * (blobs.count + 1))
        body.appendLE(offset)
        for b in blobs { offset += UInt32(b.count); body.appendLE(offset) }
        for b in blobs { body.append(b) }
        guard let level else { return Data([1]) + body }
        let bound = ZSTD_compressBound(body.count)
        var compressed = Data(count: bound)
        let written = compressed.withUnsafeMutableBytes { dst in
            body.withUnsafeBytes { src in
                ZSTD_compress(dst.baseAddress, bound, src.baseAddress, src.count, level)
            }
        }
        if ZSTD_isError(written) != 0 { throw WriteError.compression(String(cString: ZSTD_getErrorName(written))) }
        compressed.count = written
        return Data([5]) + compressed
    }

    static func isCompressible(_ mime: String) -> Bool {
        mime.hasPrefix("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml") || mime == "image/svg+xml"
    }

    static func less(_ ns1: UInt8, _ a: [UInt8], _ ns2: UInt8, _ b: [UInt8]) -> Bool {
        if ns1 != ns2 { return ns1 < ns2 }
        return a.lexicographicallyPrecedes(b)
    }
}

private extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }
}
