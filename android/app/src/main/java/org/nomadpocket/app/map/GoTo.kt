// GoTo.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// "Go to": compass navigation to a place or along a drawn route. Port of GoTo.swift.
// Needs only the GPS and the compass, so it works with no signal.

package org.nomadpocket.app.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GoToTarget(val name: String, val waypoints: List<ShapePoint>, val index: Int = 0, val line: List<ShapePoint> = emptyList()) {
    val current: ShapePoint get() = waypoints[minOf(index, waypoints.size - 1)]
    val isRoute: Boolean get() = waypoints.size > 1
    val isLast: Boolean get() = index >= waypoints.size - 1
}

object GoToMath {
    /** Close enough to call a waypoint reached. */
    const val ARRIVAL_RADIUS = 25.0
    /** Walking pace, metres a second (4.8 km/h). */
    const val WALKING_SPEED = 1.34

    private fun dist(a: ShapePoint, b: ShapePoint) = KnownRadioParser.distance(a.latitude, a.longitude, b.latitude, b.longitude)

    fun leg(a: ShapePoint, b: ShapePoint): Pair<Double, Double> = dist(a, b) to ShapeMath.bearing(a, b)

    /** How far to turn from the way the phone points to face the target, -180 to 180 (positive = turn right). */
    fun arrowAngle(bearing: Double, heading: Double): Double {
        var d = (bearing - heading) % 360
        if (d > 180) d -= 360 else if (d < -180) d += 360
        return d
    }

    fun advanced(t: GoToTarget, position: ShapePoint): GoToTarget {
        var r = t
        while (!r.isLast && dist(position, r.current) <= ARRIVAL_RADIUS) r = r.copy(index = r.index + 1)
        return r
    }

    fun arrived(t: GoToTarget, position: ShapePoint) = t.isLast && dist(position, t.current) <= ARRIVAL_RADIUS

    fun remaining(t: GoToTarget, position: ShapePoint): Double {
        var total = dist(position, t.current)
        for (i in (t.index + 1) until t.waypoints.size) total += dist(t.waypoints[i - 1], t.waypoints[i])
        return total
    }

    fun eta(distance: Double, speed: Double = WALKING_SPEED) = distance / speed

    fun etaText(seconds: Double): String {
        if (seconds < 45) return "under a minute"
        val m = Math.round(seconds / 60).toInt()
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}

/** What the Map is navigating to, shared so any screen can start or stop it. */
object NavigationState {
    private val _target = MutableStateFlow<GoToTarget?>(null)
    val target: StateFlow<GoToTarget?> = _target
    private val _arrived = MutableStateFlow(false)
    val arrived: StateFlow<Boolean> = _arrived
    /** Bumped when a destination is set, so the app can bring the Map to the front. */
    private val _activations = MutableStateFlow(0)
    val activations: StateFlow<Int> = _activations

    fun start(name: String, latitude: Double, longitude: Double) = begin(GoToTarget(name, listOf(ShapePoint(latitude, longitude))))
    /** Turn-by-turn along a planned road route: each turn is a waypoint, the route is drawn on the map. */
    fun start(name: String, route: Route) {
        val turns = route.steps.drop(1).map { ShapePoint(it.latitude, it.longitude) }
        if (turns.isNotEmpty()) begin(GoToTarget(name, turns, 0, route.points))
    }

    fun start(route: MapShape) { if (route.points.isNotEmpty()) begin(GoToTarget(route.name, route.points)) }

    private fun begin(t: GoToTarget) { _target.value = t; _arrived.value = false; _activations.value += 1 }

    fun update(position: ShapePoint) {
        val t = _target.value ?: return
        val next = GoToMath.advanced(t, position)
        if (next != t) _target.value = next
        _arrived.value = GoToMath.arrived(next, position)
    }

    fun skip() { val t = _target.value ?: return; if (!t.isLast) _target.value = t.copy(index = t.index + 1) }
    fun stop() { _target.value = null; _arrived.value = false }
}
