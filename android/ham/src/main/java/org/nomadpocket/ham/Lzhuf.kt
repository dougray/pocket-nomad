// Lzhuf.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// LZHUF as Winlink's B2F uses it (LZSS + adaptive Huffman, with a CRC16 and
// length header). Port of LZHUF.swift, itself ported from wl2k-go's lzhuf
// (MIT, LA5NTA) and JNOS 2's lzhuf.c ("use, distribute, and modify freely").
// Byte-for-byte compatible with wl2k-go; see NOTICE.

package org.nomadpocket.ham

object Lzhuf {
    private const val N = 2048
    private const val F = 60
    private const val THRESHOLD = 2
    private const val N_CHAR = 256 - THRESHOLD + F
    private const val T = N_CHAR * 2 - 1
    private const val R = T - 1
    private const val MAX_FREQ = 0x8000
    private const val NIL = N

    class Failure(message: String) : Exception(message)

    fun compress(data: ByteArray): ByteArray {
        val enc = Encoder()
        for (b in data) enc.write(b.toInt() and 0xFF)
        val body = enc.finish()
        val n = data.size
        val len = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val sum = crc16(len + body)
        return byteArrayOf(sum.toByte(), (sum shr 8).toByte()) + len + body
    }

    fun decompress(data: ByteArray): ByteArray {
        if (data.size < 6) throw Failure("truncated")
        val crc = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (crc16(data.copyOfRange(2, data.size)) != crc) throw Failure("bad checksum")
        val size = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8) or
            ((data[4].toInt() and 0xFF) shl 16) or ((data[5].toInt() and 0xFF) shl 24)
        if (size < 0 || size > (64 shl 20)) throw Failure("bad length")
        val d = Decoder(data, 6)
        val out = java.io.ByteArrayOutputStream(size)
        while (out.size() < size) d.next(out, size)
        return out.toByteArray()
    }

    private class Tree {
        val freq = IntArray(T + 1)
        val prnt = IntArray(T + N_CHAR)
        val son = IntArray(T)

        init {
            for (i in 0 until N_CHAR) { freq[i] = 1; son[i] = i + T; prnt[i + T] = i }
            var i = 0; var j = N_CHAR
            while (j <= R) {
                freq[j] = freq[i] + freq[i + 1]
                son[j] = i; prnt[i] = j; prnt[i + 1] = j
                i += 2; j += 1
            }
            freq[T] = 0xFFFF
            prnt[R] = 0
        }

        fun reconst() {
            var j = 0
            for (i in 0 until T) if (son[i] >= T) { freq[j] = (freq[i] + 1) / 2; son[j] = son[i]; j++ }
            var i = 0; j = N_CHAR
            while (j < T) {
                freq[j] = freq[i] + freq[i + 1]
                val first = freq[j]
                var k = j
                while (first < freq[k - 1]) k--
                val last = j - k
                if (last > 0) {
                    System.arraycopy(freq, k, freq, k + 1, last)
                    System.arraycopy(son, k, son, k + 1, last)
                }
                freq[k] = first
                son[k] = i
                i += 2; j += 1
            }
            for (x in 0 until T) {
                val k = son[x]
                if (k >= T) prnt[k] = x else { prnt[k] = x; prnt[k + 1] = x }
            }
        }

        fun update(c0: Int) {
            if (freq[R] == MAX_FREQ) reconst()
            var c = prnt[c0 + T]
            while (true) {
                freq[c]++
                if (freq[c] <= freq[c + 1] || freq.size <= c + 2) {
                    c = prnt[c]
                    if (c == 0) break
                    continue
                }
                var l = c + 1
                val k = freq[c]
                while (k > freq[l + 1]) l++
                freq[c] = freq[l]
                freq[l] = k
                val i = son[c]
                prnt[i] = l
                if (i < T) prnt[i + 1] = l
                val jj = son[l]
                son[l] = i
                prnt[jj] = c
                if (jj < T) prnt[jj + 1] = c
                son[c] = jj
                c = prnt[l]
                if (c == 0) break
            }
        }
    }

    private class Decoder(val bytes: ByteArray, var pos: Int) {
        val tree = Tree()
        val text = ByteArray(N + F - 1).also { java.util.Arrays.fill(it, 0, N - F, 0x20) }
        var bitBuf = 0L
        var bitCount = 0
        var r = N - R

        fun bits(n: Int): Int {
            while (n > bitCount) {
                if (pos >= bytes.size) throw Failure("truncated")
                bitBuf = (bitBuf shl 8) or (bytes[pos].toLong() and 0xFF)
                pos++
                bitCount += 8
            }
            val v = (bitBuf ushr (bitCount - n)) and ((1L shl n) - 1)
            bitCount -= n
            return v.toInt()
        }

        fun decodeChar(): Int {
            var c = tree.son[R]
            while (c < T) { c += bits(1); c = tree.son[c] }
            c -= T
            tree.update(c)
            return c
        }

        fun decodePosition(): Int {
            var i = bits(8)
            val c = (D_CODE[i].toInt() and 0xFF) shl 6
            var j = (D_LEN[i].toInt() and 0xFF) - 2
            while (j > 0) { i = (i shl 1) + bits(1); j-- }
            return c or (i and 0x3F)
        }

        fun next(out: java.io.ByteArrayOutputStream, limit: Int) {
            val c = decodeChar()
            if (c < 256) {
                out.write(c)
                text[r] = c.toByte()
                r = (r + 1) and (N - 1)
                return
            }
            val i = (r - decodePosition() - 1) and (N - 1)
            val j = c - 255 + THRESHOLD
            for (k in 0 until j) {
                val b = text[(i + k) and (N - 1)]
                if (out.size() < limit) out.write(b.toInt())
                text[r] = b
                r = (r + 1) and (N - 1)
            }
        }
    }

    private class Encoder {
        val tree = Tree()
        val text = IntArray(N + F - 1)
        val dad = IntArray(N + 1) { NIL }
        val lson = IntArray(N + 1)
        val rson = IntArray(N + 257)
        var matchLength = 0
        var matchPosition = 0
        val out = java.io.ByteArrayOutputStream()
        var putbuf = 0L
        var putlen = 0
        var len = 0
        var r = N - F
        var s = 0
        var lastMatchLength = 0
        var preFilled = false

        init {
            for (i in N + 1..N + 256) rson[i] = NIL
            for (i in 0 until r) text[i] = 0x20
        }

        fun write(b: Int) {
            if (!preFilled) {
                text[r + len] = b
                len++
                insertNode(r - len)
                lastMatchLength = 1
                preFilled = len == F
                return
            }
            advance(b)
        }

        fun finish(): ByteArray {
            while (len > 0) advance(null)
            encode()
            if (putlen > 0) out.write(((putbuf ushr 8) and 0xFF).toInt())
            return out.toByteArray()
        }

        fun advance(c: Int?) {
            if (c != null) {
                text[s] = c
                if (s < F - 1) text[s + N] = c
                len++
            }
            insertNode(r)
            lastMatchLength--
            if (lastMatchLength == 0) encode()
            deleteNode(s)
            s = (s + 1) and (N - 1)
            r = (r + 1) and (N - 1)
            len--
        }

        fun encode() {
            if (len == 0) return
            if (matchLength > len) matchLength = len
            if (matchLength <= THRESHOLD) {
                matchLength = 1
                encodeChar(text[r])
            } else {
                encodeChar(255 - THRESHOLD + matchLength)
                encodePosition(matchPosition)
            }
            lastMatchLength = matchLength
        }

        fun encodeChar(c: Int) {
            var i = 0L
            var j = 0
            var k = tree.prnt[c + T]
            do {
                i = i ushr 1
                j++
                if (k and 1 != 0) i += 0x8000
                k = tree.prnt[k]
            } while (k != R)
            putCode(j, i)
            tree.update(c)
        }

        fun encodePosition(c: Int) {
            val i = c shr 6
            putCode(P_LEN[i].toInt(), (P_CODE[i].toLong() and 0xFF) shl 8)
            putCode(6, ((c and 0x3F).toLong()) shl 10)
        }

        fun putCode(l: Int, c: Long) {
            putbuf = putbuf or (c ushr putlen)
            putlen += l
            if (putlen < 8) return
            out.write(((putbuf ushr 8) and 0xFF).toInt())
            putlen -= 8
            if (putlen >= 8) {
                out.write((putbuf and 0xFF).toInt())
                putlen -= 8
                putbuf = c shl (l - putlen)
            } else {
                putbuf = putbuf shl 8
            }
        }

        fun insertNode(r: Int) {
            var cmp = 1
            var p = N + 1 + text[r]
            rson[r] = NIL; lson[r] = NIL
            matchLength = 0
            while (true) {
                if (cmp >= 0) {
                    if (rson[p] != NIL) p = rson[p] else { rson[p] = r; dad[r] = p; return }
                } else {
                    if (lson[p] != NIL) p = lson[p] else { lson[p] = r; dad[r] = p; return }
                }
                var i = 1
                while (i < F) {
                    cmp = text[r + i] - text[p + i]
                    if (cmp != 0) break
                    i++
                }
                if (i > THRESHOLD) {
                    if (i > matchLength) {
                        matchPosition = ((r - p) and (N - 1)) - 1
                        matchLength = i
                        if (matchLength >= F) break
                    }
                    if (i == matchLength) {
                        val c = ((r - p) and (N - 1)) - 1
                        if (c < matchPosition) matchPosition = c
                    }
                }
            }
            dad[r] = dad[p]; lson[r] = lson[p]; rson[r] = rson[p]
            dad[lson[p]] = r; dad[rson[p]] = r
            if (rson[dad[p]] == p) rson[dad[p]] = r else lson[dad[p]] = r
            dad[p] = NIL
        }

        fun deleteNode(p: Int) {
            if (dad[p] == NIL) return
            var q: Int
            if (rson[p] == NIL) q = lson[p]
            else if (lson[p] == NIL) q = rson[p]
            else {
                q = lson[p]
                if (rson[q] != NIL) {
                    while (rson[q] != NIL) q = rson[q]
                    rson[dad[q]] = lson[q]
                    dad[lson[q]] = dad[q]
                    lson[q] = lson[p]
                    dad[lson[p]] = q
                }
                rson[q] = rson[p]
                dad[rson[p]] = q
            }
            dad[q] = dad[p]
            if (rson[dad[p]] == p) rson[dad[p]] = q else lson[dad[p]] = q
            dad[p] = NIL
        }
    }

    /** XMODEM CRC-CCITT over the bytes plus two zeros, as Winlink computes it. */
    fun crc16(bytes: ByteArray): Int {
        var sum = 0
        for (b in bytes + byteArrayOf(0, 0)) sum = ((sum shl 8) and 0xFF00) xor CRC_TABLE[(sum shr 8) and 0xFF] xor (b.toInt() and 0xFF)
        return sum and 0xFFFF
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        crc
    }

    private val P_CODE = byteArrayOf(
        0x00, 0x20, 0x30, 0x40, 0x50, 0x58, 0x60, 0x68, 0x70, 0x78, 0x80.toByte(), 0x88.toByte(), 0x90.toByte(), 0x94.toByte(), 0x98.toByte(), 0x9C.toByte(),
        0xA0.toByte(), 0xA4.toByte(), 0xA8.toByte(), 0xAC.toByte(), 0xB0.toByte(), 0xB4.toByte(), 0xB8.toByte(), 0xBC.toByte(),
        0xC0.toByte(), 0xC2.toByte(), 0xC4.toByte(), 0xC6.toByte(), 0xC8.toByte(), 0xCA.toByte(), 0xCC.toByte(), 0xCE.toByte(),
        0xD0.toByte(), 0xD2.toByte(), 0xD4.toByte(), 0xD6.toByte(), 0xD8.toByte(), 0xDA.toByte(), 0xDC.toByte(), 0xDE.toByte(),
        0xE0.toByte(), 0xE2.toByte(), 0xE4.toByte(), 0xE6.toByte(), 0xE8.toByte(), 0xEA.toByte(), 0xEC.toByte(), 0xEE.toByte(),
        0xF0.toByte(), 0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte(), 0xF4.toByte(), 0xF5.toByte(), 0xF6.toByte(), 0xF7.toByte(),
        0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(), 0xFB.toByte(), 0xFC.toByte(), 0xFD.toByte(), 0xFE.toByte(), 0xFF.toByte(),
    )
    private val P_LEN = ByteArray(64) { i -> when { i < 1 -> 3; i < 4 -> 4; i < 12 -> 5; i < 24 -> 6; i < 48 -> 7; else -> 8 }.toByte() }

    private val D_CODE = ByteArray(256)
    private val D_LEN = ByteArray(256)

    init {
        for (i in 0 until 64) {
            val l = P_LEN[i].toInt()
            val start = P_CODE[i].toInt() and 0xFF
            for (k in 0 until (1 shl (8 - l))) { D_CODE[start + k] = i.toByte(); D_LEN[start + k] = l.toByte() }
        }
    }
}
