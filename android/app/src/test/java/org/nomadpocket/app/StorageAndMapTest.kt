package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nomadpocket.app.map.CoverageColors
import org.nomadpocket.app.map.MapPin
import org.nomadpocket.app.map.TileMath
import org.nomadpocket.app.tools.ChecklistState
import org.nomadpocket.app.tools.MedicalInfo

class StorageAndMapTest {
    @Test fun checklistStateRoundTrips() {
        val s = ChecklistState().toggled("go-bag", "Whistle").toggled("go-bag", "Cash").toggled("go-bag", "Cash").adding("go-bag", "Spare socks")
        assertTrue(s.isChecked("go-bag", "Whistle"))
        assertEquals(setOf("Whistle"), s.checked["go-bag"])
        val back = ChecklistState.fromJson(s.toJson())
        assertEquals(s, back)
        assertEquals(emptySet<String>(), back.cleared("go-bag").checked["go-bag"])
    }

    @Test fun medicalInfoRoundTrips() {
        val info = MedicalInfo(name = "Test Person", bloodType = "O+", contacts = listOf(MedicalInfo.Contact(id = "1", name = "A", phone = "555")))
        assertEquals(info, MedicalInfo.fromJson(info.toJson()))
        assertTrue(MedicalInfo().isEmpty)
        assertTrue(!info.isEmpty)
    }

    @Test fun pinsRoundTrip() {
        val pins = listOf(MapPin(id = "p", name = "Creek", kind = MapPin.Kind.WATER, latitude = 39.74, longitude = -104.99, created = 5))
        assertEquals(pins, MapPin.fromJson(MapPin.toJson(pins)))
    }

    @Test fun tileCounts() {
        // The whole world at zoom 0 is one tile; at zoom 1, four.
        assertEquals(1L, TileMath.tileCount(-85.0, -180.0, 85.0, 180.0, 0, 0))
        assertEquals(5L, TileMath.tileCount(-85.0, -180.0, 85.0, 180.0, 0, 1))
        // A small box is one tile per zoom at low zooms and grows by about 4x per zoom.
        val city = TileMath.tileCount(39.70, -105.05, 39.78, -104.93, 0, 14)
        assertTrue("$city", city in 50L..200L)
        assertEquals("2 MB", TileMath.formatBytes(TileMath.estimateBytes(0)))
        assertEquals("1.5 GB", TileMath.formatBytes(1_500_000_000))
    }

    @Test fun coverageColoursInterpolate() {
        assertEquals(CoverageColors.stops.first().second, CoverageColors.color(-30.0))
        assertEquals(CoverageColors.stops.last().second, CoverageColors.color(20.0))
        val mid = CoverageColors.color(4.0)
        val a = CoverageColors.stops[2].second
        val b = CoverageColors.stops[3].second
        assertEquals((a.red + b.red) / 2, mid.red, 0.01f)
    }
}
