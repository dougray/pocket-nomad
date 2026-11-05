package org.nomadpocket.library

import com.github.luben.zstd.Zstd
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/**
 * Writes small ZIM 6.1 archives for tests, a port of NomadKit's `ZIMWriter.swift`:
 * content in `C`, metadata in `M`, the main page as `W/mainPage`, and a front-article
 * listing in `X/listing/titleOrdered/v1`. Clusters can be zstd (as on iOS), xz or
 * zlib, so every decompressor is tested without downloading anything.
 */
class TestZimWriter {
    class Item(val path: String, val title: String, val mimeType: String, val data: ByteArray, val isArticle: Boolean)

    enum class Compression(val type: Int) { ZSTD(5), XZ(4), ZLIB(2) }

    val metadata = LinkedHashMap<String, ByteArray>()
    var mainPath: String? = null
    var clusterSize = 1 shl 20
    var compression = Compression.ZSTD
    /** Padding after each compressed stream, which real files sometimes have. */
    var padding = 0
    private val items = ArrayList<Item>()

    fun setMetadata(key: String, value: String) { metadata[key] = value.toByteArray() }
    fun add(item: Item) { items.add(item) }
    fun add(path: String, title: String, mime: String, text: String, isArticle: Boolean) =
        add(Item(path, title, mime, text.toByteArray(), isArticle))

    private class Entry(
        val ns: Byte, val path: ByteArray, val title: ByteArray,
        var data: ByteArray = ByteArray(0), val isArticle: Boolean = false, val compressible: Boolean = true,
        val redirectTo: ByteArray? = null,
    ) {
        var mime = 0
        var cluster = 0
        var blob = 0
        val key: String get() = "${ns.toInt().toChar()}${String(path, Charsets.UTF_8)}"
    }

