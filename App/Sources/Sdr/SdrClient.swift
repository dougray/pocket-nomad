// SdrClient.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// Connects to an SDR box over the network (a Raspberry Pi with an RTL-SDR dongle, or the
// simulator on a Mac) and feeds what it hears into the app. Receive-only: the only things sent
// to the box are "start listening to X" and "stop".

import Foundation
import Network
import Observation

@MainActor
@Observable
final class SdrClient {
    static let shared = SdrClient()

    enum Link: Equatable {
        case idle, connecting, connected
        case failed(String)
    }

    private(set) var link: Link = .idle
    private(set) var mode: String?
    var host = UserDefaults.standard.string(forKey: "sdr.host") ?? "" { didSet { UserDefaults.standard.set(host, forKey: "sdr.host") } }
    var port = UserDefaults.standard.object(forKey: "sdr.port") as? Int ?? 8731 { didSet { UserDefaults.standard.set(port, forKey: "sdr.port") } }

    /// The box's pairing token, kept in the Keychain.
    var token: String {
        get { SdrToken.load() }
        set { SdrToken.save(newValue) }
    }

    private var connection: NWConnection?
    private var reader = SdrLineReader()
    private let state = SdrState.shared

    var isConnected: Bool { link == .connected }

    func connect() {
        disconnect()
        let h = host.trimmingCharacters(in: .whitespaces)
        guard !h.isEmpty, let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { link = .failed("Enter the box's address."); return }
        link = .connecting
        state.reset()
        reader = SdrLineReader()
        let c = NWConnection(host: NWEndpoint.Host(h), port: p, using: .tcp)
        connection = c
        c.stateUpdateHandler = { [weak self] s in
            Task { @MainActor in
                guard let self, self.connection === c else { return }
                switch s {
                case .ready: self.link = .connected; self.receiveLoop(c); self.send(["cmd": "auth", "token": self.token])
                case .failed(let e): self.link = .failed(e.localizedDescription); self.connection = nil
                case .waiting(let e): self.link = .failed("Can't reach the box (\(e.localizedDescription)).")
                case .cancelled: if self.link != .idle { self.link = .idle }
                default: break
                }
            }
        }
        c.start(queue: .main)
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        mode = nil
        link = .idle
    }

    func start(mode m: String) { send(["cmd": "start", "mode": m]); mode = m }
    func stop() { send(["cmd": "stop"]); mode = nil }

    private func send(_ cmd: [String: Any]) {
        guard let c = connection, link == .connected, var data = try? JSONSerialization.data(withJSONObject: cmd) else { return }
        data.append(0x0A)
        c.send(content: data, completion: .contentProcessed { _ in })
    }

    private func receiveLoop(_ c: NWConnection) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, error in
            Task { @MainActor in
                guard let self, self.connection === c else { return }
                if let data { self.received(data) }
                if done || error != nil { self.link = .failed("The box closed the connection."); self.connection = nil; return }
                self.receiveLoop(c)
            }
        }
    }

    func received(_ data: Data) {
        for obj in reader.feed(data) { if let e = SdrParser.event(obj) { handle(e) } }
    }

    /// Apply one event. APRS frames go to the Ham tab's decoder, but never from a simulated box.
    func handle(_ e: SdrEvent) {
        if case .error(let code) = e {
            link = .failed(code == "blocked" ? "Too many wrong tries. Wait a minute and try again." : "The box didn't accept the pairing token.")
            connection?.cancel(); connection = nil
            return
        }
        state.apply(e)
        if case .aprs(let frame, _) = e, !state.simulated { HamService.shared.receive(frame) }
    }
}
