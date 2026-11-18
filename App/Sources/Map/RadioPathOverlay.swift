// RadioPathOverlay.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// The radio path you last checked, kept on the map as a line coloured by the verdict
// (green clear, orange tight, red blocked), until you clear it.

import Foundation
import Observation

@MainActor
@Observable
final class RadioPathOverlay {
    static let shared = RadioPathOverlay()
    private(set) var from: MapShape.Coordinate?
    private(set) var to: MapShape.Coordinate?
    private(set) var verdict: PathVerdict = .unknown
    private(set) var label = ""
    private(set) var distance = 0.0

    var isActive: Bool { from != nil && to != nil }

    func show(from: MapShape.Coordinate, to: MapShape.Coordinate, verdict: PathVerdict, label: String, distance: Double) {
        self.from = from; self.to = to; self.verdict = verdict; self.label = label; self.distance = distance
    }

    func clear() { from = nil; to = nil; label = "" }
}