    fun write(file: File) {
        val C = 'C'.code.toByte(); val M = 'M'.code.toByte(); val W = 'W'.code.toByte(); val X = 'X'.code.toByte()
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (item in items) {
            require(seen.add(item.path)) { "duplicate path: ${item.path}" }
            val title = if (item.title == item.path) ByteArray(0) else item.title.toByteArray()
            entries.add(Entry(C, item.path.toByteArray(), title, item.data, item.isArticle, isCompressible(item.mimeType)))
        }
        mainPath?.let {
            require(it in seen) { "main page not found: $it" }
            entries.add(Entry(W, "mainPage".toByteArray(), ByteArray(0), redirectTo = it.toByteArray()))
        }
        val meta = LinkedHashMap(metadata)
        meta["Counter"] = items.groupBy { it.mimeType }.map { "${it.key}=${it.value.size}" }.sorted().joinToString(";").toByteArray()
        for ((k, v) in meta) entries.add(Entry(M, k.toByteArray(), ByteArray(0), v))
        entries.add(Entry(X, "listing/titleOrdered/v1".toByteArray(), ByteArray(0), compressible = false))

        val cmp = Comparator<Pair<Byte, ByteArray>> { a, b -> compare(a.first, a.second, b.first, b.second) }
        entries.sortWith { a, b -> cmp.compare(a.ns to a.path, b.ns to b.path) }
        val indexOf = entries.withIndex().associate { it.value.key to it.index }
        fun titleKey(e: Entry) = if (e.title.isEmpty()) e.path else e.title
        val titleOrder = entries.indices.sortedWith { a, b -> cmp.compare(entries[a].ns to titleKey(entries[a]), entries[b].ns to titleKey(entries[b])) }
        val listing = ByteArrayOutputStream()
        for (i in titleOrder) if (entries[i].isArticle) listing.write(le32(i.toLong()))
        entries[indexOf["Xlisting/titleOrdered/v1"]!!].data = listing.toByteArray()

        val mimeTypes = ArrayList<String>()
        fun mime(m: String): Int {
            val i = mimeTypes.indexOf(m)
            if (i >= 0) return i
            mimeTypes.add(m)
            return mimeTypes.size - 1
        }
        val mimeOfPath = items.associate { it.path to it.mimeType }
        for (e in entries) if (e.redirectTo == null) {
            e.mime = mime(if (e.ns == C) mimeOfPath[String(e.path, Charsets.UTF_8)]!! else if (e.ns == X) "application/octet-stream+zimlisting" else "text/plain")
        }

        val clusters = ArrayList<ByteArray>()
        for (compressible in listOf(true, false)) {
            val blobs = ArrayList<ByteArray>()
            val members = ArrayList<Entry>()
            var size = 0
            fun flush() {
                if (blobs.isEmpty()) return
                for ((k, e) in members.withIndex()) { e.cluster = clusters.size; e.blob = k }
                clusters.add(cluster(blobs, compressible))
                blobs.clear(); members.clear(); size = 0
            }
            for (e in entries) if (e.redirectTo == null && e.compressible == compressible) {
                blobs.add(e.data); members.add(e); size += e.data.size
                if (size >= clusterSize) flush()
            }
            flush()
        }

        val dirents = entries.map { e ->
            val d = ByteArrayOutputStream()
            if (e.redirectTo != null) {
                d.write(le16(0xffff)); d.write(0); d.write(e.ns.toInt()); d.write(le32(0))
                d.write(le32(indexOf["C" + String(e.redirectTo, Charsets.UTF_8)]!!.toLong()))
            } else {
                d.write(le16(e.mime)); d.write(0); d.write(e.ns.toInt()); d.write(le32(0))
                d.write(le32(e.cluster.toLong())); d.write(le32(e.blob.toLong()))
            }
            d.write(e.path); d.write(0); d.write(e.title); d.write(0)
            d.toByteArray()
        }

        val mimeList = ByteArrayOutputStream()
        for (m in mimeTypes) { mimeList.write(m.toByteArray()); mimeList.write(0) }
        mimeList.write(0)
        val mimeListPos = 80L
        val pathPtrPos = mimeListPos + mimeList.size()
        val titlePtrPos = pathPtrPos + 8L * entries.size
        val clusterPtrPos = titlePtrPos + 4L * entries.size
        var pos = clusterPtrPos + 8L * clusters.size
        val direntOffsets = dirents.map { val o = pos; pos += it.size; o }
        val clusterOffsets = clusters.map { val o = pos; pos += it.size; o }
        val checksumPos = pos

        val out = ByteArrayOutputStream()
        out.write(le32(72_173_914)); out.write(le16(6)); out.write(le16(1))
        out.write(ByteArray(16).also { java.util.Random().nextBytes(it) })
        out.write(le32(entries.size.toLong())); out.write(le32(clusters.size.toLong()))
        out.write(le64(pathPtrPos)); out.write(le64(titlePtrPos)); out.write(le64(clusterPtrPos)); out.write(le64(mimeListPos))
        out.write(le32(if (mainPath != null) indexOf["WmainPage"]!!.toLong() else 0xffffffffL))
        out.write(le32(0xffffffffL))
        out.write(le64(checksumPos))
        out.write(mimeList.toByteArray())
        direntOffsets.forEach { out.write(le64(it)) }
        titleOrder.forEach { out.write(le32(it.toLong())) }
        clusterOffsets.forEach { out.write(le64(it)) }
        dirents.forEach { out.write(it) }
        clusters.forEach { out.write(it) }
        val bytes = out.toByteArray()
        file.writeBytes(bytes + MessageDigest.getInstance("MD5").digest(bytes))
    }

    private fun cluster(blobs: List<ByteArray>, compress: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        var offset = 4L * (blobs.size + 1)
        body.write(le32(offset))
        for (b in blobs) { offset += b.size; body.write(le32(offset)) }
        for (b in blobs) body.write(b)
        val raw = body.toByteArray()
        if (!compress) return byteArrayOf(1) + raw
        val packed = when (compression) {
            Compression.ZSTD -> Zstd.compress(raw, 15)
            Compression.XZ -> ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options()).use { it.write(raw) } }.toByteArray()
            Compression.ZLIB -> {
                val d = Deflater(9)
                d.setInput(raw); d.finish()
                val o = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (!d.finished()) o.write(buf, 0, d.deflate(buf))
                d.end()
                o.toByteArray()
            }
        }
        return byteArrayOf(compression.type.toByte()) + packed + ByteArray(padding)
    }

    private companion object {
        fun isCompressible(mime: String) =
            mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml") || mime == "image/svg+xml"

        fun compare(ns1: Byte, a: ByteArray, ns2: Byte, b: ByteArray): Int {
            if (ns1 != ns2) return (ns1.toInt() and 0xff) - (ns2.toInt() and 0xff)
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
        fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    }
}
