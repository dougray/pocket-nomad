package org.nomadpocket.library.zim

import java.io.File
import java.util.Locale

/**
 * A directory entry in the content namespace. [path] has no namespace prefix on
 * new-layout (6.1+) files and is "<ns>/<path>" (e.g. "A/Foo") on old-layout files.
 * For redirects, [mimeType] is the MIME type of the final target ("" if it can't be resolved).
 */
data class ZimEntry(
    val index: Int,
    val path: String,
    val title: String,
    val mimeType: String,
    val isRedirect: Boolean,
)

/** The bytes of an entry, after following redirects. [path] is the final target's path. */
class ZimContent(val data: ByteArray, val mimeType: String, val path: String)

/**
 * Pure-Kotlin, read-only ZIM reader (major versions 5 and 6), ported from NomadKit's
 * ZIMKit (`NomadKit/Sources/ZIMKit/ZIMArchive.swift`), which is written from the
 * openZIM spec. No libzim: it is GPL.
 *
 * Reads with positional file reads and keeps only small caches in memory, so it is
 * safe to open very large archives. All methods are safe to call from any thread.
 */
class ZimArchive(val file: File) : AutoCloseable {
    private val reader = FileReader(file)
    private val header: Header
    private val mimeTypes: List<String>

    /** True for ZIM 6.1+ ("new namespace scheme": C, M, W, X). */
    val usesNewNamespaceScheme: Boolean
    private val contentNamespaces: ByteArray

    private class Cluster(val data: ByteArray, val extended: Boolean)
    private val clusterCache = LruCache<Int, Cluster>(8)

    private val lock = Any()
    private var frontListingLoaded = false
    private var frontListingData: ByteArray? = null
    private var articleCountCache: Int? = null

    init {
        try {
            header = Header(reader)
            usesNewNamespaceScheme = header.major >= 6 && header.minor >= 1
            contentNamespaces = if (usesNewNamespaceScheme) byteArrayOf(C) else byteArrayOf(A, I, DASH)
            mimeTypes = readMimeList()
        } catch (e: Exception) {
            reader.close()
            throw e
        }
    }

    override fun close() = reader.close()

