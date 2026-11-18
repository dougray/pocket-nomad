// MapShapes.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Lines and areas the user draws on the map: measuring, naming, saving,
// exporting (GeoJSON, GPX) and a short summary for the mesh. All offline.
// Port of MapShapes.swift; the geometry is pure and unit-tested.

package org.nomadpocket.app.map

import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.ham.HamGeo
import org.nomadpocket.mesh.MeshText
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class ShapePoint(val latitude: Double, val longitude: Double)

data class MapShape(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Index into [ShapeMath.PALETTE]. */
    val colorIndex: Int = 0,
    val points: List<ShapePoint>,
    /** A closed shape is an area; an open one is a route or a line. */
    val closed: Boolean,
    val created: Long = System.currentTimeMillis(),
) {
    val isArea: Boolean get() = closed && points.size >= 3

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", colorIndex).put("closed", closed).put("created", created)
        .put("points", JSONArray(points.map { JSONArray(listOf(it.latitude, it.longitude)) }))

    companion object {
        fun fromJson(o: JSONObject): MapShape {
            val a = o.getJSONArray("points")
            return MapShape(o.getString("id"), o.getString("name"), o.optInt("color"),
                (0 until a.length()).map { val p = a.getJSONArray(it); ShapePoint(p.getDouble(0), p.getDouble(1)) }, o.optBoolean("closed"), o.optLong("created"))
        }
    }
}

object ShapeMath {
    /** Name, 0xRRGGBB: orange, blue, green, red, purple, yellow. */
    val PALETTE = listOf("Orange" to 0xFC9712, "Blue" to 0x4F8DF5, "Green" to 0x46C46E, "Red" to 0xE5484D, "Purple" to 0xAF52DE, "Yellow" to 0xFFD43B)
    private const val EARTH_RADIUS = 6_371_008.8

    private fun dist(a: ShapePoint, b: ShapePoint) = KnownRadioParser.distance(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Metres along the line; a closed shape includes the leg back to the start. */
    fun length(p: List<ShapePoint>, closed: Boolean): Double {
        if (p.size < 2) return 0.0
        var total = (1 until p.size).sumOf { dist(p[it - 1], p[it]) }
        if (closed && p.size >= 3) total += dist(p.last(), p.first())
        return total
    }

    fun legs(p: List<ShapePoint>): List<Double> = if (p.size < 2) emptyList() else (1 until p.size).map { dist(p[it - 1], p[it]) }

    /** Square metres enclosed by a polygon (spherical, Chamberlain-Duquette). Order and winding don't matter. */
    fun area(p: List<ShapePoint>): Double {
        if (p.size < 3) return 0.0
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            var dLon = Math.toRadians(b.longitude - a.longitude)
            if (dLon > PI) dLon -= 2 * PI else if (dLon < -PI) dLon += 2 * PI
            sum += dLon * (2 + sin(Math.toRadians(a.latitude)) + sin(Math.toRadians(b.latitude)))
        }
        return abs(sum) * EARTH_RADIUS * EARTH_RADIUS / 2
    }

    fun bearing(a: ShapePoint, b: ShapePoint): Double {
        val l1 = Math.toRadians(a.latitude); val l2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(l2)
        val x = cos(l1) * sin(l2) - sin(l1) * cos(l2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun centre(p: List<ShapePoint>): ShapePoint =
        if (p.isEmpty()) ShapePoint(0.0, 0.0) else ShapePoint(p.sumOf { it.latitude } / p.size, p.sumOf { it.longitude } / p.size)

    // MARK: Words

    /** A short height, in metres or feet. */
    fun heightText(metres: Double, metric: Boolean): String =
        if (metric) String.format(Locale.US, "%.0f m", metres) else String.format(Locale.US, "%.0f ft", metres * 3.28084)

    fun distanceText(metres: Double, metric: Boolean): String {
        if (metric) return if (metres < 1000) "${metres.roundToInt()} m" else String.format(Locale.US, "%.2f km", metres / 1000)
        val feet = metres * 3.28084
        return if (feet < 528) "${feet.roundToInt()} ft" else String.format(Locale.US, "%.2f mi", metres / 1609.344)
    }

    fun areaText(squareMetres: Double, metric: Boolean): String {
        if (metric) return if (squareMetres < 1_000_000) String.format(Locale.US, "%.2f ha", squareMetres / 10_000) else String.format(Locale.US, "%.2f km²", squareMetres / 1_000_000)
        val acres = squareMetres / 4046.8564224
        return if (acres < 640) String.format(Locale.US, "%.2f acres", acres) else String.format(Locale.US, "%.2f mi²", squareMetres / 2_589_988.110336)
    }

    fun bearingText(degrees: Double): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return "${degrees.roundToInt() % 360}° ${dirs[((degrees + 22.5) / 45).toInt() % 8]}"
    }

    /** "Camp perimeter: 9.88 acres, 0.50 mi around, centre 39.7409,-104.9888 DM79mr", trimmed to fit a mesh message. */
    fun meshSummary(s: MapShape, metric: Boolean, maxBytes: Int): String {
        val c = centre(s.points)
        val place = String.format(Locale.US, "%.4f,%.4f", c.latitude, c.longitude) + " " + HamGeo.maidenhead(c.latitude, c.longitude)
        val size = if (s.isArea) "${areaText(area(s.points), metric)}, ${distanceText(length(s.points, true), metric)} around"
                   else distanceText(length(s.points, false), metric)
        return MeshText.truncated("${s.name}: $size, centre $place", maxBytes)
    }

    // MARK: Export

    fun geoJson(shapes: List<MapShape>): String {
        val features = JSONArray(shapes.map { s ->
            val ring = JSONArray(s.points.map { JSONArray(listOf(it.longitude, it.latitude)) })
            val geometry = if (s.isArea) {
                ring.put(JSONArray(listOf(s.points[0].longitude, s.points[0].latitude)))
                JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
            } else JSONObject().put("type", "LineString").put("coordinates", ring)
            val props = JSONObject().put("name", s.name).put("color", String.format(Locale.US, "#%06X", PALETTE[s.colorIndex % PALETTE.size].second))
            if (s.isArea) props.put("area_m2", Math.round(area(s.points))) else props.put("length_m", Math.round(length(s.points, false)))
            JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)
        })
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    fun gpx(shapes: List<MapShape>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Pocket Nomad\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (s in shapes) {
            val pts = if (s.isArea) s.points + s.points[0] else s.points
            sb.append("  <trk><name>${esc(s.name)}</name><trkseg>\n")
            for (p in pts) sb.append(String.format(Locale.US, "    <trkpt lat=\"%.6f\" lon=\"%.6f\"/>\n", p.latitude, p.longitude))
            sb.append("  </trkseg></trk>\n")
        }
        return sb.append("</gpx>\n").toString()
    }
}

/** Saved drawings, one JSON file in the app's private storage. */
class ShapeStore(private val file: File) {
    var shapes: List<MapShape> = load()
        private set

    private fun load(): List<MapShape> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { MapShape.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun save() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(JSONArray(shapes.map { it.toJson() }).toString())
        tmp.renameTo(file)
    }

    fun add(s: MapShape) { shapes = shapes + s; save() }
    fun rename(id: String, name: String) { shapes = shapes.map { if (it.id == id) it.copy(name = name) else it }; save() }
    fun recolor(id: String, index: Int) { shapes = shapes.map { if (it.id == id) it.copy(colorIndex = index) else it }; save() }
    fun delete(id: String) { shapes = shapes.filterNot { it.id == id }; save() }
    fun deleteAll() { shapes = emptyList(); save() }
}
