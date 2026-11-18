package org.nomadpocket.ham

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SatelliteStoreTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("satellites/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun favouritesPassesAndPersistence() = runBlocking {
        val dir = Files.createTempDirectory("sat").toFile()
        val prefs = MemoryHamPrefs()
        SatelliteStore.configure(dir, prefs)
        val start = 1_794_953_000_000L
        SatelliteStore.now = { start }
        SatelliteStore.fetcher = { g -> if (g == "stations") res("stations.tle") else res("amateur.tle") }
        SatelliteStore.locate = { Observer(39.74, -104.99, 0.0) }
        assertEquals(setOf(SatelliteStore.ISS_ID), SatelliteStore.state.value.favorites)
        SatelliteStore.refresh()
        val s = SatelliteStore.state.value
        assertTrue(s.satellites.size > 5); assertNull(s.error); assertEquals(start, s.fetchedAtMillis)
        assertNotNull(s.iss)
        assertEquals(0.0, SatelliteStore.orbitAgeDays()!!, 1e-9)
        SatelliteStore.updateObserver()
        assertEquals(39.74, SatelliteStore.state.value.observer!!.latitude, 1e-9)
        SatelliteStore.computePasses(6.0)
        val p = SatelliteStore.state.value.passes
        assertTrue(p.isNotEmpty()); assertTrue(p.all { it.noradId == SatelliteStore.ISS_ID })
        assertEquals(1_794_953_065_700.0, p[0].startMillis.toDouble(), 20_000.0)
        // Saved data and favourites come back after a restart.
        val other = s.satellites.first { it.noradId != SatelliteStore.ISS_ID }
        SatelliteStore.toggleFavorite(other.noradId)
        SatelliteStore.configure(dir, prefs)
        val back = SatelliteStore.state.value
        assertEquals(s.satellites.size, back.satellites.size)
        assertEquals(setOf(SatelliteStore.ISS_ID, other.noradId), back.favorites)
        assertEquals(39.74, back.observer!!.latitude, 1e-9)
        SatelliteStore.toggleFavorite(other.noradId)
        assertFalse(other.noradId in SatelliteStore.state.value.favorites)
        // A failed download keeps the old data and says so.
        SatelliteStore.fetcher = { error("offline") }
        SatelliteStore.refresh()
        assertTrue(SatelliteStore.state.value.error!!.contains("Showing the last"))
        assertEquals(s.satellites.size, SatelliteStore.state.value.satellites.size)
        SatelliteStore.deleteAll()
        assertTrue(SatelliteStore.state.value.satellites.isEmpty()); assertNull(SatelliteStore.state.value.observer)
    }
}
