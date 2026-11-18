// Directions.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Offline route planning. Roads come from OpenStreetMap (public Overpass service), are saved
// for an area, and routed on the phone (A*). Port of Directions.swift.

package org.nomadpocket.app.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

enum class TravelMode(val label: String) {
    FOOT("Walk"), BIKE("Bike"), CAR("Drive");

    /** Typical speed in m/s on a road of this class, or null if this mode cannot use it. */
    fun speed(highway: String): Double? = when (this) {
        FOOT -> when (highway) { "motorway", "motorway_link", "trunk", "trunk_link" -> null; else -> 1.34 }
        BIKE -> when (highway) {
            "motorway", "motorway_link", "trunk", "trunk_link", "steps" -> null
            "path", "track", "footway", "pedestrian" -> 3.0
            else -> 4.5
        }
        CAR -> when (highway) {
            "motorway" -> 29.0
            "motorway_link", "trunk" -> 22.0
            "trunk_link", "primary" -> 17.0
            "primary_link", "secondary", "secondary_link" -> 14.0
            "tertiary", "tertiary_link" -> 12.0
            "residential", "unclassified", "living_street" -> 9.0
            "service" -> 5.0
            "track" -> 4.0
            else -> null
        }
    }
}

data class RoadWay(val id: Long, val highway: String, val name: String, val oneway: Boolean,
                   val nodes: LongArray, val lat: DoubleArray, val lon: DoubleArray) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("hw", highway).put("name", name).put("oneway", oneway)
        .put("nodes", JSONArray(nodes.toList())).put("lat", JSONArray(lat.toList())).put("lon", JSONArray(lon.toList()))

    companion object {
        fun fromJson(o: JSONObject): RoadWay {
            val n = o.getJSONArray("nodes"); val la = o.getJSONArray("lat"); val lo = o.getJSONArray("lon")
            return RoadWay(o.getLong("id"), o.getString("hw"), o.optString("name"), o.optBoolean("oneway"),
                LongArray(n.length()) { n.getLong(it) }, DoubleArray(la.length()) { la.getDouble(it) }, DoubleArray(lo.length()) { lo.getDouble(it) })
        }
    }
}

data class RouteStep(val text: String, val meters: Double, val latitude: Double, val longitude: Double)
data class Route(val points: List<ShapePoint>, val meters: Double, val seconds: Double, val steps: List<RouteStep>)

object Overpass {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val SKIPPED = "proposed|construction|abandoned|razed|platform|bus_guideway|raceway|corridor|elevator"

    fun query(south: Double, west: Double, north: Double, east: Double) =
        "[out:json][timeout:120];way[highway][highway!~\"^($SKIPPED)\$\"][access!~\"^(private|no)\$\"][service!~\"^(driveway|parking_aisle|alley|drive-through)\$\"]($south,$west,$north,$east);out geom qt;"

    fun parse(json: String): List<RoadWay> {
        val els = runCatching { JSONObject(json).getJSONArray("elements") }.getOrNull() ?: return emptyList()
        val out = ArrayList<RoadWay>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            if (e.optString("type") != "way") continue
            val tags = e.optJSONObject("tags") ?: continue
            val hw = tags.optString("highway").ifEmpty { continue }
            val nodes = e.optJSONArray("nodes") ?: continue
            val geom = e.optJSONArray("geometry") ?: continue
            if (nodes.length() != geom.length() || nodes.length() < 2) continue
            val oneway = if (tags.has("oneway")) tags.optString("oneway") in listOf("yes", "true", "1") else (hw == "motorway" || tags.optString("junction") == "roundabout")
            out += RoadWay(e.getLong("id"), hw, tags.optString("name").ifEmpty { tags.optString("ref") }, oneway,
                LongArray(nodes.length()) { nodes.getLong(it) }, DoubleArray(geom.length()) { Math.round(geom.getJSONObject(it).getDouble("lat") * 1e6) / 1e6 },
                DoubleArray(geom.length()) { Math.round(geom.getJSONObject(it).getDouble("lon") * 1e6) / 1e6 })
        }
        return out
    }
}

/** A road graph for one travel mode. */
class RoadGraph(private val ways: List<RoadWay>, val mode: TravelMode) {
    private class Edge(val to: Int, val meters: Double, val seconds: Double, val way: Int)
    private val coords = ArrayList<ShapePoint>()
    private val adjacency = ArrayList<ArrayList<Edge>>()

