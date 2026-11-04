import Compression
import Foundation
import libzstd

/// Cluster decompressors. All stream, because ZIM clusters don't record their
/// decompressed size, and stop at the end of the first compressed stream
/// (the cluster's byte range may carry padding after it).
enum Decompress {
    /// Upper bound on a decompressed cluster, to fail cleanly on corrupt data.
    static let maxOutput = 1 << 30

    /// xz (.xz container, LZMA2) via Apple's Compression framework.
    static func xz(_ input: Data) throws -> Data {
        try appleStream(input, algorithm: COMPRESSION_LZMA)
    }

    /// zlib-wrapped deflate (very old ZIMs, compression type 2).
    static func zlib(_ input: Data) throws -> Data {
        // COMPRESSION_ZLIB is raw deflate: skip the 2-byte zlib header.
        guard input.count > 2 else { throw ZIMError.decompressionFailed("zlib stream too short") }
        return try appleStream(input.subdata(in: 2 ..< input.count), algorithm: COMPRESSION_ZLIB)
    }

    private static func appleStream(_ input: Data, algorithm: compression_algorithm) throws -> Data {
        let streamPtr = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPtr.deallocate() }
        guard compression_stream_init(streamPtr, COMPRESSION_STREAM_DECODE, algorithm) == COMPRESSION_STATUS_OK else {
            throw ZIMError.decompressionFailed("compression_stream_init")
        }
        defer { compression_stream_destroy(streamPtr) }

        let chunk = 256 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        var output = Data()

        try input.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
            guard let base = src.bindMemory(to: UInt8.self).baseAddress else {
                throw ZIMError.decompressionFailed("empty input")
            }
            streamPtr.pointee.src_ptr = base
            streamPtr.pointee.src_size = src.count
            while true {
                streamPtr.pointee.dst_ptr = buffer
                streamPtr.pointee.dst_size = chunk
                let flags = streamPtr.pointee.src_size == 0 ? Int32(COMPRESSION_STREAM_FINALIZE.rawValue) : 0
                let status = compression_stream_process(streamPtr, flags)
                let produced = chunk - streamPtr.pointee.dst_size
                if produced > 0 { output.append(buffer, count: produced) }
                if output.count > maxOutput { throw ZIMError.decompressionFailed("output too large") }
                switch status {
                case COMPRESSION_STATUS_END:
                    return
                case COMPRESSION_STATUS_OK:
                    if produced == 0 && streamPtr.pointee.src_size == 0 && flags != 0 {
                        throw ZIMError.decompressionFailed("stream ended early")
                    }
                default:
                    throw ZIMError.decompressionFailed("compression_stream_process error")
                }
            }
        }
        return output
    }

    /// zstd (compression type 5), streaming so frames without a content size work.
    static func zstd(_ input: Data) throws -> Data {
        guard let ds = ZSTD_createDStream() else { throw ZIMError.decompressionFailed("ZSTD_createDStream") }
        defer { ZSTD_freeDStream(ds) }
        _ = ZSTD_initDStream(ds)

        let chunk = ZSTD_DStreamOutSize()
        let buffer = UnsafeMutableRawPointer.allocate(byteCount: chunk, alignment: 16)
        defer { buffer.deallocate() }
        var output = Data()

        try input.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
            var inBuf = ZSTD_inBuffer(src: src.baseAddress, size: src.count, pos: 0)
            while true {
                var outBuf = ZSTD_outBuffer(dst: buffer, size: chunk, pos: 0)
                let ret = ZSTD_decompressStream(ds, &outBuf, &inBuf)
                if ZSTD_isError(ret) != 0 {
                    throw ZIMError.decompressionFailed(String(cString: ZSTD_getErrorName(ret)))
                }
                if outBuf.pos > 0 { output.append(buffer.assumingMemoryBound(to: UInt8.self), count: outBuf.pos) }
                if output.count > maxOutput { throw ZIMError.decompressionFailed("output too large") }
                if ret == 0 { return } // frame complete
                if inBuf.pos >= inBuf.size && outBuf.pos < outBuf.size {
                    throw ZIMError.decompressionFailed("zstd input truncated")
                }
            }
        }
        return output
    }
}
