import SwiftUI

/// Household bleach dosing from the EPA/CDC emergency disinfection guidance:
/// per gallon of clear water, 8 drops of 6% bleach or 6 drops of 8.25% bleach;
/// double it for cloudy, colored or very cold water; wait 30 minutes.
struct WaterCalculatorView: View {
    enum Unit: String, CaseIterable, Identifiable { case gallons, liters; var id: Self { self } }
    enum Strength: String, CaseIterable, Identifiable {
        case six = "6%", eight = "8.25%"
        var id: Self { self }
        var dropsPerGallon: Double { self == .six ? 8 : 6 }
    }

    @State private var amount = 1.0
    @State private var unit: Unit = .gallons
    @State private var strength: Strength = .six
    @State private var cloudy = false
    @State private var location = LocationService.shared

    private var gallons: Double { unit == .gallons ? amount : amount / 3.785 }
    private var drops: Int { Self.drops(gallons: gallons, strength: strength, cloudy: cloudy) }

    static func drops(gallons: Double, strength: Strength, cloudy: Bool) -> Int {
        Int((gallons * strength.dropsPerGallon * (cloudy ? 2 : 1)).rounded(.up))
    }

    /// Reads "5 gallons of cloudy water with 8.25% bleach" into an exact dose, so Ask
    /// can show arithmetic the app did instead of arithmetic a small model guessed.
    static func dose(for question: String) -> String? {
        let q = question.lowercased()
        guard q.contains("bleach") else { return nil }
        let words = ["half a": 0.5, "half an": 0.5, "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4,
                     "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10]
        let pattern = #"\b(\d+(?:\.\d+)?|half an?|an?|one|two|three|four|five|six|seven|eight|nine|ten)\s+(gallons?|gal|liters?|litres?|l\b|quarts?|qt)|(\d+(?:\.\d+)?)\s*(gallons?|gal|liters?|litres?|l\b|quarts?|qt)"#
        let amount: Double
        let unit: Substring
        if let match = q.firstMatch(of: try! Regex(pattern)) {
            let number = (match.output[1].substring ?? match.output[3].substring).map(String.init) ?? ""
            guard let value = Double(number) ?? words[number],
                  let u = match.output[2].substring ?? match.output[4].substring else { return nil }
            amount = value
            unit = u
        } else if q.contains("water"), ["purif", "disinfect", "drink", "safe", "treat"].contains(where: q.contains) {
            // "How much bleach makes water safe?" has no amount: answer per gallon.
            amount = 1
            unit = "gallon"
        } else {
            return nil
        }
        let gallons: Double = unit.hasPrefix("gal") ? amount : unit.hasPrefix("q") ? amount / 4 : amount / 3.785
        let strength: Strength = q.contains("8.25") ? .eight : .six
        let cloudy = ["cloudy", "murky", "muddy", "colored", "coloured", "cold"].contains { q.contains($0) }
        let n = drops(gallons: gallons, strength: strength, cloudy: cloudy)
        let tsp = Double(n) / 64
        let measure = tsp >= 0.125 ? " (about \(kitchenMeasure(tsp)))" : ""
        let unitName = (String(unit).hasPrefix("l") ? "liter" : String(unit).hasPrefix("q") ? "quart" : "gallon") + (amount == 1 ? "" : "s")
        return "\(amount.formatted()) \(unitName) of \(cloudy ? "cloudy" : "clear") water with \(strength.rawValue) bleach: \(n) drops\(measure). Stir, wait 30 minutes."
    }

    var body: some View {
        Form {
            Section("Water") {
                Stepper(value: $amount, in: 0.25...100, step: amount < 2 ? 0.25 : 1) {
                    Text("\(amount.formatted(.number.precision(.fractionLength(0...2)))) \(amount == 1 ? String(unit.rawValue.dropLast()) : unit.rawValue)")
                        .font(.title3.weight(.semibold))
                }
                Picker("Unit", selection: $unit) { ForEach(Unit.allCases) { Text($0.rawValue.capitalized).tag($0) } }
                    .pickerStyle(.segmented)
                Toggle("Cloudy, colored or very cold", isOn: $cloudy)
            }
            Section("Plain, unscented bleach") {
                Picker("Strength on label", selection: $strength) { ForEach(Strength.allCases) { Text($0.rawValue).tag($0) } }
                    .pickerStyle(.segmented)
            }
            Section("Add") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(drops) drops").font(.system(size: 44, weight: .bold, design: .rounded))
                    Text(teaspoons).foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
                Label("Stir and wait 30 minutes.", systemImage: "timer")
                Label("It should smell slightly of chlorine. If not, add the same dose again and wait 15 more minutes.", systemImage: "nose")
                Label("If it is cloudy, let it settle and pour it through a clean cloth or coffee filter first.", systemImage: "line.3.horizontal.decrease")
            }
            Section("Or boil it") {
                Label(boilText, systemImage: "flame.fill")
                Text("Boiling is the surest way to kill germs. Let it cool, and pour it between clean containers to improve the taste.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Text("Never use scented, color-safe or \"splash-less\" bleach, or bleach with added cleaners. Bleach and boiling don't remove chemicals, fuel or salt; find another source if you suspect them.")
                    .font(.footnote).foregroundStyle(.secondary)
                NavigationLink("Read the full water guide") { WaterGuideLink() }
            }
        }
        .navigationTitle("Water Purifier")
        .onAppear { location.begin() }
        .onDisappear { location.end() }
    }

