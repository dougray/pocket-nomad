import Foundation
import Observation
import WatchConnectivity

/// The watch end of the phone link. It asks the phone for the mesh status and,
/// only when the user taps, asks it to send an SOS or a reply.
@MainActor @Observable
final class WatchConnector: NSObject {
    static let shared = WatchConnector()

    private(set) var status: WatchRelay.Status?
    private(set) var isReachable = false
    private(set) var updated: Date?
    @ObservationIgnored private var started = false
    #if DEBUG
    @ObservationIgnored private var demo = false
    #endif

    /// The phone is nearby and connected to a radio, so a mesh send can work.
    var canUseMesh: Bool { isReachable && status?.radioConnected == true }

    func start() {
        guard !started else { return }
        started = true
        #if DEBUG
        if demo { return }
        #endif
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    func refresh() async {
        let response = await send(.status)
        if let s = response.status { status = s; updated = Date() }
    }

    func send(_ request: WatchRelay.Request) async -> WatchRelay.Response {
        #if DEBUG
        if demo { return demoResponse(request) }
        #endif
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else {
            return WatchRelay.Response(ok: false, detail: "Your iPhone isn't reachable. Keep it nearby with Pocket Nomad installed.")
        }
        guard let message = try? WatchRelay.encode(request) else {
            return WatchRelay.Response(ok: false, detail: "Could not prepare the request.")
        }
        let response: WatchRelay.Response = await withCheckedContinuation { continuation in
            session.sendMessage(message, replyHandler: { reply in
                let decoded = (try? WatchRelay.decode(WatchRelay.Response.self, from: reply))
                    ?? WatchRelay.Response(ok: false, detail: "The phone sent an unreadable reply.")
                continuation.resume(returning: decoded)
            }, errorHandler: { error in
                continuation.resume(returning: WatchRelay.Response(ok: false, detail: error.localizedDescription))
            })
        }
        if let s = response.status { status = s; updated = Date() }
        return response
    }

    private func apply(_ status: WatchRelay.Status) {
        self.status = status
        updated = Date()
    }

    #if DEBUG
    /// `-demo YES`: canned mesh data for screenshots and the simulator. Nothing is sent.
    func startDemo() {
        demo = true
        isReachable = true
        let now = Date()
        status = WatchRelay.Status(radioConnected: true, radioName: "Heltec V4", maxTextBytes: 200, messages: [
            .init(id: UUID(), sender: "Ana", text: "Water at the north trailhead. Where are you?",
                  date: now.addingTimeInterval(-120), isOutgoing: false,
                  conversation: .channel(0), conversationTitle: "Primary channel"),
            .init(id: UUID(), sender: "Base Camp", text: "Check in by 18:00 please",
                  date: now.addingTimeInterval(-900), isOutgoing: false,
                  conversation: .direct(0xA002), conversationTitle: "Base Camp"),
            .init(id: UUID(), sender: "Me", text: "On my way", date: now.addingTimeInterval(-1500),
                  isOutgoing: true, conversation: .channel(0), conversationTitle: "Primary channel"),
        ])
        updated = now
    }

    private func demoResponse(_ request: WatchRelay.Request) -> WatchRelay.Response {
        switch request {
        case .status: return WatchRelay.Response(ok: true, detail: "", status: status)
        case .sos: return WatchRelay.Response(ok: true, detail: "SOS - I need help. (demo, not sent)", status: status)
        case .reply(let text, _): return WatchRelay.Response(ok: true, detail: "\(text) (demo, not sent)", status: status)
        }
    }
    #endif
}

extension WatchConnector: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: (any Error)?) {
        let reachable = session.isReachable
        let context = try? WatchRelay.decode(WatchRelay.Status.self, from: session.receivedApplicationContext)
        Task { @MainActor in
            self.isReachable = reachable
            if let context, self.status == nil { self.apply(context) }
            if reachable { await self.refresh() }
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor in
            self.isReachable = reachable
            if reachable { await self.refresh() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let status = try? WatchRelay.decode(WatchRelay.Status.self, from: applicationContext) else { return }
        Task { @MainActor in self.apply(status) }
    }
}
