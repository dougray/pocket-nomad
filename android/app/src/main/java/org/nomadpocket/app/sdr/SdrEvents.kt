// SdrEvents.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// The events an SDR box sends over Bluetooth (receive-only). Protocol v1, JSON lines; see the
// sdrbox project. Port of SdrEvents.swift. Parsing is separate from the Bluetooth link.

package org.nomadpocket.app.sdr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.nomadpocket.app.map.KnownRadioParser
import java.io.ByteArrayOutputStream

data class SdrHello(val proto: Int, val firmware: String, val model: String, val dongle: String, val modes: List<String>, val simulated: Boolean)
data class SdrAircraft(val icao: String, val callsign: String, val latitude: Double, val longitude: Double, val altitudeFeet: Int,
                       val groundSpeedKnots: Int, val trackDegrees: Int, val squawk: String, val heardAtMillis: Long)
data class SdrAlert(val source: String, val mhz: Double, val same: String, val text: String, val receivedAtMillis: Long)
data class SdrChannel(val mhz: Double, val snr: Double)

sealed class SdrEvent {
    data class Hello(val hello: SdrHello) : SdrEvent()
    data class Status(val mode: String, val decoded: Int, val dongle: Boolean) : SdrEvent()
    data class Aircraft(val aircraft: SdrAircraft) : SdrEvent()
    /** A raw AX.25 frame (no KISS framing): hand it to the Ham APRS decoder. */
    class Aprs(val frame: ByteArray, val dateMillis: Long) : SdrEvent()
    data class Alert(val alert: SdrAlert) : SdrEvent()
    data class WeatherChannels(val channels: List<SdrChannel>) : SdrEvent()
    class Spectrum(val lowHz: Int, val stepHz: Int, val db: List<Int>) : SdrEvent()
}

/** Collects Bluetooth chunks into complete protocol lines. */
class SdrLineReader {
    private val buffer = ByteArrayOutputStream()
    private var discarding = false
    var errors = 0
        private set

    fun feed(data: ByteArray): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var start = 0
        if (discarding) {
            val i = data.indexOf(0x0A)
            if (i < 0) return out
            start = i + 1; discarding = false
        }
        buffer.write(data, start, data.size - start)
        var bytes = buffer.toByteArray()
        while (true) {
            val i = bytes.indexOf(0x0A)
            if (i < 0) break
            val line = String(bytes, 0, i, Charsets.UTF_8)
            bytes = bytes.copyOfRange(i + 1, bytes.size)
            if (i > MAX_LINE) { errors++; continue }
            if (line.isBlank()) continue
            val obj = runCatching { JSONObject(line) }.getOrNull()
            if (obj != null) out += obj else errors++
        }
        buffer.reset()
        if (bytes.size > MAX_LINE) { discarding = true; errors++ } else buffer.write(bytes)
        return out
    }

    companion object { const val MAX_LINE = 64 * 1024 }
}

object SdrParser {
    fun event(j: JSONObject): SdrEvent? {
        val t = j.optString("t").ifEmpty { return null }
        val ts = j.optLong("ts", 0)
        return when (t) {
            "hello" -> {
                if (j.optInt("proto", 0) != 1) return null
                val modes = j.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                SdrEvent.Hello(SdrHello(1, j.optString("fw"), j.optString("model"), j.optString("dongle"), modes, j.optBoolean("sim", false)))
            }
            "status" -> SdrEvent.Status(j.optString("mode"), j.optInt("decoded", 0), j.optBoolean("dongle", true))
            "aircraft" -> {
                val icao = j.optString("icao").ifEmpty { return null }
                if (!j.has("lat") || !j.has("lon")) return null
                val lat = j.getDouble("lat"); val lon = j.getDouble("lon")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                SdrEvent.Aircraft(SdrAircraft(icao.uppercase(), j.optString("call").trim(), lat, lon, j.optInt("alt"), j.optInt("gs"), j.optInt("trk"), j.optString("squawk"), ts))
            }
            "aprs" -> {
                val d = runCatching { java.util.Base64.getDecoder().decode(j.optString("kiss")) }.getOrNull() ?: return null
                if (d.size < 16) null else SdrEvent.Aprs(d, ts)
            }
            "alert" -> SdrEvent.Alert(SdrAlert(j.optString("src"), j.optDouble("freq", 0.0), j.optString("same").ifEmpty { return null }, j.optString("text"), ts))
            "nwr" -> {
                val a = j.optJSONArray("channels")
                SdrEvent.WeatherChannels((0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i)?.let { c -> if (c.has("mhz") && c.has("snr")) SdrChannel(c.getDouble("mhz"), c.getDouble("snr")) else null } })
            }
            "spectrum" -> {
                val a = j.optJSONArray("db") ?: return null
                SdrEvent.Spectrum(j.optInt("lo"), j.optInt("step"), (0 until a.length()).map { a.getInt(it) })
            }
            else -> null
        }
    }
}

/** What the app currently knows from the box. */
object SdrState {
    private val _aircraft = MutableStateFlow<Map<String, SdrAircraft>>(emptyMap())
    val aircraft: StateFlow<Map<String, SdrAircraft>> = _aircraft
    private val _alerts = MutableStateFlow<List<SdrAlert>>(emptyList())
    val alerts: StateFlow<List<SdrAlert>> = _alerts
    private val _channels = MutableStateFlow<List<SdrChannel>>(emptyList())
    val channels: StateFlow<List<SdrChannel>> = _channels
    private val _hello = MutableStateFlow<SdrHello?>(null)
    val hello: StateFlow<SdrHello?> = _hello
    /** Set once any hello says sim:true; the whole run then counts as invented. */
    private val _simulated = MutableStateFlow(false)
    val simulated: StateFlow<Boolean> = _simulated
    var decoded = 0
        private set

    /** How long an aircraft stays listed after it was last heard. */
    const val AIRCRAFT_LIFETIME_MS = 90_000L

    fun apply(e: SdrEvent) {
        when (e) {
            is SdrEvent.Hello -> { _hello.value = e.hello; if (e.hello.simulated) _simulated.value = true }
            is SdrEvent.Status -> decoded = e.decoded
            is SdrEvent.Aircraft -> _aircraft.value = _aircraft.value + (e.aircraft.icao to e.aircraft)
            is SdrEvent.Alert -> _alerts.value = (listOf(e.alert) + _alerts.value).take(50)
            is SdrEvent.WeatherChannels -> _channels.value = e.channels
            else -> {}
        }
    }

    /** Aircraft heard within the lifetime, nearest first when a position is given. */
    fun visibleAircraft(nowMillis: Long, near: Pair<Double, Double>? = null): List<SdrAircraft> {
        val live = _aircraft.value.values.filter { nowMillis - it.heardAtMillis <= AIRCRAFT_LIFETIME_MS }
        return if (near == null) live.sortedBy { it.icao }
        else live.sortedBy { KnownRadioParser.distance(near.first, near.second, it.latitude, it.longitude) }
    }

    fun reset() { _aircraft.value = emptyMap(); _alerts.value = emptyList(); _channels.value = emptyList(); _hello.value = null; _simulated.value = false; decoded = 0 }
}
