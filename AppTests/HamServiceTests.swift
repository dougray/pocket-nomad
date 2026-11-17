import XCTest
@testable import NomadPocket

@MainActor
final class FakeHamTransport: HamTransport {
    var onEvent: ((HamTransportEvent) -> Void)?
    var written: [[UInt8]] = []
    func startScan(showAll: Bool) {}
    func stopScan() {}
    func connect(_ id: UUID) { onEvent?(.ready(id: id, name: "Test TNC")) }
    func disconnect() {}
    func write(_ bytes: [UInt8]) { written.append(bytes) }

    /// AX.25 frames written (KISS parameter frames skipped).
    var frames: [AX25Frame] {
        var d = KISSDecoder()
        return written.flatMap { d.feed($0) }.filter { $0.command == 0 }.compactMap { AX25Frame.decode($0.payload) }
    }
}

/// The Ham service: listen-only enforcement, APRS messaging and connected-mode routing.
@MainActor
final class HamServiceTests: XCTestCase {
    var defaults: UserDefaults!
    var suite: String!
    var dir: URL!
    var transport: FakeHamTransport!
    var settings: HamSettings!
    var ham: HamService!
    var clock = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() async throws {
        suite = "ham-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        dir = FileManager.default.temporaryDirectory.appending(path: suite)
        transport = FakeHamTransport()
        settings = HamSettings(defaults: defaults, keychainService: "test.\(suite!)")
        ham = HamService(transport: transport, settings: settings, files: HamFiles(directory: dir))
        ham.now = { [unowned self] in clock }
        ham.connect(HamDevice(id: UUID(), name: "Test TNC", rssi: 0))
    }

    override func tearDown() async throws {
        settings.winlinkPassword = nil
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: dir)
    }

    private func license() {
        settings.callsign = "kf0abc"
        settings.licensed = true
        settings.aprsSSID = 7
    }

    private func hear(_ tnc2: String) {
        let p = APRS.decode(tnc2: tnc2)!
        let src = AX25Address(p.source)!
        let f = AX25Frame(destination: AX25Address(p.destination)!, source: src, path: [],
                          control: .ui(poll: false), info: Array(p.raw.utf8))
        ham.receive(f.encoded())
    }

    func testListenOnlyNeverTransmits() {
        XCTAssertFalse(settings.canTransmit)
        hear("KF0XYZ>APRS::KF0ABC-7 :hello{5")
        ham.sendMessage(to: "KF0XYZ", text: "hi")
        ham.connectTerminal(call: "KF0BBS", path: "")
        XCTAssertTrue(transport.frames.isEmpty)
        XCTAssertNotNil(ham.notice)
        // A callsign without the licence confirmation is still listen-only.
        settings.callsign = "KF0ABC"
        XCTAssertFalse(settings.canTransmit)
        ham.sendMessage(to: "KF0XYZ", text: "hi")
        XCTAssertTrue(transport.frames.isEmpty)
        // It still hears and decodes.
        hear("KF0XYZ-9>APRS:=3944.40N/10459.40W>Mobile")
        XCTAssertEqual(ham.stations.first?.id, "KF0XYZ-9")
    }

    func testIncomingMessageIsStoredAndAcked() {
        license()
        hear("KF0XYZ>APRS::KF0ABC-7 :Are you on the net?{17")
        XCTAssertEqual(ham.chats.last?.text, "Are you on the net?")
        XCTAssertEqual(ham.unread["KF0XYZ"], 1)
        let ack = transport.frames.last
        XCTAssertEqual(ack.map { String(decoding: $0.info, as: UTF8.self) }, ":KF0XYZ   :ack17")
        XCTAssertEqual(ack?.source.description, "KF0ABC-7")
        // A repeat (the sender didn't hear our ack) is acked again but not stored twice.
        hear("KF0XYZ>APRS::KF0ABC-7 :Are you on the net?{17")
        XCTAssertEqual(ham.chats.count, 1)
        XCTAssertEqual(transport.frames.count, 2)
    }

    func testOutgoingMessageRetriesUntilAcked() {
        license()
        ham.sendMessage(to: "kf0xyz", text: "Meet at the trailhead")
        XCTAssertEqual(transport.frames.count, 1)
        let id = ham.chats.last!.msgID!
        clock += 31
        ham.tick()
        XCTAssertEqual(transport.frames.count, 2)
        hear("KF0XYZ>APRS::KF0ABC-7 :ack\(id)")
        XCTAssertEqual(ham.chats.last?.status, .acked)
        clock += 300
        ham.tick()
        XCTAssertEqual(transport.frames.count, 2)
    }

    func testUnansweredMessageFailsAfterFiveTries() {
        license()
        ham.sendMessage(to: "KF0XYZ", text: "anyone?")
        for _ in 0..<20 { clock += 600; ham.tick() }
        XCTAssertEqual(ham.chats.last?.status, .failed)
        XCTAssertEqual(transport.frames.count, 5)
    }

    func testUnexpectedConnectIsRefusedUnlessAccepted() {
        license()
        let sabm = AX25Frame(destination: AX25Address("KF0ABC")!, source: AX25Address("KF0XYZ")!, control: .sabm(poll: true))
        ham.receive(sabm.encoded())
        XCTAssertEqual(transport.frames.last?.control, .dm(final: true))
        settings.acceptIncoming = true
        ham.receive(sabm.encoded())
        XCTAssertEqual(transport.frames.last?.control, .ua(final: true))
        XCTAssertEqual(ham.terminalPeer, "KF0XYZ")
        let data = AX25Frame(destination: AX25Address("KF0ABC")!, source: AX25Address("KF0XYZ")!,
                             control: .i(ns: 0, nr: 0, poll: false), info: Array("hello\r".utf8))
        ham.receive(data.encoded())
        XCTAssertTrue(ham.terminalText.contains("hello"))
    }

    func testWinlinkComposeNeedsCallsignAndPersists() throws {
        XCTAssertFalse(ham.compose(to: ["KF0XYZ"], cc: [], subject: "s", body: "b"))
        settings.callsign = "KF0ABC"
        XCTAssertTrue(ham.compose(to: ["KF0XYZ"], cc: [], subject: "s", body: "b"))
        let again = HamService(transport: FakeHamTransport(), settings: settings, files: HamFiles(directory: dir))
        XCTAssertEqual(again.outbox.count, 1)
        XCTAssertEqual(again.outbox.first?.from, "KF0ABC")
    }

    func testPasswordStaysInKeychain() {
        settings.winlinkPassword = "s3cret"
        XCTAssertEqual(settings.winlinkPassword, "s3cret")
        XCTAssertNil(defaults.string(forKey: "winlink"))
        XCTAssertFalse(defaults.dictionaryRepresentation().values.contains { ($0 as? String) == "s3cret" })
        settings.winlinkPassword = nil
        XCTAssertFalse(settings.hasWinlinkPassword)
    }

    func testCallsignValidation() {
        for ok in ["KF0ABC", "W1AW", "N0CALL", "VE3XYZ", "G4ABC", "2E0ABC", "JA1XYZ"] { XCTAssertTrue(HamSettings.isValidCallsign(ok), ok) }
        for bad in ["", "ABC", "1234", "KF0ABCDEF", "NOCALL"] { XCTAssertFalse(HamSettings.isValidCallsign(bad), bad) }
    }
}
