// Ft8Decoder.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: MIT
//
// A Kotlin port of the FT8 decoder in ft8_lib by Karlis Goba (https://github.com/kgoba/ft8_lib), MIT licence,
// Copyright (c) 2018 Karlis Goba. The algorithm, constants and tuning values follow that code; FT8 itself is by Joe Taylor, K1JT,
// Steve Franke, K9AN and Bill Somerville, G4WJS (the WSJT-X authors).
// Receive only. App/Sources/Ham/Ft8Decoder.swift is the iOS twin; keep them in step.

package org.nomadpocket.ham

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The sender's callsign and 4-character grid square when a message carries both (CQ calls and first replies), as the SDR box does for FT8 spots. */
fun ft8Spot(text: String): Pair<String, String>? {
    val call = Regex("[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z](?:/[A-Z0-9]{1,4})?")
    val grid = Regex("[A-R]{2}[0-9]{2}")
    val t = text.uppercase().split(' ').filter { it.isNotEmpty() }
    if (t.size >= 3 && t[0] == "CQ" && call.matches(t[t.size - 2]) && grid.matches(t.last())) return t[t.size - 2] to t.last()
    if (t.size == 3 && call.matches(t[1]) && grid.matches(t[2]) && t[2] != "RR73") return t[1] to t[2]
    return null
}

/** One decoded FT8 message. [snr] is the same rough estimate ft8_lib prints (half the sync score), not a calibrated SNR. */
data class Ft8Message(val text: String, val snr: Double, val dtSeconds: Double, val freqHz: Double)

/**
 * Decodes one 15 second FT8 slot from 12 kHz audio. Mono floats in -1..1, about 15 seconds long, starting near the slot's start.
 * The decoder keeps a table of callsigns it has seen so hashed callsigns in later messages show as names.
 */
