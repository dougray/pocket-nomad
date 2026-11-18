package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.*
import org.nomadpocket.ham.Aprs
import org.nomadpocket.ham.Ax25Frame

class SdrEventsTest {
    private fun stream(): List<SdrEvent> {
        val data = javaClass.getResource("/sdr/events-sim.jsonl")!!.readBytes()
        val reader = SdrLineReader()
        val objs = ArrayList<org.json.JSONObject>()
        var i = 0
        while (i < data.size) { objs += reader.feed(data.copyOfRange(i, minOf(i + 7, data.size))); i += 7 }
        assertEquals(0, reader.errors)
        return objs.mapNotNull { SdrParser.event(it) }
    }

    @Test fun recordedStreamParses() {
        val ev = stream()
        assertEquals(66, ev.size)
        assertTrue((ev[0] as SdrEvent.Hello).hello.simulated)
        assertTrue(ev.any { it is SdrEvent.Alert })
    }

    @Test fun stateTracksAircraft() {
        SdrState.reset()
        stream().forEach { SdrState.apply(it) }
        assertTrue(SdrState.simulated.value)
        assertEquals(setOf("A1B2C3", "A4D5E6", "AC0F11", "A8B9CA"), SdrState.aircraft.value.keys)
        val last = SdrState.aircraft.value.values.maxOf { it.heardAtMillis }
        assertEquals(4, SdrState.visibleAircraft(last).size)
        assertEquals(0, SdrState.visibleAircraft(last + 200_000).size)
        assertTrue(SdrState.alerts.value.first().same.startsWith("ZCZC-WXR-TOR"))
        assertEquals(2, SdrState.channels.value.size)
        SdrState.reset()
    }

    @Test fun aprsFrameFromBoxDecodesWithHamDecoder() {
        val frame = stream().filterIsInstance<SdrEvent.Aprs>().first().frame
        val packet = Aprs.decode(Ax25Frame.decode(frame)!!)!!
        assertEquals("W5SIM-9", packet.source)
        assertEquals(30.32, packet.position!!.latitude, 0.01)
        assertTrue(packet.comment.contains("Simulated"))
    }

    @Test fun badEventsRejected() {
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"aircraft","icao":"A","lat":95,"lon":0}""")))
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"hello","proto":2}""")))
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"nonsense"}""")))
        assertEquals(SdrEvent.Error("auth"), SdrParser.event(org.json.JSONObject("""{"t":"error","code":"auth"}""")))
        val r = SdrLineReader()
        assertEquals(1, r.feed("garbage\n{\"t\":\"status\",\"seq\":1}\n".toByteArray()).size)
        assertEquals(1, r.errors)
    }
}
