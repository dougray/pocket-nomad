import XCTest
@testable import NomadPocket

/// The ham radio protocol core: KISS, AX.25, APRS, LZHUF, Winlink.
@MainActor
final class HamCoreTests: XCTestCase {
    func fixture(_ name: String) throws -> [UInt8] {
        let url = try XCTUnwrap(Bundle(for: HamCoreTests.self).url(forResource: name, withExtension: nil))
        return [UInt8](try Data(contentsOf: url))
    }

    // MARK: KISS

    func testKISSEscapesAndReassemblesAcrossChunks() {
        let payload: [UInt8] = [1, 0xC0, 2, 0xDB, 3]
        let wire = KISS.encode(payload)
        XCTAssertEqual(wire, [0xC0, 0x00, 1, 0xDB, 0xDC, 2, 0xDB, 0xDD, 3, 0xC0])
        var d = KISSDecoder()
        var frames: [KISSDecoder.Frame] = []
        for b in wire + wire { frames += d.feed([b]) }
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].payload, payload)
        XCTAssertEqual(frames[0].command, 0)
    }

    // MARK: AX.25

    func testAX25AddressBytesAndRoundTrip() throws {
        let f = AX25Frame(destination: AX25Address("APZNMD")!, source: AX25Address("KF0ABC-7")!,
                          path: [AX25Address("WIDE1-1")!], control: .ui(poll: false), info: Array("hello".utf8))
        let b = f.encoded()
        XCTAssertEqual(Array(b[0..<6]), Array("APZNMD".utf8).map { $0 << 1 })
        XCTAssertEqual(b[6], 0xE0) // dest: C bit set (command), not last
        XCTAssertEqual(b[13], 0x6E) // source SSID 7, C bit clear, not last
        XCTAssertEqual(b[20], 0x63) // WIDE1-1, last address
        XCTAssertEqual(b[21], 0x03) // UI
        XCTAssertEqual(b[22], 0xF0)
        let back = try XCTUnwrap(AX25Frame.decode(b))
        XCTAssertEqual(back, f)
        XCTAssertEqual(back.monitorLine, "KF0ABC-7>APZNMD,WIDE1-1:hello")
        XCTAssertNil(AX25Address("TOOLONGCALL"))
        XCTAssertNil(AX25Address("AB-16"))
        XCTAssertEqual(AX25Address("wide2-2*")?.repeated, true)
    }

    func testControlFieldsRoundTrip() {
        let all: [AX25Control] = [.i(ns: 5, nr: 3, poll: true), .rr(nr: 7, pf: false), .rnr(nr: 1, pf: true),
                                  .rej(nr: 2, pf: false), .sabm(poll: true), .disc(poll: true), .ua(final: true),
                                  .dm(final: false), .frmr(final: false), .ui(poll: false)]
        for c in all { XCTAssertEqual(AX25Control(byte: c.byte), c, c.name) }
        XCTAssertEqual(AX25Control.sabm(poll: true).byte, 0x3F)
        XCTAssertEqual(AX25Control.ua(final: true).byte, 0x73)
    }

    /// Two links talking through a lossy channel deliver every byte, in order, both ways.
    func testConnectedModeSurvivesLoss() {
        let a = AX25Address("KF0AAA")!, b = AX25Address("KF0BBB-1")!
        let left = AX25Link(local: a, remote: b, path: [])
        let right = AX25Link(local: b, remote: a, path: [])
        var air: [(to: AX25Link, frame: AX25Frame)] = []
        var count = 0
        left.send = { f in count += 1; if count % 4 != 0 { air.append((right, AX25Frame.decode(f.encoded())!)) } }
        right.send = { f in count += 1; if count % 5 != 0 { air.append((left, AX25Frame.decode(f.encoded())!)) } }
        var gotRight: [UInt8] = [], gotLeft: [UInt8] = []
        right.onData = { gotRight += $0 }
        left.onData = { gotLeft += $0 }
        var now = Date(timeIntervalSince1970: 0)
        // The right side accepts the SABM when it arrives.
        let original = right.send
        _ = original
        left.connect(now: now)
        let msgA = (0..<1500).map { UInt8($0 % 251) }
        let msgB = Array(repeating: UInt8(7), count: 700)
        var wrote = false
        for _ in 0..<2000 {
            while !air.isEmpty {
                let (to, f) = air.removeFirst()
                to.receive(f, now: now)
            }
            if left.state == .connected, right.state == .connected, !wrote {
                left.write(msgA, now: now)
                right.write(msgB, now: now)
                wrote = true
            }
            if wrote, gotRight.count == msgA.count, gotLeft.count == msgB.count { break }
            now += 1
            left.tick(now: now)
            right.tick(now: now)
        }
        XCTAssertEqual(gotRight, msgA)
        XCTAssertEqual(gotLeft, msgB)
        left.disconnect(now: now)
        for _ in 0..<200 where left.state != .disconnected {
            while !air.isEmpty { let (to, f) = air.removeFirst(); to.receive(f, now: now) }
            now += 1
            left.tick(now: now)
            right.tick(now: now)
        }
        XCTAssertEqual(left.state, .disconnected)
        XCTAssertNil(left.endReason)
    }

    func testConnectGivesUpWithReason() {
        let link = AX25Link(local: AX25Address("KF0AAA")!, remote: AX25Address("KF0ZZZ")!, path: [],
                            config: AX25LinkConfig(n2: 3))
        var sent = 0
        link.send = { _ in sent += 1 }
        var now = Date(timeIntervalSince1970: 0)
        link.connect(now: now)
        for _ in 0..<100 { now += 1; link.tick(now: now) }
        XCTAssertEqual(link.state, .disconnected)
        XCTAssertEqual(sent, 4)
        XCTAssertNotNil(link.endReason)
    }

    // MARK: APRS

    func testPlainPositionWithCourseAltitudeAndComment() throws {
        let p = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC-9>APRS,WIDE1-1:=3944.40N/10459.40W>090/036/A=005280Driving"))
        XCTAssertEqual(p.kind, .position)
        XCTAssertTrue(p.messaging)
        XCTAssertEqual(p.position!.latitude, 39.74, accuracy: 0.0001)
        XCTAssertEqual(p.position!.longitude, -104.99, accuracy: 0.0001)
        XCTAssertEqual(p.position!.symbolCode, ">")
        XCTAssertEqual(p.courseDegrees, 90)
        XCTAssertEqual(p.speedKnots, 36)
        XCTAssertEqual(p.altitudeFeet, 5280)
        XCTAssertEqual(p.comment, "Driving")
    }

    func testTimestampedAndAmbiguousPositions() throws {
        let p = try XCTUnwrap(APRS.decode(tnc2: "N0CALL>APRS:@092345z3944.  N/10459.  W-Home"))
        XCTAssertEqual(p.position!.ambiguity, 2)
        XCTAssertEqual(p.position!.latitude, 39 + 44.0 / 60, accuracy: 0.0001)
        XCTAssertEqual(p.comment, "Home")
    }

    func testCompressedPosition() throws {
        func b91(_ v: Int) -> String {
            var v = v, s = ""
            for _ in 0..<4 { s = String(UnicodeScalar(UInt8(v % 91 + 33))) + s; v /= 91 }
            return s
        }
        let y = Int((90 - 39.74) * 380926), x = Int((180 - 104.99) * 190463)
        let p = try XCTUnwrap(APRS.decode(tnc2: "N0CALL>APRS:!/" + b91(y) + b91(x) + ">7P[comment"))
        XCTAssertEqual(p.position!.latitude, 39.74, accuracy: 0.0001)
        XCTAssertEqual(p.position!.longitude, -104.99, accuracy: 0.0001)
        XCTAssertEqual(p.courseDegrees, (Int(UInt8(ascii: "7")) - 33) * 4)
        XCTAssertEqual(p.comment, "comment")
    }

    func testMicE() throws {
        let info: [UInt8] = [0x60, 112, 87, 68, 30, 30, 79, UInt8(ascii: ">"), UInt8(ascii: "/")] + Array("]Hello=".utf8)
        let p = try XCTUnwrap(APRS.decode(source: "KF0ABC-7", destination: "SY4TTP", path: [], info: "", rawInfo: info))
        XCTAssertEqual(p.kind, .micE)
        XCTAssertEqual(p.position!.latitude, 39.74, accuracy: 0.0001)
        XCTAssertEqual(p.position!.longitude, -104.99, accuracy: 0.0001)
        XCTAssertEqual(p.speedKnots, 20)
        XCTAssertEqual(p.courseDegrees, 251)
        XCTAssertEqual(p.micEStatus, "En Route")
        XCTAssertEqual(p.comment, "Hello")
    }

    func testMessagesAcksAndBulletins() throws {
        let m = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS::KF0XYZ-7 :Meet at the trailhead{42"))
        XCTAssertEqual(m.kind, .message)
        XCTAssertEqual(m.addressee, "KF0XYZ-7")
        XCTAssertEqual(m.text, "Meet at the trailhead")
        XCTAssertEqual(m.messageID, "42")
        let reply = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS::KF0XYZ   :ok{AB}12"))
        XCTAssertEqual(reply.messageID, "AB")
        let ack = try XCTUnwrap(APRS.decode(tnc2: "KF0XYZ>APRS::KF0ABC   :ack42"))
        XCTAssertEqual(ack.kind, .ack)
        XCTAssertEqual(ack.messageID, "42")
        let bln = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS::BLN1     :Net tonight 8pm 146.52"))
        XCTAssertEqual(bln.kind, .bulletin)
        XCTAssertEqual(APRS.messageInfo(to: "kf0xyz-7", text: "hi | there", id: "3"), ":KF0XYZ-7 :hi ? there{3")
        XCTAssertEqual(APRS.ackInfo(to: "KF0ABC", id: "42"), ":KF0ABC   :ack42")
    }

    func testObjectsItemsWeatherStatusThirdParty() throws {
        let o = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS:;SHELTER  *092345z3944.40N/10459.40W;Red Cross"))
        XCTAssertEqual(o.kind, .object)
        XCTAssertEqual(o.subject, "SHELTER")
        XCTAssertEqual(o.comment, "Red Cross")
        let i = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS:)AID!3944.40N/10459.40W+First aid"))
        XCTAssertEqual(i.kind, .item)
        XCTAssertEqual(i.objectName, "AID")
        let w = try XCTUnwrap(APRS.decode(tnc2: "KF0WX>APRS:=3944.40N/10459.40W_220/004g005t077r000p000P000h50b09900wRSW"))
        XCTAssertEqual(w.kind, .weather)
        XCTAssertEqual(w.weather?.temperatureF, 77)
        XCTAssertEqual(w.weather?.windDirection, 220)
        XCTAssertEqual(w.weather?.humidity, 50)
        XCTAssertEqual(w.weather!.pressureMbar!, 990, accuracy: 0.01)
        let s = try XCTUnwrap(APRS.decode(tnc2: "KF0ABC>APRS:>On the air"))
        XCTAssertEqual(s.kind, .status)
        let t = try XCTUnwrap(APRS.decode(tnc2: "IGATE>APRS:}KF0ABC>APRS,TCPIP,IGATE*::KF0XYZ   :hi{1"))
        XCTAssertEqual(t.source, "KF0ABC")
        XCTAssertEqual(t.kind, .message)
    }

    func testPositionEncodingRoundTrips() throws {
        let info = APRS.positionInfo(lat: 39.74, lon: -104.99, courseDegrees: 90, speedKnots: 36, comment: "Pocket Nomad")
        XCTAssertEqual(info, "=3944.40N/10459.40W[090/036Pocket Nomad")
        let p = try XCTUnwrap(APRS.decode(tnc2: "N0CALL>APZNMD:" + info))
        XCTAssertEqual(p.position!.latitude, 39.74, accuracy: 0.0001)
        XCTAssertEqual(p.comment, "Pocket Nomad")
        XCTAssertEqual(HamGeo.maidenhead(lat: 39.74, lon: -104.99), "DM79mr")
    }

    // MARK: LZHUF + Winlink

    func testLZHUFMatchesReferenceOutput() throws {
        for name in ["gettysburg.txt", "e.txt"] {
            let plain = try fixture(name)
            let packed = try fixture(name + ".lzh")
            XCTAssertEqual(try LZHUF.decompress(packed), plain, name)
            XCTAssertEqual(LZHUF.compress(plain), packed, name)
        }
        XCTAssertEqual(try LZHUF.decompress(LZHUF.compress([])), [])
        var bad = try fixture("gettysburg.txt.lzh")
        bad[40] ^= 0xFF
        XCTAssertThrowsError(try LZHUF.decompress(bad))
    }

    func testSecureLoginResponse() {
        XCTAssertEqual(WinlinkAuth.response(challenge: "23753528", password: "FOOBAR"), "72768415")
        XCTAssertEqual(WinlinkAuth.response(challenge: "23753528", password: "FooBar"), "95074758")
    }

    func testWinlinkMessageRoundTrip() throws {
        var m = WinlinkMessage.compose(from: "kf0abc", to: ["KF0XYZ", "someone@example.com"], subject: "Café check-in",
                                       body: "All safe.\nAt the shelter.", date: Date(timeIntervalSince1970: 1_800_000_000))
        m.attachments = [WinlinkAttachment(name: "note.txt", data: Data("hello".utf8))]
        XCTAssertEqual(m.to, ["KF0XYZ", "SMTP:someone@example.com"])
        let raw = m.encoded()
        XCTAssertTrue(String(decoding: raw, as: UTF8.self).contains("Body: 26\r\n"))
        let back = try WinlinkMessage.parse(raw)
        XCTAssertEqual(back.mid, m.mid)
        XCTAssertEqual(back.subject, "Café check-in")
        XCTAssertEqual(back.body, "All safe.\nAt the shelter.")
        XCTAssertEqual(back.to, m.to)
        XCTAssertEqual(back.attachments, m.attachments)
        XCTAssertEqual(back.date, m.date)
    }

    /// A full exchange against a scripted RMS gateway: login, send one message, receive one, quit.
    func testB2FSessionAgainstScriptedGateway() async throws {
        let toClient = HamByteChannel(), toGateway = HamByteChannel()
        let outgoing = WinlinkMessage.compose(from: "N0CALL", to: ["KF0XYZ"], subject: "Status", body: "Fine here.")
        let incoming = WinlinkMessage.compose(from: "KF0XYZ", to: ["N0CALL"], subject: "Reply", body: "Good to hear.")
        let session = B2FSession(mycall: "N0CALL", password: "FOOBAR", locator: "DM79", target: "TEST-10",
                                 outbound: [outgoing], input: toClient, write: { toGateway.feed($0) })
        var gatewayGot: WinlinkMessage?
        let gateway = Task { @MainActor in
            toClient.feed(Array("Welcome\r[WL2K-5.0-B2FWIHJM$]\r;PQ: 23753528\rCMS via TEST >\r".utf8))
            let v1 = try await toGateway.readLine()
            XCTAssertEqual(v1, ";FW: N0CALL")
            let v2 = try await toGateway.readLine()
            XCTAssertTrue(v2.hasPrefix("[PocketNomad-"))
            let v3 = try await toGateway.readLine()
            XCTAssertEqual(v3, ";PR: 72768415")
            let v4 = try await toGateway.readLine()
            XCTAssertEqual(v4, "; TEST-10 DE N0CALL (DM79)")
            var sum = 0
            var line = try await toGateway.readLine()
            var csize = 0
            while line.hasPrefix("FC") {
                for c in line.utf8 { sum += Int(c) }
                sum += 13
                csize = Int(line.split(separator: " ")[4])!
                line = try await toGateway.readLine()
            }
            XCTAssertEqual(line, String(format: "F> %02X", (-sum) & 0xFF))
            toClient.feed(Array("FS +\r".utf8))
            let v5 = try await toGateway.readByte()
            XCTAssertEqual(v5, 1)
            _ = try await toGateway.readByte()
            let v6 = try await toGateway.readUntil(0)
            XCTAssertEqual(String(decoding: v6, as: UTF8.self), "Status")
            _ = try await toGateway.readUntil(0)
            var data: [UInt8] = []
            while true {
                let c = try await toGateway.readByte()
                if c == 4 { _ = try await toGateway.readByte(); break }
                let n = Int(try await toGateway.readByte())
                for _ in 0..<n { data.append(try await toGateway.readByte()) }
            }
            XCTAssertEqual(data.count, csize)
            gatewayGot = try WinlinkMessage.parse(try LZHUF.decompress(data))
            // Our turn: one message for the client.
            let raw = incoming.encoded(), z = LZHUF.compress(raw)
            let prop = "FC EM \(incoming.mid) \(raw.count) \(z.count) 0"
            var s2 = 0
            for c in prop.utf8 { s2 += Int(c) }
            s2 += 13
            toClient.feed(Array((prop + "\r" + String(format: "F> %02X", (-s2) & 0xFF) + "\r").utf8))
            let v7 = try await toGateway.readLine()
            XCTAssertEqual(v7, "FS +")
            var block: [UInt8] = [1, UInt8("Reply".count + 3)] + Array("Reply".utf8) + [0, 48, 0]
            var ck = 0
            for chunk in stride(from: 0, to: z.count, by: 125) {
                let part = z[chunk..<min(chunk + 125, z.count)]
                block += [2, UInt8(part.count)] + part
                for b in part { ck += Int(b) }
            }
            block += [4, UInt8((-ck) & 0xFF)]
            toClient.feed(block)
            let v8 = try await toGateway.readLine()
            XCTAssertEqual(v8, "FF")
            toClient.feed(Array("FQ\r".utf8))
        }
        let outcome = try await session.run()
        try await gateway.value
        XCTAssertEqual(outcome.sent, [outgoing.mid])
        XCTAssertEqual(outcome.received.map(\.mid), [incoming.mid])
        XCTAssertEqual(outcome.received.first?.body, "Good to hear.")
        XCTAssertEqual(gatewayGot?.body, "Fine here.")
    }

    func testB2FWithoutPasswordExplains() async {
        let toClient = HamByteChannel()
        let session = B2FSession(mycall: "N0CALL", password: nil, locator: "", target: "TEST", outbound: [],
                                 input: toClient, write: { _ in })
        toClient.feed(Array("[WL2K-5.0-B2FWIHJM$]\r;PQ: 1234\rCMS >\r".utf8))
        do {
            _ = try await session.run()
            XCTFail("should fail")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("password"))
        }
    }
}
