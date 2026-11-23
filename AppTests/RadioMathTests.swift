import XCTest
@testable import NomadPocket

final class RadioMathTests: XCTestCase {
    func testAntennaLengths() throws {
        let sat = try XCTUnwrap(RadioMath.antenna(freqMHz: 137.1))
        XCTAssertEqual(sat.quarterWaveMetres, 0.520, accuracy: 0.001)
        XCTAssertEqual(sat.halfWaveTotalMetres, 1.0405, accuracy: 0.001)
        XCTAssertEqual(sat.vDipoleElementMetres, sat.quarterWaveMetres)
        XCTAssertEqual(try XCTUnwrap(RadioMath.antenna(freqMHz: 1090)).quarterWaveMetres, 0.0654, accuracy: 0.0005)
        XCTAssertEqual(try XCTUnwrap(RadioMath.antenna(freqMHz: 144.39)).quarterWaveMetres, 0.494, accuracy: 0.001)
        XCTAssertEqual(try XCTUnwrap(RadioMath.antenna(freqMHz: 14.074)).halfWaveTotalMetres, 10.136, accuracy: 0.01)
        XCTAssertEqual(sat.cutLong(1.0), 1.03, accuracy: 1e-9)
        XCTAssertNil(RadioMath.antenna(freqMHz: 0)); XCTAssertNil(RadioMath.antenna(freqMHz: 99_999)); XCTAssertNil(RadioMath.antenna(freqMHz: -5))
    }

    func testLengthText() {
        XCTAssertEqual(RadioMath.lengthText(0.494), "49.4 cm (19.4 in)")
        XCTAssertEqual(RadioMath.lengthText(10.136), "10.14 m (33 ft 3 in)")
        XCTAssertEqual(RadioMath.lengthText(0.254), "25.4 cm (10.0 in)")
        XCTAssertEqual(RadioMath.lengthText(1.0), "1.00 m (3 ft 3 in)")
    }

    func testSolarSums() throws {
        let tight = try XCTUnwrap(RadioMath.solar(panelWatts: 2, sunHours: 3.5, batteryVolts: 3.7, batteryMAh: 5000, drawMA: 75))
        XCTAssertEqual(tight.loadWhPerDay, 6.66, accuracy: 0.01)
        XCTAssertEqual(tight.batteryWh, 18.5, accuracy: 0.01)
        XCTAssertEqual(tight.autonomyDays, 2.78, accuracy: 0.01)
        XCTAssertEqual(tight.panelWhPerDay, 7.0, accuracy: 0.01)
        XCTAssertEqual(tight.verdict, .tight)
        XCTAssertEqual(try XCTUnwrap(RadioMath.solar(panelWatts: 5, sunHours: 3.5, batteryVolts: 3.7, batteryMAh: 5000, drawMA: 75)).verdict, .keepsUp)
        guard case .runsDown(let days) = try XCTUnwrap(RadioMath.solar(panelWatts: 0.5, sunHours: 3.5, batteryVolts: 3.7, batteryMAh: 5000, drawMA: 75)).verdict else { return XCTFail("should run down") }
        XCTAssertEqual(days, 3.77, accuracy: 0.02)
        XCTAssertNil(RadioMath.solar(panelWatts: 2, sunHours: 3.5, batteryVolts: 0, batteryMAh: 5000, drawMA: 75))
        XCTAssertNil(RadioMath.solar(panelWatts: 2, sunHours: 3.5, batteryVolts: 3.7, batteryMAh: 5000, drawMA: 0))
    }

    func testToolsAreReachableFromAsk() {
        XCTAssertEqual(ToolKind.related(to: "How long should my antenna be for 144 MHz?"), .antenna)
        XCTAssertEqual(ToolKind.related(to: "What size solar panel for my node?"), .solarNode)
    }
}
