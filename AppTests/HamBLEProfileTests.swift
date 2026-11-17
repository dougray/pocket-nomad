import XCTest
@testable import NomadPocket

/// Picking the Bluetooth service that carries KISS on radios that differ.
final class HamBLEProfileTests: XCTestCase {
    private func svc(_ uuid: String, _ chars: [(String, Bool, Bool)]) -> HamBLEServiceInfo {
        HamBLEServiceInfo(uuid: uuid, characteristics: chars.map { HamBLEChar(uuid: $0.0, notify: $0.1, write: $0.2) })
    }

    private let gap = HamBLEServiceInfo(uuid: "1800", characteristics: [HamBLEChar(uuid: "2A00", notify: false, write: true)])
    private let battery = HamBLEServiceInfo(uuid: "180F", characteristics: [HamBLEChar(uuid: "2A19", notify: true, write: false)])

    func testBLEKISSWinsOverOthers() {
        let p = HamBLEProfile.choose([gap, svc("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", [("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", false, true),
                                                                                          ("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", true, false)]),
                                      svc("00000001-ba2a-46c9-ae49-01b0961f68bb", [("00000002-ba2a-46c9-ae49-01b0961f68bb", false, true),
                                                                                   ("00000003-ba2a-46c9-ae49-01b0961f68bb", true, false)])])
        XCTAssertEqual(p, .bleKISS)
    }

    func testNordicUARTAndHM10() {
        XCTAssertEqual(HamBLEProfile.choose([gap, svc("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", [
            ("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", false, true), ("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", true, false)])]), .nordicUART)
        // HM-10: one characteristic both writes and notifies, often reported in short form.
        XCTAssertEqual(HamBLEProfile.choose([battery, svc("FFE0", [("FFE1", true, true)])]), .hm10)
    }

    func testUnknownVendorServiceFallsBackToGeneric() {
        let p = HamBLEProfile.choose([gap, battery, svc("A1B2C3D4-0000-1000-8000-00AABBCCDDEE", [
            ("A1B2C3D5-0000-1000-8000-00AABBCCDDEE", true, false), ("A1B2C3D6-0000-1000-8000-00AABBCCDDEE", false, true)])])
        XCTAssertEqual(p?.notify, "A1B2C3D5-0000-1000-8000-00AABBCCDDEE")
        XCTAssertEqual(p?.write, "A1B2C3D6-0000-1000-8000-00AABBCCDDEE")
        XCTAssertTrue(p?.name.hasPrefix("Generic serial") == true)
    }

    func testStandardServicesAloneAreNotATNC() {
        XCTAssertNil(HamBLEProfile.choose([gap, battery]))
        XCTAssertTrue(HamBLEProfile.isStandard("180A"))
        XCTAssertFalse(HamBLEProfile.isStandard("FFE0"))
        XCTAssertEqual(HamBLEProfile.describe([svc("FFE0", [("FFE1", true, true)])]),
                       ["Service 0000FFE0-0000-1000-8000-00805F9B34FB: FFE1[wn]"])
    }
}
