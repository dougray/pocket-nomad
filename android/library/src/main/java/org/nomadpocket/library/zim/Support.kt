package org.nomadpocket.library.zim

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Errors thrown while opening or reading a ZIM archive. */
sealed class ZimException(message: String) : IOException(message) {
    class CannotOpen(path: String, cause: String) : ZimException("cannot open $path: $cause")
    class Truncated(offset: Long) : ZimException("file truncated at $offset")
    class NotAZimFile : ZimException("not a ZIM file (bad magic number)")
    class UnsupportedVersion(major: Int, minor: Int) : ZimException("unsupported ZIM version $major.$minor")
    class InvalidHeader(reason: String) : ZimException("invalid ZIM header: $reason")
    class InvalidDirent(index: Int) : ZimException("invalid directory entry $index")
    class InvalidCluster(index: Int, reason: String) : ZimException("invalid cluster $index: $reason")
    class UnsupportedCompression(type: Int) : ZimException("unsupported cluster compression $type")
    class DecompressionFailed(reason: String) : ZimException("decompression failed: $reason")
    class RedirectLoop(path: String) : ZimException("redirect chain too long for $path")
    class IndexOutOfRange(index: Int) : ZimException("entry index $index out of range")
}

/** Least-recently-used cache. Not thread-safe on its own; callers lock. */
internal class LruCache<K, V>(capacity: Int) {
    private val max = maxOf(1, capacity)
    // accessOrder = true keeps the least recently used entry first.
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    fun get(key: K): V? = map[key]
    fun set(key: K, value: V) { map[key] = value }
    val count: Int get() = map.size
}

internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.u32(at: Int): Long =
    (this[at].toLong() and 0xff) or ((this[at + 1].toLong() and 0xff) shl 8) or
        ((this[at + 2].toLong() and 0xff) shl 16) or ((this[at + 3].toLong() and 0xff) shl 24)

internal fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

/**
 * Positional reads on a file, plus a block cache for the small structures
 * (pointer lists, dirents, MIME list). Never maps or reads the whole file:
 * books are several GB. Positional `FileChannel.read` is safe from many threads.
 */
internal class FileReader(file: File) : AutoCloseable {
    private val raf: RandomAccessFile
    private val channel: FileChannel
    val size: Long
    private val blocks = LruCache<Long, ByteArray>(512) // ~8 MB

    init {
        raf = try {
            RandomAccessFile(file, "r")
        } catch (e: IOException) {
            throw ZimException.CannotOpen(file.path, e.message ?: "I/O error")
        }
        channel = raf.channel
        size = channel.size()
    }

    override fun close() { raf.close() }

    /** Reads exactly [count] bytes at [offset] straight from the file (uncached). */
    fun read(offset: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        if (offset < 0 || offset > size || count.toLong() > size - offset) throw ZimException.Truncated(offset)
        val out = ByteArray(count)
        val buf = ByteBuffer.wrap(out)
        var done = 0
        while (done < count) {
            val n = channel.read(buf, offset + done)
            if (n <= 0) throw ZimException.Truncated(offset + done)
            done += n
        }
        return out
    }

    /** Reads up to [count] bytes (clamped at end of file) through the block cache. */
    fun cachedRead(offset: Long, count: Int): ByteArray {
        if (offset < 0 || offset >= size) throw ZimException.Truncated(offset)
        val n = minOf(count.toLong(), size - offset).toInt()
        if (n <= 0) return ByteArray(0)
        var first = offset / BLOCK
        val last = (offset + n - 1) / BLOCK
        if (first == last) {
            val block = block(first)
            val start = (offset - first * BLOCK).toInt()
            return block.copyOfRange(start, start + n)
        }
        val out = ByteArray(n)
        var written = 0
        while (first <= last) {
            val block = block(first)
            val blockStart = first * BLOCK
            val lo = (maxOf(offset, blockStart) - blockStart).toInt()
            val hi = (minOf(offset + n, blockStart + block.size) - blockStart).toInt()
            System.arraycopy(block, lo, out, written, hi - lo)
            written += hi - lo
            first++
        }
        return out
    }

    private fun block(n: Long): ByteArray {
        synchronized(blocks) { blocks.get(n) }?.let { return it }
        val start = n * BLOCK
        val b = read(start, minOf(BLOCK.toLong(), size - start).toInt())
        synchronized(blocks) { blocks.set(n, b) }
        return b
    }

    fun u32(offset: Long): Long {
        val d = cachedRead(offset, 4)
        if (d.size != 4) throw ZimException.Truncated(offset)
        return d.u32(0)
    }

    fun u64(offset: Long): Long {
        val d = cachedRead(offset, 8)
        if (d.size != 8) throw ZimException.Truncated(offset)
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private companion object {
        const val BLOCK = 16 * 1024
    }
}
