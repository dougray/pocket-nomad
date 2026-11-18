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
data class SdrShip(val mmsi: Int, val name: String, val latitude: Double, val longitude: Double, val speedKnots: Double?, val courseDegrees: Double?, val kind: String, val heardAtMillis: Long)
data class SdrSonde(val id: String, val model: String, val latitude: Double, val longitude: Double, val altitudeMetres: Int, val climbMS: Double, val mhz: Double, val heardAtMillis: Long)
/** A station heard on HF (FT8 or WSPR). The position is the middle of its grid square. */
data class SdrSpot(val mode: String, val call: String, val grid: String, val snr: Int, val mhz: Double, val latitude: Double, val longitude: Double, val heardAtMillis: Long) {
    val id get() = "$mode|$call"
}

sealed class SdrEvent {
    data class Hello(val hello: SdrHello) : SdrEvent()
    data class Status(val mode: String, val decoded: Int, val dongle: Boolean) : SdrEvent()
    data class Aircraft(val aircraft: SdrAircraft) : SdrEvent()
    /** A raw AX.25 frame (no KISS framing): hand it to the Ham APRS decoder. */
    class Aprs(val frame: ByteArray, val dateMillis: Long) : SdrEvent()
    data class Alert(val alert: SdrAlert) : SdrEvent()
    data class WeatherChannels(val channels: List<SdrChannel>) : SdrEvent()
    class Spectrum(val lowHz: Int, val stepHz: Int, val db: List<Int>) : SdrEvent()
    /** The box refused us: "auth" (wrong or missing token) or "blocked" (too many wrong tries). */
    data class Error(val code: String) : SdrEvent()
    data class Ship(val ship: SdrShip) : SdrEvent()
    data class Sonde(val sonde: SdrSonde) : SdrEvent()
    data class Spot(val spot: SdrSpot) : SdrEvent()
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
            "error" -> SdrEvent.Error(j.optString("code", "unknown"))
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
    private val _ships = MutableStateFlow<Map<Int, SdrShip>>(emptyMap())
    val ships: StateFlow<Map<Int, SdrShip>> = _ships
    private val _sondes = MutableStateFlow<Map<String, SdrSonde>>(emptyMap())
    val sondes: StateFlow<Map<String, SdrSonde>> = _sondes
    private val _spots = MutableStateFlow<Map<String, SdrSpot>>(emptyMap())
    val spots: StateFlow<Map<String, SdrSpot>> = _spots
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
            is SdrEvent.Ship -> _ships.value = _ships.value + (e.ship.mmsi to e.ship)
            is SdrEvent.Sonde -> _sondes.value = _sondes.value + (e.sonde.id to e.sonde)
            is SdrEvent.Spot -> _spots.value = _spots.value + (e.spot.id to e.spot)
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

    fun reset() { _ships.value = emptyMap(); _sondes.value = emptyMap(); _spots.value = emptyMap(); _aircraft.value = emptyMap(); _alerts.value = emptyList(); _channels.value = emptyList(); _hello.value = null; _simulated.value = false; decoded = 0 }
}