    init {
        val index = HashMap<Long, Int>()
        fun node(id: Long, lat: Double, lon: Double): Int = index.getOrPut(id) { coords.add(ShapePoint(lat, lon)); adjacency.add(ArrayList()); coords.size - 1 }
        ways.forEachIndexed { wi, w ->
            val speed = mode.speed(w.highway) ?: return@forEachIndexed
            for (k in 1 until w.nodes.size) {
                val a = node(w.nodes[k - 1], w.lat[k - 1], w.lon[k - 1]); val b = node(w.nodes[k], w.lat[k], w.lon[k])
                val m = KnownRadioParser.distance(w.lat[k - 1], w.lon[k - 1], w.lat[k], w.lon[k])
                adjacency[a].add(Edge(b, m, m / speed, wi))
                // One-way streets bind cars and bikes; people on foot can go either way.
                if (!w.oneway || mode == TravelMode.FOOT) adjacency[b].add(Edge(a, m, m / speed, wi))
            }
        }
    }

    val isEmpty get() = coords.isEmpty()

    fun nearest(lat: Double, lon: Double, maxMeters: Double = 1500.0): Int? {
        var best = -1; var bestD = Double.MAX_VALUE
        for (i in coords.indices) {
            if (adjacency[i].isEmpty()) continue
            val d = KnownRadioParser.distance(lat, lon, coords[i].latitude, coords[i].longitude)
            if (d <= maxMeters && d < bestD) { best = i; bestD = d }
        }
        return if (best >= 0) best else null
    }

    fun route(a: ShapePoint, b: ShapePoint): Route? {
        val s = nearest(a.latitude, a.longitude) ?: return null
        val t = nearest(b.latitude, b.longitude) ?: return null
        val maxSpeed = when (mode) { TravelMode.CAR -> 29.0; TravelMode.BIKE -> 4.5; TravelMode.FOOT -> 1.34 }
        fun h(i: Int) = KnownRadioParser.distance(coords[i].latitude, coords[i].longitude, coords[t].latitude, coords[t].longitude) / maxSpeed
        val g = HashMap<Int, Double>(); val prev = HashMap<Int, Pair<Int, Edge>>(); val done = HashSet<Int>()
        val open = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        g[s] = 0.0; open.add(h(s) to s)
        while (open.isNotEmpty()) {
            val (_, u) = open.poll()!!
            if (u == t) break
            if (!done.add(u)) continue
            for (e in adjacency[u]) {
                if (e.to in done) continue
                val cost = g[u]!! + e.seconds
                if (cost < (g[e.to] ?: Double.POSITIVE_INFINITY)) { g[e.to] = cost; prev[e.to] = u to e; open.add(cost + h(e.to) to e.to) }
            }
        }
        if (g[t] == null) return null
        val edges = ArrayList<Pair<Int, Edge>>()
        var cur = t
        while (cur != s) { val p = prev[cur] ?: break; edges.add(p.first to p.second); cur = p.first }
        edges.reverse()
        val pts = if (s != t) listOf(a, coords[s]) + edges.map { coords[it.second.to] } + b else listOf(a, b)
        return Route(pts, edges.sumOf { it.second.meters }, edges.sumOf { it.second.seconds }, steps(edges))
    }

    private fun steps(edges: List<Pair<Int, Edge>>): List<RouteStep> {
        if (edges.isEmpty()) return emptyList()
        fun bearing(a: Int, b: Int) = ShapeMath.bearing(coords[a], coords[b])
        fun street(e: Edge) = ways[e.way].name.ifEmpty { "the path" }
        val steps = ArrayList<RouteStep>()
        var meters = 0.0
        var name = street(edges[0].second)
        steps.add(RouteStep("Head ${ShapeMath.bearingText(bearing(edges[0].first, edges[0].second.to))} on $name", 0.0, coords[edges[0].first].latitude, coords[edges[0].first].longitude))
        for (i in edges.indices) {
            meters += edges[i].second.meters
            if (i + 1 >= edges.size) break
            val next = edges[i + 1]
            val turn = GoToMath.arrowAngle(bearing(next.first, next.second.to), bearing(edges[i].first, edges[i].second.to))
            val nextName = street(next.second)
            if (nextName != name || abs(turn) >= 45) {
                steps[steps.size - 1] = steps.last().copy(meters = meters)
                val dir = when {
                    abs(turn) < 20 -> "Continue"
                    abs(turn) < 60 -> if (turn > 0) "Bear right" else "Bear left"
                    abs(turn) < 135 -> if (turn > 0) "Turn right" else "Turn left"
                    else -> if (turn > 0) "Sharp right" else "Sharp left"
                }
                steps.add(RouteStep("$dir onto $nextName", 0.0, coords[next.first].latitude, coords[next.first].longitude))
                meters = 0.0; name = nextName
            }
        }
        steps[steps.size - 1] = steps.last().copy(meters = meters)
        val last = edges.last().second.to
        steps.add(RouteStep("Arrive", 0.0, coords[last].latitude, coords[last].longitude))
        return steps
    }
}