    private fun readMimeList(): List<String> {
        val end = if (header.pathPtrPos > header.mimeListPos) header.pathPtrPos else reader.size
        val length = minOf(end - header.mimeListPos, 1L shl 20).toInt()
        val bytes = reader.cachedRead(header.mimeListPos, length)
        val list = ArrayList<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] != 0.toByte()) continue
            if (i == start) return list // an empty string terminates the list
            list.add(String(bytes, start, i - start, Charsets.UTF_8))
            start = i + 1
        }
        throw ZimException.InvalidHeader("unterminated MIME type list")
    }

    // Basic properties

    val uuid: String get() = header.uuid.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val entryCount: Int get() = header.entryCount
    val clusterCount: Int get() = header.clusterCount
    val majorVersion: Int get() = header.major
    val minorVersion: Int get() = header.minor

    /**
     * Front articles if the archive has a front-article listing, otherwise the
     * number of non-redirect HTML entries in the content namespace. Cached.
     */
    val articleCount: Int
        get() {
            synchronized(lock) { articleCountCache }?.let { return it }
            val listing = frontListing()
            val n = when {
                listing != null -> listing.size / 4
                else -> counterHTML() ?: run {
                    val ns = if (usesNewNamespaceScheme) C else A
                    var count = 0
                    for (i in runCatching { namespaceRange(ns) }.getOrDefault(IntRange.EMPTY)) {
                        val d = runCatching { dirent(i) }.getOrNull() ?: continue
                        if (d.isItem && mimeName(d.mime).startsWith("text/html")) count++
                    }
                    count
                }
            }
            synchronized(lock) { articleCountCache = n }
            return n
        }

    /** Reads `M/<key>` as UTF-8 text, e.g. "Title", "Description", "Language". */
    fun metadata(key: String): String? = metadataData(key)?.toString(Charsets.UTF_8)

    /** Raw bytes of `M/<key>`. */
    fun metadataData(key: String): ByteArray? = runCatching {
        val d = findDirent(M, key.toByteArray()) ?: return null
        blob(resolve(d))
    }.getOrNull()

    /** Keys present in the metadata namespace. */
    val metadataKeys: List<String>
        get() = runCatching { namespaceRange(M) }.getOrDefault(IntRange.EMPTY).mapNotNull { runCatching { dirent(it).path }.getOrNull() }

    /** PNG illustration (`M/Illustration_<size>x<size>@1`), falling back to the old favicon. */
    fun illustration(size: Int = 48): ByteArray? {
        metadataData("Illustration_${size}x$size@1")?.let { return it }
        for ((ns, path) in listOf(DASH to "favicon", DASH to "favicon.png", I to "favicon.png")) {
            val data = runCatching { findDirent(ns, path.toByteArray())?.let { blob(resolve(it)) } }.getOrNull()
            if (data != null) return data
        }
        return null
    }

    /** Path (no namespace on new files) of the main page, with redirects resolved. */
    val mainPagePath: String?
        get() {
            if (header.mainPage != UINT32_MAX && header.mainPage < entryCount) {
                runCatching { publicPath(resolve(dirent(header.mainPage.toInt()))) }.getOrNull()?.let { return it }
            }
            return runCatching { findDirent(W, "mainPage".toByteArray())?.let { publicPath(resolve(it)) } }.getOrNull()
        }

    // Lookup

    /**
     * Looks up a content entry by path.
     *
     * New layout (6.1+): paths have no namespace prefix ("Ray_Charles").
     * Old layout: returned paths include the namespace ("A/Foo", "I/foo.png", "-/style.css")
     * so relative links in the HTML resolve; such paths are accepted here, and a bare
     * path is tried in `A`, `I`, then `-`. Percent-encoded paths are decoded as a fallback.
     */
    fun entry(path: String): ZimEntry? = lookupDirent(path)?.let { makeEntry(it) }

    /** Entry bytes for a path, following redirects (max depth 10). Null if the path doesn't exist; throws on corrupt data. */
    fun content(path: String): ZimContent? {
        val d = lookupDirent(path) ?: return null
        val target = resolve(d)
        if (!target.isItem) return null
        return ZimContent(blob(target), mimeName(target.mime), publicPath(target))
    }

    /** Content entry by its index in the path-ordered directory (null for other namespaces). */
    fun entryAt(index: Int): ZimEntry? {
        val d = runCatching { dirent(index) }.getOrNull() ?: return null
        return if (d.ns in contentNamespaces) makeEntry(d) else null
    }

    private fun lookupDirent(rawPath: String): Dirent? {
        val first = rawPath.removePrefix("/")
        val candidates = mutableListOf(first)
        percentDecode(first)?.let { if (it != first) candidates.add(it) }
        for (path in candidates) {
            val bytes = path.toByteArray()
            // "X/rest" with a single-char content namespace: old-layout public paths
            // ("A/Foo", "I/foo.png", "-/style.css"); also "C/foo" on new files.
            val explicit = bytes.size > 2 && bytes[1] == SLASH && bytes[0] in contentNamespaces
            if (explicit) runCatching { findDirent(bytes[0], bytes.copyOfRange(2, bytes.size)) }.getOrNull()?.let { return it }
            for (ns in contentNamespaces) {
                runCatching { findDirent(ns, bytes) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    // Title search

    /**
     * Case-insensitive title-prefix search over articles. Binary-searches the
     * title-ordered lists using several capitalisations of the query, merges and
     * dedupes; exact title matches come first.
     */
    fun titleSearch(query: String, limit: Int): List<ZimEntry> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val lowerQ = q.lowercase(Locale.ROOT)
        val variants = LinkedHashSet<String>()
        variants.add(q)
        variants.add(q.take(1).uppercase(Locale.ROOT) + q.drop(1))
        variants.add(q.take(1).uppercase(Locale.ROOT) + q.drop(1).lowercase(Locale.ROOT))
        variants.add(capitalizeWords(q))
        variants.add(lowerQ)
        variants.add(q.uppercase(Locale.ROOT))
        variants.add(q.replace(" ", "_"))
        val ns = if (usesNewNamespaceScheme) C else A

        val results = ArrayList<ZimEntry>()
        val seen = HashSet<Int>()
        val scanCap = limit * 4 + 64

        fun search(count: Int, indexAt: (Int) -> Int) {
            for (variant in variants) {
                if (results.size >= limit) return
                val key = variant.toByteArray()
                // lower bound of (ns, key) in title order
                var lo = 0
                var hi = count
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    val d = runCatching { dirent(indexAt(mid)) }.getOrNull() ?: return
                    if (compare(d.ns, d.sortTitle, ns, key) < 0) lo = mid + 1 else hi = mid
                }
                var i = lo
                var scanned = 0
                while (i < count && scanned < scanCap && results.size < limit) {
                    val idx = runCatching { indexAt(i) }.getOrNull() ?: break
                    i++; scanned++
                    val d = runCatching { dirent(idx) }.getOrNull() ?: break
                    if (d.ns != ns || !startsWith(d.sortTitle, key)) break
                    // Dedupe on the resolved target so a redirect and its article appear once.
                    val target = if (d.isRedirect) runCatching { resolve(d).index }.getOrDefault(idx) else idx
                    if (!seen.add(target)) continue
                    val e = makeEntry(d)
                    if (!e.mimeType.startsWith("text/html")) continue
                    if (!e.title.lowercase(Locale.ROOT).startsWith(lowerQ) && !d.path.lowercase(Locale.ROOT).startsWith(lowerQ)) continue
                    results.add(e)
                }
            }
        }

        if (header.hasTitleList(reader.size)) {
            val base = header.titlePtrPos
            search(entryCount) { reader.u32(base + 4L * it).toInt() }
        }
        if (results.size < limit) {
            frontListing()?.let { listing -> search(listing.size / 4) { listing.u32(4 * it).toInt() } }
        }

        val exact = results.filter { it.title.lowercase(Locale.ROOT) == lowerQ }
        val rest = results.filter { it.title.lowercase(Locale.ROOT) != lowerQ }
        return (exact + rest).take(limit)
    }

    // Iteration

    /** Order for [forEachArticle]. */
    enum class ArticleOrder {
        /** Title order (front-article listing) or path order (fallback). */
        LISTING,

        /**
         * Same articles, re-sorted in chunks by cluster and blob so each compressed
         * cluster is decompressed about once. Much faster for bulk reads such as indexing.
         */
        STORAGE,
    }

    /**
     * Calls [body] for each front article (the `X/listing/titleOrdered/v1` list when
     * present; otherwise every non-redirect HTML entry in the content namespace, in path
     * order), with `position`: how many listing slots have been consumed so far
     * (1..articleCount), for progress. Return false to stop. Entries that fail to read are skipped.
     */
    fun forEachArticle(order: ArticleOrder = ArticleOrder.LISTING, body: (entry: ZimEntry, position: Int) -> Boolean) {
        val listing = frontListing()
        val slotCount: Int
        val range: IntRange
        if (listing != null) {
            slotCount = listing.size / 4
            range = IntRange.EMPTY
        } else {
            range = runCatching { namespaceRange(if (usesNewNamespaceScheme) C else A) }.getOrDefault(IntRange.EMPTY)
            slotCount = if (range.isEmpty()) 0 else range.last - range.first + 1
        }
        fun direntForSlot(slot: Int): Dirent? {
            val idx = listing?.u32(4 * slot)?.toInt() ?: (range.first + slot)
            val d = runCatching { dirent(idx) }.getOrNull() ?: return null
            if (!d.isItem) return null
            if (listing == null && !mimeName(d.mime).startsWith("text/html")) return null
            return d
        }

        when (order) {
            ArticleOrder.LISTING -> {
                var produced = 0
                for (slot in 0 until slotCount) {
                    val d = direntForSlot(slot) ?: continue
                    produced++
                    // Position counts listing slots; without a listing it counts articles (matches articleCount).
                    if (!body(makeEntry(d), if (listing == null) produced else slot + 1)) return
                }
            }
            ArticleOrder.STORAGE -> {
                val chunk = 50_000
                var chunkStart = 0
                var produced = 0
                while (chunkStart < slotCount) {
                    val chunkEnd = minOf(slotCount, chunkStart + chunk)
                    val items = ArrayList<Dirent>(chunkEnd - chunkStart)
                    for (slot in chunkStart until chunkEnd) direntForSlot(slot)?.let { items.add(it) }
                    items.sortWith(compareBy<Dirent> { it.cluster }.thenBy { it.blob })
                    for ((k, d) in items.withIndex()) {
                        produced++
                        val pos = if (listing == null) produced
                        else chunkStart + ((k + 1).toDouble() / items.size * (chunkEnd - chunkStart)).toInt()
                        if (!body(makeEntry(d), pos)) return
                    }
                    chunkStart = chunkEnd
                }
            }
        }
    }

    /** Like [forEachArticle] in listing order, without the position. */
    fun forEachArticle(body: (ZimEntry) -> Boolean) = forEachArticle(ArticleOrder.LISTING) { e, _ -> body(e) }

    // Internals: listings

    private fun frontListing(): ByteArray? {
        synchronized(lock) { if (frontListingLoaded) return frontListingData }
        var listing: ByteArray? = null
        if (usesNewNamespaceScheme) {
            listing = runCatching { findDirent(X, "listing/titleOrdered/v1".toByteArray())?.let { blob(resolve(it)) } }.getOrNull()
        }
        synchronized(lock) {
            frontListingData = listing
            frontListingLoaded = true
        }
        return listing
    }

    /** Sum of "text/html" items from the `M/Counter` metadata ("text/html=12;image/png=3"). */
    private fun counterHTML(): Int? {
        if (usesNewNamespaceScheme) return null
        val counter = metadata("Counter") ?: return null
        var total: Int? = null
        for (part in counter.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0].startsWith("text/html")) kv[1].toIntOrNull()?.let { total = (total ?: 0) + it }
        }
        return total
    }

    // Internals: dirents

    internal class Dirent(
        val index: Int,
        val mime: Int,
        val ns: Byte,
        val cluster: Long,
        val blob: Long,
        val redirectIndex: Long,
        val pathBytes: ByteArray,
        val titleBytes: ByteArray,
    ) {
        val isRedirect: Boolean get() = mime == 0xffff

        /** A real item with data (not redirect / linktarget / deleted). */
        val isItem: Boolean get() = mime < 0xfffd
        val path: String get() = String(pathBytes, Charsets.UTF_8)

        /** Title as used for sorting and display: the path when the title is empty. */
        val sortTitle: ByteArray get() = if (titleBytes.isEmpty()) pathBytes else titleBytes
        val title: String get() = String(sortTitle, Charsets.UTF_8)
    }

    internal fun dirent(index: Int): Dirent {
        if (index < 0 || index >= entryCount) throw ZimException.IndexOutOfRange(index)
        val offset = reader.u64(header.pathPtrPos + 8L * index)
        if (offset < 0 || offset >= reader.size) throw ZimException.InvalidDirent(index)
        var want = 256
        while (true) {
            val data = reader.cachedRead(offset, want)
            parseDirent(data, index)?.let { return it }
            if (data.size < want || want >= 1 shl 17) throw ZimException.InvalidDirent(index)
            want *= 4
        }
    }

    private fun parseDirent(data: ByteArray, index: Int): Dirent? {
        if (data.size < 8) return null
        val mime = data.u16(0)
        val ns = data[3]
        var pos = 8
        var cluster = 0L
        var blob = 0L
        var redirect = 0L
        when (mime) {
            0xffff -> {
                if (data.size < pos + 4) return null
                redirect = data.u32(pos); pos += 4
            }
            0xfffe, 0xfffd -> {} // linktarget / deleted: no cluster or redirect fields
            else -> {
                if (data.size < pos + 8) return null
                cluster = data.u32(pos)
                blob = data.u32(pos + 4)
                pos += 8
            }
        }
        val pathEnd = indexOfZero(data, pos)
        if (pathEnd < 0) return null
        val titleEnd = indexOfZero(data, pathEnd + 1)
        if (titleEnd < 0) return null
        return Dirent(index, mime, ns, cluster, blob, redirect, data.copyOfRange(pos, pathEnd), data.copyOfRange(pathEnd + 1, titleEnd))
    }

    /** Follows redirects (max depth 10). */
    internal fun resolve(start: Dirent): Dirent {
        var d = start
        var depth = 0
        while (d.isRedirect) {
            depth++
            if (depth > 10) throw ZimException.RedirectLoop(start.path)
            if (d.redirectIndex >= entryCount) throw ZimException.InvalidDirent(d.index)
            d = dirent(d.redirectIndex.toInt())
        }
        return d
    }

    private fun makeEntry(d: Dirent): ZimEntry {
        val mime = when {
            d.isRedirect -> runCatching { resolve(d) }.getOrNull()?.takeIf { it.isItem }?.let { mimeName(it.mime) } ?: ""
            d.isItem -> mimeName(d.mime)
            else -> ""
        }
        return ZimEntry(d.index, publicPath(d), d.title, mime, d.isRedirect)
    }

    /** New layout: bare path. Old layout: "<ns>/<path>" so relative links like "../I/x.png" resolve. */
    private fun publicPath(d: Dirent): String =
        if (usesNewNamespaceScheme) d.path else "${(d.ns.toInt() and 0xff).toChar()}/${d.path}"

    private fun mimeName(m: Int): String = mimeTypes.getOrElse(m) { "" }

    /** Index of the first entry >= (ns, path) in path order. */
    private fun lowerBound(ns: Byte, path: ByteArray): Int {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val d = dirent(mid)
            if (compare(d.ns, d.pathBytes, ns, path) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun findDirent(ns: Byte, path: ByteArray): Dirent? {
        val i = lowerBound(ns, path)
        if (i >= entryCount) return null
        val d = dirent(i)
        return if (d.ns == ns && d.pathBytes.contentEquals(path)) d else null
    }

    private fun namespaceRange(ns: Byte): IntRange {
        val lo = lowerBound(ns, ByteArray(0))
        val hi = if ((ns.toInt() and 0xff) == 255) entryCount else lowerBound((ns + 1).toByte(), ByteArray(0))
        return lo until maxOf(lo, hi)
    }

    // Internals: clusters

    private fun blob(d: Dirent): ByteArray {
        if (!d.isItem) throw ZimException.InvalidDirent(d.index)
        return readBlob(d.cluster.toInt(), d.blob.toInt())
    }

    private fun clusterBounds(c: Int): Pair<Long, Long> {
        if (c < 0 || c >= clusterCount) throw ZimException.InvalidCluster(c, "index out of range")
        val start = reader.u64(header.clusterPtrPos + 8L * c)
        var end = if (c + 1 < clusterCount) {
            reader.u64(header.clusterPtrPos + 8L * (c + 1))
        } else {
            if (header.checksumPos > start && header.checksumPos <= reader.size) header.checksumPos else reader.size
        }
        if (end > reader.size || end < 0) end = reader.size
        if (start < 0 || start >= end) throw ZimException.InvalidCluster(c, "bad bounds")
        return start to end
    }

    private fun readBlob(c: Int, b: Int): ByteArray {
        synchronized(clusterCache) { clusterCache.get(c) }?.let { return extractBlob(it, c, b) }
        val (start, end) = clusterBounds(c)
        val info = reader.cachedRead(start, 1)[0].toInt() and 0xff
        val compression = info and 0x0f
        val extended = info and 0x10 != 0
        val offSize = if (extended) 8L else 4L
        val base = start + 1

        fun readOffset(i: Int): Long = if (extended) reader.u64(base + i * offSize) else reader.u32(base + i * offSize)

        return when (compression) {
            0, 1 -> {
                // Uncompressed: read only the offsets we need and the blob itself.
                val first = readOffset(0)
                if (first < offSize || first % offSize != 0L) throw ZimException.InvalidCluster(c, "bad first offset")
                val blobCount = (first / offSize).toInt() - 1
                if (b < 0 || b >= blobCount) throw ZimException.InvalidCluster(c, "blob $b out of range")
                val o1 = readOffset(b)
                val o2 = readOffset(b + 1)
                if (o1 > o2 || base + o2 > end) throw ZimException.InvalidCluster(c, "bad blob offsets")
                if (o2 - o1 > Int.MAX_VALUE) throw ZimException.InvalidCluster(c, "blob too large")
                reader.read(base + o1, (o2 - o1).toInt())
            }
            2, 4, 5 -> {
                if (end - base > Int.MAX_VALUE) throw ZimException.InvalidCluster(c, "cluster too large")
                val raw = reader.read(base, (end - base).toInt())
                val data = when (compression) {
                    4 -> Decompress.xz(raw)
                    5 -> Decompress.zstd(raw)
                    else -> Decompress.zlib(raw)
                }
                val cluster = Cluster(data, extended)
                synchronized(clusterCache) { clusterCache.set(c, cluster) }
                extractBlob(cluster, c, b)
            }
            else -> throw ZimException.UnsupportedCompression(compression)
        }
    }

    private fun extractBlob(cluster: Cluster, c: Int, b: Int): ByteArray {
        val data = cluster.data
        if (data.isEmpty()) throw ZimException.InvalidCluster(c, "empty")
        val offSize = if (cluster.extended) 8 else 4
        fun offset(i: Int): Int {
            val at = i.toLong() * offSize
            if (at < 0 || at + offSize > data.size) throw ZimException.InvalidCluster(c, "offset table")
            val v = if (cluster.extended) data.u64(at.toInt()) else data.u32(at.toInt())
            if (v < 0 || v > data.size) throw ZimException.InvalidCluster(c, "offset past end")
            return v.toInt()
        }
        val first = offset(0)
        if (first < offSize || first % offSize != 0) throw ZimException.InvalidCluster(c, "bad first offset")
        if (b < 0 || b >= first / offSize - 1) throw ZimException.InvalidCluster(c, "blob $b out of range")
        val o1 = offset(b)
        val o2 = offset(b + 1)
        if (o1 > o2) throw ZimException.InvalidCluster(c, "bad blob offsets")
        return data.copyOfRange(o1, o2)
    }

    companion object {
        internal const val C = 'C'.code.toByte()
        internal const val A = 'A'.code.toByte()
        internal const val I = 'I'.code.toByte()
        internal const val M = 'M'.code.toByte()
        internal const val W = 'W'.code.toByte()
        internal const val X = 'X'.code.toByte()
        internal const val DASH = '-'.code.toByte()
        internal const val SLASH = '/'.code.toByte()
        internal const val UINT32_MAX = 0xffffffffL

        /** Byte-wise unsigned comparison of (namespace, bytes), like memcmp. */
        internal fun compare(ns1: Byte, a: ByteArray, ns2: Byte, b: ByteArray): Int {
            if (ns1 != ns2) return if ((ns1.toInt() and 0xff) < (ns2.toInt() and 0xff)) -1 else 1
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i].toInt() and 0xff
                val y = b[i].toInt() and 0xff
                if (x != y) return if (x < y) -1 else 1
            }
            return a.size.compareTo(b.size).coerceIn(-1, 1)
        }

        private fun startsWith(a: ByteArray, prefix: ByteArray): Boolean {
            if (prefix.size > a.size) return false
            for (i in prefix.indices) if (a[i] != prefix[i]) return false
            return true
        }

        private fun indexOfZero(data: ByteArray, from: Int): Int {
            for (i in from until data.size) if (data[i] == 0.toByte()) return i
            return -1
        }

        /** Swift's `String.capitalized`: each word's first letter upper case, the rest lower case. */
        internal fun capitalizeWords(s: String): String {
            val out = StringBuilder(s.length)
            var startOfWord = true
            for (ch in s) {
                if (ch.isLetterOrDigit()) {
                    out.append(if (startOfWord) ch.uppercase(Locale.ROOT) else ch.lowercase(Locale.ROOT))
                    startOfWord = false
                } else {
                    out.append(ch)
                    startOfWord = true
                }
            }
            return out.toString()
        }

        /** Decodes %XX escapes as UTF-8; null when the string has a malformed escape. */
        fun percentDecode(s: String): String? {
            if (!s.contains('%')) return s
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            val bytes = s.toByteArray()
            while (i < bytes.size) {
                val c = bytes[i]
                if (c == '%'.code.toByte()) {
                    if (i + 2 >= bytes.size) return null
                    val hex = String(bytes, i + 1, 2, Charsets.US_ASCII).toIntOrNull(16) ?: return null
                    out.write(hex)
                    i += 3
                } else {
                    out.write(c.toInt())
                    i++
                }
            }
            return out.toString("UTF-8")
        }
    }
}

