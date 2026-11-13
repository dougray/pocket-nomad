// Known mesh radios from the public community maps: meshmap.net (Meshtastic
// nodes heard by the official MQTT server) and map.meshcore.io (MeshCore
// nodes that uploaded their adverts). Downloaded only when the user asks,
// trimmed to the area around them, and kept on the phone for offline use.
// Nothing is sent to either site beyond the download request itself.
// A port of App/Sources/Map/KnownRadios.swift.
// SPDX-License-Identifier: Apache-2.0
package org.nomadpocket.app.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.HiddenRadio
import org.nomadpocket.mesh.MeshCoreRadioParams
import org.nomadpocket.mesh.OptOut
import org.nomadpocket.mesh.OwnRadios
import org.nomadpocket.mesh.proto.Bytes
import org.nomadpocket.mesh.proto.MeshCoreContact
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One radio from a community map. */
data class KnownRadio(
    val id: String,
    val firmware: Firmware,
    val name: String,
    val role: Role,
    val latitude: Double,
    val longitude: Double,
    val lastSeen: Instant? = null,
    /** MeshCore only: the radio settings it uploaded, when known. */
    val params: MeshCoreRadioParams? = null,
) {
    enum class Role {
        /** Repeaters and routers: the fixed sites that carry the mesh. */
        REPEATER,
        /** MeshCore room servers (bulletin boards). */
        ROOM,
        CLIENT,
        SENSOR,
    }

    val roleLabel: String
        get() = when (role) {
            Role.REPEATER -> if (firmware == Firmware.MESHCORE) "Repeater" else "Router"
            Role.ROOM -> "Room server"
            Role.CLIENT -> "Client"
            Role.SENSOR -> "Sensor"
        }

    /** Repeaters and room servers are drawn bigger and ringed. */
    val isBig: Boolean get() = role == Role.REPEATER || role == Role.ROOM
}

object KnownRadioParser {
    /** meshmap.net: `{ "<node number>": { longName, role, latitude, longitude (degrees x 1e7), seenBy: { topic: unix time } } }`. */
    fun parseMeshtastic(json: String): List<KnownRadio> {
        val nodes = JSONObject(json)
        return nodes.keys().asSequence().mapNotNull { key ->
            val n = nodes.optJSONObject(key) ?: return@mapNotNull null
            if (!n.has("latitude") || !n.has("longitude") || n.isNull("latitude") || n.isNull("longitude")) return@mapNotNull null
            val lat = n.getLong("latitude")
            val lon = n.getLong("longitude")
            if (lat == 0L && lon == 0L) return@mapNotNull null
            val role = when (n.optString("role", "")) {
                "ROUTER", "ROUTER_LATE", "ROUTER_CLIENT", "REPEATER" -> KnownRadio.Role.REPEATER
                "SENSOR" -> KnownRadio.Role.SENSOR
                else -> KnownRadio.Role.CLIENT
            }
            val name = n.optStringOrNull("longName")?.trim(' ', '\t') ?: ""
            val seen = n.optJSONObject("seenBy")?.let { s -> s.keys().asSequence().map { s.getDouble(it) }.maxOrNull() }
            KnownRadio(
                id = "t$key", firmware = Firmware.MESHTASTIC,
                name = name.ifEmpty { n.optStringOrNull("shortName") ?: key },
                role = role, latitude = lat / 1e7, longitude = lon / 1e7,
                lastSeen = seen?.let { Instant.ofEpochMilli((it * 1000).toLong()) },
            )
        }.toList()
    }

