// Favorites.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Places the user stars: Home, Camp, Rally point, Car and anything else. Kept
// on the phone only. A favorite is its own entry (name and position) so it
// works from anywhere: a map pin, a searched coordinate, a heard radio, a
// satellite-free spot you just stood on.

import Foundation
import Observation

struct Favorite: Codable, Identifiable, Equatable, Sendable {
    enum Kind: String, Codable, CaseIterable, Identifiable, Sendable {
        case home, camp, rally, car, water, shelter, other
        var id: Self { self }
        var label: String {
            switch self {
            case .home: "Home"
            case .camp: "Camp"
            case .rally: "Rally point"
            case .car: "Car"
            case .water: "Water"
            case .shelter: "Shelter"
            case .other: "Favorite"
            }
        }
        var symbol: String {
            switch self {
            case .home: "house.fill"
            case .camp: "tent.fill"
            case .rally: "flag.fill"
            case .car: "car.fill"
            case .water: "drop.fill"
            case .shelter: "building.2.fill"
            case .other: "star.fill"
            }
        }
        /// Quick spots: one each, so "Go to Car" is always one tap.
        var isQuick: Bool { self == .home || self == .camp || self == .rally || self == .car }
    }

    var id = UUID()
    var name: String
    var kind: Kind = .other
    var latitude: Double
    var longitude: Double
    var note = ""
    var created = Date()

    var coordinate: MapShape.Coordinate { .init(latitude: latitude, longitude: longitude) }
}

@MainActor
@Observable
final class FavoritesStore {
    static let shared = FavoritesStore()
    private(set) var favorites: [Favorite] = []
    private let url: URL

    init(url: URL = URL.applicationSupportDirectory.appending(path: "favorites.json")) {
        self.url = url
        favorites = (try? JSONDecoder().decode([Favorite].self, from: Data(contentsOf: url))) ?? []
    }

    /// Quick spots first (Home, Camp, Rally point, Car), then the rest by name.
    var sorted: [Favorite] {
        favorites.sorted {
            let a = $0.kind.isQuick ? Favorite.Kind.allCases.firstIndex(of: $0.kind)! : 99
            let b = $1.kind.isQuick ? Favorite.Kind.allCases.firstIndex(of: $1.kind)! : 99
            return (a, $0.name.lowercased()) < (b, $1.name.lowercased())
        }
    }

    var quickSpots: [Favorite] { sorted.filter { $0.kind.isQuick } }

    @discardableResult
    func add(name: String, kind: Favorite.Kind = .other, latitude: Double, longitude: Double, note: String = "") -> Favorite {
        if kind.isQuick { favorites.removeAll { $0.kind == kind } } // one Home, one Car...
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let f = Favorite(name: clean.isEmpty ? kind.label : clean, kind: kind, latitude: latitude, longitude: longitude, note: note)
        favorites.append(f)
        save()
        return f
    }

    func remove(_ id: UUID) { favorites.removeAll { $0.id == id }; save() }
    func rename(_ id: UUID, to name: String) { update(id) { $0.name = name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? $0.name : name } }
    func setNote(_ id: UUID, _ note: String) { update(id) { $0.note = note } }
    func removeAll() { favorites = []; save() }

    private func update(_ id: UUID, _ change: (inout Favorite) -> Void) {
        guard let i = favorites.firstIndex(where: { $0.id == id }) else { return }
        change(&favorites[i])
        save()
    }

    /// A favorite within `metres` of a position (so starring the same spot twice is a no-op).
    func favorite(near latitude: Double, _ longitude: Double, within metres: Double = 20) -> Favorite? {
        favorites.first { CoverageShare.distance($0.latitude, $0.longitude, latitude, longitude) <= metres }
    }

    func isFavorite(latitude: Double, longitude: Double) -> Bool { favorite(near: latitude, longitude) != nil }

    /// Stars a place, or un-stars it if it already is one. Returns whether it is now a favorite.
    @discardableResult
    func toggle(name: String, latitude: Double, longitude: Double) -> Bool {
        if let existing = favorite(near: latitude, longitude) { remove(existing.id); return false }
        add(name: name, latitude: latitude, longitude: longitude)
        return true
    }

    func search(_ text: String) -> [Favorite] {
        let q = text.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.kind.label.localizedCaseInsensitiveContains(q) || $0.note.localizedCaseInsensitiveContains(q) }
    }

    func exportGeoJSON() -> String {
        let features: [[String: Any]] = favorites.map {
            ["type": "Feature", "properties": ["name": $0.name, "kind": $0.kind.label, "note": $0.note],
             "geometry": ["type": "Point", "coordinates": [$0.longitude, $0.latitude]]]
        }
        let data = (try? JSONSerialization.data(withJSONObject: ["type": "FeatureCollection", "features": features], options: [.sortedKeys, .prettyPrinted])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    private func save() {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(favorites).write(to: url, options: .atomic)
    }
}
