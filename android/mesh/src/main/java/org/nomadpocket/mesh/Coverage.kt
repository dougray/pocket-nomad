// Coverage.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The coverage scanner: each time the radio hears another node, note where the
// phone was. That maps where the mesh reaches, which is how you pick spots for
// repeaters. Everything stays on the phone unless the user turns sharing on,
// and even then only a privacy-filtered summary (hexagons, no node names or
// IDs, no times finer than a month, private zones blanked) is ever written out.
//
// Ported from App/Sources/Mesh/Coverage.swift. The grid maths and GeoJSON must
// stay identical to it and to tools/coverage-merge.py (CoverageGoldenTest
// checks that).

package org.nomadpocket.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A GPS fix. `accuracy` is horizontal accuracy in metres (negative = invalid). */
data class Fix(val latitude: Double, val longitude: Double, val accuracy: Double, val timeMillis: Long)

/** One "heard a node here" moment. Kept only on this phone. */
data class CoverageRecord(
    val dateMillis: Long,
    val latitude: Double,
    val longitude: Double,
    /** GPS accuracy in metres. */
    val accuracy: Double,
    val firmware: Firmware,
    val node: Long,
    val snr: Float?,
    val hops: Long?,
)

/** One drive-test ping: where the phone was when it was sent, and what came back. */
data class PingRecord(
    val dateMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val firmware: Firmware,
    val outcome: PingOutcome,
)

/** A place never to share anything from (home, work). */
data class CoverageZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean = CoverageShare.distance(latitude, longitude, lat, lon) <= radius
}

/** A live "the radio just heard this node" event from MeshService. */
data class MeshHeard(val node: Long, val snr: Float?, val hops: Long?, val firmware: Firmware)

