// ConditionsModels.swift
// Pocket Nomad - Conditions module
// SPDX-License-Identifier: Apache-2.0
//
// Snapshots of free, keyless public data: USGS earthquakes, National Weather
// Service alerts, Open-Meteo forecasts, NOAA space weather and NOAA Weather
// Radio transmitters. Each is downloaded when the user taps Update and then
// read from the phone, so it still works with no signal.

import Foundation

struct Quake: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var magnitude: Double
    var place: String
    var time: Date
    var latitude: Double
    var longitude: Double
    var depthKm: Double
    var url: String?
    var tsunami: Bool
    var felt: Int?
    /// From USGS's "significant" feed.
    var significant = false
}

struct WeatherAlert: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var event: String
    /// Extreme, Severe, Moderate, Minor, Unknown.
    var severity: String
    var urgency: String
    var headline: String
    var detail: String
    var instruction: String?
    var sender: String
    var areas: String
    var effective: Date?
    var expires: Date?

    /// 0 = most severe, for sorting.
    var rank: Int {
        switch severity {
        case "Extreme": 0
        case "Severe": 1
        case "Moderate": 2
        case "Minor": 3
        default: 4
        }
    }

    func isExpired(at now: Date) -> Bool { expires.map { $0 < now } ?? false }
}

struct ForecastHour: Codable, Equatable, Sendable {
    var time: Date
    var temperatureC: Double
    var precipitationMm: Double
    var precipitationChance: Int?
    var windKmh: Double
    var gustKmh: Double
    var windDirection: Int
    var code: Int
}

struct Forecast: Codable, Equatable, Sendable {
    /// The rounded position that was asked for.
    var latitude: Double
    var longitude: Double
    var hours: [ForecastHour]
}

struct KpPoint: Codable, Equatable, Sendable {
    var time: Date
    var kp: Double
    var predicted: Bool
}

struct SpaceWeather: Codable, Equatable, Sendable {
    /// Newest observed Kp.
    var kpNow: Double
    var kpTime: Date
    /// Observed and forecast, oldest first.
    var kp: [KpPoint]
    /// Current NOAA scales, 0-5 (radio blackout, solar radiation, geomagnetic storm).
    var radio: Int
    var radiation: Int
    var geomagnetic: Int
    /// Highest geomagnetic scale expected in the next three days.
    var geomagneticOutlook: Int
}

struct WeatherRadioStation: Codable, Equatable, Identifiable, Sendable {
    var callsign: String
    var name: String
    var state: String
    /// MHz, e.g. "162.550".
    var frequency: String
    var latitude: Double
    var longitude: Double
    var powerWatts: Int
    var status: String
    /// SAME county codes this transmitter covers.
    var counties: [String]
    var id: String { callsign }
}

/// What one download of one source holds, and when.
struct Snapshot<Value: Codable & Sendable>: Codable, Sendable {
    var fetchedAt: Date
    var value: Value
}

enum ConditionsSettings {
    static let radiusKey = "conditions.quakeRadiusKm"
    static let magnitudeKey = "conditions.quakeMinMagnitude"
    static let autoRefreshKey = "conditions.refreshOnOpen"

    static var quakeRadiusKm: Int {
        get { let v = UserDefaults.standard.integer(forKey: radiusKey); return v == 0 ? 500 : v }
        set { UserDefaults.standard.set(newValue, forKey: radiusKey) }
    }
    static var quakeMinMagnitude: Double {
        get { let v = UserDefaults.standard.double(forKey: magnitudeKey); return v == 0 ? 2.5 : v }
        set { UserDefaults.standard.set(newValue, forKey: magnitudeKey) }
    }
    static var refreshOnOpen: Bool {
        get { UserDefaults.standard.bool(forKey: autoRefreshKey) }
        set { UserDefaults.standard.set(newValue, forKey: autoRefreshKey) }
    }
}