class Ft8Decoder {
    companion object {
        const val SAMPLE_RATE = 12000
        private const val SYMBOL_PERIOD = 0.16
        private const val SLOT_TIME = 15.0
        private const val F_MIN = 200.0
        private const val F_MAX = 3000.0
        private const val TIME_OSR = 2
        private const val FREQ_OSR = 2
        private const val MIN_SCORE = 10
        private const val MAX_CANDIDATES = 140
        private const val LDPC_ITERATIONS = 25
        private const val MAX_DECODED = 50
        private const val N = 174
        private const val K = 91
        private const val M = 83
        private val COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)
        private val GRAY = intArrayOf(0, 1, 3, 2, 5, 6, 4, 7)
        private const val CRC_POLY = 0x2757
        private const val CRC_WIDTH = 14
        private const val NTOKENS = 2063592L
        private const val MAX22 = 4194304L
        private const val MAXGRID4 = 32400
        private const val FULL = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
        private const val ALPHANUM_SPACE_SLASH = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
        private const val ALPHANUM_SPACE = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LETTERS_SPACE = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val ALPHANUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val NUMERIC = "0123456789"
    }

    // ---- waterfall ---------------------------------------------------------------------------------------------------
    private val blockSize = (SAMPLE_RATE * SYMBOL_PERIOD).toInt()          // 1920 samples per symbol
    private val subblockSize = blockSize / TIME_OSR
    private val nfft = blockSize * FREQ_OSR
    private val minBin = (F_MIN * SYMBOL_PERIOD).toInt()
    private val maxBin = (F_MAX * SYMBOL_PERIOD).toInt() + 1
    private val numBins = maxBin - minBin
    private val maxBlocks = (SLOT_TIME / SYMBOL_PERIOD).toInt()
    private val blockStride = TIME_OSR * FREQ_OSR * numBins
    private val window = FloatArray(nfft) { i -> val x = sin(PI * i / nfft).toFloat(); (2f / nfft) * x * x }

    private class Waterfall(val mag: ByteArray, var numBlocks: Int)
    private class Candidate(var score: Int, var timeOffset: Int, var freqOffset: Int, var timeSub: Int, var freqSub: Int)

    private val callsigns = LinkedHashMap<Long, String>()   // 22-bit hash -> callsign

    fun decode(signal: FloatArray, length: Int = signal.size): List<Ft8Message> {
        val wf = buildWaterfall(signal, length)
        val cands = findCandidates(wf)
        val seen = HashSet<String>()
        val out = ArrayList<Ft8Message>()
        for (c in cands) {
            val payload = decodeCandidate(wf, c) ?: continue
            val key = payload.joinToString("") { "%02x".format(it) }
            if (!seen.add(key) || seen.size > MAX_DECODED) continue
            val text = unpack(payload) ?: continue
            val freq = (minBin + c.freqOffset + c.freqSub.toFloat() / FREQ_OSR) / SYMBOL_PERIOD
            val time = (c.timeOffset + c.timeSub.toFloat() / TIME_OSR) * SYMBOL_PERIOD
            out.add(Ft8Message(text, c.score * 0.5, time, freq))
        }
        while (callsigns.size > 512) callsigns.remove(callsigns.keys.first())
        return out
    }

    private fun buildWaterfall(signal: FloatArray, length: Int): Waterfall {
        val mag = ByteArray(maxBlocks * blockStride)
        val last = FloatArray(nfft)
        val re = DoubleArray(nfft); val im = DoubleArray(nfft)
        var block = 0
        var framePos = 0
        while (framePos + blockSize <= length && block < maxBlocks) {
            var offset = block * blockStride
            var fp = framePos
            for (timeSub in 0 until TIME_OSR) {
                System.arraycopy(last, subblockSize, last, 0, nfft - subblockSize)
                for (pos in nfft - subblockSize until nfft) last[pos] = signal[fp++]
                for (i in 0 until nfft) { re[i] = (window[i] * last[i]).toDouble(); im[i] = 0.0 }
                Fft.mixedRadix(re, im)
                for (freqSub in 0 until FREQ_OSR) {
                    for (bin in minBin until maxBin) {
                        val src = bin * FREQ_OSR + freqSub
                        val mag2 = re[src] * re[src] + im[src] * im[src]
                        val db = 10.0 * log10(1e-12 + mag2)
                        val scaled = (2 * db + 240).toInt()
                        mag[offset++] = (if (scaled < 0) 0 else if (scaled > 255) 255 else scaled).toByte()
                    }
                }
            }
            block++
            framePos += blockSize
        }
        return Waterfall(mag, block)
    }

    private fun magAt(wf: Waterfall, index: Int) = wf.mag[index].toInt() and 0xFF

    private fun candBase(c: Candidate): Int {
        var offset = c.timeOffset
        offset = offset * TIME_OSR + c.timeSub
        offset = offset * FREQ_OSR + c.freqSub
        offset = offset * numBins + c.freqOffset
        return offset
    }

    private fun syncScore(wf: Waterfall, c: Candidate): Int {
        var score = 0
        var numAverage = 0
        val base = candBase(c)
        for (m in 0 until 3) {
            for (k in 0 until 7) {
                val block = 36 * m + k
                val blockAbs = c.timeOffset + block
                if (blockAbs < 0) continue
                if (blockAbs >= wf.numBlocks) break
                val p8 = base + block * blockStride
                val sm = COSTAS[k]
                if (sm > 0) { score += magAt(wf, p8 + sm) - magAt(wf, p8 + sm - 1); numAverage++ }
                if (sm < 7) { score += magAt(wf, p8 + sm) - magAt(wf, p8 + sm + 1); numAverage++ }
                if (k > 0 && blockAbs > 0) { score += magAt(wf, p8 + sm) - magAt(wf, p8 + sm - blockStride); numAverage++ }
                if (k + 1 < 7 && blockAbs + 1 < wf.numBlocks) { score += magAt(wf, p8 + sm) - magAt(wf, p8 + sm + blockStride); numAverage++ }
            }
        }
        if (numAverage > 0) score /= numAverage
        return score
    }

    private fun findCandidates(wf: Waterfall): List<Candidate> {
        val heap = ArrayList<Candidate>()
        for (timeSub in 0 until TIME_OSR) for (freqSub in 0 until FREQ_OSR) for (timeOffset in -10 until 20) {
            var freqOffset = 0
            while (freqOffset + 7 < numBins) {
                val cand = Candidate(0, timeOffset, freqOffset, timeSub, freqSub)
                cand.score = syncScore(wf, cand)
                freqOffset++
                if (cand.score < MIN_SCORE) continue
                if (heap.size == MAX_CANDIDATES && cand.score > heap[0].score) {
                    heap[0] = heap[heap.size - 1]; heap.removeAt(heap.size - 1)
                    heapifyDown(heap, heap.size)
                }
                if (heap.size < MAX_CANDIDATES) { heap.add(cand); heapifyUp(heap, heap.size) }
            }
        }
        var unsorted = heap.size
        while (unsorted > 1) {
            val tmp = heap[unsorted - 1]; heap[unsorted - 1] = heap[0]; heap[0] = tmp
            unsorted--
            heapifyDown(heap, unsorted)
        }
        return heap
    }

    private fun heapifyDown(heap: ArrayList<Candidate>, size: Int) {
        var current = 0
        while (true) {
            val left = 2 * current + 1; val right = left + 1
            var smallest = current
            if (left < size && heap[left].score < heap[smallest].score) smallest = left
            if (right < size && heap[right].score < heap[smallest].score) smallest = right
            if (smallest == current) break
            val tmp = heap[smallest]; heap[smallest] = heap[current]; heap[current] = tmp
            current = smallest
        }
    }

    private fun heapifyUp(heap: ArrayList<Candidate>, size: Int) {
        var current = size - 1
        while (current > 0) {
            val parent = (current - 1) / 2
            if (!(heap[current].score < heap[parent].score)) break
            val tmp = heap[parent]; heap[parent] = heap[current]; heap[current] = tmp
            current = parent
        }
    }

    // ---- soft decisions and LDPC ------------------------------------------------------------------------------------
    private fun max4(a: Float, b: Float, c: Float, d: Float) = maxOf(maxOf(a, b), maxOf(c, d))

    private fun extractLikelihood(wf: Waterfall, c: Candidate): FloatArray {
        val log174 = FloatArray(N)
        val base = candBase(c)
        for (k in 0 until 58) {
            val symIdx = k + if (k < 29) 7 else 14
            val bitIdx = 3 * k
            val block = c.timeOffset + symIdx
            if (block < 0 || block >= wf.numBlocks) continue   // stays 0
            val p = base + symIdx * blockStride
            val s2 = FloatArray(8) { j -> magAt(wf, p + GRAY[j]) * 0.5f - 120f }
            log174[bitIdx] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3])
            log174[bitIdx + 1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5])
            log174[bitIdx + 2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6])
        }
        return log174
    }

    private fun normalize(l: FloatArray) {
        var sum = 0f; var sum2 = 0f
        for (v in l) { sum += v; sum2 += v * v }
        val invN = 1f / N
        val variance = (sum2 - sum * sum * invN) * invN
        if (variance <= 0f) return
        val f = sqrt(24f / variance)
        for (i in l.indices) l[i] *= f
    }

    private fun fastTanh(x: Float): Float {
        if (x < -4.97f) return -1f
        if (x > 4.97f) return 1f
        val x2 = x * x
        val a = x * (945f + x2 * (105f + x2))
        val b = 945f + x2 * (420f + x2 * 15f)
        return a / b
    }

    private fun fastAtanh(x: Float): Float {
        val x2 = x * x
        val a = x * (945f + x2 * (-735f + x2 * 64f))
        val b = 945f + x2 * (-1050f + x2 * 225f)
        return a / b
    }

    private fun ldpcCheck(cw: IntArray): Int {
        var errors = 0
        for (m in 0 until M) {
            var x = 0
            for (i in 0 until Ft8Constants.NUM_ROWS[m]) x = x xor cw[Ft8Constants.NM[m * 7 + i] - 1]
            if (x != 0) errors++
        }
        return errors
    }

    /** Belief propagation. Returns the number of unsatisfied parity checks (0 = a valid codeword) and fills [plain]. */
    private fun bpDecode(codeword: FloatArray, maxIters: Int, plain: IntArray): Int {
        val tov = Array(N) { FloatArray(3) }
        val toc = Array(M) { FloatArray(7) }
        var minErrors = M
        for (iter in 0 until maxIters) {
            var plainSum = 0
            for (n in 0 until N) { plain[n] = if (codeword[n] + tov[n][0] + tov[n][1] + tov[n][2] > 0) 1 else 0; plainSum += plain[n] }
            if (plainSum == 0) break
            val errors = ldpcCheck(plain)
            if (errors < minErrors) { minErrors = errors; if (errors == 0) break }
            for (m in 0 until M) {
                for (nIdx in 0 until Ft8Constants.NUM_ROWS[m]) {
                    val n = Ft8Constants.NM[m * 7 + nIdx] - 1
                    var tnm = codeword[n]
                    for (mIdx in 0 until 3) if (Ft8Constants.MN[n * 3 + mIdx] - 1 != m) tnm += tov[n][mIdx]
                    toc[m][nIdx] = fastTanh(-tnm / 2)
                }
            }
            for (n in 0 until N) {
                for (mIdx in 0 until 3) {
                    val m = Ft8Constants.MN[n * 3 + mIdx] - 1
                    var tmn = 1f
                    for (nIdx in 0 until Ft8Constants.NUM_ROWS[m]) if (Ft8Constants.NM[m * 7 + nIdx] - 1 != n) tmn *= toc[m][nIdx]
                    tov[n][mIdx] = -2 * fastAtanh(tmn)
                }
            }
        }
        return minErrors
    }

    private fun crc14(msg: ByteArray, numBits: Int): Int {
        val topBit = 1 shl (CRC_WIDTH - 1)
        var remainder = 0
        var idxByte = 0
        for (idxBit in 0 until numBits) {
            if (idxBit % 8 == 0) { remainder = remainder xor ((msg[idxByte].toInt() and 0xFF) shl (CRC_WIDTH - 8)); idxByte++ }
            remainder = if (remainder and topBit != 0) (remainder shl 1) xor CRC_POLY else remainder shl 1
        }
        return remainder and ((topBit shl 1) - 1)
    }

    /** The 10 payload bytes (77 bits) of a candidate whose LDPC and CRC both check out, or null. */
    private fun decodeCandidate(wf: Waterfall, c: Candidate): ByteArray? {
        val log174 = extractLikelihood(wf, c)
        normalize(log174)
        val plain = IntArray(N)
        if (bpDecode(log174, LDPC_ITERATIONS, plain) > 0) return null
        val a91 = ByteArray(12)
        for (i in 0 until K) if (plain[i] != 0) a91[i / 8] = (a91[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
        val extracted = ((a91[9].toInt() and 0x07) shl 11) or ((a91[10].toInt() and 0xFF) shl 3) or ((a91[11].toInt() and 0xFF) shr 5)
        a91[9] = (a91[9].toInt() and 0xF8).toByte()
        a91[10] = 0
        if (extracted != crc14(a91, 96 - 14)) return null
        return a91.copyOf(10)
    }

    // ---- unpacking the 77-bit message --------------------------------------------------------------------------------
    private fun b(p: ByteArray, i: Int) = p[i].toInt() and 0xFF

    private fun unpack(p: ByteArray): String? {
        val i3 = (b(p, 9) shr 3) and 0x07
        val n3 = ((b(p, 8) shl 2) and 0x04) or ((b(p, 9) shr 6) and 0x03)
        return when (i3) {
            0 -> when (n3) { 0 -> freeText(p); 5 -> telemetry(p); else -> null }
            1, 2 -> standard(p, i3)
            4 -> nonStandard(p)
            else -> null
        }
    }

    private fun charAt(table: String, i: Int) = if (i in table.indices) table[i] else ' '

    private fun lookup(bits: Int, value: Long): String {
        val hit = callsigns.entries.firstOrNull { (k, _) ->
            when (bits) { 22 -> k == value; 12 -> (k shr 10) == value; else -> (k shr 12) == value }
        }
        return if (hit != null) "<${hit.value}>" else "<...>"
    }

    private fun save(callsign: String) {
        var n58 = 0L
        for (ch in callsign.take(11)) {
            val j = ALPHANUM_SPACE_SLASH.indexOf(ch)
            if (j < 0) return
            n58 = 38 * n58 + j
        }
        for (i in callsign.take(11).length until 11) n58 *= 38
        val n22 = ((47055833459L * n58) ushr (64 - 22)) and 0x3FFFFF   // 64-bit wrap-around, as in C
        callsigns[n22] = callsign
    }

    private fun unpack28(n28In: Long, ip: Int, i3: Int): String? {
        var n28 = n28In
        if (n28 < NTOKENS) {
            if (n28 <= 2) return when (n28.toInt()) { 0 -> "DE"; 1 -> "QRZ"; else -> "CQ" }
            if (n28 <= 1002) return "CQ " + "%03d".format((n28 - 3).toInt())
            if (n28 <= 532443L) {
                var n = n28 - 1003
                val aaaa = CharArray(4)
                for (i in 3 downTo 0) { aaaa[i] = charAt(LETTERS_SPACE, (n % 27).toInt()); n /= 27 }
                return "CQ " + String(aaaa).trimStart(' ')
            }
            return null
        }
        n28 -= NTOKENS
        if (n28 < MAX22) return lookup(22, n28)
        var n = n28 - MAX22
        val c = CharArray(6)
        c[5] = charAt(LETTERS_SPACE, (n % 27).toInt()); n /= 27
        c[4] = charAt(LETTERS_SPACE, (n % 27).toInt()); n /= 27
        c[3] = charAt(LETTERS_SPACE, (n % 27).toInt()); n /= 27
        c[2] = charAt(NUMERIC, (n % 10).toInt()); n /= 10
        c[1] = charAt(ALPHANUM, (n % 36).toInt()); n /= 36
        c[0] = charAt(ALPHANUM_SPACE, (n % 37).toInt())
        val call = String(c)
        var result = when {
            call.startsWith("3D0") && call[3] != ' ' -> "3DA0" + call.substring(3).trim()
            call[0] == 'Q' && call[1] in 'A'..'Z' -> "3X" + call.substring(1).trim()
            else -> call.trim()
        }
        if (result.length < 3) return null
        if (ip != 0) result += when (i3) { 1 -> "/R"; 2 -> "/P"; else -> return null }
        save(result)
        return result
    }

    private fun unpackGrid(igrid4: Int, ir: Int): String? {
        if (igrid4 <= MAXGRID4) {
            var n = igrid4
            val d3 = '0' + (n % 10); n /= 10
            val d2 = '0' + (n % 10); n /= 10
            val d1 = 'A' + (n % 18); n /= 18
            val d0 = 'A' + (n % 18)
            return (if (ir > 0) "R " else "") + "$d0$d1$d2$d3"
        }
        val irpt = igrid4 - MAXGRID4
        return when (irpt) {
            1 -> ""
            2 -> "RRR"
            3 -> "RR73"
            4 -> "73"
            else -> {
                val v = irpt - 35
                (if (ir > 0) "R" else "") + (if (v < 0) "-" else "+") + "%02d".format(Math.abs(v))
            }
        }
    }

    private fun standard(p: ByteArray, i3: Int): String? {
        val n29a = (b(p, 0).toLong() shl 21) or (b(p, 1).toLong() shl 13) or (b(p, 2).toLong() shl 5) or (b(p, 3).toLong() shr 3)
        val n29b = ((b(p, 3).toLong() and 0x07) shl 26) or (b(p, 4).toLong() shl 18) or (b(p, 5).toLong() shl 10) or (b(p, 6).toLong() shl 2) or (b(p, 7).toLong() shr 6)
        val ir = (b(p, 7) and 0x20) shr 5
        val igrid4 = ((b(p, 7) and 0x1F) shl 10) or (b(p, 8) shl 2) or (b(p, 9) shr 6)
        val to = unpack28(n29a shr 1, (n29a and 1).toInt(), i3) ?: return null
        val de = unpack28(n29b shr 1, (n29b and 1).toInt(), i3) ?: return null
        val extra = unpackGrid(igrid4, ir) ?: return null
        return listOf(to, de, extra).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun nonStandard(p: ByteArray): String? {
        val n12 = (b(p, 0) shl 4) or (b(p, 1) shr 4)
        var n58 = (b(p, 1).toLong() and 0x0F) shl 54
        n58 = n58 or (b(p, 2).toLong() shl 46) or (b(p, 3).toLong() shl 38) or (b(p, 4).toLong() shl 30) or (b(p, 5).toLong() shl 22) or
            (b(p, 6).toLong() shl 14) or (b(p, 7).toLong() shl 6) or (b(p, 8).toLong() shr 2)
        val iflip = (b(p, 8) shr 1) and 1
        val nrpt = ((b(p, 8) and 1) shl 1) or (b(p, 9) shr 7)
        val icq = (b(p, 9) shr 6) and 1
        val chars = CharArray(11)
        var n = n58
        for (i in 10 downTo 0) { chars[i] = charAt(ALPHANUM_SPACE_SLASH, (n % 38).toInt()); n /= 38 }
        val decoded = String(chars).trim()
        if (decoded.length >= 3) save(decoded)
        val other = lookup(12, n12.toLong())
        val call1 = if (iflip == 1) decoded else other
        val call2 = if (iflip == 1) other else decoded
        return if (icq == 0) {
            val extra = when (nrpt) { 1 -> "RRR"; 2 -> "RR73"; 3 -> "73"; else -> "" }
            listOf(call1, call2, extra).filter { it.isNotEmpty() }.joinToString(" ")
        } else "CQ $call2"
    }

    private fun shiftedPayload(p: ByteArray): IntArray {
        val out = IntArray(9)
        var carry = 0
        for (i in 0 until 9) { out[i] = (carry shl 7) or (b(p, i) shr 1); carry = b(p, i) and 1 }
        return out
    }

    private fun freeText(p: ByteArray): String {
        val b71 = shiftedPayload(p)
        val c = CharArray(13)
        for (idx in 12 downTo 0) {
            var rem = 0
            for (i in 0 until 9) { rem = (rem shl 8) or b71[i]; b71[i] = rem / 42; rem %= 42 }
            c[idx] = charAt(FULL, rem)
        }
        return String(c).trim()
    }

    private fun telemetry(p: ByteArray): String = shiftedPayload(p).joinToString("") { "%02X".format(it) }
}

/** Mixed-radix FFT for any length whose prime factors are small (FT8 uses 3840 = 2^8 x 3 x 5). */
internal object Fft {
    private var tableN = 0
    private var cosT = DoubleArray(0)
    private var sinT = DoubleArray(0)

    private fun table(n: Int) {
        if (tableN == n) return
        cosT = DoubleArray(n) { cos(-2.0 * PI * it / n) }
        sinT = DoubleArray(n) { sin(-2.0 * PI * it / n) }
        tableN = n
    }

    @Synchronized
    fun mixedRadix(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        table(n)
        val oRe = DoubleArray(n); val oIm = DoubleArray(n)
        rec(re, im, 0, 1, n, oRe, oIm, 0, n)
        System.arraycopy(oRe, 0, re, 0, n); System.arraycopy(oIm, 0, im, 0, n)
    }

    private fun smallestFactor(n: Int): Int { var p = 2; while (p * p <= n) { if (n % p == 0) return p; p++ }; return n }

    /** DFT of the n samples x[start], x[start+stride], ... into out[outPos until outPos+n]; [total] is the full transform length. */
    private fun rec(re: DoubleArray, im: DoubleArray, start: Int, stride: Int, n: Int, oRe: DoubleArray, oIm: DoubleArray, outPos: Int, total: Int) {
        if (n == 1) { oRe[outPos] = re[start]; oIm[outPos] = im[start]; return }
        val p = smallestFactor(n)
        val m = n / p
        for (r in 0 until p) rec(re, im, start + r * stride, stride * p, m, oRe, oIm, outPos + r * m, total)
        val tRe = DoubleArray(p); val tIm = DoubleArray(p)
        val stepN = total / n
        val stepP = total / p
        for (k in 0 until m) {
            for (r in 0 until p) {
                val idx = (r * k * stepN) % total
                val c = cosT[idx]; val s = sinT[idx]
                val xr = oRe[outPos + r * m + k]; val xi = oIm[outPos + r * m + k]
                tRe[r] = xr * c - xi * s; tIm[r] = xr * s + xi * c
            }
            for (q in 0 until p) {
                var sr = 0.0; var si = 0.0
                for (r in 0 until p) {
                    val idx = (r * q * stepP) % total
                    val c = cosT[idx]; val s = sinT[idx]
                    sr += tRe[r] * c - tIm[r] * s; si += tRe[r] * s + tIm[r] * c
                }
                oRe[outPos + q * m + k] = sr; oIm[outPos + q * m + k] = si
            }
        }
    }
}
