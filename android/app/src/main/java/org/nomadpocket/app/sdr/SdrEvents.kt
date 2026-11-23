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

data class SdrHello(val proto: Int, val firmware: String, val model: String, val dongle: String, val modes: List<String>, val simulated: Boolean, val dongles: Int = 1)
data class SdrAircraft(val icao: String, val callsign: String, val latitude: Double, val longitude: Double, val altitudeFeet: Int,
                       val groundSpeedKnots: Int, val trackDegrees: Int, val squawk: String, val heardAtMillis: Long)
data class SdrAlert(val source: String, val mhz: Double, val same: String, val text: String, val receivedAtMillis: Long)
data class SdrChannel(val mhz: Double, val snr: Double)
data class SdrShip(val mmsi: Int, val name: String, val latitude: Double, val longitude: Double, val speedKnots: Double?, val courseDegrees: Double?, val kind: String, val heardAtMillis: Long)
data class SdrSonde(val id: String, val model: String, val latitude: Double, val longitude: Double, val altitudeMetres: Int, val climbMS: Double, val mhz: Double, val heardAtMillis: Long)
/** A weather-satellite picture the box has ready: fetch it over HTTP (Wi-Fi) from the box. */
data class SdrImage(val id: String, val sat: String, val width: Int, val height: Int, val kb: Int, val url: String, val heardAtMillis: Long)

/** A satellite pass the box plans to record. */
data class SdrPass(val sat: String, val aosMillis: Long, val losMillis: Long, val elevation: Int)

/** One JS8 message heard on HF (receive only). kind: "msg" (directed), "hb" (heartbeat) or "cq". */
data class SdrJs8(val from: String, val to: String, val text: String, val snr: Int, val mhz: Double, val kind: String, val heardAtMillis: Long) {
    val id get() = "${heardAtMillis / 1000}|$from|$to|$text"
}

/** One sweep across a frequency range: dB values from lowHz in steps of stepHz. */
data class SdrSpectrumFrame(val lowHz: Int, val stepHz: Int, val db: List<Int>) {
    val highHz get() = lowHz + stepHz * (db.size - 1).coerceAtLeast(0)
    fun hzAt(i: Int) = lowHz + stepHz * i
}

/** A station heard on HF (FT8 or WSPR). The position is the middle of its grid square. */
data class SdrSpot(val mode: String, val call: String, val grid: String, val snr: Int, val mhz: Double, val latitude: Double, val longitude: Double, val heardAtMillis: Long) {
    val id get() = "$mode|$call"
}

