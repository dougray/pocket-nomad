package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.map.*

class DirectionsTest {
    private val ways = Overpass.parse(javaClass.getResource("/roads/roads-fixture.json")!!.readText())
    private fun c(i: Int, j: Int) = ShapePoint(30.0 + i * 0.001, -97.0 + j * 0.001)

    @Test fun parse() {
        assertEquals(6, ways.size)
        assertTrue(ways.first { it.name == "Oak Ave" }.oneway)
        assertFalse(ways.first { it.name == "Main St" }.oneway)
    }

    @Test fun carRouteUsesStreets() {
        val r = RoadGraph(ways, TravelMode.CAR).route(c(0, 0), c(2, 2))!!
        assertEquals(2 * 96.4 + 2 * 111.2, r.meters, 8.0) // two blocks east, two north
        assertTrue(r.steps.none { it.text.contains("Park Trail") })
        assertEquals("Arrive", r.steps.last().text)
    }

    @Test fun footUsesTrail() {
        val r = RoadGraph(ways, TravelMode.FOOT).route(c(2, 2), c(2, 0))!!
        assertEquals(2 * 96.4, r.meters, 8.0)
        assertTrue(r.steps.first().text.contains("Park Trail"))
    }

    @Test fun onewayHonouredForCarsOnly() {
        val car = RoadGraph(ways, TravelMode.CAR).route(c(1, 2), c(1, 0))!!
        val foot = RoadGraph(ways, TravelMode.FOOT).route(c(1, 2), c(1, 0))!!
        assertTrue(car.meters > foot.meters + 150)
        assertEquals(2 * 96.4, foot.meters, 8.0)
    }

    @Test fun turnsAndFailures() {
        val r = RoadGraph(ways, TravelMode.CAR).route(c(0, 0), c(2, 2))!!
        assertTrue(r.steps.joinToString(" | ") { it.text }, r.steps[1].text.startsWith("Turn"))
        assertNull(RoadGraph(ways, TravelMode.CAR).route(c(0, 0), ShapePoint(40.0, -97.0)))
        assertNull(RoadGraph(emptyList(), TravelMode.CAR).route(c(0, 0), c(1, 1)))
        assertTrue(RoadStore.cells(30.27, -97.74, 10.0).size in 9..30)
    }
}

class RouteSafetyTest {
    private fun c(i: Int, j: Int) = ShapePoint(30.0 + i * 0.001, -97.0 + j * 0.001)

    @Test fun distanceToLineAndOffRoute() {
        val line = listOf(c(0, 0), c(0, 2))
        assertEquals(0.0, RouteGeometry.distance(c(0, 1), line), 0.5)
        assertEquals(55.6, RouteGeometry.distance(ShapePoint(30.0005, -96.999), line), 1.0)
        assertEquals(289.2, RouteGeometry.distance(ShapePoint(30.0, -96.995), line), 1.0)
        assertTrue(RouteGeometry.distance(ShapePoint(30.001, -96.999), line) > GoToMath.OFF_ROUTE_METERS)
    }

    @Test fun avoidOptionsRemoveRoads() {
        fun way(id: Long, hw: String, a: Long, b: Long, unpaved: Boolean = false) =
            RoadWay(id, hw, "W$id", false, longArrayOf(a, b), doubleArrayOf(30.0, 30.0), doubleArrayOf(-97 + a * 0.001, -97 + b * 0.001), unpaved)
        val ways = listOf(way(1, "motorway", 1, 2), way(2, "residential", 2, 3), way(3, "track", 3, 4, true))
        fun names(p: RoutePrefs, m: TravelMode) = RoadGraph(ways, m, p).route(ShapePoint(30.0, -96.999), ShapePoint(30.0, -96.996))?.steps?.joinToString(" | ") { it.text } ?: "none"
        assertTrue(names(RoutePrefs(), TravelMode.CAR).let { "W1" in it && "W3" in it })
        assertFalse("W3" in names(RoutePrefs(avoidUnpaved = true), TravelMode.BIKE))
        assertTrue("W3" in names(RoutePrefs(avoidUnpaved = true), TravelMode.FOOT))
        assertFalse("W1" in names(RoutePrefs(avoidHighways = true), TravelMode.CAR))
    }
}
