// RadioSettingsTests.swift
// Pocket Nomad - Mesh module tests
// SPDX-License-Identifier: Apache-2.0
//
// Radio settings: MeshCore CMD_SET_RADIO_PARAMS / CMD_SET_RADIO_TX_POWER /
// CMD_REBOOT bytes (MyMesh.cpp, companion-v1.18.0), Meshtastic AdminMessage
// get/set LoRa config with the session passkey (golden bytes from the field
// numbers in admin.proto and config.proto), "match radios near you", input
// limits, the radio-card hint, and both apply flows against fake radios.

import XCTest
#if canImport(NomadPocket)
@testable import NomadPocket
#endif

final class RadioSettingsCodecTests: XCTestCase {
    let usa = MeshCoreRadioParams(frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5)

    // MARK: MeshCore

    func testSetRadioParamsBytes() {
        XCTAssertEqual(usa.frequencyKHz, 910_525)
        XCTAssertEqual(usa.bandwidthHz, 62_500)
        // [11][freq kHz u32 LE][bw Hz u32 LE][sf][cr]
        XCTAssertEqual([UInt8](MeshCoreCommand.setRadioParams(usa)),
                       [0x0B, 0xBD, 0xE4, 0x0D, 0x00, 0x24, 0xF4, 0x00, 0x00, 0x07, 0x05])
        let narrowBW = MeshCoreRadioParams(frequencyMHz: 869.618, bandwidthKHz: 31.25, spreadingFactor: 12, codingRate: 8)
        // 869618 = 0x000D44F2, 31250 = 0x7A12
        XCTAssertEqual([UInt8](MeshCoreCommand.setRadioParams(narrowBW)),
                       [0x0B, 0xF2, 0x44, 0x0D, 0x00, 0x12, 0x7A, 0x00, 0x00, 0x0C, 0x08])
    }

    func testTxPowerAndRebootBytes() {
        XCTAssertEqual([UInt8](MeshCoreCommand.setRadioTxPower(22)), [0x0C, 0x16])
        XCTAssertEqual([UInt8](MeshCoreCommand.setRadioTxPower(-9)), [0x0C, 0xF7])
        XCTAssertEqual([UInt8](MeshCoreCommand.reboot()), [0x13, 0x72, 0x65, 0x62, 0x6F, 0x6F, 0x74])
    }

    func testOkErrAndSelfInfoTxPower() throws {
        XCTAssertEqual(MeshCoreFrame.decode(Data([0])), .ok(nil))
        XCTAssertEqual(MeshCoreFrame.decode(Data([1, 6])), .error(6))
        guard case .selfInfo(let me)? = MeshCoreFrame.decode(CoreFrames.selfInfo(key: CoreFrames.key(1), name: "Base")) else {
            return XCTFail("not self info")
        }
        XCTAssertEqual(me.txPowerDBm, 20)
        XCTAssertEqual(me.maxTxPowerDBm, 22)
        XCTAssertEqual(me.radioParams, usa)
        XCTAssertTrue(MeshService.describeCoreError(6).contains("refused"))
    }

    // MARK: Meshtastic

    func testGetLoRaConfigRequestBytes() {
        // get_config_request (5, varint) = LORA_CONFIG (5); no passkey yet.
        XCTAssertEqual([UInt8](MeshPBAdmin.getLoRaConfig().encode()), [0x28, 0x05])
    }

    func testSetLoRaConfigBytesCarryThePasskey() {
        let key: [UInt8] = [1, 2, 3, 4, 5, 6, 7, 8]
        let lora = MeshPBLoRaConfig(usePreset: true, modemPreset: 0, region: 1, hopLimit: 3, txEnabled: true)
        let bytes = [UInt8](MeshPBAdmin.setLoRaConfig(lora, passkey: Data(key)).encode())
        let expected: [UInt8] = [0x92, 0x02, 0x0A,            // set_config (34, LEN) 10 bytes
                                 0x32, 0x08,                  // Config.lora (6, LEN) 8 bytes
                                 0x08, 0x01, 0x38, 0x01,      // use_preset, region US
                                 0x40, 0x03, 0x48, 0x01,      // hop_limit 3, tx_enabled
                                 0xAA, 0x06, 0x08] + key      // session_passkey (101, LEN)
        XCTAssertEqual(bytes, expected)
    }

