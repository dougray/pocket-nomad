package org.nomadpocket.library.zim

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Inflater
import org.tukaani.xz.SingleXZInputStream

/**
 * Cluster decompressors. ZIM clusters don't record their decompressed size, and
 * a cluster's byte range may carry padding after the compressed stream, so each
 * of these reads exactly one stream and ignores what follows.
 */
internal object Decompress {
    /** Upper bound on a decompressed cluster, to fail cleanly on corrupt data. */
    const val MAX_OUTPUT = 1 shl 30

    /** xz container with LZMA2 (compression type 4), via XZ for Java. */
    fun xz(input: ByteArray): ByteArray = try {
        drain(SingleXZInputStream(ByteArrayInputStream(input)))
    } catch (e: ZimException) {
        throw e
    } catch (e: Exception) {
        throw ZimException.DecompressionFailed("xz: ${e.message}")
    }

    /** zlib-wrapped deflate (very old ZIMs, compression type 2). */
    fun zlib(input: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size * 4)
            val buf = ByteArray(256 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw ZimException.DecompressionFailed("zlib stream ended early")
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_OUTPUT) throw ZimException.DecompressionFailed("output too large")
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw ZimException.DecompressionFailed("zlib: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /** zstd (compression type 5). Streams, so frames without a content size work. */
    fun zstd(input: ByteArray): ByteArray {
        val frame = Zstd.findFrameCompressedSize(input, 0)
        if (Zstd.isError(frame)) throw ZimException.DecompressionFailed("zstd: ${Zstd.getErrorName(frame)}")
        return try {
            drain(ZstdInputStreamNoFinalizer(ByteArrayInputStream(input, 0, frame.toInt())))
        } catch (e: ZimException) {
            throw e
        } catch (e: Exception) {
            throw ZimException.DecompressionFailed("zstd: ${e.message}")
        }
    }

    private fun drain(stream: InputStream): ByteArray = stream.use { s ->
        val out = ByteArrayOutputStream(256 * 1024)
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_OUTPUT) throw ZimException.DecompressionFailed("output too large")
        }
        out.toByteArray()
    }
}
