import XCTest
@testable import NomadPocket

@MainActor
final class QsoLogTests: XCTestCase {
    private func date(_ s: String) -> Date {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMddHHmmss"
        return f.date(from: s)!
    }

    func testBands() {
        XCTAssertEqual(QsoBands.band(mhz: 14.074), "20m")
        XCTAssertEqual(QsoBands.band(mhz: 146.52), "2m")
        XCTAssertEqual(QsoBands.band(mhz: 446.0), "70cm")
        XCTAssertNil(QsoBands.band(mhz: 100.0))
    }

    func testAdifExportExactFormat() {
        var q = Qso(call: "W1AW", when: date("20261118171147"), mhz: 14.074, mode: "FT8")
        q.rstSent = "-12"; q.rstRcvd = "-08"; q.grid = "FN31"; q.myCall = "N0CALL"; q.sig = "POTA"; q.sigInfo = "US-0001"; q.comment = "Café"
        let adif = ADIF.export([q])
        XCTAssertTrue(adif.contains("<EOH>"))
        XCTAssertTrue(adif.contains("<CALL:4>W1AW <QSO_DATE:8>20261118 <TIME_ON:6>171147 <BAND:3>20m <FREQ:6>14.074 <MODE:3>FT8 "))
        XCTAssertTrue(adif.contains("<SIG:4>POTA <SIG_INFO:7>US-0001 <EOR>"))
        XCTAssertTrue(adif.contains("<COMMENT:5>Café "), "lengths count bytes, not characters")
    }

    func testRoundTripAndTolerantImport() throws {
        var q = Qso(call: "K5ABC", when: date("20261118120000"), mhz: 7.074, mode: "FT8")
        q.name = "Dana"; q.comment = "<tag> inside & text"
        let back = ADIF.parse(ADIF.export([q]))
        XCTAssertEqual(back.count, 1)
        XCTAssertEqual(back[0].call, "K5ABC"); XCTAssertEqual(back[0].when, q.when); XCTAssertEqual(back[0].mhz, 7.074, accuracy: 1e-9)
        XCTAssertEqual(back[0].comment, "<tag> inside & text"); XCTAssertEqual(back[0].name, "Dana")
        // Another program's file: lower-case tags, a band but no frequency, extra fields, no time.
        let other = "WSJT-X ADIF Export\n<adif_ver:5>3.1.0\n<eoh>\n<call:5>VE3XY <qso_date:8>20261101 <band:3>40m <mode:3>CW <operator:6>N0CALL <app_foo:3>bar <eor>\n<call:0> <qso_date:8>20261101 <eor>\n"
        let imported = ADIF.parse(other)
        XCTAssertEqual(imported.count, 1)
        XCTAssertEqual(imported[0].call, "VE3XY"); XCTAssertEqual(imported[0].band, "40m"); XCTAssertEqual(imported[0].myCall, "N0CALL")
    }

    func testStoreMergeSkipsDuplicatesAndSearches() {
        let store = QsoLogStore(url: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "qso-\(UUID().uuidString).json"))
        let a = Qso(call: "W1AW", when: date("20261118120000"), mhz: 14.074, mode: "FT8")
        store.add(a)
        XCTAssertEqual(store.merge([a, Qso(call: "W1AW", when: date("20261118120030"), mhz: 14.075, mode: "FT8"), Qso(call: "K5ABC", when: date("20261118130000"), mhz: 7.1, mode: "SSB")]), 1)
        XCTAssertEqual(store.log.count, 2)
        XCTAssertEqual(store.search("k5").count, 1)
        XCTAssertEqual(store.newestFirst.first?.call, "K5ABC")
        let again = QsoLogStore(url: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "qso-\(UUID().uuidString).json"))
        XCTAssertTrue(again.log.isEmpty)
    }

    func testPowerDistanceAndMilesPerWatt() throws {
        var q = Qso(call: "K5ABC", when: date("20261118120000"), mhz: 7.078, mode: "JS8")
        q.myGrid = "EM10"; q.grid = "EM13"; q.watts = 5
        let km = try XCTUnwrap(q.distanceKm)
        XCTAssertEqual(km, 334, accuracy: 25)                 // three field rows, about 3 degrees of latitude
        XCTAssertEqual(try XCTUnwrap(q.milesPerWatt), km / 1.609344 / 5, accuracy: 1e-9)
        let adif = ADIF.export([q])
        XCTAssertTrue(adif.contains("<TX_PWR:1>5 "))
        XCTAssertTrue(adif.contains("<DISTANCE:3>"))
        XCTAssertEqual(ADIF.parse(adif).first?.watts, 5)
        var none = q; none.watts = nil; none.grid = ""
        XCTAssertNil(none.distanceKm); XCTAssertNil(none.milesPerWatt)
        XCTAssertEqual(QsoLogView.rangeLine(none), nil)
        XCTAssertTrue(QsoLogView.rangeLine(q)!.hasPrefix("5 W · "))
    }
}
