package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.nomadpocket.app.conditions.ConditionsFormat
import org.nomadpocket.app.conditions.ConditionsParsers as P
import org.nomadpocket.app.conditions.SpaceWeather
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The conditions parsers on real saved responses. Mirrors ConditionsTests.swift. */
class ConditionsTest {
    private fun fx(name: String) = javaClass.classLoader!!.getResourceAsStream("conditions/$name")!!.readBytes().toString(Charsets.UTF_8)
    private fun utc(s: String) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(s)!!.time

    @Test fun quakesParseAndFilter() {
        val q = P.parseQuakes(fx("usgs.json"))
        assertEquals(15, q.size)
        assertEquals("aka2026wuapjf", q[0].id)
        assertEquals(2.8, q[0].magnitude, 0.0)
        assertEquals("29 km E of Chenega, Alaska", q[0].place)
        val near = P.nearby(q, 39.74, -104.99, 50.0, 2.5)
        assertEquals(listOf("test-co"), near.map { it.id })
        assertEquals(8.2, near[0].depthKm, 0.01)
        assertTrue(P.nearby(q, 39.74, -104.99, 50.0, 3.5).isEmpty())
    }

    @Test fun significantKeptAnywhere() {
        val week = P.parseQuakes(fx("usgs.json"))
        val sig = P.parseQuakes(fx("usgs-sig.json"), significant = true)
        assertEquals(7, sig.size)
        assertEquals("us7000tymu", sig[0].id)
        assertFalse(sig[0].tsunami)
        val merged = P.merge(week, sig)
        assertEquals(22, merged.size)
        val near = P.nearby(merged, 39.78, -105.22, 10.0, 2.5)
        assertEquals(8, near.size)
        assertEquals(near, near.sortedByDescending { it.timeMillis })
        try { P.parseQuakes("[]"); fail() } catch (_: P.Failure) {}
    }

    @Test fun alertsParseSortExpire() {
        assertTrue(P.parseAlerts(fx("nws-empty.json")).isEmpty())
        val a = P.parseAlerts(fx("nws-one.json")).single()
        assertEquals("Dense Fog Advisory", a.event)
        assertEquals("Moderate", a.severity)
        assertEquals(2, a.rank)
        assertEquals("NWS San Angelo TX", a.sender)
        assertEquals("Coke; Tom Green", a.areas)
        assertEquals(utc("2026-11-17T15:00:00Z"), a.expiresMillis)
        assertNotNull(a.instruction)
        assertFalse(a.isExpired(utc("2026-11-17T14:59:00Z")))
        assertTrue(a.isExpired(utc("2026-11-17T15:01:00Z")))
        val json = """{"features":[{"properties":{"id":"a","event":"Frost Advisory","severity":"Minor"}},{"properties":{"id":"b","event":"Tornado Warning","severity":"Extreme"}},{"properties":{"id":"c","event":"Wind Advisory","severity":"Moderate"}}]}"""
        assertEquals(listOf("Tornado Warning", "Wind Advisory", "Frost Advisory"), P.parseAlerts(json).map { it.event })
    }

    @Test fun forecastUsesLocalTimes() {
        val f = P.parseForecast(fx("openmeteo.json"))
        assertEquals(168, f.hours.size)
        assertEquals(39.690926, f.latitude, 1e-6)
        assertEquals(utc("2026-11-17T07:00:00Z"), f.hours[0].timeMillis)
        assertEquals(3.6, f.hours[0].temperatureC, 0.01)
        assertEquals(0, f.hours[0].code)
        val next = P.upcoming(f, utc("2026-11-17T10:10:00Z"), 5)
        assertEquals(5, next.size)
        assertEquals(utc("2026-11-17T10:00:00Z"), next[0].timeMillis)
        assertEquals("Clear", P.describe(0))
        assertEquals("Thunderstorm", P.describe(95))
        assertEquals("N", P.compass(0)); assertEquals("SW", P.compass(225)); assertEquals("N", P.compass(350))
        try { P.parseForecast("""{"hourly":{"time":[]}}"""); fail() } catch (_: P.Failure) {}
    }

    @Test fun daysGroupInThePlacesZone() {
        val f = P.parseForecast(fx("openmeteo.json"))
        val tz = TimeZone.getTimeZone("GMT-07:00")
        val days = P.days(f, tz)
        assertEquals(7, days.size)
        val first = f.hours.take(24)
        assertEquals(first.maxOf { it.temperatureC }, days[0].highC, 1e-9)
        assertEquals(first.minOf { it.temperatureC }, days[0].lowC, 1e-9)
        assertEquals(first.sumOf { it.precipitationMm }, days[0].rainMm, 1e-9)
        assertTrue(days.all { it.lowC <= it.highC })
    }

