package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.tools.SolarCalculator
import java.time.ZoneId
import java.time.ZonedDateTime

class SolarCalculatorTest {
    private val denver = ZoneId.of("America/Denver")

    @Test fun sunsetDenverMidsummer() {
        val date = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, denver).toInstant()
        val day = SolarCalculator.day(date, 39.7392, -104.9903, denver)
        val sunrise = day.sunrise!!.atZone(denver)
        val sunset = day.sunset!!.atZone(denver)
        // Denver, June 21: sunrise about 5:32, sunset about 8:31 pm MDT.
        assertEquals(5, sunrise.hour)
        assertEquals(32.0, sunrise.minute.toDouble(), 3.0)
        assertEquals(20, sunset.hour)
        assertEquals(31.0, sunset.minute.toDouble(), 3.0)
        assertTrue(day.civilDawn!! < day.sunrise)
        assertTrue(day.civilDusk!! > day.sunset)
    }

    @Test fun polarNightHasNoSunrise() {
        val date = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        assertNull(SolarCalculator.day(date, 78.2, 15.6, ZoneId.of("UTC")).sunrise)
    }

    @Test fun midnightSunHasNoSunset() {
        val date = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        val day = SolarCalculator.day(date, 78.2, 15.6, ZoneId.of("UTC"))
        assertNull(day.sunset)
        assertNotNull(date)
    }

    @Test fun moonPhase() {
        // New moon 2000-01-06 18:14 UTC; full moon 2000-01-21 04:40 UTC.
        assertEquals(0.0, SolarCalculator.moonPhase(ZonedDateTime.of(2000, 1, 6, 18, 14, 0, 0, ZoneId.of("UTC")).toInstant()), 0.001)
        val full = SolarCalculator.moonPhase(ZonedDateTime.of(2000, 1, 21, 4, 40, 0, 0, ZoneId.of("UTC")).toInstant())
        assertEquals(0.5, full, 0.03)
        assertEquals("Full moon", SolarCalculator.moonName(full))
        assertEquals("New moon", SolarCalculator.moonName(0.99))
        assertEquals("First quarter", SolarCalculator.moonName(0.25))
    }

    @Test fun duration() {
        assertEquals("1 h 30 min", SolarCalculator.duration(5400))
        assertEquals("45 min", SolarCalculator.duration(2700))
    }
}
