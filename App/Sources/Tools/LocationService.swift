import CoreLocation
import Observation

/// GPS works with no cell service, so position, heading and altitude are always
/// available outdoors. One shared manager feeds the compass, map and daylight tools.
@MainActor @Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    private(set) var location: CLLocation?
    struct Heading: Equatable, Sendable { let magneticHeading: Double; let trueHeading: Double }

    private(set) var heading: Heading?
    private(set) var authorization: CLAuthorizationStatus
    private let manager = CLLocationManager()
    private var users = 0

    override private init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.headingFilter = 1
    }

    /// Call from onAppear / onDisappear so sensors only run while a screen needs them.
    func begin() {
        users += 1
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
        if CLLocationManager.headingAvailable() { manager.startUpdatingHeading() }
    }

    func end() {
        users = max(0, users - 1)
        guard users == 0 else { return }
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    private var backgroundSession: CLBackgroundActivitySession?

    /// Keeps GPS running while the phone is locked or another app is open
    /// (drive mode). Uses "while using" permission: iOS shows the blue location
    /// pill so the user can see it is on. Pair every call with `endBackground()`.
    func beginBackground() {
        begin()
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
        manager.activityType = .automotiveNavigation
        backgroundSession = CLBackgroundActivitySession()
    }

    func endBackground() {
        backgroundSession?.invalidate()
        backgroundSession = nil
        manager.allowsBackgroundLocationUpdates = false
        manager.pausesLocationUpdatesAutomatically = true
        manager.activityType = .other
        end()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in self.location = last }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let h = Heading(magneticHeading: newHeading.magneticHeading, trueHeading: newHeading.trueHeading)
        Task { @MainActor in self.heading = h }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in self.authorization = status }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {}
}
