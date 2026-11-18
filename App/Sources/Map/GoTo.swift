// GoTo.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// "Go to": compass navigation to a place or along a drawn route. An arrow
// points at the next waypoint; it shows distance, heading and a walking-time
// estimate. Works with no signal: it needs only the GPS and the compass.

import CoreLocation
import Foundation
import Observation

struct GoToTarget: Equatable, Sendable {
    var name: String
    var waypoints: [MapShape.Coordinate]
    var index = 0
    /// The full road route to draw on the map (empty for a straight-line Go to).
    var line: [MapShape.Coordinate] = []

    var current: MapShape.Coordinate { waypoints[min(index, waypoints.count - 1)] }
    var isRoute: Bool { waypoints.count > 1 }
    var isLast: Bool { index >= waypoints.count - 1 }
}

enum RouteGeometry {
    /// Metres from a point to the nearest place on a polyline (flat-earth maths, fine over a few km).
    static func distance(from p: MapShape.Coordinate, toLine line: [MapShape.Coordinate]) -> Double {
        guard line.count >= 2 else { return line.first.map { CoverageShare.distance(p.latitude, p.longitude, $0.latitude, $0.longitude) } ?? .infinity }
        let k = cos(p.latitude * .pi / 180)
        func xy(_ c: MapShape.Coordinate) -> (Double, Double) { ((c.longitude - p.longitude) * 111_320 * k, (c.latitude - p.latitude) * 110_540) }
        var best = Double.infinity
        for i in 1..<line.count {
            let a = xy(line[i - 1]), b = xy(line[i])
            let dx = b.0 - a.0, dy = b.1 - a.1
            let len2 = dx * dx + dy * dy
            let t = len2 == 0 ? 0 : max(0, min(1, -(a.0 * dx + a.1 * dy) / len2))
            let cx = a.0 + t * dx, cy = a.1 + t * dy
            best = min(best, (cx * cx + cy * cy).squareRoot())
        }
        return best
    }
}

enum GoToMath {
    /// Further than this from the planned road for a few fixes in a row means you left the route.
    static let offRouteMeters = 60.0
    static let offRouteFixes = 3
    /// Close enough to call a waypoint reached.
    static let arrivalRadius = 25.0
    /// Walking pace, metres a second (4.8 km/h).
    static let walkingSpeed = 1.34

    static func leg(from a: MapShape.Coordinate, to b: MapShape.Coordinate) -> (distance: Double, bearing: Double) {
        (CoverageShare.distance(a.latitude, a.longitude, b.latitude, b.longitude), ShapeMath.bearing(a, b))
    }

    /// How far to turn from the way the phone points to face the target, -180 to 180 (positive = turn right).
    static func arrowAngle(bearing: Double, heading: Double) -> Double {
        var d = (bearing - heading).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 } else if d < -180 { d += 360 }
        return d
    }

    /// Moves a route on to its next waypoint once the current one is reached. A single place stays put.
    static func advanced(_ t: GoToTarget, position: MapShape.Coordinate) -> GoToTarget {
        var t = t
        while !t.isLast, leg(from: position, to: t.current).distance <= arrivalRadius { t.index += 1 }
        return t
    }

    static func arrived(_ t: GoToTarget, position: MapShape.Coordinate) -> Bool {
        t.isLast && leg(from: position, to: t.current).distance <= arrivalRadius
    }

    /// Metres to the current waypoint plus the legs after it.
    static func remaining(_ t: GoToTarget, position: MapShape.Coordinate) -> Double {
        var total = leg(from: position, to: t.current).distance
        if t.index < t.waypoints.count - 1 {
            for i in (t.index + 1)..<t.waypoints.count { total += leg(from: t.waypoints[i - 1], to: t.waypoints[i]).distance }
        }
        return total
    }

    static func eta(distance: Double, speed: Double = walkingSpeed) -> TimeInterval { distance / speed }

    static func etaText(_ seconds: TimeInterval) -> String {
        if seconds < 45 { return "under a minute" }
        let m = Int((seconds / 60).rounded())
        if m < 60 { return "\(m) min" }
        return "\(m / 60) h \(m % 60) min"
    }
}

