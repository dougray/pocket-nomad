// SameDecoder.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

/** One NOAA Weather Radio alert (SAME header) heard on the air. */
data class SameAlert(val header: String, val org: String, val code: String, val event: String, val areas: List<String>, val minutes: Int, val issued: String, val station: String) {
    /** A one-line summary, the same wording the SDR box uses. */
    val text: String get() = "$event for ${areas.size} area${if (areas.size == 1) "" else "s"}, valid $minutes min"
}

/**
 * Receive-only decoder for NOAA Weather Radio alerts (SAME, the digital header before a warning) from audio samples: AFSK at
 * 520.83 baud, 2083.33 Hz for a one and 1562.5 Hz for a zero, ASCII letters sent least-significant bit first after a 0xAB preamble.
 * Every alert is sent three times; an alert is reported when two identical copies arrive, or when one clean copy is all that came.
 * App/Sources/Ham/SameDecoder.swift is the iOS twin; keep them in step.
 */
class SameDecoder(private val sampleRate: Int, private val onAlert: (SameAlert) -> Unit) {
    private val slicer = ToneSlicer(sampleRate, 520.8333, 2083.3333, 1562.5) { bit(it) }
    private var reg24 = 0
    private var synced = false
    private var reg8 = 0
    private var nbits = 0
    private val text = StringBuilder()
    private var samples = 0L
    private var pending: SameAlert? = null
    private var pendingAt = 0L
    private var lastEmitted: String? = null
    private var lastEmittedAt = -1_000_000_000L

    fun feed(s: FloatArray, length: Int = s.size) { for (k in 0 until length) tick(s[k].toDouble()) }
    fun feed(s: ShortArray, length: Int = s.size) { for (k in 0 until length) tick(s[k] / 32768.0) }

    private fun tick(x: Double) {
        slicer.step(x)
        samples++
        val p = pending
        if (p != null && samples - pendingAt > 12L * sampleRate) { pending = null; emit(p) }   // the other copies never came: report the one we have
    }

    private fun bit(b: Int) {
        if (!synced) {
            reg24 = (reg24 ushr 1) or (b shl 23)
            if (reg24 == 0xABABAB) { synced = true; reg8 = 0; nbits = 0; text.setLength(0) }
            return
        }
        reg8 = (reg8 ushr 1) or (b shl 7)
        if (++nbits < 8) return
        nbits = 0
        val c = reg8
        if (c == 0xAB && text.isEmpty()) return                     // more preamble
        if (c in 0x20..0x7E && text.length < 268) { text.append(c.toChar()); return }
        finishMessage()
    }

    private fun finishMessage() {
        val m = text.toString()
        synced = false; reg24 = 0; text.setLength(0)
        if (!m.startsWith("ZCZC")) return
        val a = parse(m) ?: return
        val p = pending
        if (p != null && p.header == a.header) { pending = null; emit(a) } else { pending = a; pendingAt = samples }
    }

    private fun emit(a: SameAlert) {
        if (a.header == lastEmitted && samples - lastEmittedAt < 60L * sampleRate) return   // the same alert again
        lastEmitted = a.header; lastEmittedAt = samples
        onAlert(a)
    }

    companion object {
        val EVENT_NAMES = mapOf(
            "TOR" to "Tornado Warning", "TOA" to "Tornado Watch", "SVR" to "Severe Thunderstorm Warning", "SVA" to "Severe Thunderstorm Watch",
            "FFW" to "Flash Flood Warning", "FFA" to "Flash Flood Watch", "FLW" to "Flood Warning", "FLA" to "Flood Watch",
            "SVS" to "Severe Weather Statement", "SMW" to "Special Marine Warning", "EWW" to "Extreme Wind Warning",
            "HUW" to "Hurricane Warning", "HUA" to "Hurricane Watch", "TRW" to "Tropical Storm Warning", "TRA" to "Tropical Storm Watch",
            "SSW" to "Storm Surge Warning", "SSA" to "Storm Surge Watch", "WSW" to "Winter Storm Warning", "WSA" to "Winter Storm Watch",
            "BZW" to "Blizzard Warning", "ISW" to "Ice Storm Warning", "HWW" to "High Wind Warning", "HWA" to "High Wind Watch",
            "DSW" to "Dust Storm Warning", "FRW" to "Fire Warning", "EVI" to "Evacuation Immediate", "SPS" to "Special Weather Statement",
            "CEM" to "Civil Emergency Message", "LAE" to "Local Area Emergency", "SPW" to "Shelter In Place Warning", "RHW" to "Radiological Hazard Warning",
            "CAE" to "Child Abduction Emergency", "EAN" to "Emergency Action Notification", "NPT" to "National Periodic Test",
            "RMT" to "Required Monthly Test", "RWT" to "Required Weekly Test", "DMO" to "Practice/Demo Warning", "ADR" to "Administrative Message",
        )
        private val SAME = Regex("""ZCZC-([A-Z0-9]{3})-([A-Z0-9]{3})((?:-\d{6})+)\+(\d{4})-(\d{7})-([A-Z0-9/ ]{1,8})-?""")

        fun parse(text: String): SameAlert? {
            val m = SAME.find(text) ?: return null
            val (org, code, areas, dur, whenText, station) = m.destructured
            val minutes = dur.substring(0, 2).toInt() * 60 + dur.substring(2).toInt()
            return SameAlert(m.value.trimEnd('-') + "-", org, code, EVENT_NAMES[code] ?: "Alert $code", areas.split("-").filter { it.isNotEmpty() }, minutes, whenText, station.trim('/', ' '))
        }
    }
}