    private var teaspoons: String {
        // CDC: 1/8 teaspoon is about 8 drops.
        let tsp = Double(drops) / 64
        guard tsp >= 0.125 else { return "Use a clean dropper or the tip of a spoon." }
        return "About \(Self.kitchenMeasure(tsp)) (\(max(1, Int((tsp * 4.93).rounded()))) mL)"
    }

    /// 0.125 -> "1/8 teaspoon", 1.5 -> "1 1/2 teaspoons", 7 -> "2 1/3 tablespoons".
    static func kitchenMeasure(_ tsp: Double) -> String {
        let useTbsp = tsp >= 3
        let amount = useTbsp ? tsp / 3 : tsp
        let fractions: [(Double, String)] = [(0, ""), (0.125, "1/8"), (0.25, "1/4"), (1.0 / 3, "1/3"), (0.5, "1/2"), (2.0 / 3, "2/3"), (0.75, "3/4"), (1, "")]
        var whole = Int(amount)
        let rest = amount - Double(whole)
        let nearest = fractions.min { abs($0.0 - rest) < abs($1.0 - rest) }!
        if nearest.0 == 1 { whole += 1 }
        let fraction = nearest.0 == 1 ? "" : nearest.1
        let number = [whole > 0 ? "\(whole)" : "", fraction].filter { !$0.isEmpty }.joined(separator: " ")
        let unit = useTbsp ? "tablespoon" : "teaspoon"
        return "\(number) \(unit)\(amount > 1.01 ? "s" : "")"
    }

    private var boilText: String {
        if let alt = location.location?.altitude, location.location?.verticalAccuracy ?? -1 >= 0 {
            let high = alt > 1981 // 6,500 ft
            return "Bring to a rolling boil for \(high ? "3 minutes" : "1 minute"). You are at about \(Int(alt * 3.28084)) ft\(high ? ", which needs the longer boil" : "")."
        }
        return "Bring to a rolling boil for 1 minute, or 3 minutes above 6,500 ft (1,981 m)."
    }
}

private struct WaterGuideLink: View {
    var body: some View {
        if let guide = GuideStore.categories.first(where: { $0.id == "water-sanitation" })?.guides.first {
            GuideReaderView(guide: guide)
        } else {
            ContentUnavailableView("Guide not found", systemImage: "drop")
        }
    }
}
