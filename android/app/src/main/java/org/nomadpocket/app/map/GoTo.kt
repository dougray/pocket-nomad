// GoTo.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// "Go to": compass navigation to a place or along a drawn route. Port of GoTo.swift.
// Needs only the GPS and the compass, so it works with no signal.

package org.nomadpocket.app.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GoToTarget(val name: String, val waypoints: List<ShapePoint>, val index: Int = 0, val line: List<ShapePoint> = emptyList()) {
    val current: ShapePoint get() = waypoints[minOf(index, waypoints.size - 1)]
    val isRoute: Boolean get() = waypoints.size > 1
    val isLast: Boolean get() = index >= waypoints.size - 1
}

object RouteGeometry {
    /** Metres from a point to the nearest place on a polyline (flat-earth maths, fine over a few km). */
    fun distance(p: ShapePoint, line: List<ShapePoint>): Double {
        if (line.size < 2) return line.firstOrNull()?.let { KnownRadioParser.distance(p.latitude, p.longitude, it.latitude, it.longitude) } ?: Double.POSITIVE_INFINITY
        val k = Math.cos(Math.toRadians(p.latitude))
        fun x(c: ShapePoint) = (c.longitude - p.longitude) * 111_320 * k
        fun y(c: ShapePoint) = (c.latitude - p.latitude) * 110_540
        var best = Double.POSITIVE_INFINITY
        for (i in 1 until line.size) {
            val ax = x(line[i - 1]); val ay = y(line[i - 1]); val dx = x(line[i]) - ax; val dy = y(line[i]) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else maxOf(0.0, minOf(1.0, -(ax * dx + ay * dy) / len2))
            val cx = ax + t * dx; val cy = ay + t * dy
            best = minOf(best, Math.sqrt(cx * cx + cy * cy))
        }
        return best
    }
}

object GoToMath {
    /** Further than this from the planned road for a few fixes in a row means you left the route. */
    const val OFF_ROUTE_METERS = 60.0
    const val OFF_ROUTE_FIXES = 3
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
    /** What the active road route was planned for, so it can be planned again if you leave it. */
    private var destination: ShapePoint? = null
    private var mode = TravelMode.FOOT
    private var prefs = RoutePrefs()
    private var planner: (suspend (TravelMode, RoutePrefs, ShapePoint, ShapePoint) -> Route?)? = null
    private var offRouteFixes = 0
    private var rerouting = false
    private var backoff = 0
    private val _reroutes = MutableStateFlow(0)
    /** Counts reroutes, so the screen can say "Rerouted". */
    val reroutes: StateFlow<Int> = _reroutes
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    fun start(name: String, latitude: Double, longitude: Double) = begin(GoToTarget(name, listOf(ShapePoint(latitude, longitude))))
    /** Turn-by-turn along a planned road route: each turn is a waypoint, the route is drawn on the map. */
    fun start(name: String, route: Route, destination: ShapePoint, mode: TravelMode, prefs: RoutePrefs,
              planner: suspend (TravelMode, RoutePrefs, ShapePoint, ShapePoint) -> Route?) {
        this.destination = destination; this.mode = mode; this.prefs = prefs; this.planner = planner; offRouteFixes = 0; backoff = 0
        start(name, route)
    }

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
        // Off the planned road for a few fixes in a row: plan again from here.
        val dest = destination
        if (next.line.isNotEmpty() && dest != null && !_arrived.value) {
            if (RouteGeometry.distance(position, next.line) > GoToMath.OFF_ROUTE_METERS) offRouteFixes++ else offRouteFixes = 0
            if (offRouteFixes >= GoToMath.OFF_ROUTE_FIXES && !rerouting && backoff <= 0) reroute(position, dest, next.name)
            if (backoff > 0) backoff--
        }
    }

    private fun reroute(from: ShapePoint, dest: ShapePoint, name: String) {
        val plan = planner ?: return
        rerouting = true
        scope.launch {
            val route = plan(mode, prefs, from, dest)
            rerouting = false; offRouteFixes = 0
            if (route == null) { backoff = 20; return@launch }   // no road route from here: try again in a while
            val turns = route.steps.drop(1).map { ShapePoint(it.latitude, it.longitude) }
            if (turns.isNotEmpty()) { _target.value = GoToTarget(name, turns, 0, route.points); _reroutes.value += 1 }
        }
    }

    /** Ask the app to bring the Map to the front (without starting navigation). */
    fun requestMap() { _activations.value += 1 }

    fun skip() { val t = _target.value ?: return; if (!t.isLast) _target.value = t.copy(index = t.index + 1) }
    fun stop() { _target.value = null; _arrived.value = false }
}
