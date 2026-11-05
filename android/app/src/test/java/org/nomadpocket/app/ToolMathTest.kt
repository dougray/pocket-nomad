package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.tools.ToolKind
import org.nomadpocket.core.CoordinateFormat
import org.nomadpocket.core.Morse

/** Ports of the coordinate, Morse and tool tests in AppTests/ToolMathTests.swift. */
class ToolMathTest {
    @Test fun mgrsWashingtonMonument() {
        // Published MGRS for the Washington Monument: 18S UJ 23487 06483.
        val mgrs = CoordinateFormat.mgrs(38.88952, -77.03524)!!
        assertTrue(mgrs, mgrs.startsWith("18S UJ 234"))
        assertTrue(mgrs, mgrs.contains(" 064"))
    }

    @Test fun mgrsSouthernHemisphere() {
        // Sydney Opera House: 56H LH 34 51 (zone, band and square).
        val mgrs = CoordinateFormat.mgrs(-33.85678, 151.21530)
        assertTrue(mgrs ?: "null", mgrs?.startsWith("56H LH") == true)
    }

    @Test fun mgrsOutOfRange() {
        assertNull(CoordinateFormat.mgrs(86.0, 0.0))
        assertNull(CoordinateFormat.mgrs(-80.5, 0.0))
    }

    @Test fun mgrsShape() {
        val mgrs = CoordinateFormat.mgrs(39.7392, -104.9903)!!
        assertTrue(mgrs, Regex("""^13S [A-Z]{2} \d{5} \d{5}$""").matches(mgrs))
    }

    @Test fun mgrsNorwayAndSvalbardZones() {
        assertTrue(CoordinateFormat.mgrs(60.0, 5.0)!!.startsWith("32V"))
        assertTrue(CoordinateFormat.mgrs(78.0, 15.0)!!.startsWith("33X"))
    }

    @Test fun dms() {
        assertEquals("39°30′00.0″N 104°45′00.0″W", CoordinateFormat.dms(39.5, -104.75))
        assertEquals("33°51′24.4″S 151°12′55.1″E", CoordinateFormat.dms(-33.85678, 151.21530))
    }

    @Test fun decimal() {
        assertEquals("39.73920, -104.99030", CoordinateFormat.decimal(39.7392, -104.9903))
    }

    @Test fun cardinal() {
        assertEquals("N", CoordinateFormat.cardinal(0.0))
        assertEquals("N", CoordinateFormat.cardinal(359.0))
        assertEquals("SW", CoordinateFormat.cardinal(225.0))
        assertEquals("E", CoordinateFormat.cardinal(90.0))
    }

    @Test fun morseEncode() {
        assertEquals("... --- ...", Morse.encode("SOS"))
        assertEquals(".... .. / -.-- --- ..-", Morse.encode("hi you"))
        assertEquals("", Morse.encode(""))
    }

    @Test fun sosTimelineIsOneRun() {
        val t = Morse.timeline("SOS")
        assertEquals(listOf(1, 1, 1, 3, 3, 3, 1, 1, 1), t.filter { it.on }.map { it.units })
        // No letter gaps (3 units) inside the SOS run, only symbol gaps.
        assertFalse(t.dropLast(1).any { !it.on && it.units > 1 })
        assertEquals(Morse.Step(false, 7), t.last())
    }

    @Test fun timelineGaps() {
        // "E E": dot, word gap, dot, end gap.
        assertEquals(listOf(Morse.Step(true, 1), Morse.Step(false, 7), Morse.Step(true, 1), Morse.Step(false, 7)), Morse.timeline("E E"))
        // "ET": dot, letter gap, dash.
        assertEquals(listOf(Morse.Step(true, 1), Morse.Step(false, 3), Morse.Step(true, 3), Morse.Step(false, 7)), Morse.timeline("et"))
    }

    @Test fun parisTiming() {
        assertEquals(150L, Morse.unitMillis(8.0))
        assertEquals(60L, Morse.unitMillis(20.0))
    }

    @Test fun relatedTool() {
        assertEquals(ToolKind.WATER, ToolKind.related("How much bleach purifies a gallon of water?"))
        assertEquals(ToolKind.SOS, ToolKind.related("How do I signal a helicopter?"))
        assertNull(ToolKind.related("How do I splint a broken arm?"))
    }
}
