// Pota.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Parks on the Air (POTA): the parks near you and who is on the air from them right now. Free,
// keyless public data from pota.app, kept as a snapshot (update with signal, read with none).
// Parks are asked for a box around your position rounded to 0.1 degree. Port of Pota.swift.

package org.nomadpocket.app.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

data class PotaPark(val reference: String, val name: String, val latitude: Double, val longitude: Double)

data class PotaSpot(val id: Long, val activator: String, val reference: String, val parkName: String, val frequencyKHz: Double, val mode: String,
                    val latitude: Double, val longitude: Double, val spottedMillis: Long, val comments: String, val region: String) {
    val frequencyText get() = if (frequencyKHz >= 1000) "%.3f MHz".format(frequencyKHz / 1000) else "%.0f kHz".format(frequencyKHz)
}

data class PotaSnapshot(val fetchedMillis: Long, val centreLat: Double, val centreLon: Double, val parks: List<PotaPark>, val spots: List<PotaSpot>)

object PotaParsers {
    fun spots(json: String): List<PotaSpot> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!s.has("spotId") || !s.has("latitude") || !s.has("longitude")) return@mapNotNull null
            val lat = s.optDouble("latitude", 999.0); val lon = s.optDouble("longitude", 999.0)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val freq = s.optString("frequency").toDoubleOrNull() ?: return@mapNotNull null
            val ref = s.optString("reference").ifEmpty { return@mapNotNull null }
            val millis = runCatching { LocalDateTime.parse(s.optString("spotTime").take(19)).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            PotaSpot(s.getLong("spotId"), s.optString("activator"), ref, s.optString("name").ifEmpty { ref }, freq, s.optString("mode"), lat, lon, millis,
                s.optString("comments"), s.optString("locationDesc"))
        }
    }

    fun parks(json: String): List<PotaPark> {
        val feats = runCatching { JSONObject(json).getJSONArray("features") }.getOrNull() ?: return emptyList()
        return (0 until feats.length()).mapNotNull { i ->
            val f = feats.optJSONObject(i) ?: return@mapNotNull null
            val ref = f.optJSONObject("properties")?.optString("reference")?.ifEmpty { null } ?: return@mapNotNull null
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            if (c.length() < 2) return@mapNotNull null
            val lon = c.optDouble(0, 999.0); val lat = c.optDouble(1, 999.0)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            PotaPark(ref, f.getJSONObject("properties").optString("name", ref), lat, lon)
        }
    }

    /** Spots only count while they are fresh. */
    fun current(spots: List<PotaSpot>, nowMillis: Long, maxAgeMs: Long = 45 * 60_000L) = spots.filter { nowMillis - it.spottedMillis <= maxAgeMs }

    /** 0.1 degree, so the box asked for never pins down where you are. */
    fun rounded(v: Double) = Math.round(v * 10) / 10.0

    fun ageText(thenMillis: Long, nowMillis: Long): String {
        val s = (nowMillis - thenMillis) / 1000
        return when { s < 90 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
    }
}

class PotaStore(context: Context) {
    private val file = File(context.filesDir, "pota.json")
    data class State(val snapshot: PotaSnapshot?, val busy: Boolean = false, val error: String? = null)
    private val _state = MutableStateFlow(State(load()))
    val state: StateFlow<State> = _state
    private var lastTry = 0L

    private fun load(): PotaSnapshot? = try {
        val o = JSONObject(file.readText())
        PotaSnapshot(o.getLong("t"), o.getDouble("lat"), o.getDouble("lon"),
            o.getJSONArray("parks").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p -> PotaPark(p.getString("r"), p.getString("n"), p.getDouble("a"), p.getDouble("o")) } } },
            o.getJSONArray("spots").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { s -> PotaSpot(s.getLong("i"), s.getString("c"), s.getString("r"), s.getString("n"), s.getDouble("f"), s.getString("m"), s.getDouble("a"), s.getDouble("o"), s.getLong("t"), s.optString("k"), s.optString("g")) } } })
    } catch (_: Exception) { null }

    private fun save(s: PotaSnapshot) {
        runCatching {
            file.writeText(JSONObject().put("t", s.fetchedMillis).put("lat", s.centreLat).put("lon", s.centreLon)
                .put("parks", JSONArray(s.parks.map { JSONObject().put("r", it.reference).put("n", it.name).put("a", it.latitude).put("o", it.longitude) }))
                .put("spots", JSONArray(s.spots.map { JSONObject().put("i", it.id).put("c", it.activator).put("r", it.reference).put("n", it.parkName).put("f", it.frequencyKHz).put("m", it.mode)
                    .put("a", it.latitude).put("o", it.longitude).put("t", it.spottedMillis).put("k", it.comments).put("g", it.region) })).toString())
        }
    }

    suspend fun refresh(lat: Double, lon: Double, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (_state.value.busy || (!force && now - lastTry < MIN_INTERVAL_MS)) return@withContext
        lastTry = now
        _state.value = _state.value.copy(busy = true, error = null)
        val la = PotaParsers.rounded(lat); val lo = PotaParsers.rounded(lon)
        fun get(path: String): String {
            val c = URL("https://api.pota.app$path").openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "PocketNomad/1.0 (+https://pocketnomad.us)")
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        }
        try {
            val spots = get("/spot/activator")
            val box = "%.1f/%.1f/%.1f/%.1f".format(java.util.Locale.US, la - 0.5, lo - 0.75, la + 0.5, lo + 0.75)
            val parks = get("/park/grids/$box/0")
            val snap = PotaSnapshot(System.currentTimeMillis(), la, lo, PotaParsers.parks(parks), PotaParsers.spots(spots))
            save(snap)
            _state.value = State(snap)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = "Couldn't reach the POTA service. The last copy is kept.")
        }
    }

    fun activeSpots(near: Pair<Double, Double>?, nowMillis: Long = System.currentTimeMillis()): List<PotaSpot> {
        val live = PotaParsers.current(_state.value.snapshot?.spots ?: emptyList(), nowMillis)
        return if (near == null) live.sortedByDescending { it.spottedMillis } else live.sortedBy { KnownRadioParser.distance(near.first, near.second, it.latitude, it.longitude) }
    }

    companion object {
        const val MIN_INTERVAL_MS = 120_000L
        @Volatile private var shared: PotaStore? = null
        fun get(context: Context): PotaStore = shared ?: synchronized(this) { shared ?: PotaStore(context.applicationContext).also { shared = it } }
    }
}
