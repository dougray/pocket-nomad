package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.map.*
import java.io.File

class FavoritesAndGoToTest {
    private fun store() = FavoritesStore(File.createTempFile("fav", ".json").also { it.delete() })

    @Test fun quickSpotsReplaceAndSortFirst() {
        val s = store()
        s.add("Zebra", latitude = 1.0, longitude = 1.0)
        s.add("Old car", FavoriteKind.CAR, latitude = 2.0, longitude = 2.0)
        s.add("New car", FavoriteKind.CAR, latitude = 3.0, longitude = 3.0)
        s.add("Base", FavoriteKind.HOME, latitude = 4.0, longitude = 4.0)
        assertEquals(listOf("Base", "New car", "Zebra"), s.sorted.map { it.name })
    }

    @Test fun toggleAndPersistence() {
        val f = File.createTempFile("fav", ".json").also { it.delete() }
        val s = FavoritesStore(f)
        assertTrue(s.toggle("Spring", 30.0, -97.0))
        assertTrue(s.isFavorite(30.00005, -97.0))
        assertEquals(1, FavoritesStore(f).favorites.size)
        assertFalse(s.toggle("Spring", 30.0, -97.0))
        assertTrue(s.favorites.isEmpty())
    }

    @Test fun searchAndExport() {
        val s = store()
        s.add("Blue spring", FavoriteKind.WATER, latitude = 30.0, longitude = -97.0)
        assertEquals(1, s.search("water").size)
        assertEquals(0, s.search("nothing").size)
        assertTrue(s.exportGeoJson().contains("Blue spring"))
    }

    @Test fun arrowAndEta() {
        assertEquals(90.0, GoToMath.arrowAngle(100.0, 10.0), 1e-9)
        assertEquals(-20.0, GoToMath.arrowAngle(350.0, 10.0), 1e-9)
        assertEquals("under a minute", GoToMath.etaText(30.0))
        assertEquals("2 min", GoToMath.etaText(120.0))
        assertEquals("1 h 5 min", GoToMath.etaText(3900.0))
    }

    @Test fun routeAdvancesAndArrives() {
        val a = ShapePoint(30.0, -97.0); val b = ShapePoint(30.01, -97.0); val c = ShapePoint(30.02, -97.0)
        val t = GoToTarget("Loop", listOf(a, b, c))
        assertEquals(1, GoToMath.advanced(t, a).index)
        assertFalse(GoToMath.arrived(t, a))
        assertTrue(GoToMath.arrived(t.copy(index = 2), c))
        assertTrue(GoToMath.remaining(t.copy(index = 1), a) > 2000)
    }
}
