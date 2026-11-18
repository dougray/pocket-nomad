// SatelliteStore.kt
// Pocket Nomad - Satellites module
// SPDX-License-Identifier: Apache-2.0
//
// Orbit data (two-line element sets) for the space station and amateur radio
// satellites from CelesTrak, downloaded when the user taps Update and kept on the
// phone. Passes are worked out on-device from the user's location, which is never
// sent anywhere. Free, no key. Port of SatelliteStore.swift.

package org.nomadpocket.ham

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
import org.nomadpocket.mesh.Mesh
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SatelliteStore {
    const val ISS_ID = 25544
    private val GROUPS = listOf("stations", "amateur")
    private const val USER_AGENT = "PocketNomad/1.0 (https://www.pocketnomad.us)"

    data class State(
        val satellites: List<Tle> = emptyList(),
        val fetchedAtMillis: Long? = null,
        val updating: Boolean = false,
        val error: String? = null,
        val passes: List<SatellitePass> = emptyList(),
        val observer: Observer? = null,
        val computing: Boolean = false,
        val favorites: Set<Int> = setOf(ISS_ID),
    ) {
        val iss: Tle? get() = satellites.firstOrNull { it.noradId == ISS_ID }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var dir: File? = null
    private var prefs: HamPrefs? = null
    var now: () -> Long = { System.currentTimeMillis() }
    /** One fix (latitude, longitude, altitude m), or null. Replaced in tests. */
    var locate: suspend () -> Observer? = {
        if (!Mesh.isInitialized) null else Mesh.location.currentLocation(10_000)?.let { Observer(it.latitude, it.longitude, 0.0) }
    }
    /** Replaced in tests: fetches one CelesTrak group as text. */
    var fetcher: (String) -> String = ::httpGet

    fun init(context: Context) = configure(File(context.applicationContext.noBackupFilesDir, "satellites"), SharedHamPrefs(context.applicationContext))

    fun configure(directory: File, preferences: HamPrefs) {
        dir = directory.also { it.mkdirs() }
        prefs = preferences
        var s = State(favorites = readFavorites())
        try {
            val o = JSONObject(File(directory, "elements.json").readText())
            val a = o.getJSONArray("sats")
            s = s.copy(fetchedAtMillis = o.getLong("t"), satellites = (0 until a.length()).map { val e = a.getJSONObject(it); Tle(e.getString("n"), e.getString("a"), e.getString("b")) })
        } catch (_: Exception) {}
        preferences.string("observer")?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.let { s = s.copy(observer = Observer(it[0], it[1], it[2])) }
        _state.value = s
    }

    private fun readFavorites(): Set<Int> = prefs?.string("favorites")?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(ISS_ID)

    fun toggleFavorite(id: Int) {
        val f = _state.value.favorites.toMutableSet()
        if (!f.add(id)) f.remove(id)
        prefs?.putString("favorites", f.sorted().joinToString(","))
        _state.value = _state.value.copy(favorites = f)
    }

    fun orbitAgeDays(s: State = _state.value): Double? = s.fetchedAtMillis?.let { (now() - it) / 86_400_000.0 }

    suspend fun refresh() {
        if (_state.value.updating) return
        _state.value = _state.value.copy(updating = true, error = null)
        try {
            val results = coroutineScope { GROUPS.map { g -> async(Dispatchers.IO) { runCatching { Tle.parse(fetcher(g)).also { if (it.isEmpty()) error("empty") } } } }.map { it.await() } }
            val byId = LinkedHashMap<Int, Tle>()
            var failed = 0
            for (r in results) r.onSuccess { list -> list.filter { Sgp4.create(it) != null }.forEach { byId[it.noradId] = it } }.onFailure { failed++ }
            if (byId.isEmpty()) {
                _state.value = _state.value.copy(error = if (failed == results.size) "Couldn't reach CelesTrak. Showing the last orbit data." else "CelesTrak sent no usable satellites.")
                return
            }
            val t = now()
            val sats = byId.values.sortedBy { it.name }
            _state.value = _state.value.copy(satellites = sats, fetchedAtMillis = t, error = if (failed > 0) "Some orbit data couldn't be updated." else null)
            try {
                File(dir ?: return, "elements.json").writeText(JSONObject().put("t", t)
                    .put("sats", JSONArray(sats.map { JSONObject().put("n", it.name).put("a", it.line1).put("b", it.line2) })).toString())
            } catch (_: Exception) {}
        } finally {
            _state.value = _state.value.copy(updating = false)
        }
    }

    suspend fun updateObserver() {
        val fix = locate()
        if (fix != null) {
            prefs?.putString("observer", "${fix.latitude},${fix.longitude},${fix.altitudeMetres}")
            _state.value = _state.value.copy(observer = fix)
        }
    }

    suspend fun computePasses(hours: Double = 48.0) {
        val s = _state.value
        val o = s.observer
        if (o == null || s.satellites.isEmpty()) { _state.value = s.copy(passes = emptyList()); return }
        _state.value = s.copy(computing = true)
        val start = now()
        val favs = s.favorites
        val result = withContext(Dispatchers.Default) {
            s.satellites.filter { it.noradId in favs }.flatMap { SatelliteMath.passes(it, o, start, hours) }.sortedBy { it.startMillis }
        }
        _state.value = _state.value.copy(passes = result, computing = false)
    }

    suspend fun passesFor(tle: Tle, hours: Double = 72.0): List<SatellitePass> {
        val o = _state.value.observer ?: return emptyList()
        val start = now()
        return withContext(Dispatchers.Default) { SatelliteMath.passes(tle, o, start, hours) }
    }

    fun deleteAll() {
        dir?.listFiles()?.forEach { it.delete() }
        prefs?.putString("observer", null)
        _state.value = State(favorites = _state.value.favorites)
    }

    private fun httpGet(group: String): String {
        val c = URL("https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle").openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