    /**
     * map.meshcore.io: `[ { public_key, type (1 client, 2 repeater, 3 room, 4 sensor), adv_name, adv_lat, adv_lon,
     * last_advert, updated_date, params: { freq (MHz), bw (kHz), sf, cr } } ]`. `params` may be missing or empty.
     */
    fun parseMeshCore(json: String): List<KnownRadio> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val n = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = n.optStringOrNull("public_key") ?: return@mapNotNull null
            if (!n.has("adv_lat") || !n.has("adv_lon") || n.isNull("adv_lat") || n.isNull("adv_lon")) return@mapNotNull null
            val lat = n.getDouble("adv_lat")
            val lon = n.getDouble("adv_lon")
            if ((lat == 0.0 && lon == 0.0) || abs(lat) > 90 || abs(lon) > 180) return@mapNotNull null
            val role = when (if (n.has("type") && !n.isNull("type")) n.optInt("type") else null) {
                2 -> KnownRadio.Role.REPEATER
                3 -> KnownRadio.Role.ROOM
                4 -> KnownRadio.Role.SENSOR
                else -> KnownRadio.Role.CLIENT
            }
            KnownRadio(
                id = "c" + key.take(16), firmware = Firmware.MESHCORE,
                name = n.optStringOrNull("adv_name") ?: key.take(8),
                role = role, latitude = lat, longitude = lon,
                // The server's update time; a radio's own advert clock is often wrong.
                lastSeen = (n.optStringOrNull("updated_date") ?: n.optStringOrNull("last_advert"))
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                params = n.optJSONObject("params")?.let { parseParams(it) },
            )
        }
    }

    /** `{ freq, bw, sf, cr }` with numbers (MHz, kHz); null when any part is missing. */
    fun parseParams(p: JSONObject): MeshCoreRadioParams? {
        if (listOf("freq", "bw", "sf", "cr").any { !p.has(it) || p.isNull(it) }) return null
        val freq = p.optDouble("freq")
        val bw = p.optDouble("bw")
        val sf = p.optDouble("sf")
        val cr = p.optDouble("cr")
        if (listOf(freq, bw, sf, cr).any { it.isNaN() }) return null
        return MeshCoreRadioParams(freq, bw, sf.toInt(), cr.toInt()).normalized()
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

    /** Great-circle distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Radios within the radius, minus any whose owner opted out of mapping. */
    fun nearby(radios: List<KnownRadio>, lat: Double, lon: Double, radiusKm: Double, optsOut: (String) -> Boolean = OptOut::optsOut): List<KnownRadio> =
        radios.filter { !optsOut(it.name) && distance(lat, lon, it.latitude, it.longitude) <= radiusKm * 1000 }

    /**
     * Leaves out the user's own radios: on Meshtastic by node number, on
     * MeshCore by the start of the public key (the radio's id here is "c" + key hex).
     */
    fun withoutOwn(radios: List<KnownRadio>, own: OwnRadios): List<KnownRadio> {
        if (own.nodeNums.isEmpty() && own.meshCoreKeyPrefixes.isEmpty()) return radios
        val tastic = own.nodeNums.map { "t$it" }.toSet()
        val core = own.meshCoreKeyPrefixes.map { "c" + it.lowercase().take(16) }
        return radios.filterNot { r ->
            when (r.firmware) {
                Firmware.MESHTASTIC -> r.id in tastic
                Firmware.MESHCORE -> core.any { r.id.lowercase().startsWith(it) }
            }
        }
    }

    /**
     * The hidden-radio entry for "This is mine: hide it from the map" on a
     * community radio: "t<num>" is a Meshtastic node number; "c<key hex>" is
     * a MeshCore public-key prefix, whose first four bytes give the node number.
     * Null when the id cannot be read.
     */
    fun hiddenRadio(radio: KnownRadio): HiddenRadio? {
        val body = radio.id.drop(1)
        return when (radio.firmware) {
            Firmware.MESHTASTIC -> radio.id.takeIf { it.startsWith("t") }?.let { body.toLongOrNull() }
                ?.takeIf { it != 0L }?.let { HiddenRadio(Firmware.MESHTASTIC, it, radio.name) }
            Firmware.MESHCORE -> {
                if (!radio.id.startsWith("c")) return null
                val key = Bytes.fromHex(body.lowercase())?.takeIf { it.size >= 4 } ?: return null
                HiddenRadio(Firmware.MESHCORE, MeshCoreContact.nodeNum(key.array), radio.name, key.hex())
            }
        }
    }

    fun toJson(updated: Instant, areaName: String?, radios: List<KnownRadio>): String = JSONObject().apply {
        put("updated", updated.toEpochMilli())
        areaName?.let { put("areaName", it) }
        put("radios", JSONArray(radios.map { r ->
            JSONObject().put("id", r.id).put("firmware", r.firmware.name).put("name", r.name).put("role", r.role.name)
                .put("latitude", r.latitude).put("longitude", r.longitude).apply {
                    r.lastSeen?.let { put("lastSeen", it.toEpochMilli()) }
                    r.params?.let { put("params", JSONObject().put("freq", it.frequencyMHz).put("bw", it.bandwidthKHz).put("sf", it.spreadingFactor).put("cr", it.codingRate)) }
                }
        }))
    }.toString()

    data class Saved(val updated: Instant, val areaName: String?, val radios: List<KnownRadio>)

    fun fromJson(json: String): Saved {
        val o = JSONObject(json)
        val arr = o.getJSONArray("radios")
        return Saved(
            Instant.ofEpochMilli(o.getLong("updated")),
            o.optStringOrNull("areaName"),
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                KnownRadio(
                    r.getString("id"), Firmware.valueOf(r.getString("firmware")), r.getString("name"),
                    KnownRadio.Role.valueOf(r.getString("role")), r.getDouble("latitude"), r.getDouble("longitude"),
                    if (r.has("lastSeen")) Instant.ofEpochMilli(r.getLong("lastSeen")) else null,
                    r.optJSONObject("params")?.let { parseParams(it) },
                )
            },
        )
    }
}

