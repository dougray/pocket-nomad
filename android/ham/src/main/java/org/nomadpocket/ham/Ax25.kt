// Ax25.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// AX.25 v2.0 frames: addresses, digipeater path, control field. The TNC adds
// and checks the FCS. Port of AX25.swift.

package org.nomadpocket.ham

data class Ax25Address(val call: String, val ssid: Int = 0, val repeated: Boolean = false) {
    override fun toString() = if (ssid == 0) call else "$call-$ssid"
    val base: Ax25Address get() = Ax25Address(call, ssid)

    fun encoded(last: Boolean, cBit: Boolean): ByteArray {
        val out = ByteArray(7) { 0x40 }
        call.toByteArray(Charsets.US_ASCII).take(6).forEachIndexed { i, c -> out[i] = ((c.toInt() and 0xFF) shl 1).toByte() }
        out[6] = (0x60 or (ssid shl 1) or (if (cBit) 0x80 else 0) or (if (last) 1 else 0)).toByte()
        return out
    }

    companion object {
        fun of(call: String, ssid: Int = 0) = Ax25Address(call.uppercase(), ssid and 0x0F)

        /** "CALL", "CALL-12" or "CALL*". Null if not valid. */
        fun parse(text: String): Ax25Address? {
            var t = text.trim().uppercase()
            var rep = false
            if (t.endsWith("*")) { rep = true; t = t.dropLast(1) }
            val parts = t.split("-")
            if (parts.size > 2) return null
            val c = parts[0]
            if (c.length !in 1..6 || !c.all { it in 'A'..'Z' || it in '0'..'9' }) return null
            var ssid = 0
            if (parts.size == 2) {
                ssid = parts[1].toIntOrNull()?.takeIf { it in 0..15 } ?: return null
            }
            return Ax25Address(c, ssid, rep)
        }

        data class Decoded(val address: Ax25Address, val cBit: Boolean, val last: Boolean)

        fun decode(b: ByteArray, at: Int): Decoded? {
            if (at + 7 > b.size) return null
            val sb = StringBuilder()
            for (i in 0 until 6) {
                val ch = (b[at + i].toInt() and 0xFF) shr 1
                if (!(ch == 0x20 || ch in 0x30..0x39 || ch in 0x41..0x5A)) return null
                if (ch != 0x20) sb.append(ch.toChar())
            }
            if (sb.isEmpty()) return null
            val x = b[at + 6].toInt() and 0xFF
            return Decoded(Ax25Address(sb.toString(), (x shr 1) and 0x0F, x and 0x80 != 0), x and 0x80 != 0, x and 1 != 0)
        }
    }
}

sealed class Ax25Control {
    data class I(val ns: Int, val nr: Int, val poll: Boolean) : Ax25Control()
    data class RR(val nr: Int, val pf: Boolean) : Ax25Control()
    data class RNR(val nr: Int, val pf: Boolean) : Ax25Control()
    data class REJ(val nr: Int, val pf: Boolean) : Ax25Control()
    data class SABM(val poll: Boolean) : Ax25Control()
    data class DISC(val poll: Boolean) : Ax25Control()
    data class UA(val fin: Boolean) : Ax25Control()
    data class DM(val fin: Boolean) : Ax25Control()
    data class FRMR(val fin: Boolean) : Ax25Control()
    data class UI(val poll: Boolean) : Ax25Control()
    data class Unknown(val b: Int) : Ax25Control()

    val byte: Int
        get() {
            fun pf(x: Boolean) = if (x) 0x10 else 0
            return when (this) {
                is I -> ((nr and 7) shl 5) or pf(poll) or ((ns and 7) shl 1)
                is RR -> ((nr and 7) shl 5) or pf(pf) or 0x01
                is RNR -> ((nr and 7) shl 5) or pf(pf) or 0x05
                is REJ -> ((nr and 7) shl 5) or pf(pf) or 0x09
                is SABM -> 0x2F or pf(poll)
                is DISC -> 0x43 or pf(poll)
                is UA -> 0x63 or pf(fin)
                is DM -> 0x0F or pf(fin)
                is FRMR -> 0x87 or pf(fin)
                is UI -> 0x03 or pf(poll)
                is Unknown -> b
            }
        }

    val hasInfo: Boolean get() = this is I || this is UI

