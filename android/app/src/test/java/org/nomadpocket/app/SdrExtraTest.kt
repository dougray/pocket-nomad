package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.sdr.*

class SdrExtraTest {
    @Test fun extraStreamParsesIntoState() {
        SdrState.reset()
        val data = javaClass.getResource("/sdr/events-extra.jsonl")!!.readBytes()
        val reader = SdrLineReader()
        reader.feed(data).mapNotNull { SdrParser.event(it) }.forEach { SdrState.apply(it) }
        assertEquals(0, reader.errors)
        assertTrue(SdrState.simulated.value)
        assertEquals(setOf(366999000, 338087471), SdrState.ships.value.keys)
        assertEquals("RS41", SdrState.sondes.value["R1234567"]?.model)
        assertTrue(SdrState.spots.value.isNotEmpty())
        val last = SdrState.ships.value.values.maxOf { it.heardAtMillis }
        assertEquals(2, SdrState.visibleShips(last).size)
        assertEquals(0, SdrState.visibleShips(last + 1_000_000).size)
        assertEquals(1, SdrState.visibleSondes(last + 1_000_000).size)
        SdrState.reset()
    }

    @Test fun rejectsBadRows() {
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"ship","mmsi":1,"lat":95,"lon":0}""")))
        assertNull(SdrParser.event(org.json.JSONObject("""{"t":"spot","call":"W1AW","grid":"ZZ99"}""")))
        assertNotNull(SdrParser.event(org.json.JSONObject("""{"t":"spot","call":"W1AW","grid":"FN31","snr":-5,"mhz":14.07,"mode":"ft8"}""")))
    }
}
