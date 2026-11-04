import XCTest
@testable import NomadPocket

/// The pure parts of the Apple Watch app: guide step extraction (Shared/FirstAidSteps)
/// and the phone-watch relay encoding (Shared/WatchRelay, App/Sources/Watch/WatchBridge).
final class WatchTests: XCTestCase {
    // MARK: - First-aid extraction

    func testExtractsDoThisNowStepsAndCallouts() {
        let html = """
        <h2>Do this now</h2>
        <ol>
        <li>Call 911. Put on <strong>gloves</strong>.</li>
        <li>Press  hard &amp; don&#39;t   let go.
        </li>
        </ol>
        <div class="callout danger">Gasping is <em>not</em> breathing.</div>
        <h2>More</h2>
        <ol><li>Not a first step</li></ol>
        <div class="callout warn">Not this one</div>
        """
        let parts = FirstAidSteps.extract(html: html)
        XCTAssertEqual(parts.steps, ["Call 911. Put on gloves.", "Press hard & don't let go."])
        XCTAssertEqual(parts.warnings, ["Gasping is not breathing."])
    }

    func testListItemsWithoutClosingTags() {
        let parts = FirstAidSteps.extract(html: "<h2>Now</h2><ol><li>One<li>Two</ol>")
        XCTAssertEqual(parts.steps, ["One", "Two"])
    }

    func testEntityDecoding() {
        XCTAssertEqual(FirstAidSteps.decodeEntities("40&deg; &lt;2&gt; &#x2014; &bogus; a&b"), "40° <2> — &bogus; a&b")
    }

    func testUrgentEntriesComeFromIndex() throws {
        let data = try Data(contentsOf: GuideStore.root.appending(path: "index.json"))
        let entries = FirstAidSteps.urgentEntries(indexJSON: data)
        XCTAssertEqual(entries.map(\.id), GuideStore.urgent.map(\.id))
        for id in ["cpr", "bleeding", "choking"] { XCTAssertTrue(entries.contains { $0.id == id }, id) }
    }

    /// Runs the watch's extraction over the real bundled guides, so a guide edit
    /// that breaks the "Do this now" list fails here instead of on a wrist.
    func testEveryUrgentGuideHasWatchSteps() {
        let cards = FirstAidSteps.load(guidesRoot: GuideStore.root)
        XCTAssertEqual(cards.map(\.id), GuideStore.urgent.map(\.id))
        for card in cards {
            XCTAssertGreaterThanOrEqual(card.steps.count, 3, card.id)
            for step in card.steps + card.warnings {
                XCTAssertFalse(step.contains("<") || step.contains(">"), "\(card.id): \(step)")
                XCTAssertFalse(step.contains("&amp;") || step.contains("&#"), "\(card.id): \(step)")
            }
        }
        let cpr = cards.first { $0.id == "cpr" }
        XCTAssertTrue(cpr?.steps.contains { $0.contains("100 to 120") } ?? false)
    }

    // MARK: - Relay encoding

    func testRequestsRoundTrip() throws {
        let requests: [WatchRelay.Request] = [
            .status,
            .sos(latitude: 39.74956, longitude: -104.99654),
            .sos(latitude: nil, longitude: nil),
            .reply(text: "On my way", conversation: .direct(0xA002)),
            .reply(text: "OK", conversation: .channel(1)),
        ]
        for r in requests {
            let message = try WatchRelay.encode(r)
            XCTAssertNotNil(message[WatchRelay.payloadKey] as? Data)
            XCTAssertEqual(try WatchRelay.decode(WatchRelay.Request.self, from: message), r)
        }
    }

    func testDecodeRejectsMissingPayload() {
        XCTAssertThrowsError(try WatchRelay.decode(WatchRelay.Request.self, from: ["other": 1]))
    }

    func testResponseRoundTripWithStatus() throws {
        let status = WatchRelay.Status(radioConnected: true, radioName: "Radio", maxTextBytes: 200, messages: [
            .init(id: UUID(), sender: "Ana", text: "Hi", date: Date(timeIntervalSince1970: 1_000),
                  isOutgoing: false, conversation: .channel(0), conversationTitle: "Primary channel"),
        ])
        let response = WatchRelay.Response(ok: true, detail: "Sent", status: status)
        XCTAssertEqual(try WatchRelay.decode(WatchRelay.Response.self, from: WatchRelay.encode(response)), response)
    }

    func testCleanedRefusesEmptyReplies() {
        XCTAssertNil(WatchRelay.cleaned("  \n "))
        XCTAssertEqual(WatchRelay.cleaned("  Need help \n"), "Need help")
    }

    func testQuickRepliesFitEveryRadio() {
        for reply in WatchRelay.quickReplies {
            XCTAssertLessThanOrEqual(MeshText.byteCount(reply), MeshConstants.maxTextBytes)
        }
    }

    @MainActor
    func testBridgeStatusMapsConversationsAndCaps() {
        let me: UInt32 = 0x1
        var messages: [MeshMessage] = []
        for i in 0..<30 {
            messages.append(MeshMessage(packetID: UInt32(i), from: 0xA000, to: MeshConstants.broadcastNum, channel: 0,
                                        text: "ch \(i)", date: Date(timeIntervalSince1970: Double(i)),
                                        isOutgoing: false, delivery: .received))
        }
        messages.append(MeshMessage(packetID: 99, from: 0xA002, to: me, channel: 0, text: "dm",
                                    date: Date(timeIntervalSince1970: 100), isOutgoing: false, delivery: .received))
        messages.append(MeshMessage(packetID: 100, from: me, to: 0xA002, channel: 0, text: "reply",
                                    date: Date(timeIntervalSince1970: 101), isOutgoing: true, delivery: .sent))
        let status = WatchBridge.status(messages: messages, myNum: me, connected: true, radioName: "R",
                                        maxTextBytes: 200, nodeName: { $0 == 0xA002 ? "Base Camp" : "Ana" },
                                        channelName: { _ in nil })
        XCTAssertEqual(status.messages.count, WatchRelay.maxMessages)
        XCTAssertEqual(status.messages[0].text, "reply")
        XCTAssertEqual(status.messages[0].sender, "Me")
        XCTAssertEqual(status.messages[0].conversation, .direct(0xA002))
        XCTAssertEqual(status.messages[1].conversation, .direct(0xA002))
        XCTAssertEqual(status.messages[1].conversationTitle, "Base Camp")
        XCTAssertEqual(status.messages[2].conversation, .channel(0))
        XCTAssertEqual(status.messages[2].conversationTitle, "Primary channel")
        XCTAssertEqual(status.messages[2].sender, "Ana")
        XCTAssertEqual(WatchBridge.meshConversation(.direct(7)), .direct(7))
        XCTAssertEqual(WatchBridge.meshConversation(.channel(2)), .channel(2))
    }

    @MainActor
    func testBridgeRefusesWhenNoRadio() async {
        let bridge = WatchBridge(mesh: .shared)
        let response = await bridge.handle(.reply(text: "OK", conversation: .channel(0)))
        XCTAssertFalse(response.ok)
        let sos = await bridge.handle(.sos(latitude: 1, longitude: 2))
        XCTAssertFalse(sos.ok)
    }
}