class CoverageStore(
    private val prefs: MeshPrefs,
    private val file: File,
    private val scope: CoroutineScope,
    private val location: () -> Fix?,
    private val nodeName: (Long) -> String = { "" },
    private val now: () -> Long = System::currentTimeMillis,
    /** Called with true when GPS is needed (recording on) and false when not. */
    private val useLocation: (Boolean) -> Unit = {},
) {
    private val pingsFile = File(file.parentFile, file.nameWithoutExtension + ".pings.json")

    private val _samples = MutableStateFlow<List<CoverageRecord>>(emptyList())
    private val _pings = MutableStateFlow<List<PingRecord>>(emptyList())
    private val _zones = MutableStateFlow<List<CoverageZone>>(emptyList())
    private val _recording = MutableStateFlow(false)
    private val _sharing = MutableStateFlow(false)
    private val _retention = MutableStateFlow(30)

    val samplesFlow: StateFlow<List<CoverageRecord>> = _samples.asStateFlow()
    val pingsFlow: StateFlow<List<PingRecord>> = _pings.asStateFlow()
    val zonesFlow: StateFlow<List<CoverageZone>> = _zones.asStateFlow()
    val recordingFlow: StateFlow<Boolean> = _recording.asStateFlow()
    val sharingFlow: StateFlow<Boolean> = _sharing.asStateFlow()
    val retentionFlow: StateFlow<Int> = _retention.asStateFlow()

    val samples: List<CoverageRecord> get() = _samples.value
    val pings: List<PingRecord> get() = _pings.value
    val zones: List<CoverageZone> get() = _zones.value

    /** Off by default. Needs location while the app is open. */
    var recording: Boolean
        get() = _recording.value
        set(v) {
            _recording.value = v
            prefs.putBoolean(Keys.RECORDING, v)
            updateLocationUse()
        }

    /** Off by default; can only be turned on once at least one private zone exists. */
    var sharing: Boolean
        get() = _sharing.value
        set(v) {
            val value = v && zones.isNotEmpty()
            _sharing.value = value
            if (value || !v) prefs.putBoolean(Keys.SHARING, value)
        }

    var retentionDays: Int
        get() = _retention.value
        set(v) {
            _retention.value = v
            prefs.putInt(Keys.RETENTION, v)
            prune()
        }

    private var usingLocation = false
    private var saveJob: Job? = null

    private object Keys {
        const val RECORDING = "coverage.recording"
        const val SHARING = "coverage.sharing"
        const val RETENTION = "coverage.retentionDays"
        const val ZONES = "coverage.zones"
    }

    init {
        _recording.value = prefs.getBoolean(Keys.RECORDING, false)
        _sharing.value = prefs.getBoolean(Keys.SHARING, false)
        _retention.value = prefs.getInt(Keys.RETENTION, 30)
        _zones.value = prefs.getString(Keys.ZONES)?.let { runCatching { decodeZones(it) }.getOrNull() } ?: emptyList()
        _samples.value = runCatching { decodeSamples(file.readText()) }.getOrElse { emptyList() }
        _pings.value = runCatching { decodePings(pingsFile.readText()) }.getOrElse { emptyList() }
        if (zones.isEmpty()) _sharing.value = false
        prune()
    }

    /** Starts GPS when recording is on (call once at launch). */
    fun start() = updateLocationUse()

    private fun updateLocationUse() {
        if (recording && !usingLocation) { usingLocation = true; useLocation(true) }
        if (!recording && usingLocation) { usingLocation = false; useLocation(false) }
    }

    /**
     * Notes where the phone is now. Ignored when recording is off or there's no
     * fresh, reasonably accurate fix; one sample per node per 20 s unless the
     * phone has moved more than 25 m.
     */
    fun record(heard: MeshHeard) {
        if (!recording || optedOut(heard.node)) return
        val fix = freshFix() ?: return
        val t = now()
        val last = samples.lastOrNull { it.node == heard.node }
        if (last != null && t - last.dateMillis < 20_000 &&
            CoverageShare.distance(last.latitude, last.longitude, fix.latitude, fix.longitude) < 25) return
        _samples.value = samples + CoverageRecord(t, fix.latitude, fix.longitude, fix.accuracy,
            heard.firmware, heard.node, heard.snr, heard.hops)
        scheduleSave()
    }

    /** A GPS fix from the last 30 s that is good to 100 m, or null. */
    fun freshFix(): Fix? {
        val fix = location() ?: return null
        if (now() - fix.timeMillis >= 30_000 || fix.accuracy < 0 || fix.accuracy > 100) return null
        return fix
    }

    /** Adds a drive-test result at the place the ping was sent from. */
    fun recordPing(outcome: PingOutcome, fix: Fix, firmware: Firmware) {
        _pings.value = pings + PingRecord(now(), fix.latitude, fix.longitude, fix.accuracy, firmware, outcome)
        scheduleSave()
    }

    fun optedOut(node: Long): Boolean = OptOut.optsOut(nodeName(node))

    fun deleteAll() {
        _samples.value = emptyList()
        _pings.value = emptyList()
        saveNow()
    }

    fun addZone(name: String, latitude: Double, longitude: Double, radius: Double) {
        _zones.value = zones + CoverageZone(name = name, latitude = latitude, longitude = longitude, radius = radius)
        saveZones()
    }

    fun removeZones(indices: Collection<Int>) {
        _zones.value = zones.filterIndexed { i, _ -> i !in indices }
        saveZones()
        if (zones.isEmpty()) sharing = false
    }

    fun removeZone(id: String) {
        val i = zones.indexOfFirst { it.id == id }
        if (i >= 0) removeZones(listOf(i))
    }

    /** What sharing would publish, built from the current samples and zones. */
    val sharedCells: List<CoverageShare.Cell>
        get() = CoverageShare.cells(samples.filterNot { optedOut(it.node) }, pings, zones)

    /** Writes the privacy-filtered GeoJSON into `directory` for the share sheet. */
    fun exportFile(directory: File): File {
        if (!sharing) throw IllegalStateException("Sharing is off.")
        directory.mkdirs()
        val out = File(directory, "pocket-nomad-coverage.geojson")
        atomicWrite(out, CoverageShare.geoJSON(sharedCells))
        return out
    }

    /** Screenshots and demos: a drive around a pretend tower at a made-up place. */
    fun loadDemo() {
        val towerLat = 10.1480
        val towerLon = -20.3970
        val rng = kotlin.random.Random(7)
        val base = now() - 3 * 86_400_000L
        val out = mutableListOf<CoverageRecord>()
        for (route in 0 until 6) {
            val angle = route * PI / 3 + 0.4
            for (step in 0 until 40) {
                val d = step * 0.0011
                val lat = towerLat + d * sin(angle) + rng.nextDouble(-0.0004, 0.0004)
                val lon = towerLon + d * cos(angle) * 1.3 + rng.nextDouble(-0.0004, 0.0004)
                val km = CoverageShare.distance(towerLat, towerLon, lat, lon) / 1000
                val snr = (9 - km * 5 + rng.nextDouble(-2.0, 2.0)).toFloat()
                if (snr < -18) continue
                out.add(CoverageRecord(base + (route * 40 + step) * 30_000L, lat, lon, 6.0,
                    if (route % 2 == 0) Firmware.MESHTASTIC else Firmware.MESHCORE, 0xA000L + route % 3, snr, 0))
            }
        }
        _samples.value = out
        _zones.value = listOf(CoverageZone(name = "Home (demo)", latitude = 10.1392, longitude = -20.3849, radius = 500.0))
        saveZones()
        saveNow()
    }

    private fun prune() {
        val cutoff = now() - retentionDays * 86_400_000L
        val s = samples.filterNot { it.dateMillis < cutoff }
        val p = pings.filterNot { it.dateMillis < cutoff }
        if (s.size != samples.size || p.size != pings.size) {
            _samples.value = s
            _pings.value = p
            scheduleSave()
        }
    }

    private fun saveZones() {
        prefs.putString(Keys.ZONES, JSONArray().apply {
            zones.forEach { z ->
                put(JSONObject().put("id", z.id).put("name", z.name).put("latitude", z.latitude)
                    .put("longitude", z.longitude).put("radius", z.radius))
            }
        }.toString())
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            saveNow()
        }
    }

    fun saveNow() {
        try {
            atomicWrite(file, encodeSamples(samples))
            atomicWrite(pingsFile, encodePings(pings))
        } catch (_: Exception) {}
    }

    private companion object {
        fun decodeZones(s: String): List<CoverageZone> {
            val a = JSONArray(s)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                CoverageZone(o.getString("id"), o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"), o.getDouble("radius"))
            }
        }

        fun encodeSamples(list: List<CoverageRecord>): String = JSONArray().apply {
            list.forEach { s ->
                put(JSONObject().put("date", s.dateMillis).put("latitude", s.latitude).put("longitude", s.longitude)
                    .put("accuracy", s.accuracy).put("firmware", s.firmware.rawValue).put("node", s.node).apply {
                        s.snr?.let { put("snr", it.toDouble()) }
                        s.hops?.let { put("hops", it) }
                    })
            }
        }.toString()

        fun decodeSamples(s: String): List<CoverageRecord> {
            val a = JSONArray(s)
            return (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                val fw = firmwareOf(o.optString("firmware")) ?: return@mapNotNull null
                CoverageRecord(o.getLong("date"), o.getDouble("latitude"), o.getDouble("longitude"), o.getDouble("accuracy"),
                    fw, o.getLong("node"), if (o.has("snr")) o.getDouble("snr").toFloat() else null,
                    if (o.has("hops")) o.getLong("hops") else null)
            }
        }

        fun encodePings(list: List<PingRecord>): String = JSONArray().apply {
            list.forEach { p ->
                put(JSONObject().put("date", p.dateMillis).put("latitude", p.latitude).put("longitude", p.longitude)
                    .put("accuracy", p.accuracy).put("firmware", p.firmware.rawValue).put("outcome", p.outcome.rawValue))
            }
        }.toString()

        fun decodePings(s: String): List<PingRecord> {
            val a = JSONArray(s)
            return (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                val fw = firmwareOf(o.optString("firmware")) ?: return@mapNotNull null
                val outcome = pingOutcomeOf(o.optString("outcome")) ?: return@mapNotNull null
                PingRecord(o.getLong("date"), o.getDouble("latitude"), o.getDouble("longitude"), o.getDouble("accuracy"), fw, outcome)
            }
        }
    }
}

