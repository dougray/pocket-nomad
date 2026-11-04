import Foundation

/// Errors thrown while opening or reading a ZIM archive.
public enum ZIMError: Error, Sendable, Equatable, CustomStringConvertible {
    case cannotOpen(path: String, errno: Int32)
    case readFailed(offset: UInt64, errno: Int32)
    case truncated(offset: UInt64)
    case notAZIMFile
    case unsupportedVersion(major: Int, minor: Int)
    case invalidHeader(String)
    case invalidDirent(index: Int)
    case invalidCluster(index: Int, reason: String)
    case unsupportedCompression(Int)
    case decompressionFailed(String)
    case redirectLoop(path: String)
    case indexOutOfRange(Int)

    public var description: String {
        switch self {
        case let .cannotOpen(path, e): return "cannot open \(path): \(String(cString: strerror(e)))"
        case let .readFailed(off, e): return "read failed at \(off): \(String(cString: strerror(e)))"
        case let .truncated(off): return "file truncated at \(off)"
        case .notAZIMFile: return "not a ZIM file (bad magic number)"
        case let .unsupportedVersion(ma, mi): return "unsupported ZIM version \(ma).\(mi)"
        case let .invalidHeader(s): return "invalid ZIM header: \(s)"
        case let .invalidDirent(i): return "invalid directory entry \(i)"
        case let .invalidCluster(i, r): return "invalid cluster \(i): \(r)"
        case let .unsupportedCompression(c): return "unsupported cluster compression \(c)"
        case let .decompressionFailed(s): return "decompression failed: \(s)"
        case let .redirectLoop(p): return "redirect chain too long for \(p)"
        case let .indexOutOfRange(i): return "entry index \(i) out of range"
        }
    }
}

/// Tiny lock wrapper (NSLock) so state can be guarded on iOS 17 / macOS 14.
final class Locked<Value>: @unchecked Sendable {
    private var value: Value
    private let lock = NSLock()
    init(_ value: Value) { self.value = value }
    func withLock<R>(_ body: (inout Value) throws -> R) rethrows -> R {
        lock.lock(); defer { lock.unlock() }
        return try body(&value)
    }
}

/// Least-recently-used cache. Access is O(1); eviction scans (only on insert past capacity).
struct LRUCache<Key: Hashable, Value> {
    private var items: [Key: (value: Value, stamp: UInt64)] = [:]
    private var clock: UInt64 = 0
    let capacity: Int

    init(capacity: Int) { self.capacity = max(1, capacity) }

    mutating func get(_ key: Key) -> Value? {
        guard let item = items[key] else { return nil }
        clock &+= 1
        items[key] = (item.value, clock)
        return item.value
    }

    mutating func set(_ key: Key, _ value: Value) {
        clock &+= 1
        items[key] = (value, clock)
        if items.count > capacity, let oldest = items.min(by: { $0.value.stamp < $1.value.stamp })?.key {
            items.removeValue(forKey: oldest)
        }
    }

    var count: Int { items.count }
}

extension Data {
    @inline(__always)
    func le<T: FixedWidthInteger>(_: T.Type, at offset: Int) -> T {
        precondition(offset >= 0 && offset + MemoryLayout<T>.size <= count)
        return withUnsafeBytes { raw in
            T(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: T.self))
        }
    }
}

/// Positional reads on a file descriptor, plus a block cache for the small
/// structures (pointer lists, dirents, MIME list). Never maps the whole file.
final class FileReader: @unchecked Sendable {
    let fd: Int32
    let size: UInt64
    private static let blockSize = 16 * 1024
    private let blocks = Locked(LRUCache<UInt64, Data>(capacity: 512)) // ~8 MB

    init(url: URL) throws {
        let path = url.path
        let fd = open(path, O_RDONLY | O_CLOEXEC)
        guard fd >= 0 else { throw ZIMError.cannotOpen(path: path, errno: errno) }
        var st = stat()
        guard fstat(fd, &st) == 0 else {
            let e = errno
            close(fd)
            throw ZIMError.cannotOpen(path: path, errno: e)
        }
        self.fd = fd
        self.size = UInt64(st.st_size)
    }

    deinit { close(fd) }

    /// Reads exactly `count` bytes at `offset` straight from the file (uncached).
    func read(at offset: UInt64, count: Int) throws -> Data {
        guard count > 0 else { return Data() }
        guard offset <= size, UInt64(count) <= size - offset else { throw ZIMError.truncated(offset: offset) }
        var data = Data(count: count)
        try data.withUnsafeMutableBytes { raw in
            var done = 0
            while done < count {
                let n = pread(fd, raw.baseAddress! + done, count - done, off_t(offset + UInt64(done)))
                if n < 0 {
                    if errno == EINTR { continue }
                    throw ZIMError.readFailed(offset: offset + UInt64(done), errno: errno)
                }
                if n == 0 { throw ZIMError.truncated(offset: offset + UInt64(done)) }
                done += n
            }
        }
        return data
    }

    /// Reads up to `count` bytes (clamped at end of file) through the block cache.
    func cachedRead(at offset: UInt64, count: Int) throws -> Data {
        guard offset < size else { throw ZIMError.truncated(offset: offset) }
        let count = Int(Swift.min(UInt64(count), size - offset))
        let bs = UInt64(Self.blockSize)
        var first = offset / bs
        let last = (offset + UInt64(count) - 1) / bs
        if first == last {
            let block = try block(first)
            let start = Int(offset - first * bs)
            return block.subdata(in: start ..< start + count)
        }
        var out = Data(capacity: count)
        while first <= last {
            let block = try block(first)
            let blockStart = first * bs
            let lo = Int(Swift.max(offset, blockStart) - blockStart)
            let hi = Int(Swift.min(offset + UInt64(count), blockStart + UInt64(block.count)) - blockStart)
            out.append(block.subdata(in: lo ..< hi))
            first += 1
        }
        return out
    }

    private func block(_ n: UInt64) throws -> Data {
        if let b = blocks.withLock({ $0.get(n) }) { return b }
        let start = n * UInt64(Self.blockSize)
        let len = Int(Swift.min(UInt64(Self.blockSize), size - start))
        let b = try read(at: start, count: len)
        blocks.withLock { $0.set(n, b) }
        return b
    }

    func u32(at offset: UInt64) throws -> UInt32 {
        let d = try cachedRead(at: offset, count: 4)
        guard d.count == 4 else { throw ZIMError.truncated(offset: offset) }
        return d.le(UInt32.self, at: 0)
    }

    func u64(at offset: UInt64) throws -> UInt64 {
        let d = try cachedRead(at: offset, count: 8)
        guard d.count == 8 else { throw ZIMError.truncated(offset: offset) }
        return d.le(UInt64.self, at: 0)
    }
}
