package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.map.PotaParsers

class PotaTest {
    private fun res(n: String) = javaClass.getResource("/pota/$n.json")!!.readText()

    @Test fun parsesSpots() {
        val spots = PotaParsers.spots(res("spots"))
        assertEquals(6, spots.size)
        val s = spots.first()
        assertTrue(s.reference.contains("-") && s.activator.isNotEmpty() && s.frequencyKHz > 1000)
        assertTrue(s.frequencyText.endsWith("MHz"))
        assertTrue(s.spottedMillis > 1_700_000_000_000L)
    }

    @Test fun parsesParksAndRejectsBadRows() {
        assertTrue(PotaParsers.parks(res("grid")).isNotEmpty())
        assertEquals(0, PotaParsers.parks("""{"features":[{"properties":{"reference":"X"},"geometry":{"coordinates":[-97,95]}}]}""").size)
        assertEquals(0, PotaParsers.spots("nonsense").size)
    }

    @Test fun freshnessRoundingAndAge() {
        val spots = PotaParsers.spots(res("spots"))
        val t = spots[0].spottedMillis
        assertTrue(PotaParsers.current(spots, t + 86_400_000L).isEmpty())
        assertEquals(30.3, PotaParsers.rounded(30.2672), 1e-9)
        assertEquals(-97.7, PotaParsers.rounded(-97.7431), 1e-9)
        assertEquals("just now", PotaParsers.ageText(t, t + 30_000))
        assertEquals("2 h ago", PotaParsers.ageText(t, t + 7_200_000))
    }
}