/** The downloaded radios, saved in the app's files so they work offline. */
object KnownRadioStore {
    const val MESHTASTIC_URL = "https://meshmap.net/nodes.json"
    const val MESHCORE_URL = "https://map.meshcore.io/api/v1/nodes"
    val radiusChoices = listOf(50, 100, 200, 400)

    data class State(
        val radios: List<KnownRadio> = emptyList(),
        val updated: Instant? = null,
        val areaName: String? = null,
        val downloading: Boolean = false,
        val lastError: String? = null,
    ) {
        val meshtasticCount get() = radios.count { it.firmware == Firmware.MESHTASTIC }
        val meshcoreCount get() = radios.count { it.firmware == Firmware.MESHCORE }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "known-radios.json")
        file = f
        runCatching { KnownRadioParser.fromJson(f.readText()) }.getOrNull()?.let {
            _state.value = State(it.radios, it.updated, it.areaName)
        }
    }

    /** Downloads both maps and keeps radios within [radiusKm] of the centre. One map failing still keeps the other. */
    suspend fun download(lat: Double, lon: Double, radiusKm: Int, areaName: String?) {
        if (_state.value.downloading) return
        _state.value = _state.value.copy(downloading = true, lastError = null)
        try {
            val results = coroutineScope {
                val tastic = async { fetch(MESHTASTIC_URL, KnownRadioParser::parseMeshtastic) }
                val core = async { fetch(MESHCORE_URL, KnownRadioParser::parseMeshCore) }
                listOf("meshmap.net" to tastic.await(), "map.meshcore.io" to core.await())
            }
            val all = results.mapNotNull { it.second.getOrNull() }.flatten()
            val failed = results.filter { it.second.isFailure }.map { it.first }
            if (failed.size == results.size) {
                _state.value = _state.value.copy(lastError = "Couldn't reach the community maps. Check your connection and try again.")
                return
            }
            val error = if (failed.isNotEmpty()) "Couldn't reach ${failed.joinToString(" and ")}; kept the rest." else null
            val radios = withContext(Dispatchers.Default) { KnownRadioParser.nearby(all, lat, lon, radiusKm.toDouble()) }
            val now = Instant.now()
            _state.value = _state.value.copy(radios = radios, updated = now, areaName = areaName, lastError = error)
            save()
        } finally {
            _state.value = _state.value.copy(downloading = false)
        }
    }

    fun deleteAll() {
        _state.value = State()
        file?.delete()
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val s = _state.value
        val f = file ?: return@withContext
        val updated = s.updated ?: return@withContext
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(KnownRadioParser.toJson(updated, s.areaName, s.radios))
        tmp.renameTo(f)
    }

    private suspend fun fetch(url: String, parse: (String) -> List<KnownRadio>): Result<List<KnownRadio>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 60_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Debug screenshots only: shows radios without a download. */
    fun setForDemo(radios: List<KnownRadio>) {
        _state.value = State(radios = radios, updated = Instant.now())
    }
}
