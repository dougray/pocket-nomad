package org.nomadpocket.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch app's pure logic: the SOS rhythm, heading maths, the position card and launch routes. */
class WearLogicTest {
    @Test fun sosRhythm() {
        val frames = SosSignal.frames()
        // · · · — — — · · · : nine flashes, 150 ms dots and 450 ms dashes.
        assertEquals(listOf(150L, 150, 150, 450, 450, 450, 150, 150, 150), frames.filter { it.on }.map { it.millis })
        // Gaps of one unit inside the run, then a 7-unit word gap before repeating.
        assertTrue(frames.dropLast(1).filter { !it.on }.all { it.millis == 150L })
        assertEquals(SosSignal.Frame(false, 1050), frames.last())
        // 15 units of light + 8 inner gaps + 7 end gap = 30 units = 4.5 s.
        assertEquals(4500L, SosSignal.cycleMillis())
        assertEquals(frames.indices.map { it % 2 == 0 }, frames.map { it.on })
    }

    @Test fun sosVibrationFitsInsideEachFlash() {
        for (frame in SosSignal.frames()) {
            val buzz = SosSignal.vibrationMillis(frame)
            if (frame.on) assertTrue(buzz in 1..frame.millis) else assertEquals(0L, buzz)
        }
        // Dashes still buzz clearly longer than dots.
        assertTrue(SosSignal.vibrationMillis(SosSignal.Frame(true, 450)) > 2 * SosSignal.vibrationMillis(SosSignal.Frame(true, 150)))
    }

    @Test fun headingMaths() {
        assertEquals(0.0, Heading.fromAzimuth(0.0), 1e-9)
        assertEquals(270.0, Heading.fromAzimuth(-Math.PI / 2), 1e-9)
        assertEquals(180.0, Heading.fromAzimuth(Math.PI), 1e-9)
        // Denver's declination is about 7.5° east.
        assertEquals(4.5, Heading.trueHeading(357.0, 7.5), 1e-9)
        assertEquals(350.0, Heading.trueHeading(5.0, -15.0), 1e-9)
        assertEquals(2.0, Heading.diff(359.0, 1.0), 1e-9)
        assertEquals(180.0, Heading.diff(0.0, 180.0), 1e-9)
        assertEquals("312° NW", Heading.label(312.0))
        assertEquals("0° N", Heading.label(359.6))
    }

    @Test fun positionWithFix() {
        val report = PositionReport.of(true, true, 39.74956, -104.99654, 1609.0, 5f, ageMillis = 1000)
        report as PositionReport.Fix
        assertFalse(report.stale)
        val rows = report.rows.toMap()
        assertEquals(listOf("Decimal", "MGRS", "DMS", "Altitude", "Accuracy"), report.rows.map { it.first })
        assertEquals("39.74956, -104.99654", rows["Decimal"])
        assertTrue(rows["MGRS"]!!, rows["MGRS"]!!.startsWith("13S ED"))
        assertEquals("5279 ft (1609 m)", rows["Altitude"])
        assertEquals("±5 m", rows["Accuracy"])
    }

    @Test fun positionStaleAndOptionalRows() {
        val report = PositionReport.of(true, true, 39.7, -105.0, null, null, ageMillis = PositionReport.STALE_MILLIS + 1)
        report as PositionReport.Fix
        assertTrue(report.stale)
        assertEquals(listOf("Decimal", "MGRS", "DMS"), report.rows.map { it.first })
        // Outside MGRS's range there is no MGRS row, but the rest still shows.
        val polar = PositionReport.of(true, true, 85.0, 10.0, null, null, 0) as PositionReport.Fix
        assertEquals(listOf("Decimal", "DMS"), polar.rows.map { it.first })
    }

    @Test fun positionWithoutFixSaysWhy() {
        assertEquals(PositionReport.Denied, PositionReport.of(false, true, 39.7, -105.0, null, null, 0))
        assertEquals(PositionReport.Searching, PositionReport.of(true, true, null, null, null, null, 0))
        assertEquals(PositionReport.NoGps, PositionReport.of(true, false, null, null, null, null, 0))
    }

    @Test fun launchRoutes() {
        assertEquals(listOf("sos"), routesFor("sos"))
        assertEquals(listOf("compass"), routesFor("position"))
        assertEquals(listOf("firstaid", "guide/cpr"), routesFor("guide:cpr"))
        assertTrue(routesFor(null).isEmpty())
        assertTrue(routesFor("nonsense").isEmpty())
    }
}
