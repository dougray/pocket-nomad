import SwiftUI

enum ToolKind: String, CaseIterable, Identifiable, Hashable {
    case compass, sos, morse, water, medicalID, checklists, sun

    var id: String { rawValue }

    var title: String {
        switch self {
        case .compass: "Compass & Position"
        case .sos: "SOS Light"
        case .morse: "Morse Code"
        case .water: "Water Purifier"
        case .medicalID: "Medical ID"
        case .checklists: "Checklists"
        case .sun: "Daylight"
        }
    }

    var symbol: String {
        switch self {
        case .compass: "location.north.circle.fill"
        case .sos: "flashlight.on.fill"
        case .morse: "dot.radiowaves.left.and.right"
        case .water: "drop.fill"
        case .medicalID: "heart.text.square.fill"
        case .checklists: "checklist"
        case .sun: "sun.horizon.fill"
        }
    }

    /// The tool that helps with a question, if one obviously does.
    static func related(to question: String) -> ToolKind? {
        let q = question.lowercased()
        let rules: [(ToolKind, [String])] = [
            (.water, ["bleach", "purif", "disinfect water", "boil water", "drinking water", "safe to drink", "gallon of water"]),
            (.sos, ["sos", "signal", "rescue", "flashlight"]),
            (.morse, ["morse"]),
            (.compass, ["compass", "which way is north", "coordinates", "mgrs", "my location", "where am i"]),
            (.sun, ["sunset", "sunrise", "daylight", "dark"]),
            (.medicalID, ["medical id", "blood type"]),
        ]
        return rules.first { _, words in words.contains { q.contains($0) } }?.0
    }

    @MainActor @ViewBuilder
    var destination: some View {
        switch self {
        case .compass: CompassView()
        case .sos: SignalLightView(initialText: "SOS", title: "SOS Light")
        case .morse: MorseView()
        case .water: WaterCalculatorView()
        case .medicalID: MedicalIDView()
        case .checklists: ChecklistsView()
        case .sun: DaylightView()
        }
    }
}
