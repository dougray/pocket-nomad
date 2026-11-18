package org.nomadpocket.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.nomadpocket.app.map.Coordinates
import org.nomadpocket.app.map.PositionFormat
import org.nomadpocket.ham.HamGeo

/** Coordinate parsing and conversion, checked against the Python utm and mgrs libraries. Mirrors CoordinatesTests.swift. */
class CoordinatesTest {
    private val reference: List<JSONObject> by lazy {
        val a = JSONArray(javaClass.classLoader!!.getResourceAsStream("coordinates/coord-reference.json")!!.readBytes().toString(Charsets.UTF_8))
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun utmAndMgrsMatchTheReferenceLibraries() {
        for (p in reference) {
            val lat = p.getDouble("lat"); val lon = p.getDouble("lon")
            val u = Coordinates.utm(lat, lon)!!
            assertEquals("zone $lat,$lon", p.getInt("zone"), u.zone)
            assertEquals("band $lat,$lon", p.getString("letter"), u.band.toString())
            assertEquals("easting $lat,$lon", p.getDouble("easting"), u.easting, 0.05)
            assertEquals("northing $lat,$lon", p.getDouble("northing"), u.northing, 0.05)
            assertEquals("MGRS $lat,$lon", p.getString("mgrs"), Coordinates.mgrs(lat, lon))
        }
    }

    @Test fun roundTripsBackToLatLon() {
        for (p in reference) {
            val back = Coordinates.fromMGRS(p.getString("mgrs"))!!
            assertEquals(p.getDouble("lat"), back.first, 2e-5); assertEquals(p.getDouble("lon"), back.second, 3e-5)
            val r = Coordinates.latLon(Coordinates.UTM(p.getInt("zone"), p.getString("letter")[0], p.getDouble("easting"), p.getDouble("northing")))
            assertEquals(p.getDouble("lat"), r.first, 5e-6); assertEquals(p.getDouble("lon"), r.second, 5e-6)
        }
    }

    @Test fun lowerPrecisionMgrsNamesASquare() {
        val sq = Coordinates.fromMGRS("13SED")!!
        assertEquals(39.7, sq.first, 0.5); assertEquals(-105.0, sq.second, 0.6)
        assertEquals(39.739, Coordinates.fromMGRS("13S ED 00 98")!!.first, 0.01)
        assertNull(Coordinates.fromMGRS("13SED123")); assertNull(Coordinates.fromMGRS("99SED0000")); assertNull(Coordinates.mgrs(85.0, 0.0))
    }

    @Test fun parsesCommonWaysOfWritingAPlace() {
        val dms = 39 + 44.0 / 60 + 21.1 / 3600; val dmsLon = -(104 + 59.0 / 60 + 25.1 / 3600)
        val cases = listOf(
            Triple("39.7392, -104.9903", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("39.7392 -104.9903", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("39.7392N 104.9903W", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("104.9903W 39.7392N", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("N 39.7392 W 104.9903", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("lat 39.7392 lon -104.9903", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("-33.8688, 151.2093", -33.8688 to 151.2093, PositionFormat.DECIMAL),
            Triple("39°44'21.1\"N 104°59'25.1\"W", dms to dmsLon, PositionFormat.DMS),
            Triple("39 44 21.1 N 104 59 25.1 W", dms to dmsLon, PositionFormat.DMS),
            Triple("39°44.352'N 104°59.418'W", (39 + 44.352 / 60) to -(104 + 59.418 / 60), PositionFormat.DMM),
            Triple("13S 500831 4398811", 39.7392 to -104.9903, PositionFormat.UTM),
            Triple("13S 500831E 4398811N", 39.7392 to -104.9903, PositionFormat.UTM),
            Triple("13SED0083198811", 39.7392 to -104.9903, PositionFormat.MGRS),
            Triple("13S ED 00831 98811", 39.7392 to -104.9903, PositionFormat.MGRS),
            Triple("https://maps.example.com/?q=39.7392,-104.9903&z=15", 39.7392 to -104.9903, PositionFormat.DECIMAL),
            Triple("−33.8688, 151.2093", -33.8688 to 151.2093, PositionFormat.DECIMAL),
        )
        for ((text, pos, fmt) in cases) {
            val r = Coordinates.parse(text) ?: error("didn't parse $text")
            assertEquals(text, pos.first, r.latitude, 1e-4); assertEquals(text, pos.second, r.longitude, 1e-4); assertEquals(text, fmt, r.format)
        }
        val grid = Coordinates.parse("DM79mr")!!
        assertEquals(PositionFormat.MAIDENHEAD, grid.format); assertEquals(39.73, grid.latitude, 0.03); assertEquals(-104.96, grid.longitude, 0.06)
    }

    @Test fun rejectsThingsThatAreNotPositions() {
        for (bad in listOf("", "   ", "hello", "91, 0", "0, 181", "39.7392", "39 61 0 N 10 0 0 E", "12 34 56", "N 39.7 N 104.9", "E 39.7, N 104.9"))
            assertNull("should not parse '$bad'", Coordinates.parse(bad))
    }

    @Test fun formatsEveryWay() {
        val lat = 39.7392; val lon = -104.9903
        assertEquals("39.73920, -104.99030", Coordinates.format(lat, lon, PositionFormat.DECIMAL))
        assertEquals("39°44'21.1\"N 104°59'25.1\"W", Coordinates.format(lat, lon, PositionFormat.DMS))
        assertEquals("39°44.352'N 104°59.418'W", Coordinates.format(lat, lon, PositionFormat.DMM))
        assertEquals("13S 500831E 4398811N", Coordinates.format(lat, lon, PositionFormat.UTM))
        assertEquals("13S ED 00831 98811", Coordinates.format(lat, lon, PositionFormat.MGRS))
        assertEquals("DM79mr", Coordinates.format(lat, lon, PositionFormat.MAIDENHEAD))
        assertEquals("33°52.128'S 151°12.558'E", Coordinates.format(-33.8688, 151.2093, PositionFormat.DMM))
        for (f in listOf(PositionFormat.DECIMAL, PositionFormat.DMS, PositionFormat.DMM, PositionFormat.UTM, PositionFormat.MGRS)) {
            val r = Coordinates.parse(Coordinates.format(lat, lon, f))!!
            assertEquals("$f", lat, r.latitude, 1e-3); assertEquals("$f", lon, r.longitude, 1e-3)
        }
    }

    @Test fun maidenheadRoundTrip() {
        for (sq in listOf("DM79", "DM79mr", "JO22", "QF56od", "IO91wm", "FN31pr12")) {
            val c = Coordinates.fromMaidenhead(sq)!!
            val n = if (sq.length == 4) 4 else 6
            assertEquals(sq, sq.take(n).lowercase(), HamGeo.maidenhead(c.first, c.second).take(n).lowercase())
        }
        assertNull(Coordinates.fromMaidenhead("ZZ99")); assertNull(Coordinates.fromMaidenhead("DM7")); assertNotNull(Coordinates.parse("DM79"))
    }
}