/// What the Map is navigating to, shared so any screen can start or stop it.
@MainActor
@Observable
final class NavigationState {
    static let shared = NavigationState()
    private(set) var target: GoToTarget?
    private(set) var arrived = false
    /// Bumped when a destination is set, so the app can bring the Map to the front.
    private(set) var activations = 0
    /// What the active road route was planned for, so it can be planned again if you leave it.
    private(set) var destination: (name: String, latitude: Double, longitude: Double)?
    private(set) var mode: TravelMode = .foot
    private(set) var prefs = RoutePrefs()
    private(set) var offRouteFixes = 0
    private(set) var rerouting = false
    /// Counts reroutes, so the screen can say "Rerouted".
    private(set) var reroutes = 0
    private var rerouteBackoff = 0

    func start(name: String, latitude: Double, longitude: Double) {
        begin(GoToTarget(name: name, waypoints: [.init(latitude: latitude, longitude: longitude)]))
    }

    /// Turn-by-turn along a planned road route: each turn is a waypoint, the route is drawn on the map.
    func start(name: String, route: Route, destination: (latitude: Double, longitude: Double)? = nil, mode: TravelMode = .foot, prefs: RoutePrefs = RoutePrefs()) {
        self.destination = destination.map { (name, $0.latitude, $0.longitude) }
        self.mode = mode; self.prefs = prefs; offRouteFixes = 0; rerouteBackoff = 0
        let turns = route.steps.map { MapShape.Coordinate(latitude: $0.latitude, longitude: $0.longitude) }
        begin(GoToTarget(name: name, waypoints: Array(turns.dropFirst()), line: route.points))
    }

    func start(route: MapShape) {
        guard route.points.count >= 1 else { return }
        begin(GoToTarget(name: route.name, waypoints: route.points))
    }

    private func begin(_ t: GoToTarget) {
        target = t
        arrived = false
        activations += 1
    }

    /// Called with each new position.
    func update(position: MapShape.Coordinate) {
        guard let t = target else { return }
        let next = GoToMath.advanced(t, position: position)
        if next != t { target = next }
        arrived = GoToMath.arrived(next, position: position)
        // Off the planned road for a few fixes in a row: plan again from here.
        if !next.line.isEmpty, destination != nil, !arrived {
            if RouteGeometry.distance(from: position, toLine: next.line) > GoToMath.offRouteMeters { offRouteFixes += 1 } else { offRouteFixes = 0 }
            if offRouteFixes >= GoToMath.offRouteFixes, !rerouting, rerouteBackoff <= 0 { Task { await reroute(from: position) } }
            if rerouteBackoff > 0 { rerouteBackoff -= 1 }
        }
    }

    private func reroute(from position: MapShape.Coordinate) async {
        guard let d = destination else { return }
        rerouting = true
        let route = await RoadStore.shared.plan(mode: mode, prefs: prefs, from: position, to: .init(latitude: d.latitude, longitude: d.longitude))
        rerouting = false
        offRouteFixes = 0
        guard let route else { rerouteBackoff = 20; return }   // no road route from here: try again in a while
        let turns = route.steps.map { MapShape.Coordinate(latitude: $0.latitude, longitude: $0.longitude) }
        target = GoToTarget(name: d.name, waypoints: Array(turns.dropFirst()), line: route.points)
        reroutes += 1
    }

    /// Ask the app to bring the Map to the front (without starting navigation).
    func requestMap() { activations += 1 }

    func skip() {
        guard var t = target, !t.isLast else { return }
        t.index += 1
        target = t
    }

    func stop() { target = nil; arrived = false }
}

/// Location and heading while Go to is on.
@MainActor
@Observable
final class NavigationCompass: NSObject, CLLocationManagerDelegate {
    static let shared = NavigationCompass()
    private(set) var location: CLLocation?
    private(set) var heading: Double?
    private let manager = CLLocationManager()
    private var running = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.headingFilter = 3
    }

    func start() {
        guard !running else { return }
        running = true
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func stop() {
        running = false
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in
            self.location = last
            NavigationState.shared.update(position: .init(latitude: last.coordinate.latitude, longitude: last.coordinate.longitude))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let h = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        Task { @MainActor in self.heading = h }
    }
}
