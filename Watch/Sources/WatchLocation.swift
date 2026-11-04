import CoreLocation
import Observation

/// The watch's own GPS and compass. Runs only while a screen that needs it is open.
@MainActor @Observable
final class WatchLocation: NSObject, CLLocationManagerDelegate {
    static let shared = WatchLocation()

    private(set) var location: CLLocation?
    /// Degrees from true north when known, else magnetic.
    private(set) var heading: Double?
    private(set) var authorization: CLAuthorizationStatus
    private let manager = CLLocationManager()
    private var users = 0

    var headingAvailable: Bool { CLLocationManager.headingAvailable() }
    var isDenied: Bool { authorization == .denied || authorization == .restricted }

    override private init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.headingFilter = 1
    }

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

    /// A fix no older than two minutes, for the mesh SOS. nil if there isn't one
    /// (the phone then uses its own location).
    var recentFix: CLLocationCoordinate2D? {
        guard let location, !isDenied, location.horizontalAccuracy >= 0,
              location.timestamp.timeIntervalSinceNow > -120 else { return nil }
        return location.coordinate
    }

    #if DEBUG
    func setDemo(_ coordinate: CLLocationCoordinate2D, heading: Double) {
        location = CLLocation(coordinate: coordinate, altitude: 1609, horizontalAccuracy: 5,
                              verticalAccuracy: 5, timestamp: Date())
        self.heading = heading
    }
    #endif

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in self.location = last }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let h = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        Task { @MainActor in self.heading = h }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in self.authorization = status }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {}
}
