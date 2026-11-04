import Foundation
import WatchConnectivity

/// The iPhone end of the Apple Watch link. It answers the watch's requests
/// (status, mesh SOS, quick replies) using MeshService's public API and nothing
/// else, so the mesh code doesn't know the watch exists. It never sends to the
/// mesh on its own: only a request from a tap on the watch does that.
@MainActor
final class WatchBridge: NSObject {
    static let shared = WatchBridge()

    private let mesh: MeshService
    private var started = false

    init(mesh: MeshService = .shared) {
        self.mesh = mesh
        super.init()
    }

    func start() {
        guard !started, WCSession.isSupported() else { return }
        started = true
        WCSession.default.delegate = self
        WCSession.default.activate()
        observeMesh()
    }

    // MARK: - Requests

    func handle(_ request: WatchRelay.Request) async -> WatchRelay.Response {
        switch request {
        case .status:
            return WatchRelay.Response(ok: true, detail: "", status: status())
        case .sos(let latitude, let longitude):
            guard mesh.canSend else { return notConnected() }
            let sent: String?
            if let latitude, let longitude {
                let text = MeshText.truncated(
                    MeshSOS.text(latitude: latitude, longitude: longitude, senderName: mesh.myNode?.longName),
                    maxBytes: mesh.maxTextBytes)
                sent = mesh.send(text, to: .channel(0)) ? text : nil
            } else {
                sent = await mesh.sendSOS(includeLocation: true)
            }
            return WatchRelay.Response(ok: sent != nil, detail: sent ?? (mesh.notice ?? "The SOS could not be sent."), status: status())
        case .reply(let raw, let conversation):
            guard mesh.canSend else { return notConnected() }
            guard let text = WatchRelay.cleaned(raw) else {
                return WatchRelay.Response(ok: false, detail: "Nothing to send.", status: status())
            }
            guard MeshText.byteCount(text) <= mesh.maxTextBytes else {
                return WatchRelay.Response(ok: false, detail: "Too long for the radio.", status: status())
            }
            let ok = mesh.send(text, to: Self.meshConversation(conversation))
            return WatchRelay.Response(ok: ok, detail: ok ? text : (mesh.notice ?? "Not sent."), status: status())
        }
    }

    private func notConnected() -> WatchRelay.Response {
        WatchRelay.Response(ok: false, detail: "The phone isn't connected to a mesh radio.", status: status())
    }

    func status() -> WatchRelay.Status {
        let radioName: String? = {
            if case .connected(let name) = mesh.connection { return name }
            return nil
        }()
        return Self.status(messages: mesh.messages, myNum: mesh.myNodeNum, connected: mesh.canSend,
                           radioName: radioName, maxTextBytes: mesh.maxTextBytes,
                           nodeName: { self.mesh.node($0).displayName },
                           channelName: { self.mesh.radio.channelNames[Int32($0)] })
    }

    /// Pure: builds the watch's view of the mesh from the phone's messages.
    static func status(messages: [MeshMessage], myNum: UInt32, connected: Bool, radioName: String?,
                       maxTextBytes: Int, nodeName: (UInt32) -> String,
                       channelName: (UInt32) -> String?) -> WatchRelay.Status {
        let recent = messages.sorted { $0.date > $1.date }.prefix(WatchRelay.maxMessages)
        let out: [WatchRelay.Message] = recent.compactMap { m in
            guard let c = MeshService.conversation(of: m, myNum: myNum) else { return nil }
            let conversation: WatchRelay.Conversation
            let title: String
            switch c {
            case .channel(let i):
                conversation = .channel(i)
                let name = channelName(i) ?? ""
                title = name.isEmpty ? (i == 0 ? "Primary channel" : "Channel \(i)") : name
            case .direct(let n):
                conversation = .direct(n)
                title = nodeName(n)
            }
            let sender = m.isOutgoing ? "Me" : (m.senderName ?? nodeName(m.from))
            return WatchRelay.Message(id: m.id, sender: sender, text: m.text, date: m.date,
                                      isOutgoing: m.isOutgoing, conversation: conversation, conversationTitle: title)
        }
        return WatchRelay.Status(radioConnected: connected, radioName: radioName,
                                 maxTextBytes: maxTextBytes, messages: out)
    }

    static func meshConversation(_ c: WatchRelay.Conversation) -> MeshConversation {
        switch c {
        case .channel(let i): .channel(i)
        case .direct(let n): .direct(n)
        }
    }

    // MARK: - Pushing changes

    /// Keeps the watch's copy fresh while it isn't asking: new messages and
    /// radio changes go out as the latest application context (overwritten, not queued).
    private func observeMesh() {
        withObservationTracking {
            _ = mesh.messages
            _ = mesh.connection
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.pushStatus()
                self?.observeMesh()
            }
        }
    }

    private func pushStatus() {
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled,
              let context = try? WatchRelay.encode(status()) else { return }
        try? session.updateApplicationContext(context)
    }
}

/// WatchConnectivity's reply handler isn't Sendable; it is safe to call from any thread.
private struct ReplyBox: @unchecked Sendable {
    let reply: ([String: Any]) -> Void
}

extension WatchBridge: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: (any Error)?) {
        Task { @MainActor in self.pushStatus() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // Switching watches: reactivate for the new one.
        session.activate()
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                             replyHandler: @escaping ([String: Any]) -> Void) {
        let box = ReplyBox(reply: replyHandler)
        guard let request = try? WatchRelay.decode(WatchRelay.Request.self, from: message) else {
            box.reply((try? WatchRelay.encode(WatchRelay.Response(ok: false, detail: "Unknown request."))) ?? [:])
            return
        }
        Task { @MainActor in
            let response = await self.handle(request)
            box.reply((try? WatchRelay.encode(response)) ?? [:])
        }
    }
}