sealed class SdrEvent {
    data class Hello(val hello: SdrHello) : SdrEvent()
    data class Status(val mode: String, val decoded: Int, val dongle: Boolean, val active: Map<Int, String> = emptyMap()) : SdrEvent()
    data class Aircraft(val aircraft: SdrAircraft) : SdrEvent()
    /** A raw AX.25 frame (no KISS framing): hand it to the Ham APRS decoder. */
    class Aprs(val frame: ByteArray, val dateMillis: Long) : SdrEvent()
    data class Alert(val alert: SdrAlert) : SdrEvent()
    data class WeatherChannels(val channels: List<SdrChannel>) : SdrEvent()
    class Spectrum(val lowHz: Int, val stepHz: Int, val db: List<Int>) : SdrEvent()
    data class Js8(val message: SdrJs8) : SdrEvent()
    /** The box refused us: "auth" (wrong or missing token) or "blocked" (too many wrong tries). */
    data class Error(val code: String) : SdrEvent()
    data class Ship(val ship: SdrShip) : SdrEvent()
    data class Sonde(val sonde: SdrSonde) : SdrEvent()
    data class Spot(val spot: SdrSpot) : SdrEvent()
    data class Image(val image: SdrImage) : SdrEvent()
    data class Pass(val pass: SdrPass) : SdrEvent()
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
                SdrEvent.Hello(SdrHello(1, j.optString("fw"), j.optString("model"), j.optString("dongle"), modes, j.optBoolean("sim", false), j.optInt("dongles", 1).coerceIn(1, 8)))
            }
            "status" -> {
                val active = mutableMapOf<Int, String>()
                val a = j.optJSONObject("active")
                if (a != null) a.keys().forEach { k -> k.toIntOrNull()?.takeIf { it in 0..7 }?.let { active[it] = a.optString(k) } }
                else j.optString("mode").takeIf { it.isNotEmpty() && it != "idle" }?.let { active[0] = it }
                SdrEvent.Status(j.optString("mode"), j.optInt("decoded", 0), j.optBoolean("dongle", true), active)
            }
            "aircraft" -> {
                val icao = j.optString("icao").ifEmpty { return null }
                if (!j.has("lat") || !j.has("lon")) return null
                val lat = j.getDouble("lat"); val lon = j.getDouble("lon")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                SdrEvent.Aircraft(SdrAircraft(icao.uppercase(), j.optString("call").trim(), lat, lon, j.optInt("alt"), j.optInt("gs"), j.optInt("trk"), j.optString("squawk"), ts))
            }
            "aprs" -> {
                val d = runCatching { org.nomadpocket.ham.Base64Compat.decode(j.optString("kiss")) }.getOrNull() ?: return null
                if (d.size < 16) null else SdrEvent.Aprs(d, ts)
            }
            "alert" -> SdrEvent.Alert(SdrAlert(j.optString("src"), j.optDouble("freq", 0.0), j.optString("same").ifEmpty { return null }, j.optString("text"), ts))
            "nwr" -> {
                val a = j.optJSONArray("channels")
                SdrEvent.WeatherChannels((0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i)?.let { c -> if (c.has("mhz") && c.has("snr")) SdrChannel(c.getDouble("mhz"), c.getDouble("snr")) else null } })
            }
            "ship" -> {
                if (!j.has("mmsi") || !j.has("lat") || !j.has("lon")) return null
                val lat = j.getDouble("lat"); val lon = j.getDouble("lon")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                SdrEvent.Ship(SdrShip(j.getInt("mmsi"), j.optString("name"), lat, lon, if (j.has("sog")) j.getDouble("sog") else null, if (j.has("cog")) j.getDouble("cog") else null, j.optString("kind"), ts))
            }
            "sonde" -> {
                val id = j.optString("id").ifEmpty { return null }
                if (!j.has("lat") || !j.has("lon")) return null
                val lat = j.getDouble("lat"); val lon = j.getDouble("lon")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                SdrEvent.Sonde(SdrSonde(id, j.optString("model"), lat, lon, j.optInt("alt"), j.optDouble("vv", 0.0), j.optDouble("mhz", 0.0), ts))
            }
            "spot" -> {
                val call = j.optString("call").ifEmpty { return null }
                val grid = j.optString("grid")
                val c = org.nomadpocket.app.map.Coordinates.fromMaidenhead(grid) ?: return null
                SdrEvent.Spot(SdrSpot(j.optString("mode"), call, grid, j.optInt("snr"), j.optDouble("mhz", 0.0), c.first, c.second, ts))
            }
            "image" -> {
                val id = j.optString("id").ifEmpty { return null }
                val url = j.optString("url")
                if (!url.startsWith("/img/")) return null
                SdrEvent.Image(SdrImage(id, j.optString("sat"), j.optInt("w"), j.optInt("h"), j.optInt("kb"), url, ts))
            }
            "pass" -> {
                if (!j.has("aos") || !j.has("los")) return null
                SdrEvent.Pass(SdrPass(j.optString("sat").ifEmpty { return null }, j.getLong("aos"), j.getLong("los"), j.optInt("elev")))
            }
            "error" -> SdrEvent.Error(j.optString("code", "unknown"))
            "spectrum" -> {
                val a = j.optJSONArray("db") ?: return null
                val lo = j.optInt("lo"); val step = j.optInt("step")
                if (lo <= 0 || step <= 0 || a.length() !in 2..1000) return null
                SdrEvent.Spectrum(lo, step, (0 until a.length()).map { a.getInt(it).coerceIn(-200, 50) })
            }
            "js8" -> {
                val from = j.optString("from")
                if (from.isEmpty() || from.length > 16) return null
                val kind = j.optString("kind").takeIf { it in listOf("msg", "hb", "cq") } ?: "msg"
                SdrEvent.Js8(SdrJs8(from, j.optString("to").take(16), j.optString("text").take(160), j.optInt("snr"), j.optDouble("mhz", 0.0), kind, ts))
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
    private val _ships = MutableStateFlow<Map<Int, SdrShip>>(emptyMap())
    val ships: StateFlow<Map<Int, SdrShip>> = _ships
    private val _sondes = MutableStateFlow<Map<String, SdrSonde>>(emptyMap())
    val sondes: StateFlow<Map<String, SdrSonde>> = _sondes
    private val _spots = MutableStateFlow<Map<String, SdrSpot>>(emptyMap())
    val spots: StateFlow<Map<String, SdrSpot>> = _spots
    private val _images = MutableStateFlow<List<SdrImage>>(emptyList())
    val images: StateFlow<List<SdrImage>> = _images
    private val _passes = MutableStateFlow<List<SdrPass>>(emptyList())
    val passes: StateFlow<List<SdrPass>> = _passes
    private val _active = MutableStateFlow<Map<Int, String>>(emptyMap())
    /** What each dongle is listening to (dongle number to mode), from the box's status. */
    val active: StateFlow<Map<Int, String>> = _active
    fun setActive(dongle: Int, mode: String?) { _active.value = if (mode == null) _active.value - dongle else _active.value + (dongle to mode) }
    private val _spectrum = MutableStateFlow<SdrSpectrumFrame?>(null)
    /** The latest spectrum sweep, and the strongest value seen in each bin since the range last changed. */
    val spectrum: StateFlow<SdrSpectrumFrame?> = _spectrum
    private val _peak = MutableStateFlow<List<Int>>(emptyList())
    val peak: StateFlow<List<Int>> = _peak
    private val _js8 = MutableStateFlow<List<SdrJs8>>(emptyList())
    val js8: StateFlow<List<SdrJs8>> = _js8
    fun resetPeak() { _peak.value = _spectrum.value?.db ?: emptyList() }
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
            is SdrEvent.Status -> { decoded = e.decoded; _active.value = e.active }
            is SdrEvent.Aircraft -> _aircraft.value = _aircraft.value + (e.aircraft.icao to e.aircraft)
            is SdrEvent.Alert -> _alerts.value = (listOf(e.alert) + _alerts.value).take(50)
            is SdrEvent.WeatherChannels -> _channels.value = e.channels
            is SdrEvent.Ship -> _ships.value = _ships.value + (e.ship.mmsi to e.ship)
            is SdrEvent.Sonde -> _sondes.value = _sondes.value + (e.sonde.id to e.sonde)
            is SdrEvent.Spot -> _spots.value = _spots.value + (e.spot.id to e.spot)
            is SdrEvent.Spectrum -> {
                val old = _spectrum.value
                _peak.value = if (old != null && old.lowHz == e.lowHz && old.stepHz == e.stepHz && _peak.value.size == e.db.size) _peak.value.zip(e.db) { a, b -> maxOf(a, b) } else e.db
                _spectrum.value = SdrSpectrumFrame(e.lowHz, e.stepHz, e.db)
            }
            is SdrEvent.Js8 -> if (_js8.value.none { it.id == e.message.id }) _js8.value = (listOf(e.message) + _js8.value).take(200)
            is SdrEvent.Image -> if (_images.value.none { it.id == e.image.id }) _images.value = (listOf(e.image) + _images.value).take(40)
            is SdrEvent.Pass -> _passes.value = (_passes.value.filterNot { it.sat == e.pass.sat && it.aosMillis == e.pass.aosMillis } + e.pass).sortedBy { it.aosMillis }.takeLast(6)
            else -> {}
        }
    }

    /** Aircraft heard within the lifetime, nearest first when a position is given. */
    fun visibleAircraft(nowMillis: Long, near: Pair<Double, Double>? = null): List<SdrAircraft> {
        val live = _aircraft.value.values.filter { nowMillis - it.heardAtMillis <= AIRCRAFT_LIFETIME_MS }
        return if (near == null) live.sortedBy { it.icao }
        else live.sortedBy { KnownRadioParser.distance(near.first, near.second, it.latitude, it.longitude) }
    }

    const val SHIP_LIFETIME_MS = 600_000L
    const val SONDE_LIFETIME_MS = 1_800_000L
    const val SPOT_LIFETIME_MS = 1_800_000L

    fun visibleShips(nowMillis: Long) = _ships.value.values.filter { nowMillis - it.heardAtMillis <= SHIP_LIFETIME_MS }.sortedBy { it.mmsi }
    fun visibleSondes(nowMillis: Long) = _sondes.value.values.filter { nowMillis - it.heardAtMillis <= SONDE_LIFETIME_MS }.sortedBy { it.id }
    fun visibleSpots(nowMillis: Long) = _spots.value.values.filter { nowMillis - it.heardAtMillis <= SPOT_LIFETIME_MS }.sortedByDescending { it.heardAtMillis }

    fun reset() { _spectrum.value = null; _peak.value = emptyList(); _js8.value = emptyList(); _active.value = emptyMap(); _images.value = emptyList(); _passes.value = emptyList(); _ships.value = emptyMap(); _sondes.value = emptyMap(); _spots.value = emptyMap(); _aircraft.value = emptyMap(); _alerts.value = emptyList(); _channels.value = emptyList(); _hello.value = null; _simulated.value = false; decoded = 0 }
}