/**
 * Turns private samples into the shareable summary. Pure functions, so the
 * privacy rules are easy to test.
 */
object CoverageShare {
    /**
     * Hexagon size on a global Web Mercator grid: the same everywhere, so files
     * from different people line up. Must match tools/coverage-merge.py.
     */
    const val CELL_SIZE = 400.0
    private const val EARTH_RADIUS = 6_378_137.0
    const val GENERATOR = "Pocket Nomad coverage export"

    data class Cell(
        val q: Int,
        val r: Int,
        val count: Int,
        val bestSNR: Float?,
        val firmwares: Set<Firmware>,
        val pingsReached: Int = 0,
        val pingsRelayed: Int = 0,
        val pingsNone: Int = 0,
        val firstMonth: String,
        val lastMonth: String,
    ) {
        val center: Pair<Double, Double> get() = center(q, r)
        val corners: List<Pair<Double, Double>> get() = corners(q, r)
    }

    private fun monthOf(millis: Long): String = SimpleDateFormat("yyyy-MM", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

    /**
     * Bins samples into hexagons, dropping anything in or near a private zone:
     * both samples inside a zone and whole cells that touch one, so a cell that
     * straddles your street doesn't give it away.
     */
    fun cells(samples: List<CoverageRecord>, pings: List<PingRecord> = emptyList(), zones: List<CoverageZone>): List<Cell> {
        val reach = CELL_SIZE // a cell's corners are at most one cell size from its centre
        val bins = mutableMapOf<Pair<Int, Int>, Cell>()
        fun add(lat: Double, lon: Double, date: Long, firmware: Firmware, update: (Cell) -> Cell) {
            if (zones.any { it.contains(lat, lon) }) return
            val (q, r) = hex(lat, lon)
            val (clat, clon) = center(q, r)
            if (zones.any { distance(it.latitude, it.longitude, clat, clon) <= it.radius + reach }) return
            val m = monthOf(date)
            val cell = update(bins[q to r] ?: Cell(q, r, 0, null, emptySet(), firstMonth = m, lastMonth = m))
            bins[q to r] = cell.copy(
                firmwares = cell.firmwares + firmware,
                firstMonth = minOf(cell.firstMonth, m),
                lastMonth = maxOf(cell.lastMonth, m),
            )
        }
        for (s in samples) {
            add(s.latitude, s.longitude, s.dateMillis, s.firmware) { c ->
                c.copy(count = c.count + 1, bestSNR = s.snr?.let { maxOf(c.bestSNR ?: Float.NEGATIVE_INFINITY, it) } ?: c.bestSNR)
            }
        }
        for (p in pings) {
            add(p.latitude, p.longitude, p.dateMillis, p.firmware) { c ->
                when (p.outcome) {
                    PingOutcome.REACHED -> c.copy(pingsReached = c.pingsReached + 1)
                    PingOutcome.RELAYED -> c.copy(pingsRelayed = c.pingsRelayed + 1)
                    PingOutcome.NONE -> c.copy(pingsNone = c.pingsNone + 1)
                }
            }
        }
        return bins.values.sortedWith(compareBy({ it.q }, { it.r }))
    }

    /**
     * GeoJSON hexagons with only best signal, count, drive-test ping counts,
     * firmware and month range. Keys sorted, as the iOS export writes them.
     */
    fun geoJSON(cells: List<Cell>): String {
        val features = cells.map { cell ->
            val ring = cell.corners.map { listOf(round6(it.second), round6(it.first)) }.toMutableList()
            ring.add(ring[0])
            val props = sortedMapOf<String, Any>(
                "count" to cell.count,
                "firmware" to cell.firmwares.map { it.rawValue }.sorted(),
                "first_month" to cell.firstMonth,
                "last_month" to cell.lastMonth,
            )
            cell.bestSNR?.let { props["best_snr"] = roundAway(it.toDouble() * 10) / 10 }
            if (cell.pingsReached + cell.pingsRelayed + cell.pingsNone > 0) {
                props["pings_reached"] = cell.pingsReached
                props["pings_relayed"] = cell.pingsRelayed
                props["pings_none"] = cell.pingsNone
            }
            sortedMapOf<String, Any>(
                "type" to "Feature",
                "geometry" to sortedMapOf<String, Any>("type" to "Polygon", "coordinates" to listOf(ring)),
                "properties" to props,
            )
        }
        val doc = sortedMapOf<String, Any>(
            "type" to "FeatureCollection",
            "generator" to GENERATOR,
            "cell_size_mercator_m" to CELL_SIZE,
            "features" to features,
        )
        return Json.write(doc)
    }

    // MARK: Geometry

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dp = p2 - p1
        val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun mercator(lat: Double, lon: Double): Pair<Double, Double> {
        val clamped = maxOf(-85.0, minOf(85.0, lat))
        return EARTH_RADIUS * lon * PI / 180 to EARTH_RADIUS * ln(tan(PI / 4 + clamped * PI / 360))
    }

    private fun unmercator(x: Double, y: Double): Pair<Double, Double> =
        (2 * atan(exp(y / EARTH_RADIUS)) - PI / 2) * 180 / PI to x / EARTH_RADIUS * 180 / PI

    /** Pointy-top axial hex coordinates (q, r) for a point. */
    fun hex(latitude: Double, longitude: Double): Pair<Int, Int> {
        val (x, y) = mercator(latitude, longitude)
        val fq = (sqrt(3.0) / 3 * x - y / 3) / CELL_SIZE
        val fr = (2.0 / 3 * y) / CELL_SIZE
        // cube rounding
        val fs = -fq - fr
        var q = roundAway(fq)
        var r = roundAway(fr)
        val s = roundAway(fs)
        val dq = abs(q - fq)
        val dr = abs(r - fr)
        val ds = abs(s - fs)
        if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
        return q.toInt() to r.toInt()
    }

    /** (latitude, longitude) of a cell's centre. */
    fun center(q: Int, r: Int): Pair<Double, Double> {
        val x = CELL_SIZE * sqrt(3.0) * (q + r / 2.0)
        val y = CELL_SIZE * 1.5 * r
        return unmercator(x, y)
    }

    /** (latitude, longitude) of a cell's six corners, in the app's order. */
    fun corners(q: Int, r: Int): List<Pair<Double, Double>> {
        val cx = CELL_SIZE * sqrt(3.0) * (q + r / 2.0)
        val cy = CELL_SIZE * 1.5 * r
        return (0 until 6).map { i ->
            val a = (60.0 * i - 30) * PI / 180
            unmercator(cx + CELL_SIZE * cos(a), cy + CELL_SIZE * sin(a))
        }
    }

    /** Swift's `.rounded()`: halves round away from zero. */
    private fun roundAway(v: Double): Double = if (v < 0) -floor(-v + 0.5) else floor(v + 0.5)

    private fun round6(v: Double): Double = roundAway(v * 1e6) / 1e6
}

/** Radios whose owners put a no-mapping sign in their name are never recorded, shared or shown. */
object OptOut {
    /**
     * A convention used by community mesh maps. Checked by Unicode scalar, so the
     * emoji counts with or without a variation selector.
     */
    val marks: Set<Int> = setOf(0x26D4, 0x1F6D1, 0x1F6AB) // ⛔ 🛑 🚫

    fun optsOut(name: String): Boolean = name.codePoints().anyMatch { it in marks }
}

/** A tiny deterministic JSON writer (sorted maps in, two-space pretty output). */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.append('\n').toString()

    private fun write(sb: StringBuilder, v: Any?, indent: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> number(sb, v.toDouble())
            is Double -> number(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                v.entries.forEachIndexed { i, (k, value) ->
                    pad(sb, indent + 1); string(sb, k.toString()); sb.append(" : ")
                    write(sb, value, indent + 1)
                    sb.append(if (i < v.size - 1) ",\n" else "\n")
                }
                pad(sb, indent); sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.forEachIndexed { i, value ->
                    pad(sb, indent + 1); write(sb, value, indent + 1)
                    sb.append(if (i < v.size - 1) ",\n" else "\n")
                }
                pad(sb, indent); sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun number(sb: StringBuilder, d: Double) {
        require(d.isFinite()) { "JSON cannot hold $d" }
        if (d == floor(d) && abs(d) < 1e15) sb.append(d.toLong()) else sb.append(java.math.BigDecimal(d.toString()).toPlainString())
    }

    private fun pad(sb: StringBuilder, n: Int) { repeat(n) { sb.append("  ") } }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
