import XCTest
import CoreLocation
@testable import NomadPocket

final class ToolMathTests: XCTestCase {
    func testMGRSWashingtonMonument() {
        // Published MGRS for the Washington Monument: 18S UJ 23487 06483.
        let mgrs = CoordinateFormat.mgrs(.init(latitude: 38.88952, longitude: -77.03524))
        XCTAssertNotNil(mgrs)
        XCTAssertTrue(mgrs!.hasPrefix("18S UJ 234"), mgrs!)
        XCTAssertTrue(mgrs!.contains(" 064"), mgrs!)
    }

    func testMGRSSouthernHemisphere() {
        // Sydney Opera House: 56H LH 34 51 (zone, band and square).
        let mgrs = CoordinateFormat.mgrs(.init(latitude: -33.85678, longitude: 151.21530))
        XCTAssertTrue(mgrs?.hasPrefix("56H LH") ?? false, mgrs ?? "nil")
    }

    func testMGRSOutOfRange() {
        XCTAssertNil(CoordinateFormat.mgrs(.init(latitude: 86, longitude: 0)))
    }

    func testDMS() {
        XCTAssertEqual(CoordinateFormat.dms(.init(latitude: 39.75, longitude: -104.25)), "39°45′00.0″N 104°15′00.0″W")
    }

    func testCardinal() {
        XCTAssertEqual(CoordinateFormat.cardinal(0), "N")
        XCTAssertEqual(CoordinateFormat.cardinal(359), "N")
        XCTAssertEqual(CoordinateFormat.cardinal(225), "SW")
    }

    func testMorseEncode() {
        XCTAssertEqual(Morse.encode("SOS"), "... --- ...")
        XCTAssertEqual(Morse.encode("hi you"), ".... .. / -.-- --- ..-")
    }

    func testSOSTimelineIsOneRun() {
        let t = Morse.timeline("SOS")
        let on = t.filter(\.on).map(\.units)
        XCTAssertEqual(on, [1, 1, 1, 3, 3, 3, 1, 1, 1])
        // No letter gaps (3 units) inside the SOS run, only symbol gaps.
        XCTAssertFalse(t.dropLast().contains { !$0.on && $0.units > 1 })
    }