/** The 80-byte ZIM header. Offsets are unsigned 64-bit in the file; real files fit in a Long. */
internal class Header(file: FileReader) {
    val major: Int
    val minor: Int
    val uuid: ByteArray
    val entryCount: Int
    val clusterCount: Int
    val pathPtrPos: Long
    val titlePtrPos: Long
    val clusterPtrPos: Long
    val mimeListPos: Long
    val mainPage: Long
    val layoutPage: Long
    val checksumPos: Long

    init {
        if (file.size < 80) throw ZimException.NotAZimFile()
        val h = file.read(0, 80)
        if (h.u32(0) != MAGIC) throw ZimException.NotAZimFile()
        major = h.u16(4)
        minor = h.u16(6)
        if (major != 5 && major != 6) throw ZimException.UnsupportedVersion(major, minor)
        uuid = h.copyOfRange(8, 24)
        val entries = h.u32(24)
        val clusters = h.u32(28)
        if (entries > Int.MAX_VALUE || clusters > Int.MAX_VALUE) throw ZimException.InvalidHeader("too many entries")
        entryCount = entries.toInt()
        clusterCount = clusters.toInt()
        pathPtrPos = h.u64(32)
        titlePtrPos = h.u64(40)
        clusterPtrPos = h.u64(48)
        mimeListPos = h.u64(56)
        mainPage = h.u32(64)
        layoutPage = h.u32(68)
        checksumPos = h.u64(72)

        val size = file.size
        if (mimeListPos < 80 || mimeListPos >= size) throw ZimException.InvalidHeader("mimeListPos out of range")
        if (pathPtrPos < 0 || pathPtrPos > size || entryCount * 8L > size - pathPtrPos) {
            throw ZimException.InvalidHeader("path pointer list out of range")
        }
        if (clusterPtrPos < 0 || clusterPtrPos > size || clusterCount * 8L > size - clusterPtrPos) {
            throw ZimException.InvalidHeader("cluster pointer list out of range")
        }
    }

    /** False when the field is the "absent" marker (all ones) or out of range. */
    fun hasTitleList(fileSize: Long): Boolean =
        titlePtrPos >= 80 && titlePtrPos <= fileSize && entryCount * 4L <= fileSize - titlePtrPos

    private companion object {
        const val MAGIC = 72_173_914L // "ZIM\x04"
    }
}
