// Terrain.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Elevation from open "terrarium" tiles (AWS Open Data, no key): hillshade on the map and
// the terrain under a radio path. Port of Terrain.swift.

package org.nomadpocket.app.map

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object TerrainMath {
    /** Fractional slippy-map tile coordinates. */
    fun tileXY(latitude: Double, longitude: Double, zoom: Int): Pair<Double, Double> {
        val n = Math.pow(2.0, zoom.toDouble())
        val lat = max(-85.0511, min(85.0511, latitude))
        val x = (longitude + 180) / 360 * n
        val y = (1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * n
        return x to y
    }

    /** Terrarium: metres = R*256 + G + B/256 - 32768. */
    fun terrarium(r: Int, g: Int, b: Int): Double = r * 256.0 + g + b / 256.0 - 32768

    fun bilinear(q00: Double, q10: Double, q01: Double, q11: Double, fx: Double, fy: Double): Double =
        (q00 * (1 - fx) + q10 * fx) * (1 - fy) + (q01 * (1 - fx) + q11 * fx) * fy

    /** Tiles covering a circle's bounding box at one zoom. */
    fun tiles(latitude: Double, longitude: Double, radiusKm: Double, zoom: Int): List<Pair<Int, Int>> {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * max(0.05, cos(Math.toRadians(latitude))))
        val a = tileXY(latitude + dLat, longitude - dLon, zoom)
        val b = tileXY(latitude - dLat, longitude + dLon, zoom)
        val n = 1 shl zoom
        val x0 = max(0, a.first.toInt()); val x1 = min(n - 1, b.first.toInt())
        val y0 = max(0, a.second.toInt()); val y1 = min(n - 1, b.second.toInt())
        if (x0 > x1 || y0 > y1) return emptyList()
        return (y0..y1).flatMap { y -> (x0..x1).map { it to y } }
    }
}

/** How the map looks: plain streets, or streets over shaded relief. */
enum class MapLook(val label: String, val blurb: String, val exaggeration: Float) {
    STREETS("Streets", "Roads and places.", 0f),
    TOPO("Topo", "Roads with hills shaded underneath.", 0.35f),
    RELIEF("Relief", "Hills, strongly shaded. Best for judging terrain.", 0.9f);
}

class TerrainStore(private val context: Context) {
    val folder = File(context.filesDir, "terrain")
    private val _state = MutableStateFlow(State(count(folder)))
    val state: StateFlow<State> = _state

    data class State(val tileCount: Int, val downloading: Boolean = false, val progress: Float = 0f, val message: String = "")

    val hasTiles get() = _state.value.tileCount > 0
    /** What the map's elevation source reads: the saved tiles, else the live ones. */
    val template: String get() = if (hasTiles) "file://${folder.absolutePath}/{z}/{x}/{y}.png" else REMOTE

    private fun count(f: File) = f.walkTopDown().count { it.isFile && it.extension == "png" }
    fun file(z: Int, x: Int, y: Int) = File(folder, "$z/$x/$y.png")

    fun plan(lat: Double, lon: Double, radiusKm: Double): List<Triple<Int, Int, Int>> =
        ZOOMS.flatMap { z -> TerrainMath.tiles(lat, lon, radiusKm, z).map { Triple(z, it.first, it.second) } }

    fun estimateMB(lat: Double, lon: Double, radiusKm: Double) = plan(lat, lon, radiusKm).size * 0.11

    suspend fun download(lat: Double, lon: Double, radiusKm: Double) = withContext(Dispatchers.IO) {
        if (_state.value.downloading) return@withContext
        _state.value = _state.value.copy(downloading = true, progress = 0f, message = "Downloading elevation…")
        val todo = plan(lat, lon, radiusKm)
        var failures = 0
        todo.forEachIndexed { i, (z, x, y) ->
            val dest = file(z, x, y)
            if (!dest.exists()) {
                try {
                    val c = URL(REMOTE.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "PocketNomad/1.0 (+https://pocketnomad.us)")
                    c.connectTimeout = 15000; c.readTimeout = 20000
                    if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.path + ".part")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(dest)
                } catch (_: Exception) { failures++ }
            }
            _state.value = _state.value.copy(progress = (i + 1f) / max(1, todo.size))
        }
        _state.value = State(count(folder), false, 1f,
            if (failures == 0) "Saved ${todo.size} tiles." else "Saved with $failures tiles missing. Try again with signal.")
    }

    fun deleteAll() { folder.deleteRecursively(); cache.clear(); _state.value = State(0) }

    private val cache = LinkedHashMap<String, FloatArray>()

    private fun samples(z: Int, x: Int, y: Int): FloatArray? {
        val key = "$z/$x/$y"
        cache[key]?.let { return it }
        val f = file(z, x, y)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        if (bmp.width != 256 || bmp.height != 256) return null
        val px = IntArray(256 * 256); bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        val out = FloatArray(256 * 256) { i -> val p = px[i]; TerrainMath.terrarium((p shr 16) and 255, (p shr 8) and 255, p and 255).toFloat() }
        if (cache.size > 24) cache.clear()
        cache[key] = out
        return out
    }

    /** Metres above sea level at a point from the saved tiles, or null with no data there. */
    @Synchronized
    fun elevation(latitude: Double, longitude: Double): Double? {
        val z = ZOOMS.last
        val t = TerrainMath.tileXY(latitude, longitude, z)
        fun px(gx: Int, gy: Int): Double? {
            val tx = floor(gx / 256.0).toInt(); val ty = floor(gy / 256.0).toInt()
            val s = samples(z, tx, ty) ?: return null
            return s[(gy - ty * 256) * 256 + (gx - tx * 256)].toDouble()
        }
        val fx = t.first * 256 - 0.5; val fy = t.second * 256 - 0.5
        val gx = floor(fx).toInt(); val gy = floor(fy).toInt()
        val a = px(gx, gy) ?: return null; val b = px(gx + 1, gy) ?: return null
        val c = px(gx, gy + 1) ?: return null; val d = px(gx + 1, gy + 1) ?: return null
        return TerrainMath.bilinear(a, b, c, d, fx - gx, fy - gy)
    }

    companion object {
        val ZOOMS = 6..11
        const val REMOTE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        @Volatile private var shared: TerrainStore? = null
        fun get(context: Context): TerrainStore =
            shared ?: synchronized(this) { shared ?: TerrainStore(context.applicationContext).also { shared = it } }
    }
}
