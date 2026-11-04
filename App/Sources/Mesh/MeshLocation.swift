// MeshLocation.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// One-shot GPS fix for the SOS message. Asks for "while using" permission
// only when the user sends an SOS with location.

import CoreLocation
import Foundation

@MainActor
final class MeshLocationProvider: NSObject {
    private let manager = CLLocationManager()
    private var authContinuation: CheckedContinuation<Void, Never>?
    private var fixContinuation: CheckedContinuation<CLLocation?, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    var isDenied: Bool {
        let s = manager.authorizationStatus
        return s == .denied || s == .restricted
    }

    /// Returns a coordinate, or nil if permission is refused or no fix comes
    /// within `timeout` seconds.
    func currentLocation(timeout: TimeInterval = 15) async -> CLLocation? {
        if manager.authorizationStatus == .notDetermined {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                authContinuation = c
                manager.requestWhenInUseAuthorization()
            }
        }
        let status = manager.authorizationStatus
        guard status != .denied, status != .restricted, status != .notDetermined else { return nil }

        if let recent = manager.location, recent.timestamp.timeIntervalSinceNow > -60,
           recent.horizontalAccuracy >= 0, recent.horizontalAccuracy < 100 {
            return recent
        }

        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            self?.finishFix(self?.manager.location)
        }
        let result = await withCheckedContinuation { (c: CheckedContinuation<CLLocation?, Never>) in
            fixContinuation = c
            manager.requestLocation()
        }
        timeoutTask.cancel()
        return result
    }

    private func finishFix(_ location: CLLocation?) {
        fixContinuation?.resume(returning: location)
        fixContinuation = nil
    }
}

extension MeshLocationProvider: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined else { return }
        authContinuation?.resume()
        authContinuation = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        finishFix(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishFix(manager.location)
    }
}
