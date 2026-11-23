package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.*

class SdrWeatherTest {
    @Test fun parsesPassesAndPictures() {
        SdrState.reset()
        val reader = SdrLineReader()
        val events = reader.feed(javaClass.getResource("/sdr/events-weather.jsonl")!!.readBytes()).mapNotNull { SdrParser.event(it) }
        assertEquals(4, events.size)
        events.forEach { SdrState.apply(it) }
        assertEquals(listOf("1793975900-NOAA_19", "1793975500-NOAA_19"), SdrState.images.value.map { it.id })
        assertEquals(62, SdrState.passes.value.first().elevation)
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"image","id":"x","url":"/etc/passwd"}""")))
        events.forEach { SdrState.apply(it) }
        assertEquals(2, SdrState.images.value.size)
        SdrState.reset()
    }
}

class SdrDongleTest {
    @Test fun statusCarriesWhatEachDongleDoes() {
        val h = SdrParser.event(org.json.JSONObject("""{"t":"hello","seq":0,"proto":1,"fw":"1","model":"pi","dongle":"d","modes":["adsb","ais"],"gps":false,"dongles":2}""")) as SdrEvent.Hello
        assertEquals(2, h.hello.dongles)
        val s = SdrParser.event(org.json.JSONObject("""{"t":"status","seq":1,"ts":1,"mode":"adsb","dongle":true,"decoded":0,"active":{"0":"adsb","1":"ais"}}""")) as SdrEvent.Status
        assertEquals(mapOf(0 to "adsb", 1 to "ais"), s.active)
        val old = SdrParser.event(org.json.JSONObject("""{"t":"status","seq":2,"ts":1,"mode":"aprs","dongle":true,"decoded":0}""")) as SdrEvent.Status
        assertEquals(mapOf(0 to "aprs"), old.active)
        val h1 = SdrParser.event(org.json.JSONObject("""{"t":"hello","seq":0,"proto":1,"fw":"1","model":"pi","dongle":"d","modes":[],"gps":false}""")) as SdrEvent.Hello
        assertEquals(1, h1.hello.dongles)
    }
}

class SdrSpectrumJs8Test {
    private fun load(n: String) = SdrLineReader().feed(javaClass.getResource("/sdr/$n.jsonl")!!.readBytes()).mapNotNull { SdrParser.event(it) }

    @Test fun spectrumKeepsLatestAndPeak() {
        SdrState.reset()
        val events = load("events-spectrum")
        assertEquals(1, events.size)
        events.forEach { SdrState.apply(it) }
        assertEquals(144_000_000, SdrState.spectrum.value?.lowHz)
        assertEquals(50, SdrState.spectrum.value?.db?.size)
        val first = SdrState.peak.value
        SdrState.apply(SdrEvent.Spectrum(144_000_000, 80_000, List(50) { -90 }))
        assertEquals(first, SdrState.peak.value)
        SdrState.apply(SdrEvent.Spectrum(118_000_000, 80_000, List(50) { -70 }))
        assertEquals(List(50) { -70 }, SdrState.peak.value)
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"spectrum","lo":1,"step":0,"db":[1,2]}""")))
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"spectrum","lo":1,"step":1,"db":[1]}""")))
        SdrState.reset()
    }

    @Test fun js8MessagesNewestFirstNoDuplicates() {
        SdrState.reset()
        val events = load("events-js8")
        assertEquals(5, events.size)
        events.forEach { SdrState.apply(it) }; events.forEach { SdrState.apply(it) }
        assertEquals(5, SdrState.js8.value.size)
        assertEquals(setOf("hb", "cq", "msg"), SdrState.js8.value.map { it.kind }.toSet())
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"js8","from":"","text":"x"}""")))
        SdrState.reset()
    }
}
