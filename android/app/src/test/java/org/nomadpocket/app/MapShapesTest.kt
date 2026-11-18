package org.nomadpocket.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.map.MapShape
import org.nomadpocket.app.map.ShapeMath
import org.nomadpocket.app.map.ShapePoint
import org.nomadpocket.app.map.ShapeStore
import java.io.File
import kotlin.math.cos

/** Measuring, areas, export and the mesh summary for hand-drawn map shapes. Mirrors MapShapesTests.swift. */
class MapShapesTest {
    private fun square(km: Double, lat: Double = 39.74, lon: Double = -104.99): List<ShapePoint> {
        val dLat = km / 111.19493
        val dLon = km / (111.19493 * cos(Math.toRadians(lat)))
        return listOf(ShapePoint(lat, lon), ShapePoint(lat, lon + dLon), ShapePoint(lat + dLat, lon + dLon), ShapePoint(lat + dLat, lon))
    }

    @Test fun lengthOfALineAndClosedShape() {
        val line = listOf(ShapePoint(39.74, -104.99), ShapePoint(39.74, -104.97))
        assertEquals(1710.0, ShapeMath.length(line, false), 15.0)
        val sq = square(1.0)
        assertEquals(3000.0, ShapeMath.length(sq, false), 15.0)
        assertEquals(4000.0, ShapeMath.length(sq, true), 20.0)
        assertEquals(0.0, ShapeMath.length(listOf(line[0]), false), 0.0)
        assertEquals(3, ShapeMath.legs(sq).size)
    }

    @Test fun areaOfKnownShapes() {
        assertEquals(1_000_000.0, ShapeMath.area(square(1.0)), 6000.0)
        assertEquals(10_000.0, ShapeMath.area(square(0.1)), 100.0)
        assertEquals(ShapeMath.area(square(1.0)), ShapeMath.area(square(1.0).reversed()), 1e-6)
        val degree = listOf(ShapePoint(0.0, 0.0), ShapePoint(0.0, 1.0), ShapePoint(1.0, 1.0), ShapePoint(1.0, 0.0))
        assertEquals(12_364.0, ShapeMath.area(degree) / 1e6, 40.0)
        assertEquals(0.0, ShapeMath.area(square(1.0).take(2)), 0.0)
        assertEquals(ShapeMath.area(square(1.0, 10.0, 10.0)), ShapeMath.area(square(1.0, 10.0, 179.995)), 3000.0)
    }

    @Test fun bearingAndCentre() {
        val a = ShapePoint(39.74, -104.99)
        assertEquals(0.0, ShapeMath.bearing(a, ShapePoint(39.84, -104.99)), 0.01)
        assertEquals(90.0, ShapeMath.bearing(a, ShapePoint(39.74, -104.89)), 0.5)
        assertEquals(180.0, ShapeMath.bearing(a, ShapePoint(39.64, -104.99)), 0.01)
        assertEquals(270.0, ShapeMath.bearing(a, ShapePoint(39.74, -105.09)), 0.5)
        assertEquals(39.74 + 0.5 / 111.19493, ShapeMath.centre(square(1.0)).latitude, 1e-6)
        assertEquals("225° SW", ShapeMath.bearingText(225.0))
        assertEquals("0° N", ShapeMath.bearingText(359.7))
    }

    @Test fun wordsFollowTheRegion() {
        assertEquals("850 m", ShapeMath.distanceText(850.0, true)); assertEquals("2.41 km", ShapeMath.distanceText(2410.0, true))
        assertEquals("328 ft", ShapeMath.distanceText(100.0, false)); assertEquals("1.50 mi", ShapeMath.distanceText(2410.0, false))
        assertEquals("1.00 ha", ShapeMath.areaText(10_000.0, true)); assertEquals("5.00 km²", ShapeMath.areaText(5_000_000.0, true))
        assertEquals("1.00 acres", ShapeMath.areaText(4046.8564, false)); assertEquals("1.93 mi²", ShapeMath.areaText(5_000_000.0, false))
    }

    @Test fun exportsAreValid() {
        val area = MapShape(name = "Camp & \"east\" <field>", colorIndex = 2, points = square(0.2), closed = true)
        val route = MapShape(name = "Walk out", points = square(1.0).take(3), closed = false)
        val root = JSONObject(ShapeMath.geoJson(listOf(area, route)))
        val features = root.getJSONArray("features")
        assertEquals(2, features.length())
        val poly = features.getJSONObject(0).getJSONObject("geometry")
        assertEquals("Polygon", poly.getString("type"))
        val ring = poly.getJSONArray("coordinates").getJSONArray(0)
        assertEquals(5, ring.length())
        assertEquals(ring.getJSONArray(0).toString(), ring.getJSONArray(4).toString())
        assertEquals(-104.99, ring.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals("#46C46E", features.getJSONObject(0).getJSONObject("properties").getString("color"))
        assertEquals("LineString", features.getJSONObject(1).getJSONObject("geometry").getString("type"))
        val gpx = ShapeMath.gpx(listOf(area, route))
        assertTrue(gpx.contains("Camp &amp; \"east\" &lt;field&gt;"))
        assertEquals(8, Regex("<trkpt").findAll(gpx).count())
    }

    @Test fun meshSummaryFitsAndCarriesThePlace() {
        val area = MapShape(name = "Camp perimeter", points = square(0.2), closed = true)
        val text = ShapeMath.meshSummary(area, false, 133)
        assertTrue(text, text.startsWith("Camp perimeter: 9.88 acres"))
        assertTrue(text.contains("centre 39.7")); assertTrue(text.contains("DM79"))
        val long = MapShape(name = "Very long name ".repeat(20), points = square(0.2), closed = true)
        assertTrue(ShapeMath.meshSummary(long, true, 133).toByteArray().size <= 133)
    }

    @Test fun storePersistsAndEdits() {
        val f = File.createTempFile("shapes", ".json"); f.deleteOnExit()
        f.delete()
        val store = ShapeStore(f)
        val s = MapShape(name = "A", colorIndex = 1, points = square(0.5), closed = true)
        store.add(s); store.rename(s.id, "Renamed"); store.recolor(s.id, 4)
        val again = ShapeStore(f)
        assertEquals(1, again.shapes.size)
        assertEquals("Renamed", again.shapes[0].name); assertEquals(4, again.shapes[0].colorIndex)
        assertEquals(s.points, again.shapes[0].points)
        again.delete(s.id)
        assertTrue(ShapeStore(f).shapes.isEmpty())
    }
}
