package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.tools.RadioMath
import org.nomadpocket.app.tools.ToolKind

class RadioMathTest {
    @Test fun antennaLengths() {
        val sat = RadioMath.antenna(137.1)!!
        assertEquals(0.520, sat.quarterWaveMetres, 0.001)
        assertEquals(1.0405, sat.halfWaveTotalMetres, 0.001)
        assertEquals(sat.quarterWaveMetres, sat.vDipoleElementMetres, 0.0)
        assertEquals(0.0654, RadioMath.antenna(1090.0)!!.quarterWaveMetres, 0.0005)
        assertEquals(0.494, RadioMath.antenna(144.39)!!.quarterWaveMetres, 0.001)
        assertEquals(10.136, RadioMath.antenna(14.074)!!.halfWaveTotalMetres, 0.01)
        assertEquals(1.03, sat.cutLong(1.0), 1e-9)
        assertNull(RadioMath.antenna(0.0)); assertNull(RadioMath.antenna(99_999.0)); assertNull(RadioMath.antenna(-5.0))
    }

    @Test fun lengthText() {
        assertEquals("49.4 cm (19.4 in)", RadioMath.lengthText(0.494))
        assertEquals("10.14 m (33 ft 3 in)", RadioMath.lengthText(10.136))
        assertEquals("25.4 cm (10.0 in)", RadioMath.lengthText(0.254))
        assertEquals("1.00 m (3 ft 3 in)", RadioMath.lengthText(1.0))
    }

    @Test fun solarSums() {
        val tight = RadioMath.solar(2.0, 3.5, 3.7, 5000.0, 75.0)!!
        assertEquals(6.66, tight.loadWhPerDay, 0.01); assertEquals(18.5, tight.batteryWh, 0.01)
        assertEquals(2.78, tight.autonomyDays, 0.01); assertEquals(7.0, tight.panelWhPerDay, 0.01)
        assertEquals(RadioMath.Verdict.Tight, tight.verdict)
        assertEquals(RadioMath.Verdict.KeepsUp, RadioMath.solar(5.0, 3.5, 3.7, 5000.0, 75.0)!!.verdict)
        val low = RadioMath.solar(0.5, 3.5, 3.7, 5000.0, 75.0)!!.verdict as RadioMath.Verdict.RunsDown
        assertEquals(3.77, low.daysUntilEmpty, 0.02)
        assertNull(RadioMath.solar(2.0, 3.5, 0.0, 5000.0, 75.0)); assertNull(RadioMath.solar(2.0, 3.5, 3.7, 5000.0, 0.0))
    }

    @Test fun askFindsTheTools() {
        assertEquals(ToolKind.ANTENNA, ToolKind.related("How long should my antenna be for 144 MHz?"))
        assertEquals(ToolKind.SOLAR_NODE, ToolKind.related("What size solar panel for my node?"))
    }
}
