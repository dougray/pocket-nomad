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
    /// Which dongle the picker is working on (0 is the first). Only matters when the box has more than one.
    var dongle = 0
    var dongleCount: Int { max(1, state.hello?.dongles ?? 1) }
    /// What the chosen dongle is listening to.
    var mode: String? { state.active[min(dongle, dongleCount - 1)] }
    /// True when any dongle is listening to this mode.
    func listening(_ m: String) -> Bool { state.active.values.contains(m) }
    var host = UserDefaults.standard.string(forKey: "sdr.host") ?? "" { didSet { UserDefaults.standard.set(host, forKey: "sdr.host") } }
    var port = UserDefaults.standard.object(forKey: "sdr.port") as? Int ?? 8731 { didSet { UserDefaults.standard.set(port, forKey: "sdr.port") } }

    /// The box's pairing token, kept in the Keychain.
    var token: String {
        get { SdrToken.load() }
        set { SdrToken.save(newValue) }
    }

    /// How the box is reached: over Wi-Fi (an address) or over Bluetooth LE.
    var useBluetooth = UserDefaults.standard.bool(forKey: "sdr.bluetooth") { didSet { UserDefaults.standard.set(useBluetooth, forKey: "sdr.bluetooth") } }
    private(set) var bleDevices: [SdrBleDevice] = []
    private var ble: SdrBle?

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

    // MARK: Bluetooth

    func scanBluetooth() {
        let b = ble ?? SdrBle()
        ble = b
        b.onDevices = { [weak self] d in self?.bleDevices = d }
        b.onData = { [weak self] d in self?.received(d) }
        b.onState = { [weak self] s in
            guard let self else { return }
            self.link = s
            if s == .connected { self.send(["cmd": "auth", "token": self.token]) }
        }
        bleDevices = []
        b.startScan()
    }

    func connectBluetooth(_ device: SdrBleDevice) {
        state.reset(); reader = SdrLineReader()
        ble?.connect(device)
    }

    func disconnect() {
        ble?.disconnect(); ble?.stopScan()
        connection?.cancel()
        connection = nil
        state.setActive(0, nil); dongle = 0
        link = .idle
    }

    /// With one dongle the commands are exactly what older boxes expect; with several they name the dongle.
    func start(mode m: String) {
        let d = min(dongle, dongleCount - 1)
        send(dongleCount > 1 ? ["cmd": "start", "mode": m, "dongle": d] : ["cmd": "start", "mode": m]); state.setActive(d, m)
    }
    func stop() {
        let d = min(dongle, dongleCount - 1)
        send(dongleCount > 1 ? ["cmd": "stop", "dongle": d] : ["cmd": "stop"]); state.setActive(d, nil)
    }

    /// Fetch a weather picture from the box over HTTP (Wi-Fi only: the pictures are too big for Bluetooth).
    /// Choose the band the spectrum view sweeps (MHz). The box checks the range.
    func sendSpectrumRange(lowMHz: Double, highMHz: Double) {
        send(["cmd": "settings", "spectrum_lo_mhz": lowMHz, "spectrum_hi_mhz": highMHz])
        state.resetPeak()
    }

    func fetchImage(_ image: SdrImage) async -> Data? {
        let h = host.trimmingCharacters(in: .whitespaces)
        guard !useBluetooth, !h.isEmpty, let url = URL(string: "http://\(h):8732\(image.url)") else { return nil }
        var req = URLRequest(url: url); req.timeoutInterval = 30
        if !token.isEmpty { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        guard let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }

    /// Tell the box roughly where you are (rounded to 0.1 degree, kept only on the box) so it knows when satellites pass.
    func sendArea(latitude: Double, longitude: Double) {
        send(["cmd": "settings", "lat": (latitude * 10).rounded() / 10, "lon": (longitude * 10).rounded() / 10])
    }

    private func send(_ cmd: [String: Any]) {
        guard link == .connected, var data = try? JSONSerialization.data(withJSONObject: cmd) else { return }
        data.append(0x0A)
        if useBluetooth { ble?.write(data); return }
        connection?.send(content: data, completion: .contentProcessed { _ in })
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