    val name: String
        get() = when (this) {
            is I -> "I$ns$nr${if (poll) "P" else ""}"
            is RR -> "RR$nr${if (pf) "P/F" else ""}"
            is RNR -> "RNR$nr${if (pf) "P/F" else ""}"
            is REJ -> "REJ$nr${if (pf) "P/F" else ""}"
            is SABM -> "SABM${if (poll) "P" else ""}"
            is DISC -> "DISC${if (poll) "P" else ""}"
            is UA -> "UA${if (fin) "F" else ""}"
            is DM -> "DM${if (fin) "F" else ""}"
            is FRMR -> "FRMR${if (fin) "F" else ""}"
            is UI -> "UI${if (poll) "P" else ""}"
            is Unknown -> "?%02X".format(b)
        }

    companion object {
        fun from(c: Int): Ax25Control {
            val pf = c and 0x10 != 0
            return when {
                c and 1 == 0 -> I((c shr 1) and 7, (c shr 5) and 7, pf)
                c and 3 == 1 -> when (c and 0x0F) {
                    0x01 -> RR((c shr 5) and 7, pf)
                    0x05 -> RNR((c shr 5) and 7, pf)
                    0x09 -> REJ((c shr 5) and 7, pf)
                    else -> Unknown(c)
                }
                else -> when (c and 0x10.inv() and 0xFF) {
                    0x2F -> SABM(pf)
                    0x43 -> DISC(pf)
                    0x63 -> UA(pf)
                    0x0F -> DM(pf)
                    0x87 -> FRMR(pf)
                    0x03 -> UI(pf)
                    else -> Unknown(c)
                }
            }
        }
    }
}

data class Ax25Frame(
    val destination: Ax25Address,
    val source: Ax25Address,
    val path: List<Ax25Address> = emptyList(),
    val isCommand: Boolean = true,
    val control: Ax25Control,
    val pid: Int = 0xF0,
    val info: ByteArray = ByteArray(0),
) {
    fun encoded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(destination.encoded(false, isCommand))
        out.write(source.encoded(path.isEmpty(), !isCommand))
        path.forEachIndexed { i, d -> out.write(d.encoded(i == path.size - 1, d.repeated)) }
        out.write(control.byte)
        if (control.hasInfo) { out.write(pid); out.write(info) }
        return out.toByteArray()
    }

    val monitorLine: String
        get() {
            var head = "$source>$destination"
            for (d in path) head += ",$d${if (d.repeated) "*" else ""}"
            if (control is Ax25Control.UI) return head + ":" + printable(info)
            var line = "$head <${control.name}${if (isCommand) " C" else " R"}>"
            if (control.hasInfo && info.isNotEmpty()) line += ": " + printable(info)
            return line
        }

    /** The path a reply takes: the digipeaters reversed, H bits cleared. */
    val replyPath: List<Ax25Address> get() = path.reversed().map { it.base }

    override fun equals(other: Any?): Boolean = other is Ax25Frame && destination == other.destination &&
        source == other.source && path == other.path && isCommand == other.isCommand && control == other.control &&
        pid == other.pid && info.contentEquals(other.info)

    override fun hashCode(): Int = destination.hashCode() * 31 + source.hashCode()

    companion object {
        const val NO_LAYER3 = 0xF0

        fun printable(b: ByteArray): String = String(ByteArray(b.size) { i ->
            val c = b[i].toInt() and 0xFF
            when { c == 13 || c == 10 -> 0x20; c < 0x20 -> 0x2E; else -> c }.toByte()
        }, Charsets.UTF_8)

        fun decode(b: ByteArray): Ax25Frame? {
            if (b.size < 15) return null
            val d = Ax25Address.decode(b, 0) ?: return null
            if (d.last) return null
            val s = Ax25Address.decode(b, 7) ?: return null
            var i = 14
            var last = s.last
            val path = ArrayList<Ax25Address>()
            while (!last) {
                if (path.size >= 8) return null
                val p = Ax25Address.decode(b, i) ?: return null
                path.add(p.address)
                last = p.last
                i += 7
            }
            if (i >= b.size) return null
            val control = Ax25Control.from(b[i].toInt() and 0xFF)
            i += 1
            var pid = 0
            var info = ByteArray(0)
            if (control.hasInfo) {
                if (i >= b.size) return null
                pid = b[i].toInt() and 0xFF
                info = b.copyOfRange(i + 1, b.size)
            }
            return Ax25Frame(d.address.base, s.address.base, path, d.cBit || !s.cBit, control, pid, info)
        }
    }
}

object Ax25Path {
    fun parse(text: String): List<Ax25Address>? {
        val out = ArrayList<Ax25Address>()
        for (p in text.split(',', ' ').filter { it.isNotBlank() }) {
            if (p.uppercase() == "VIA" || p.uppercase() == "V") continue
            out.add(Ax25Address.parse(p)?.base ?: return null)
        }
        return if (out.size <= 8) out else null
    }

    fun text(path: List<Ax25Address>) = path.joinToString(",")
}