    func testGetConfigResponseDecodesPasskeyAndLoRa() throws {
        // As the radio sends it: get_config_response (6) { lora (6) { region 1, modem_preset 4 } }, passkey.
        let raw: [UInt8] = [0x32, 0x06, 0x32, 0x04, 0x10, 0x04, 0x38, 0x01, 0xAA, 0x06, 0x08, 9, 9, 9, 9, 9, 9, 9, 9]
        let admin = try MeshPBAdmin.decode(Data(raw))
        XCTAssertEqual(admin.sessionPasskey, Data(repeating: 9, count: 8))
        guard case .getConfigResponse(let lora?)? = admin.variant else { return XCTFail("no lora") }
        XCTAssertEqual(lora.region, 1)
        XCTAssertEqual(lora.modemPreset, 4)
        XCTAssertFalse(lora.usePreset)
    }

    func testEditingLoRaKeepsEveryOtherField() throws {
        var w = MeshProtoWriter()
        w.writeBool(1, true)
        w.writeUInt32(2, 6)               // ShortFast
        w.writeUInt32(7, 0)               // region UNSET (explicit)
        w.writeUInt32(8, 5)               // hop_limit
        w.writeBool(9, true)              // tx_enabled
        w.writeInt32(10, -3)              // tx_power, negative
        w.writeBool(13, true)             // sx126x_rx_boosted_gain (not modelled)
        w.writeFloat(14, 906.875)         // override_frequency (not modelled)
        w.writeBytes(103, Data([0x01, 0x02])) // ignore_incoming, packed (not modelled)
        let lora = try MeshPBLoRaConfig.decode(w.data)
        XCTAssertEqual(lora.txPower, -3)
        XCTAssertFalse(lora.otherFields.isEmpty)

        let edited = lora.with(region: 1, modemPreset: 0)
        let back = try MeshPBLoRaConfig.decode(edited.encode())
        XCTAssertEqual(back.region, 1)
        XCTAssertEqual(back.modemPreset, 0)
        XCTAssertTrue(back.usePreset)
        XCTAssertEqual(back.hopLimit, 5)
        XCTAssertTrue(back.txEnabled)
        XCTAssertEqual(back.txPower, -3)
        var seen: [Int: MeshProtoField] = [:]
        try MeshProtoReader.forEachField(in: back.otherFields) { seen[$0.number] = $0 }
        XCTAssertEqual(seen[13]?.bool, true)
        XCTAssertEqual(seen[14]?.float, 906.875)
        XCTAssertEqual(seen[103]?.bytes, Data([0x01, 0x02]))
    }

    func testAdminRoundTrip() throws {
        let lora = MeshPBLoRaConfig(usePreset: true, modemPreset: 7, region: 6, hopLimit: 3, txEnabled: true)
        let admin = MeshPBAdmin(variant: .getConfigResponse(lora), sessionPasskey: Data([7, 7, 7, 7, 7, 7, 7, 7]))
        XCTAssertEqual(try MeshPBAdmin.decode(admin.encode()), admin)
        let set = MeshPBAdmin.setLoRaConfig(lora, passkey: Data([1, 2, 3, 4, 5, 6, 7, 8]))
        XCTAssertEqual(try MeshPBAdmin.decode(set.encode()), set)
    }
}

