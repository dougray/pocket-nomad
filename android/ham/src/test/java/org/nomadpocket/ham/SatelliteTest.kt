package org.nomadpocket.ham

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SGP4 and pass prediction, checked against the Python sgp4 and Skyfield libraries. Mirrors SatelliteTests.swift. */
class SatelliteTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("satellites/$name")!!.readBytes().toString(Charsets.UTF_8)
    private val ref by lazy { JSONObject(res("sat-reference.json")) }
    private fun vec(a: JSONArray) = DoubleArray(3) { a.getDouble(it) }

    @Test fun tleParsingChecksAndEpoch() {
        val all = Tle.parse(res("stations.tle"))
        assertTrue(all.size >= 2)
        val iss = all.first { it.name.startsWith("ISS") }
        assertEquals(25544, iss.noradId); assertTrue(iss.isValid); assertEquals(2026, iss.epochYear)
        assertEquals(1_794_902_505_800.0, iss.epochMillis.toDouble(), 1000.0)
        assertFalse(iss.copy(line1 = iss.line1.dropLast(1) + "0").isValid && iss.line1.last() != '0')
        assertTrue(Tle.parse("junk\nmore junk\n").isEmpty())
    }

    @Test fun valladoVerificationSatellite() {
        val v = ref.getJSONObject("vallado")
        val sgp4 = Sgp4.create(Tle("00005", v.getString("l1"), v.getString("l2")))!!
        val items = v.getJSONArray("vectors")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val s = sgp4.propagate(item.getDouble("tsince"))!!
            val er = vec(item.getJSONArray("r")); val ev = vec(item.getJSONArray("v"))
            assertEquals(er[0], s.x, 1e-5); assertEquals(er[1], s.y, 1e-5); assertEquals(er[2], s.z, 1e-5)
            assertEquals(ev[0], s.vx, 1e-8); assertEquals(ev[1], s.vy, 1e-8); assertEquals(ev[2], s.vz, 1e-8)
        }
        assertEquals(7022.46529266, sgp4.propagate(0.0)!!.x, 1e-6)
    }

    @Test fun issMatchesTheReferenceImplementation() {
        val iss = ref.getJSONObject("iss")
        val sgp4 = Sgp4.create(Tle("ISS", iss.getString("l1"), iss.getString("l2")))!!
        assertEquals(iss.getDouble("epochJd"), sgp4.epochJulianDate, 1e-6)
        val items = iss.getJSONArray("vectors")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val s = sgp4.propagate(item.getDouble("minutesAfterEpoch"))!!
            val er = vec(item.getJSONArray("r")); val ev = vec(item.getJSONArray("v"))
            assertEquals(er[0], s.x, 2e-3); assertEquals(er[1], s.y, 2e-3); assertEquals(er[2], s.z, 2e-3)
            assertEquals(ev[0], s.vx, 2e-6); assertEquals(ev[1], s.vy, 2e-6); assertEquals(ev[2], s.vz, 2e-6)
        }
    }

    @Test fun deepSpaceIsNotSupported() {
        assertNull(Sgp4.create(Tle("GEO", "1 41589U 16045A   26321.50000000 -.00000100  00000+0  00000+0 0  9990",
            "2 41589   0.0300  80.0000 0001000 100.0000 260.0000  1.00270000 12340")))
    }

    @Test fun lookAnglesMatchSkyfield() {
        val iss = ref.getJSONObject("iss")
        val sgp4 = Sgp4.create(Tle("ISS", iss.getString("l1"), iss.getString("l2")))!!
        val o = Observer(39.74, -104.99, 1600.0)
        val samples = ref.getJSONArray("samples")
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            val at = (s.getDouble("unix") * 1000).toLong()
            val sky = SatelliteMath.look(sgp4, at, o)!!
            assertEquals(s.getDouble("el"), sky.elevation, 0.1)
            assertEquals(s.getDouble("rangeKm"), sky.rangeKm, 3.0)
            if (sky.elevation > 0) {
                var d = abs(sky.azimuth - s.getDouble("az")); if (d > 180) d = 360 - d
                assertTrue("azimuth off by $d", d < 0.2)
            }
        }
    }

    @Test fun passesMatchSkyfield() {
        val iss = ref.getJSONObject("iss")
        val tle = Tle("ISS", iss.getString("l1"), iss.getString("l2"))
        val block = ref.getJSONObject("passes")
        val ob = block.getJSONObject("observer")
        val observer = Observer(ob.getDouble("lat"), ob.getDouble("lon"), ob.getDouble("altM"))
        val mine = SatelliteMath.passes(tle, observer, (block.getDouble("start") * 1000).toLong(), 48.0, 10.0)
        val events = block.getJSONArray("events")
        val theirs = (0 until events.length()).map { events.getJSONObject(it) }.filter { it.getDouble("maxEl") >= 10 }
        assertEquals(theirs.size, mine.size)
        for ((m, t) in mine.zip(theirs)) {
            assertEquals(t.getDouble("rise") * 1000, m.startMillis.toDouble(), 15_000.0)
            assertEquals(t.getDouble("culm") * 1000, m.peakMillis.toDouble(), 15_000.0)
            assertEquals(t.getDouble("set") * 1000, m.endMillis.toDouble(), 15_000.0)
            assertEquals(t.getDouble("maxEl"), m.maxElevation, 0.3)
            assertEquals(t.getDouble("culmAz"), m.peakAzimuth, 2.0)
            assertEquals(t.getDouble("riseAz"), m.startAzimuth, 2.0)
            assertTrue(m.durationSeconds in 120.0..900.0)
        }
        assertEquals(mine, mine.sortedBy { it.startMillis })
    }

    @Test fun passInProgressAndWords() {
        val iss = ref.getJSONObject("iss")
        val tle = Tle("ISS", iss.getString("l1"), iss.getString("l2"))
        val events = ref.getJSONObject("passes").getJSONArray("events")
        val first = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getDouble("maxEl") >= 10 }
        val mid = (first.getDouble("culm") * 1000).toLong()
        val p = SatelliteMath.passes(tle, Observer(39.74, -104.99, 1600.0), mid, 3.0).first()
        assertTrue(p.inProgress); assertEquals(mid, p.startMillis)
        assertTrue(SatelliteMath.describe(p).startsWith("Rises"))
        assertEquals("N", SatelliteMath.compass(0.0)); assertEquals("N", SatelliteMath.compass(359.0))
        assertEquals("SE", SatelliteMath.compass(135.0)); assertEquals("SSW", SatelliteMath.compass(202.0))
        assertEquals("5 min", SatelliteMath.countdown(300.0)); assertEquals("3 h 2 min", SatelliteMath.countdown(3 * 3600.0 + 120))
        assertEquals("2 d 5 h", SatelliteMath.countdown(2 * 86400.0 + 5 * 3600)); assertEquals("0 min", SatelliteMath.countdown(-5.0))
        assertNotNull(p.id)
    }
}