    @Test fun positionRoundedBeforeSending() {
        assertEquals(39.7, P.rounded(39.7499), 0.0)
        assertEquals(-105.0, P.rounded(-105.04), 0.0)
        assertEquals(-105.0, P.rounded(-104.96), 0.0)
    }

    @Test fun spaceWeatherFromRealFeeds() {
        val w = P.parseSpaceWeather(fx("kp.json"), fx("scales.json"), fx("3day.txt"))
        assertEquals(1.0, w.kpNow, 0.0)
        assertEquals(utc("2026-11-17T09:00:00Z"), w.kpTimeMillis)
        assertEquals(0, w.geomagnetic); assertEquals(0, w.radio)
        assertEquals(1, w.geomagneticOutlook)
        assertEquals(37, w.kp.size)
        val predicted = w.kp.filter { it.predicted }
        assertEquals(20, predicted.size)
        assertEquals(utc("2026-11-17T12:00:00Z"), predicted[0].timeMillis)
        assertEquals(3.67, predicted[0].kp, 0.001)
        assertEquals(4.67, predicted.maxOf { it.kp }, 0.0)
        assertEquals("HF radio conditions are normal.", P.hfNote(w))
        assertEquals("GPS should be normal.", P.gpsNote(w))
        assertEquals("Geomagnetic storm (G1) expected in the next three days.", P.auroraNote(w))
    }

    @Test fun kpForecastRollsOverTheMonth() {
        val text = """
            NOAA Kp index breakdown Nov 30-Dec 02 2026

                         Nov 30       Dec 01       Dec 02
            00-03UT       1.00         2.00 (G0)    3.00
            21-00UT       4.00         5.00 (G1)    6.00
        """.trimIndent()
        val kp = P.parseKpForecast(text)
        assertEquals(6, kp.size)
        assertEquals(utc("2026-11-30T00:00:00Z"), kp[0].timeMillis)
        assertEquals(utc("2026-11-30T21:00:00Z"), kp[1].timeMillis)
        assertEquals(utc("2026-12-01T00:00:00Z"), kp[2].timeMillis)
        assertEquals(2.0, kp[2].kp, 0.0)
        assertEquals(utc("2026-12-02T21:00:00Z"), kp[5].timeMillis)
        assertTrue(P.parseKpForecast("nothing useful").isEmpty())
    }

    @Test fun radioAndGpsWords() {
        fun w(r: Int = 0, s: Int = 0, g: Int = 0, kp: Double = 1.0) = SpaceWeather(kp, 0, emptyList(), r, s, g, g)
        assertTrue(P.hfNote(w(r = 3)).contains("unreliable"))
        assertTrue(P.hfNote(w(r = 1)).contains("Minor"))
        assertTrue(P.hfNote(w(g = 3)).contains("storm"))
        assertTrue(P.gpsNote(w(g = 4)).contains("noticeably"))
        assertTrue(P.gpsNote(w(kp = 6.0)).contains("slightly"))
    }

    @Test fun weatherRadioMergesCounties() {
        val stations = P.parseWeatherRadio(fx("nwr.js"))
        assertEquals(10, stations.size)
        val denver = stations.first { it.callsign == "KEC76" }
        assertEquals("162.550", denver.frequency)
        assertEquals("Denver", denver.name)
        assertEquals(7, denver.counties.size)
        val nearest = P.nearestRadio(stations, 39.74, -104.99, 3)
        assertEquals(listOf("KEC76", "WNG550", "WXM51"), nearest.map { it.first.callsign })
        assertEquals(7.0, nearest[0].second, 0.5)
        assertEquals(7, P.radioChannels.size)
        assertEquals("162.400", P.radioChannels.first().first)
    }

    @Test fun ageAndUnits() {
        val now = 1_800_000_000_000L
        assertEquals("just now", P.age(now - 30_000, now)); assertEquals("10 min ago", P.age(now - 600_000, now))
        assertEquals("2 h ago", P.age(now - 7_200_000, now)); assertEquals("3 d ago", P.age(now - 3 * 86_400_000, now))
        val us = ConditionsFormat(false); val m = ConditionsFormat(true)
        assertEquals("32°F", us.temperature(0.0)); assertEquals("22°C", m.temperature(21.6))
        assertEquals("62 mph", us.speed(100.0)); assertEquals("100 km/h", m.speed(100.0))
        assertEquals("1.00 in", us.rain(25.4)); assertEquals("0 mm", m.rain(0.0))
        assertEquals("20 mi", us.distance(32.1869)); assertEquals("5.0 mi", us.distance(8.0467))
        assertEquals("4.3 km", m.distance(4.25)); assertEquals("311 mi", us.radius(500))
    }
}