    func testSunsetDenverMidsummer() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/Denver")!
        let date = cal.date(from: DateComponents(year: 2026, month: 6, day: 21, hour: 12))!
        let day = SolarCalculator.day(for: date, at: .init(latitude: 39.7392, longitude: -104.9903), calendar: cal)
        let sunset = cal.dateComponents([.hour, .minute], from: day.sunset!)
        let sunrise = cal.dateComponents([.hour, .minute], from: day.sunrise!)
        // Denver, June 21: sunrise about 5:32, sunset about 8:31 pm MDT.
        XCTAssertEqual(sunrise.hour, 5)
        XCTAssertEqual(Double(sunrise.minute!), 32, accuracy: 3)
        XCTAssertEqual(sunset.hour, 20)
        XCTAssertEqual(Double(sunset.minute!), 31, accuracy: 3)
    }

    func testPolarNightHasNoSunrise() {
        let date = DateComponents(calendar: .init(identifier: .gregorian), year: 2026, month: 12, day: 21, hour: 12).date!
        XCTAssertNil(SolarCalculator.day(for: date, at: .init(latitude: 78.2, longitude: 15.6)).sunrise)
    }

    func testWorldMapHeader() throws {
        let info = try XCTUnwrap(PMTilesInfo(url: MapStyle.worldURL))
        XCTAssertEqual(info.minZoom, 0)
        XCTAssertEqual(info.maxZoom, 5)
        XCTAssertLessThan(info.southWest.longitude, -170)
        XCTAssertGreaterThan(info.northEast.longitude, 170)
    }

    func testStyleBuildsWithRegions() throws {
        let url = try MapStyle.write(dark: true, regions: [MapStyle.worldURL])
        let style = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let sources = try XCTUnwrap(style["sources"] as? [String: Any])
        XCTAssertEqual(Set(sources.keys), ["world", "region0"])
        let layers = try XCTUnwrap(style["layers"] as? [[String: Any]])
        XCTAssertEqual(layers.filter { $0["type"] as? String == "background" }.count, 1)
        XCTAssertTrue(layers.filter { ($0["source"] as? String) == "region0" }.allSatisfy { ($0["minzoom"] as? Double ?? 0) >= 5.5 })
    }

    func testGuidesLoadAndFilesExist() {
        XCTAssertFalse(GuideStore.categories.isEmpty)
        XCTAssertFalse(GuideStore.urgent.isEmpty)
        XCTAssertFalse(GuideStore.checklists.isEmpty)
        for guide in GuideStore.allGuides {
            XCTAssertTrue(FileManager.default.fileExists(atPath: GuideStore.root.appending(path: guide.file).path), guide.file)
        }
    }

    func testTidyDropsNotFoundBeforeAnAnswer() {
        XCTAssertEqual(AskEngine.tidy("I couldn't find that in your offline library.\n\nUse fine sand at least 26 inches deep [2]."),
                       "Use fine sand at least 26 inches deep [2].")
        XCTAssertEqual(AskEngine.tidy("I couldn't find that in your offline library, but sources say: boil it [1]."),
                       "boil it [1].")
        let honest = "I couldn't find that in your offline library. Try searching for \"sand filter\"."
        XCTAssertEqual(AskEngine.tidy(honest), honest)
        XCTAssertEqual(AskEngine.tidy("Boil water for one minute [1]."), "Boil water for one minute [1].")
        XCTAssertEqual(AskEngine.tidy("1. Press hard [1].\n\n[1], [2], [3], [4]"), "1. Press hard [1].\n")
    }

    func testPickRespectsBudgetAndDedupes() {
        func hit(_ title: String, _ len: Int, _ score: Double) -> SearchHit {
            SearchHit(ref: .guide(path: title), sourceName: "x", title: title, snippet: "", body: String(repeating: "a", count: len), score: score)
        }
        let hits = [hit("A", 1000, 9), hit("A", 1000, 8), hit("A", 1000, 7), hit("B", 3000, 6), hit("C", 500, 5)]
        let picked = AskEngine.pick(hits, budget: 3000)
        XCTAssertEqual(picked.map(\.title), ["A", "A", "C"])
        // One oversized passage still gets through when nothing else fits.
        XCTAssertEqual(AskEngine.pick([hit("Z", 9000, 1)], budget: 1000).count, 1)
        // Passages far weaker than the best stay out of the prompt.
        let weak = [hit("Severe Bleeding", 500, 1.3), hit("Severe Bleeding", 500, 1.2), hit("Nosebleeds", 500, 0.5), hit("Survival Priorities", 500, 0.43)]
        XCTAssertEqual(AskEngine.pick(weak, budget: 7000).map(\.title), ["Severe Bleeding", "Severe Bleeding"])
    }

    func testKitchenMeasure() {
        XCTAssertEqual(WaterCalculatorView.kitchenMeasure(0.125), "1/8 teaspoon")
        XCTAssertEqual(WaterCalculatorView.kitchenMeasure(0.5), "1/2 teaspoon")
        XCTAssertEqual(WaterCalculatorView.kitchenMeasure(1.5), "1 1/2 teaspoons")
        XCTAssertEqual(WaterCalculatorView.kitchenMeasure(0.98), "1 teaspoon")
        XCTAssertEqual(WaterCalculatorView.kitchenMeasure(7), "2 1/3 tablespoons")
    }

    func testKeyTermsAndCoverage() {
        let terms = SearchService.keyTerms("How do I stop severe bleeding on a leg?")
        XCTAssertEqual(Set(terms), ["stop", "sever", "bleed", "leg"])
        XCTAssertEqual(SearchService.coverage(of: terms, in: "Just Stop Oil campaigns"), 0.25)
        XCTAssertEqual(SearchService.coverage(of: terms, in: "To stop severe bleeding on an arm or leg, press hard"), 1)
        XCTAssertEqual(SearchService.coverage(of: [], in: "anything"), 1)
    }

    func testRelatedTool() {
        XCTAssertEqual(ToolKind.related(to: "How much bleach purifies a gallon of water?"), .water)
        XCTAssertEqual(ToolKind.related(to: "How do I signal a helicopter?"), .sos)
        XCTAssertNil(ToolKind.related(to: "How do I splint a broken arm?"))
    }

    func testWaterDoseFromQuestion() {
        XCTAssertEqual(WaterCalculatorView.dose(for: "How much bleach for 5 gallons of cloudy water with 8.25% bleach?"),
                       "5 gallons of cloudy water with 8.25% bleach: 60 drops (about 1 teaspoon). Stir, wait 30 minutes.")
        XCTAssertEqual(WaterCalculatorView.dose(for: "bleach for 1 gallon"),
                       "1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.")
        // On a real phone a small model answered "a gallon" with an old 16-drop figure,
        // so word amounts, and drinking-water questions with no amount, are calculated too.
        XCTAssertEqual(WaterCalculatorView.dose(for: "How much bleach purifies a gallon of water?"),
                       "1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.")
        XCTAssertEqual(WaterCalculatorView.dose(for: "bleach for two liters of murky water"),
                       "2 liters of cloudy water with 6% bleach: 9 drops (about 1/8 teaspoon). Stir, wait 30 minutes.")
        XCTAssertEqual(WaterCalculatorView.dose(for: "bleach for half a gallon"),
                       "0.5 gallons of clear water with 6% bleach: 4 drops. Stir, wait 30 minutes.")
        XCTAssertEqual(WaterCalculatorView.dose(for: "How much bleach makes drinking water safe?"),
                       "1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.")
        XCTAssertNil(WaterCalculatorView.dose(for: "How much bleach to clean a kitchen counter?"))
        XCTAssertNil(WaterCalculatorView.dose(for: "How much water should I store for 3 days?"))
    }

    func testBookURLsKeepTrailingSlashes() {
        let page = NomadSchemeHandler.bookURL("abc123", path: "www.nhs.uk/medicines/")
        XCTAssertEqual(NomadSchemeHandler.pathParts(page), ["abc123", "www.nhs.uk/medicines/"])
        let pdf = NomadSchemeHandler.bookURL("abc123", path: "files/Food Preparation (1).pdf")
        XCTAssertEqual(NomadSchemeHandler.pathParts(pdf), ["abc123", "files/Food Preparation (1).pdf"])
    }
}
