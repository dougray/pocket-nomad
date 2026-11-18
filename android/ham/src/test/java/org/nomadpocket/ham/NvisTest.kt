package org.nomadpocket.ham

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NvisTest {
    private fun t(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time

    @Test fun sunElevationKnownCases() {
        assertEquals(90.0, Nvis.sunElevation(0.0, 0.0, t("2026-03-20 12:05")), 4.0)
        assertTrue(Nvis.sunElevation(30.27, -97.74, t("2026-06-21 06:00")) < -20)
        assertEquals(83.0, Nvis.sunElevation(30.27, -97.74, t("2026-06-21 18:00")), 4.0)
        assertEquals(6.5, Nvis.sunElevation(60.0, 0.0, t("2026-12-21 12:03")), 2.0)
    }

    @Test fun adviceFollowsDayNightAndStorms() {
        val noon = Nvis.advise(150.0, 1.0, 60.0)
        assertEquals(7.3, noon.foF2, 0.05); assertEquals("60 m", noon.best?.name); assertEquals("80 m", noon.alternate?.name)
        val night = Nvis.advise(120.0, 1.0, -30.0)
        assertEquals(3.0, night.foF2, 0.05); assertEquals("160 m", night.best?.name)
        val storm = Nvis.advise(150.0, 6.0, 60.0)
        assertTrue(storm.stormed && storm.foF2 < noon.foF2); assertEquals("80 m", storm.best?.name)
        assertTrue(Nvis.advise(70.0, 1.0, 60.0).fot < noon.fot)
        assertTrue(Nvis.advise(65.0, 8.0, 60.0).summary.contains("poor"))
    }

    @Test fun antennaAndCircle() {
        val a = Nvis.antenna(Nvis.bands.first { it.name == "40 m" })
        assertEquals(142.65 / 7.1, a.dipoleMetres, 0.001); assertEquals(66.0, a.dipoleFeet, 0.5); assertEquals(4.2, a.heightMinMetres, 0.1); assertEquals(10.6, a.heightMaxMetres, 0.1)
        assertEquals(130.0, Nvis.antenna(Nvis.bands.first { it.name == "80 m" }).dipoleFeet, 1.0)
        val ring = Nvis.circle(30.27, -97.74, Nvis.RANGE_KM)
        assertEquals(73, ring.size)
        ring.forEach { assertEquals(Nvis.RANGE_KM, HamGeo.distanceKm(30.27, -97.74, it.first, it.second), 0.5) }
        assertEquals(300.0, Nvis.RANGE_KM / 1.609344, 0.01)
    }

    @Test fun parsesFlux() {
        assertEquals(121.0, Nvis.parseFlux("""[{"flux":121,"time_tag":"2026-11-17T20:00:00"}]""")!!, 0.0)
        assertNull(Nvis.parseFlux("[]")); assertNull(Nvis.parseFlux("""[{"flux":9999}]""")); assertNull(Nvis.parseFlux("junk"))
    }
}