final class RadioSettingsLogicTests: XCTestCase {
    let usa = MeshCoreRadioParams(frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5)
    let usaCR8 = MeshCoreRadioParams(frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 8)
    let old = MeshCoreRadioParams(frequencyMHz: 910.525, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5)
    let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func radio(_ i: Int, _ p: MeshCoreRadioParams?, daysAgo: Double = 1, firmware: MeshFirmware = .meshcore) -> KnownRadio {
        // Around a generic reference point, not anyone's home.
        KnownRadio(id: "c\(i)", firmware: firmware, name: "R\(i)", role: .repeater, latitude: 39.74, longitude: -104.99,
                   lastSeen: now.addingTimeInterval(-daysAgo * 86_400), params: p)
    }

    func testValidation() {
        XCTAssertNil(usa.validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 140, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 2400, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: 100, spreadingFactor: 7, codingRate: 5).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: 125, spreadingFactor: 4, codingRate: 5).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: 125, spreadingFactor: 13, codingRate: 5).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: 125, spreadingFactor: 7, codingRate: 4).validationError)
        XCTAssertNotNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: 125, spreadingFactor: 7, codingRate: 9).validationError)
        for bw in MeshCoreRadioParams.allowedBandwidthsKHz {
            XCTAssertNil(MeshCoreRadioParams(frequencyMHz: 910, bandwidthKHz: bw, spreadingFactor: 7, codingRate: 5).validationError, "\(bw)")
        }
        XCTAssertEqual(MeshCoreRadioParams.parseMHz("910,525"), 910.525)
        XCTAssertEqual(MeshCoreRadioParams.parseMHz(" 910.525 "), 910.525)
        XCTAssertNil(MeshCoreRadioParams.parseMHz("abc"))
        XCTAssertNil(MeshCoreRadioParams.parseMHz(""))
    }

    func testSummariesAndFloatTolerance() {
        XCTAssertEqual(usa.summary, "910.525 MHz · SF7 · BW 62.5")
        XCTAssertEqual(usa.fullSummary, "910.525 MHz · SF7 · BW 62.5 · CR5")
        // The firmware keeps MHz in a float: 869.618 can read back as 869617 kHz.
        let readBack = MeshCoreRadioParams(frequencyKHz: 910_524, bandwidthHz: 62_500, spreadingFactor: 7, codingRate: 5)
        XCTAssertTrue(readBack.sameChannel(as: usa))
        XCTAssertFalse(usaCR8.sameChannel(as: usa))
    }

    func testOfficialPresetsIncludeUSA() {
        XCTAssertEqual(MeshCoreRadioPreset.usa.title, "USA")
        XCTAssertEqual(MeshCoreRadioPreset.usa.params, usa)
        XCTAssertEqual(MeshCoreRadioPreset.matching(usa)?.title, "USA")
        XCTAssertTrue(MeshCoreRadioPreset.official.allSatisfy { $0.params.validationError == nil })
        XCTAssertEqual(MeshCoreRadioParams.firmwareDefault.summary, "869.618 MHz · SF8 · BW 62.5")
    }

    func testNearbyPicksTheMostCommon() throws {
        let radios = (0 ..< 5).map { radio($0, usa) } + [radio(5, usaCR8), radio(6, old)]
            + [radio(7, nil), radio(8, usa, firmware: .meshtastic)]
        let c = try XCTUnwrap(MeshCoreNearbyConsensus.compute(radios, now: now))
        XCTAssertEqual(c.params, usa)
        XCTAssertEqual(c.count, 5)
        XCTAssertEqual(c.total, 7, "missing params and Meshtastic radios are not counted")
        XCTAssertEqual(c.usageText, "Used by 5 of 7 nearby radios")
    }

    func testNearbyIgnoresInvalidAndCanBeEmpty() {
        let junk = MeshCoreRadioParams(frequencyMHz: 2500, bandwidthKHz: 500, spreadingFactor: 7, codingRate: 5)
        XCTAssertNil(MeshCoreNearbyConsensus.compute([radio(0, junk), radio(1, nil)], now: now))
        XCTAssertNil(MeshCoreNearbyConsensus.compute([], now: now))
    }

    func testNearbyTiesPreferAnOfficialPresetThenTheMostRecent() throws {
        let custom = MeshCoreRadioParams(frequencyMHz: 915.0, bandwidthKHz: 125, spreadingFactor: 9, codingRate: 5)
        let c = try XCTUnwrap(MeshCoreNearbyConsensus.compute([radio(0, custom, daysAgo: 0), radio(1, custom),
                                                               radio(2, usa, daysAgo: 5), radio(3, usa, daysAgo: 6)], now: now))
        XCTAssertEqual(c.params, usa, "a tie goes to the official preset")

        let a = MeshCoreRadioParams(frequencyMHz: 915.0, bandwidthKHz: 125, spreadingFactor: 9, codingRate: 5)
        let b = MeshCoreRadioParams(frequencyMHz: 914.0, bandwidthKHz: 125, spreadingFactor: 9, codingRate: 5)
        let recent = try XCTUnwrap(MeshCoreNearbyConsensus.compute([radio(0, a, daysAgo: 1), radio(1, b, daysAgo: 3)], now: now))
        XCTAssertEqual(recent.params, a, "then the settings heard most recently")
        let same = try XCTUnwrap(MeshCoreNearbyConsensus.compute([radio(0, a, daysAgo: 2), radio(1, b, daysAgo: 2)], now: now))
        XCTAssertEqual(same.params, b, "then the lowest frequency, so the answer never flips")
    }

    func testNearbyPrefersRecentRadios() throws {
        let radios = (0 ..< 6).map { radio($0, old, daysAgo: 300) } + [radio(10, usa, daysAgo: 2), radio(11, usa, daysAgo: 3)]
        let c = try XCTUnwrap(MeshCoreNearbyConsensus.compute(radios, now: now))
        XCTAssertEqual(c.params, usa)
        XCTAssertEqual(c.total, 2)
        XCTAssertTrue(c.recentOnly)
        XCTAssertTrue(c.usageText.contains("last 30 days"))
        // Nothing recent: all are counted.
        let stale = try XCTUnwrap(MeshCoreNearbyConsensus.compute((0 ..< 3).map { radio($0, old, daysAgo: 300) }, now: now))
        XCTAssertEqual(stale.params, old)
        XCTAssertFalse(stale.recentOnly)
    }

    func testHint() {
        let nearby = MeshCoreNearbyConsensus(params: usa, count: 40, total: 44, recentOnly: false)
        XCTAssertEqual(MeshCoreRadioHint.text(current: .firmwareDefault, nearby: nearby),
                       "Most radios near you use 910.525 MHz")
        XCTAssertEqual(MeshCoreRadioHint.text(current: usaCR8, nearby: nearby),
                       "Most radios near you use 910.525 MHz · SF7 · BW 62.5 · CR5")
        XCTAssertNil(MeshCoreRadioHint.text(current: usa, nearby: nearby))
        XCTAssertNil(MeshCoreRadioHint.text(current: .firmwareDefault, nearby: nil))
        XCTAssertNil(MeshCoreRadioHint.text(current: nil, nearby: nearby))
        let lonely = MeshCoreNearbyConsensus(params: usa, count: 1, total: 1, recentOnly: false)
        XCTAssertNil(MeshCoreRadioHint.text(current: .firmwareDefault, nearby: lonely), "one radio is not a mesh")
    }

    func testParsesMeshCoreParams() throws {
        let json = """
        [ {"public_key":"aa11","type":2,"adv_name":"Ridge","adv_lat":39.74,"adv_lon":-104.99,
           "params":{"freq":910.525,"bw":62.5,"sf":7,"cr":5}},
          {"public_key":"bb22","type":1,"adv_name":"Int","adv_lat":39.75,"adv_lon":-104.98,
           "params":{"freq":910,"bw":250,"sf":11,"cr":5}},
          {"public_key":"cc33","type":1,"adv_name":"None","adv_lat":39.76,"adv_lon":-104.97},
          {"public_key":"dd44","type":1,"adv_name":"Odd","adv_lat":39.77,"adv_lon":-104.96,"params":"?"},
          {"public_key":"ee55","type":1,"adv_name":"Half","adv_lat":39.78,"adv_lon":-104.95,"params":{"freq":910.525}} ]
        """
        let radios = try KnownRadioStore.parseMeshCore(Data(json.utf8))
        XCTAssertEqual(radios.count, 5)
        XCTAssertEqual(radios[0].params, usa)
        XCTAssertEqual(radios[1].params?.frequencyKHz, 910_000)
        XCTAssertEqual(radios[1].params?.bandwidthHz, 250_000)
        XCTAssertNil(radios[2].params)
        XCTAssertNil(radios[3].params)
        XCTAssertNil(radios[4].params)
        // Saved lists from before params were kept still load.
        let oldSaved = #"{"id":"c1","firmware":"meshcore","name":"x","role":"client","latitude":1,"longitude":2}"#
        XCTAssertNil(try JSONDecoder().decode(KnownRadio.self, from: Data(oldSaved.utf8)).params)
    }

    func testMeshtasticNamesAndSuggestions() {
        XCTAssertEqual(MeshtasticRegion.suggested(forCountry: "US"), 1)
        XCTAssertEqual(MeshtasticRegion.suggested(forCountry: "ca"), 1)
        XCTAssertEqual(MeshtasticRegion.suggested(forCountry: "DE"), 3)
        XCTAssertEqual(MeshtasticRegion.suggested(forCountry: "AU"), 6)
        XCTAssertNil(MeshtasticRegion.suggested(forCountry: "ZZ"))
        XCTAssertNil(MeshtasticRegion.suggested(forCountry: nil))
        XCTAssertEqual(Set(MeshtasticRegion.all.map(\.code)), Set(1 ... 37), "every region in config.proto but UNSET")
        XCTAssertEqual(Set(MeshtasticModemPreset.all.map(\.code)), Set(0 ... 16), "every modem preset in config.proto")
        XCTAssertEqual(MeshtasticRegion.shortName(0), "Region not set")
        XCTAssertEqual(MeshtasticRegion.shortName(1), "US")

        var info = MeshRadioInfo(firmware: .meshtastic)
        info.region = 1
        info.modemPreset = 0
        info.usePreset = true
        XCTAssertEqual(info.settingsSummary, "US · LongFast")
        info.region = 0
        XCTAssertEqual(info.settingsSummary, "Region not set")
        var core = MeshRadioInfo(firmware: .meshcore)
        core.meshCoreParams = usa
        XCTAssertEqual(core.settingsSummary, "910.525 MHz · SF7 · BW 62.5")
    }
}

