// ConditionsStore.kt
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Downloads earthquakes, weather alerts, a forecast, space weather and the
// Weather Radio transmitter list when the user taps Update, keeps the last good
// copy of each on the phone and never throws one away because a later download
// failed. Free sources, no keys. Earthquakes, space weather and transmitters are
// whole-region downloads filtered on the phone; alerts and the forecast are asked
// for a position rounded to 0.1 degree (about 7 miles). Port of ConditionsStore.swift.

package org.nomadpocket.app.conditions

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

object ConditionsStore {
    class HttpFailure(val status: Int) : Exception("HTTP $status")

    data class State(
        val quakes: Snapshot<List<Quake>>? = null,
        val alerts: Snapshot<List<WeatherAlert>>? = null,
        val forecast: Snapshot<Forecast>? = null,
        val space: Snapshot<SpaceWeather>? = null,
        val radio: Snapshot<List<WeatherRadioStation>>? = null,
        /** The rounded position the alerts and forecast were asked for. */
        val center: Pair<Double, Double>? = null,
        val updating: Boolean = false,
        val errors: Map<String, String> = emptyMap(),
        /** The National Weather Service covers only the US and its territories. */
        val alertsUnavailableHere: Boolean = false,
        val needsLocation: Boolean = false,
    ) {
        val hasAnything get() = quakes != null || alerts != null || forecast != null || space != null
        val oldestUpdate: Long? get() = listOfNotNull(quakes?.fetchedAtMillis, alerts?.fetchedAtMillis, forecast?.fetchedAtMillis, space?.fetchedAtMillis).minOrNull()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val USER_AGENT = "PocketNomad/1.0 (https://www.pocketnomad.us)"
    private const val MIN_INTERVAL_MS = 60_000L
    private const val RADIO_MAX_AGE_MS = 30L * 86_400_000
    private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null
    var now: () -> Long = { System.currentTimeMillis() }
    /** One position fix (latitude, longitude), or null. Replaced in tests. */
    var locate: suspend () -> Pair<Double, Double>? = {
        Mesh.location.currentLocation(12_000)?.let { it.latitude to it.longitude }
    }
    /** Replaced in tests: fetches a URL as text. */
    var fetcher: (String, String) -> String = ::httpGet

    fun init(context: Context) {
        if (dir != null) return
        val app = context.applicationContext
        dir = File(app.noBackupFilesDir, "conditions").also { it.mkdirs() }
        prefs = app.getSharedPreferences("conditions", Context.MODE_PRIVATE)
        _state.value = State(
            quakes = load("quakes.json") { a -> (0 until a.length()).map { Quake.fromJson(a.getJSONObject(it)) } },
            alerts = load("alerts.json") { a -> (0 until a.length()).map { WeatherAlert.fromJson(a.getJSONObject(it)) } },
            forecast = loadObj("forecast.json") { Forecast.fromJson(it) },
            space = loadObj("space.json") { SpaceWeather.fromJson(it) },
            radio = load("radio.json") { a -> (0 until a.length()).map { WeatherRadioStation.fromJson(a.getJSONObject(it)) } },
            center = loadObj("center.json") { it.getDouble("lat") to it.getDouble("lon") }?.value,
        )
    }

    // MARK: Settings

    var quakeRadiusKm: Int
        get() = prefs?.getInt("radiusKm", 500) ?: 500
        set(v) { prefs?.edit()?.putInt("radiusKm", v)?.apply(); _state.value = _state.value.copy() }
    var quakeMinMagnitude: Double
        get() = (prefs?.getFloat("minMag", 2.5f) ?: 2.5f).toDouble()
        set(v) { prefs?.edit()?.putFloat("minMag", v.toFloat())?.apply(); _state.value = _state.value.copy() }
    var refreshOnOpen: Boolean
        get() = prefs?.getBoolean("refreshOnOpen", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("refreshOnOpen", v)?.apply(); _state.value = _state.value.copy() }

    // MARK: What the screens show

    fun nearbyQuakes(s: State = _state.value): List<Quake> {
        val q = s.quakes?.value ?: return emptyList()
        val c = s.center ?: (39.74 to -104.99)
        return ConditionsParsers.nearby(q, c.first, c.second, quakeRadiusKm.toDouble(), quakeMinMagnitude)
    }

    fun activeAlerts(s: State = _state.value) = s.alerts?.value?.filter { !it.isExpired(now()) } ?: emptyList()
    fun expiredAlerts(s: State = _state.value) = s.alerts?.value?.filter { it.isExpired(now()) } ?: emptyList()

    fun nearestRadio(limit: Int, s: State = _state.value): List<Pair<WeatherRadioStation, Double>> {
        val stations = s.radio?.value ?: return emptyList()
        val c = s.center ?: return emptyList()
        return ConditionsParsers.nearestRadio(stations, c.first, c.second, limit)
    }

    // MARK: Update

    /** Only when the newest data is over half an hour old, and only if the user turned this on. */
    suspend fun refreshIfStale() {
        if (!refreshOnOpen) return
        val t = _state.value.oldestUpdate
        if (t != null && now() - t < 1_800_000) return
        refresh()
    }

    suspend fun refresh(force: Boolean = false) {
        if (_state.value.updating) return
        _state.value = _state.value.copy(updating = true, errors = emptyMap())
        try {
            val t = now()
            locate()?.let { (la, lo) ->
                val c = ConditionsParsers.rounded(la) to ConditionsParsers.rounded(lo)
                _state.value = _state.value.copy(center = c)
                save("center.json", JSONObject().put("lat", c.first).put("lon", c.second), null)
            }
            val s0 = _state.value
            val point = s0.center
            _state.value = s0.copy(needsLocation = point == null)
            fun due(fetched: Long?, maxAge: Long) = force || fetched?.let { t - it >= maxAge } ?: true
            val errors = HashMap<String, String>()
            coroutineScope {
                val q = if (due(s0.quakes?.fetchedAtMillis, MIN_INTERVAL_MS)) async(Dispatchers.IO) { runCatching { fetchQuakes() } } else null
                val sp = if (due(s0.space?.fetchedAtMillis, MIN_INTERVAL_MS)) async(Dispatchers.IO) { runCatching { fetchSpace() } } else null
                val al = if (point != null && due(s0.alerts?.fetchedAtMillis, MIN_INTERVAL_MS)) async(Dispatchers.IO) { runCatching { fetchAlerts(point) } } else null
                val fc = if (point != null && due(s0.forecast?.fetchedAtMillis, MIN_INTERVAL_MS)) async(Dispatchers.IO) { runCatching { fetchForecast(point) } } else null
                val rd = if (due(s0.radio?.fetchedAtMillis, if (force) MIN_INTERVAL_MS else RADIO_MAX_AGE_MS)) async(Dispatchers.IO) { runCatching { fetchRadio() } } else null
                q?.await()?.let { r -> r.onSuccess { v -> val s = Snapshot(t, v); _state.value = _state.value.copy(quakes = s); save("quakes.json", null, JSONArray(v.map { it.toJson() }), t) }.onFailure { errors["Earthquakes"] = words(it) } }
                sp?.await()?.let { r -> r.onSuccess { v -> _state.value = _state.value.copy(space = Snapshot(t, v)); save("space.json", v.toJson(), null, t) }.onFailure { errors["Space weather"] = words(it) } }
                al?.await()?.let { r ->
                    r.onSuccess { v -> _state.value = _state.value.copy(alerts = Snapshot(t, v), alertsUnavailableHere = false); save("alerts.json", null, JSONArray(v.map { it.toJson() }), t) }
                        .onFailure { e -> if (e is HttpFailure && (e.status == 404 || e.status == 400)) _state.value = _state.value.copy(alertsUnavailableHere = true) else errors["Weather alerts"] = words(e) }
                }
                fc?.await()?.let { r -> r.onSuccess { v -> _state.value = _state.value.copy(forecast = Snapshot(t, v)); save("forecast.json", v.toJson(), null, t) }.onFailure { errors["Forecast"] = words(it) } }
                rd?.await()?.let { r -> r.onSuccess { v -> _state.value = _state.value.copy(radio = Snapshot(t, v)); save("radio.json", null, JSONArray(v.map { it.toJson() }), t) }.onFailure { errors["Weather radio"] = words(it) } }
            }
            _state.value = _state.value.copy(errors = errors)
        } finally {
            _state.value = _state.value.copy(updating = false)
        }
    }

    fun deleteAll() {
        dir?.listFiles()?.forEach { it.delete() }
        _state.value = State()
    }

    private fun words(e: Throwable): String = when {
        e is HttpFailure -> "The service answered ${e.status}. Try again later."
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "No connection. Showing the last update."
        e is ConditionsParsers.Failure -> "The service sent something unexpected. Showing the last update."
        else -> "Couldn't update. Showing the last update."
    }

    // MARK: Network

    private fun httpGet(url: String, accept: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", accept)
        try {
            if (c.responseCode != 200) throw HttpFailure(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun get(url: String, accept: String = "application/json") = fetcher(url, accept)

    private fun fetchQuakes(): List<Quake> {
        val week = ConditionsParsers.parseQuakes(get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson"))
        val sig = ConditionsParsers.parseQuakes(get("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_month.geojson"), significant = true)
        return ConditionsParsers.merge(week, sig).sortedByDescending { it.timeMillis }
    }

    private fun fetchSpace() = ConditionsParsers.parseSpaceWeather(
        get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"),
        get("https://services.swpc.noaa.gov/products/noaa-scales.json"),
        get("https://services.swpc.noaa.gov/text/3-day-forecast.txt", "text/plain"))

    private fun fetchAlerts(p: Pair<Double, Double>) =
        ConditionsParsers.parseAlerts(get("https://api.weather.gov/alerts/active?point=${p.first},${p.second}", "application/geo+json"))

    private fun fetchForecast(p: Pair<Double, Double>): Forecast {
        val vars = "temperature_2m,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code"
        return ConditionsParsers.parseForecast(get("https://api.open-meteo.com/v1/forecast?latitude=${p.first}&longitude=${p.second}&hourly=$vars&forecast_days=7&timezone=auto"))
    }

    private fun fetchRadio(): List<WeatherRadioStation> {
        val list = ConditionsParsers.parseWeatherRadio(get("https://www.weather.gov/source/nwr/JS/CCL.js", "*/*"))
        if (list.isEmpty()) throw ConditionsParsers.Failure("No transmitters")
        return list
    }

    // MARK: Disk

    private fun save(name: String, obj: JSONObject?, arr: JSONArray?, fetchedAt: Long = 0) {
        val f = File(dir ?: return, name)
        try {
            val o = JSONObject().put("t", fetchedAt)
            if (obj != null) o.put("v", obj)
            if (arr != null) o.put("v", arr)
            f.writeText(o.toString())
        } catch (_: Exception) {}
    }

    private fun <T> load(name: String, parse: (JSONArray) -> List<T>): Snapshot<List<T>>? = try {
        val o = JSONObject(File(dir!!, name).readText())
        Snapshot(o.getLong("t"), parse(o.getJSONArray("v")))
    } catch (_: Exception) { null }

    private fun <T> loadObj(name: String, parse: (JSONObject) -> T): Snapshot<T>? = try {
        val o = JSONObject(File(dir!!, name).readText())
        Snapshot(o.getLong("t"), parse(o.getJSONObject("v")))
    } catch (_: Exception) { null }

    // MARK: Demo

    fun loadDemo() {
        val t = now()
        val kp = listOf(1.0, 2.0, 3.0, 3.0, 2.0, 4.0, 5.0, 3.0, 2.0, 2.0, 1.0, 2.0)
        _state.value = State(
            center = 39.7 to -105.0,
            quakes = Snapshot(t - 1_500_000, listOf(
                Quake("d1", 3.1, "5 km N of Golden, Colorado", t - 5_400_000, 39.78, -105.22, 8.2, felt = 12),
                Quake("d2", 2.7, "22 km SW of Pueblo, Colorado", t - 86_400_000, 38.1, -104.8, 5.0),
                Quake("d3", 6.6, "148 km WSW of Tual, Indonesia", t - 9 * 86_400_000, -6.1, 130.5, 10.0, significant = true))),
            alerts = Snapshot(t - 1_500_000, listOf(WeatherAlert("a1", "Winter Weather Advisory", "Moderate", "Expected", "Winter Weather Advisory until 5 PM MST",
                "Snow expected. Total accumulations of 3 to 6 inches. Roads may be slick, especially during the evening commute.",
                "Slow down and use caution while driving. Carry extra food, water and a blanket.", "NWS Denver/Boulder CO", "Jefferson; Denver; Arapahoe", t - 7_200_000, t + 14_400_000))),
            forecast = Snapshot(t - 1_500_000, Forecast(39.7, -105.0, (0 until 48).map { i ->
                val wet = i < 8
                ForecastHour(t + i * 3_600_000L, 2 + 4 * Math.sin(i / 4.0), if (wet) 0.4 else 0.0, if (wet) 70 else 10, 14.0 + (i % 5) * 3, 28.0, 290, if (wet) 73 else 2)
            })),
            space = Snapshot(t - 1_500_000, SpaceWeather(3.0, t, kp.mapIndexed { i, v -> KpPoint(t + (i - 4) * 10_800_000L, v, i >= 4) }, 0, 0, 1, 1)),
            radio = Snapshot(t - 86_400_000, listOf(
                WeatherRadioStation("KEC76", "Denver", "CO", "162.550", 39.74, -104.99, 1000, "NORMAL", listOf("008031")),
                WeatherRadioStation("WNG550", "Franktown", "CO", "162.450", 39.4, -104.7, 300, "NORMAL", listOf("008035")))),
        )
    }
}