/** Roads saved on the phone, in 0.05 degree squares (about 5 km). */
class RoadStore(context: Context) {
    private val folder = File(context.filesDir, "roads")
    data class State(val cellCount: Int, val downloading: Boolean = false, val progress: Float = 0f, val message: String = "")
    private val _state = MutableStateFlow(State(count()))
    val state: StateFlow<State> = _state
    private var loaded: List<RoadWay>? = null
    private val graphs = HashMap<TravelMode, RoadGraph>()

    private fun count() = folder.listFiles { f -> f.extension == "json" }?.size ?: 0
    val hasRoads get() = _state.value.cellCount > 0
    private fun file(la: Int, lo: Int) = File(folder, "${la}_$lo.json")
    fun estimateMB(lat: Double, lon: Double, radiusKm: Double) = cells(lat, lon, radiusKm).size * 2.5

    suspend fun download(lat: Double, lon: Double, radiusKm: Double) = withContext(Dispatchers.IO) {
        if (_state.value.downloading) return@withContext
        _state.value = _state.value.copy(downloading = true, progress = 0f, message = "Downloading roads…")
        folder.mkdirs()
        val todo = cells(lat, lon, radiusKm)
        var failures = 0
        todo.forEachIndexed { i, (la, lo) ->
            val dest = file(la, lo)
            if (!dest.exists()) {
                var ok = false
                for (attempt in 0 until 3) {
                    if (ok) break
                    try {
                        val c = URL(Overpass.ENDPOINT).openConnection() as HttpURLConnection
                        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 150000
                        c.setRequestProperty("User-Agent", "PocketNomad/1.0 (+https://pocketnomad.us)")
                        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        val q = Overpass.query(la / 20.0, lo / 20.0, (la + 1) / 20.0, (lo + 1) / 20.0)
                        c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                        if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                        val ways = Overpass.parse(c.inputStream.bufferedReader().readText())
                        val tmp = File(dest.path + ".part")
                        tmp.writeText(JSONArray(ways.map { it.toJson() }).toString())
                        tmp.renameTo(dest); ok = true
                    } catch (_: Exception) { delay(5000L * (attempt + 1)) }
                }
                if (!ok) failures++
                delay(1000) // be gentle with a free public service
            }
            _state.value = _state.value.copy(progress = (i + 1f) / max(1, todo.size))
        }
        loaded = null; graphs.clear()
        _state.value = State(count(), false, 1f, if (failures == 0) "Saved ${todo.size} squares." else "$failures squares failed (the free service can be busy). Try again later.")
    }

    fun deleteAll() { folder.deleteRecursively(); loaded = null; graphs.clear(); _state.value = State(0) }

    /** Plan a route off the main thread (the first call per mode builds the graph from the saved squares). */
    suspend fun plan(mode: TravelMode, from: ShapePoint, to: ShapePoint): Route? = withContext(Dispatchers.Default) {
        graph(mode)?.route(from, to)
    }

    @Synchronized
    fun graph(mode: TravelMode): RoadGraph? {
        graphs[mode]?.let { return it }
        if (loaded == null) {
            val seen = HashSet<Long>()
            loaded = (folder.listFiles { f -> f.extension == "json" } ?: emptyArray()).flatMap { f ->
                runCatching { JSONArray(f.readText()).let { a -> (0 until a.length()).map { RoadWay.fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
            }.filter { seen.add(it.id) }
        }
        val w = loaded ?: return null
        if (w.isEmpty()) return null
        return RoadGraph(w, mode).also { graphs[mode] = it }
    }

    companion object {
        fun cells(lat: Double, lon: Double, radiusKm: Double): List<Pair<Int, Int>> {
            val dLat = radiusKm / 111.32; val dLon = radiusKm / (111.32 * max(0.05, cos(Math.toRadians(lat))))
            val la0 = floor((lat - dLat) * 20).toInt(); val la1 = floor((lat + dLat) * 20).toInt()
            val lo0 = floor((lon - dLon) * 20).toInt(); val lo1 = floor((lon + dLon) * 20).toInt()
            return (la0..la1).flatMap { la -> (lo0..lo1).map { la to it } }
        }
        @Volatile private var shared: RoadStore? = null
        fun get(context: Context): RoadStore = shared ?: synchronized(this) { shared ?: RoadStore(context.applicationContext).also { shared = it } }
    }
}