@MainActor
final class RadioSettingsServiceTests: XCTestCase {
    let me: UInt32 = 0x1111_1111
    var dir: URL!
    var defaults: UserDefaults!
    var suiteName: String!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("radio-settings-\(UUID().uuidString)")
        suiteName = "radio-settings-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        defaults.removePersistentDomain(forName: suiteName)
    }

    // MARK: MeshCore

    private func meshCore() -> (MeshService, FakeMeshTransport, FakeMeshCoreRadio, UUID) {
        let fake = FakeMeshTransport()
        let radio = FakeMeshCoreRadio(fake)
        let svc = MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
        svc.start()
        let id = UUID()
        svc.connect(to: MeshDiscoveredDevice(id: id, name: "MeshCore-Base", rssi: -60, lastSeen: Date(), firmware: .meshcore))
        fake.onEvent?(.ready(id: id, name: "MeshCore-Base", firmware: .meshcore))
        radio.run()
        return (svc, fake, radio, id)
    }

    /// The radio restarts: link drops, comes back, handshake answered.
    private func restart(_ fake: FakeMeshTransport, _ radio: FakeMeshCoreRadio, _ id: UUID) {
        fake.onEvent?(.disconnected(id: id, reason: nil, willReconnect: true))
        radio.answered = fake.sent.count
        fake.onEvent?(.ready(id: id, name: "MeshCore-Base", firmware: .meshcore))
        radio.run()
    }

    func testMeshCoreReadsSettingsFromSelfInfo() {
        let (svc, _, _, _) = meshCore()
        let l = svc.link(.meshcore)
        XCTAssertEqual(l.radio.meshCoreParams?.summary, "910.525 MHz · SF7 · BW 62.5")
        XCTAssertEqual(l.radio.txPowerDBm, 20)
        XCTAssertEqual(l.radio.maxTxPowerDBm, 22)
    }

    func testMeshCoreApplySendsWaitsForOkRestartsAndConfirms() throws {
        let (svc, fake, radio, id) = meshCore()
        let l = svc.link(.meshcore)
        let usa = MeshCoreRadioPreset.usa.params
        svc.applyMeshCoreRadio(usa, txPowerDBm: 20) // same power: not sent
        XCTAssertEqual(fake.writes.last, MeshCoreCommand.setRadioParams(usa))
        XCTAssertTrue(l.radioApply.isBusy)
        fake.frame(Data([0]), from: id) // OK
        XCTAssertEqual(fake.writes.last, MeshCoreCommand.reboot())
        guard case .waitingForRadio = l.radioApply else { return XCTFail("\(l.radioApply)") }
        restart(fake, radio, id)
        XCTAssertEqual(l.radioApply, .done("The radio confirmed 910.525 MHz · SF7 · BW 62.5 · CR5 · 20 dBm."))
    }

    func testMeshCoreApplySetsTxPowerAndReportsAMismatch() throws {
        let (svc, fake, radio, id) = meshCore()
        let l = svc.link(.meshcore)
        let eu = MeshCoreRadioParams(frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8)
        svc.applyMeshCoreRadio(eu, txPowerDBm: 22)
        XCTAssertEqual(fake.writes.last, MeshCoreCommand.setRadioParams(eu))
        fake.frame(Data([0]), from: id)
        XCTAssertEqual(fake.writes.last, MeshCoreCommand.setRadioTxPower(22))
        fake.frame(Data([0]), from: id)
        XCTAssertEqual(fake.writes.last, MeshCoreCommand.reboot())
        // The fake radio still reports 910.525 / 20 dBm after its restart.
        restart(fake, radio, id)
        guard case .failed(let why) = l.radioApply else { return XCTFail("\(l.radioApply)") }
        XCTAssertTrue(why.contains("910.525"))
        XCTAssertTrue(why.contains("TX power"))
    }

    func testMeshCoreErrorAndValidationNeverRestart() throws {
        let (svc, fake, _, id) = meshCore()
        let l = svc.link(.meshcore)
        let count = fake.sent.count
        svc.applyMeshCoreRadio(MeshCoreRadioParams(frequencyMHz: 100, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5))
        XCTAssertEqual(fake.sent.count, count, "invalid settings are never sent")
        guard case .failed = l.radioApply else { return XCTFail() }
        XCTAssertTrue(l.radioApply == .failed("Frequency must be between 150 and 960 MHz."))

        svc.applyMeshCoreRadio(MeshCoreRadioPreset.usa.params, txPowerDBm: 30)
        guard case .failed(let tooHot) = l.radioApply else { return XCTFail() }
        XCTAssertTrue(tooHot.contains("22"))

        svc.applyMeshCoreRadio(MeshCoreRadioPreset.usa.params)
        fake.frame(Data([1, 6]), from: id) // ERR_CODE_ILLEGAL_ARG
        XCTAssertEqual(l.radioApply, .failed(MeshService.describeCoreError(6)))
        XCTAssertNotEqual(fake.writes.last, MeshCoreCommand.reboot())
    }

    // MARK: Meshtastic

    private func meshtastic(region: UInt32 = 0) throws -> (MeshService, FakeMeshTransport, UUID) {
        let fake = FakeMeshTransport()
        let svc = MeshService(transport: fake, store: MeshStore(directory: dir), defaults: defaults)
        svc.start()
        let id = UUID()
        svc.connect(to: MeshDiscoveredDevice(id: id, name: "Test Radio", rssi: -60, lastSeen: Date()))
        try handshake(fake, id, lora: MeshPBLoRaConfig(usePreset: true, region: region, hopLimit: 3, txEnabled: true))
        return (svc, fake, id)
    }

    private func handshake(_ fake: FakeMeshTransport, _ id: UUID, lora: MeshPBLoRaConfig) throws {
        fake.becomeReady(id)
        let cfg = try XCTUnwrap(try fake.lastWantConfigID(to: id))
        fake.enqueue(.myInfo(MeshPBMyNodeInfo(myNodeNum: me)))
        fake.enqueue(.loraConfig(lora))
        fake.enqueue(.configCompleteID(cfg))
        fake.pump()
    }

    private func reply(_ fake: FakeMeshTransport, _ id: UUID, port: MeshPort, payload: Data, requestID: UInt32) {
        fake.enqueue(.packet(MeshPBPacket(from: me, to: me, decoded: MeshPBData(portnum: port.rawValue, payload: payload,
                                                                                requestID: requestID), id: 77)))
        fake.fromNumChanged(id)
        fake.pump()
    }

    private func lastAdmin(_ fake: FakeMeshTransport) throws -> (MeshPBPacket, MeshPBAdmin) {
        let p = try XCTUnwrap(try fake.lastSentPacket())
        let data = try XCTUnwrap(p.decoded)
        XCTAssertEqual(data.portnum, MeshPort.admin.rawValue)
        return (p, try MeshPBAdmin.decode(data.payload))
    }

    func testMeshtasticApplyReadsWithPasskeyThenSetsAndConfirmsAfterRestart() throws {
        let (svc, fake, id) = try meshtastic()
        let l = svc.link(.meshtastic)
        XCTAssertEqual(l.radio.settingsSummary, "Region not set")
        XCTAssertTrue(svc.notice?.contains("Radio settings") == true)

        svc.applyMeshtasticLoRa(region: 1, modemPreset: 0)
        let (get, getAdmin) = try lastAdmin(fake)
        XCTAssertEqual(get.to, me, "settings go to our own radio")
        XCTAssertTrue(get.decoded?.wantResponse == true)
        XCTAssertEqual(getAdmin.variant, .getConfigRequest(5))
        XCTAssertTrue(getAdmin.sessionPasskey.isEmpty)

        // The radio answers with its whole LoRa config and a session key.
        var current = MeshPBLoRaConfig(usePreset: true, modemPreset: 0, region: 0, hopLimit: 3, txEnabled: true, txPower: 27)
        var extra = MeshProtoWriter()
        extra.writeBool(13, true)
        current.otherFields = extra.data
        let key = Data([0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6, 0x07, 0x18])
        reply(fake, id, port: .admin, payload: MeshPBAdmin(variant: .getConfigResponse(current), sessionPasskey: key).encode(),
              requestID: get.id)

        let (set, setAdmin) = try lastAdmin(fake)
        XCTAssertNotEqual(set.id, get.id)
        XCTAssertEqual(setAdmin.sessionPasskey, key, "set_config echoes the session key")
        guard case .setConfig(let sent)? = setAdmin.variant else { return XCTFail("\(String(describing: setAdmin.variant))") }
        XCTAssertEqual(sent.region, 1)
        XCTAssertEqual(sent.modemPreset, 0)
        XCTAssertTrue(sent.usePreset)
        XCTAssertEqual(sent.txPower, 27, "other settings are written back unchanged")
        XCTAssertTrue(sent.txEnabled)
        XCTAssertEqual(sent.hopLimit, 3)
        XCTAssertEqual(sent.otherFields, extra.data)

        // ACK, then the radio restarts and reconnects with the new config.
        reply(fake, id, port: .routing, payload: MeshPBRouting(errorReason: 0).encode(), requestID: set.id)
        guard case .waitingForRadio = l.radioApply else { return XCTFail("\(l.radioApply)") }
        fake.onEvent?(.disconnected(id: id, reason: nil, willReconnect: true))
        guard case .waitingForRadio = l.radioApply else { return XCTFail("\(l.radioApply)") }
        try handshake(fake, id, lora: sent)
        XCTAssertEqual(l.radioApply, .done("The radio confirmed US · LongFast."))
        XCTAssertEqual(l.radio.settingsSummary, "US · LongFast")
    }

    func testMeshtasticRestartWithoutAckStillConfirms() throws {
        let (svc, fake, id) = try meshtastic()
        let l = svc.link(.meshtastic)
        svc.applyMeshtasticLoRa(region: 1, modemPreset: 4)
        let (get, _) = try lastAdmin(fake)
        reply(fake, id, port: .admin, payload: MeshPBAdmin(variant: .getConfigResponse(MeshPBLoRaConfig(txEnabled: true)),
                                                           sessionPasskey: Data(repeating: 1, count: 8)).encode(), requestID: get.id)
        // No ACK: the link drops as the radio restarts.
        fake.onEvent?(.disconnected(id: id, reason: nil, willReconnect: true))
        guard case .waitingForRadio = l.radioApply else { return XCTFail("\(l.radioApply)") }
        try handshake(fake, id, lora: MeshPBLoRaConfig(usePreset: true, modemPreset: 0, region: 1, txEnabled: true))
        guard case .failed(let why) = l.radioApply else { return XCTFail("\(l.radioApply)") }
        XCTAssertTrue(why.contains("US · LongFast"), "the radio kept LongFast, not MediumFast: \(why)")
    }

    func testMeshtasticRefusalIsShown() throws {
        let (svc, fake, id) = try meshtastic(region: 1)
        let l = svc.link(.meshtastic)
        svc.applyMeshtasticLoRa(region: 3, modemPreset: 0)
        let (get, _) = try lastAdmin(fake)
        reply(fake, id, port: .admin, payload: MeshPBAdmin(variant: .getConfigResponse(MeshPBLoRaConfig(usePreset: true, region: 1)),
                                                           sessionPasskey: Data(repeating: 2, count: 8)).encode(), requestID: get.id)
        let (set, _) = try lastAdmin(fake)
        reply(fake, id, port: .routing, payload: MeshPBRouting(errorReason: MeshRoutingError.adminBadSessionKey.rawValue).encode(),
              requestID: set.id)
        guard case .failed(let why) = l.radioApply else { return XCTFail("\(l.radioApply)") }
        XCTAssertTrue(why.contains("settings key"))
    }

    func testMeshtasticNeedsARegionAndIgnoresOtherAnswers() throws {
        let (svc, fake, id) = try meshtastic()
        let l = svc.link(.meshtastic)
        svc.applyMeshtasticLoRa(region: 0, modemPreset: 0)
        XCTAssertEqual(l.radioApply, .failed("Choose your region."))
        svc.applyMeshtasticLoRa(region: 1, modemPreset: 0)
        let count = fake.sent.count
        // An answer to some other request does not trigger a write.
        reply(fake, id, port: .admin, payload: MeshPBAdmin(variant: .getConfigResponse(MeshPBLoRaConfig()),
                                                           sessionPasskey: Data(repeating: 3, count: 8)).encode(), requestID: 12345)
        XCTAssertEqual(try fake.decodedWrites().suffix(from: count).filter { if case .packet = $0 { true } else { false } }.count, 0)
    }
}
