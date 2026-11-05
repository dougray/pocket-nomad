// MeshService.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Owns the radio links, the handshakes (Meshtastic or MeshCore), the node
// lists and chat history. Views observe `MeshService.shared`.
//
// Up to two radios can be connected at once, one per firmware: each is a
// MeshLink with its own state, node list and settings, and every message is
// tagged with the network it belongs to. The two networks are never bridged.
// Single-radio callers keep working: `connection`, `radio`, `nodes`, `send`
// and friends either aggregate both links or use `defaultNetwork`.
//
// Call `MeshService.shared.start()` once at app launch (before the first
// scene renders) so CoreBluetooth state restoration can hand back the radio
// connections when iOS relaunches the app in the background.

import Foundation
import Observation

@Observable
@MainActor
final class MeshService {
    static let shared = MeshService()

    // MARK: Observable state

    private(set) var availability: MeshBluetoothAvailability = .unknown
    private(set) var discovered: [MeshDiscoveredDevice] = []
    private(set) var messages: [MeshMessage] = []
    private(set) var lastRead: [String: Date] = [:]
    /// Radios the user blocked: their messages are dropped and they leave the node list.
    private(set) var blocked: Set<UInt32> = []
    /// Shown when pairing is needed or went wrong.
    private(set) var pairingHint: String?
    /// One-line notice from the radio or about a failure. UI may clear it.
    var notice: String?
    private(set) var isScanning = false

    /// One link per firmware, Meshtastic first. Always both; a link with no
    /// remembered radio is an empty slot.
    let links: [MeshLink]

    // MARK: Internals

    private let transport: MeshTransport
    private let store: MeshStore
    private let defaults: UserDefaults
    private let location: () -> MeshLocationProvider
    private var started = false
    private var saveTask: Task<Void, Never>?
    @ObservationIgnored private lazy var locationProvider: MeshLocationProvider = location()
    /// Called each time a radio hears another node live (for the coverage scanner).
    /// `MeshHeard.firmware` says which radio heard it.
    @ObservationIgnored var onHeard: ((MeshHeard) -> Void)?

    private enum Keys {
        // Before two-radio support: one remembered radio.
        static let lastDeviceID = "mesh.lastDeviceID"
        static let lastDeviceName = "mesh.lastDeviceName"
        static let autoReconnect = "mesh.autoReconnect"
        static let migrated = "mesh.radioSlotsMigrated"
        static let blocked = "mesh.blocked"
        // One slot per firmware.
        static func deviceID(_ f: MeshFirmware) -> String { "mesh.\(f.rawValue).deviceID" }
        static func deviceName(_ f: MeshFirmware) -> String { "mesh.\(f.rawValue).deviceName" }
        static func autoReconnect(_ f: MeshFirmware) -> String { "mesh.\(f.rawValue).autoReconnect" }
    }

    init(transport: MeshTransport? = nil,
         store: MeshStore = MeshStore(),
         defaults: UserDefaults = .standard,
         location: @escaping () -> MeshLocationProvider = { MeshLocationProvider() }) {
        self.transport = transport ?? MeshBLETransport()
        self.store = store
        self.defaults = defaults
        self.location = location
        links = MeshFirmware.allCases.map { MeshLink(firmware: $0) }
        blocked = Set((defaults.array(forKey: Keys.blocked) as? [Int] ?? []).map { UInt32(truncatingIfNeeded: $0) })

        let snap = store.load()
        // Files from before two-radio support describe one radio; this is its network.
        let legacy = snap.radio.firmware ?? .meshtastic
        if let networks = snap.networks {
            for net in networks { load(net.nodes, net.radio, into: link(net.firmware)) }
        } else if !snap.nodes.isEmpty || snap.radio != MeshRadioInfo() {
            load(snap.nodes, snap.radio, into: link(legacy))
        }
        messages = snap.messages.map { m in
            var m = m
            if m.network == nil { m.network = legacy }
            return m
        }
        lastRead = Dictionary(snap.lastRead.map { key, date in
            (key.contains(".") ? key : "\(legacy.rawValue).\(key)", date)
        }, uniquingKeysWith: max)

        for l in links {
            if let s = defaults.string(forKey: Keys.deviceID(l.firmware)) { l.deviceID = UUID(uuidString: s) }
            l.deviceName = defaults.string(forKey: Keys.deviceName(l.firmware))
            l.autoReconnect = defaults.bool(forKey: Keys.autoReconnect(l.firmware))
        }
        migrateSingleRadio(into: legacy)

        self.transport.onEvent = { [weak self] event in self?.handle(event) }
    }

    private func load(_ nodes: [MeshNode], _ radio: MeshRadioInfo, into l: MeshLink) {
        l.nodes = Dictionary(nodes.map { n in
            var n = n
            n.network = l.firmware
            return (n.num, n)
        }, uniquingKeysWith: { _, b in b })
        l.radio = radio
        l.radio.firmware = l.firmware
    }

    /// The one remembered radio of older versions moves into its firmware's slot, once.
    private func migrateSingleRadio(into firmware: MeshFirmware) {
        guard !defaults.bool(forKey: Keys.migrated) else { return }
        defer { defaults.set(true, forKey: Keys.migrated) }
        guard let s = defaults.string(forKey: Keys.lastDeviceID), let id = UUID(uuidString: s) else { return }
        let l = link(firmware)
        if l.deviceID == nil {
            l.deviceID = id
            l.deviceName = defaults.string(forKey: Keys.lastDeviceName)
            l.autoReconnect = defaults.bool(forKey: Keys.autoReconnect)
            saveSlot(l)
        }
        defaults.removeObject(forKey: Keys.lastDeviceID)
        defaults.removeObject(forKey: Keys.lastDeviceName)
        defaults.removeObject(forKey: Keys.autoReconnect)
    }

    // MARK: - Links

    func link(_ firmware: MeshFirmware) -> MeshLink {
        links.first { $0.firmware == firmware }!
    }

    /// The link that owns this Bluetooth device, if any.
    func link(forDevice id: UUID) -> MeshLink? {
        links.first { $0.deviceID == id }
    }

    /// Radios the user has set up (connected or not), Meshtastic first.
    var rememberedLinks: [MeshLink] { links.filter(\.isRemembered) }
    /// Radios with a working Bluetooth link (syncing or connected).
    var activeLinks: [MeshLink] { links.filter { $0.connection.isLinkUp } }
    var connectedLinks: [MeshLink] { links.filter { $0.connection.isConnected } }

    /// Networks to show chats and nodes for: set up, or with saved history.
    var networksInUse: [MeshFirmware] {
        links.filter { l in l.hasData || messages.contains { $0.network == l.firmware } }.map(\.firmware)
    }

    /// The network single-radio callers mean: the only active radio, else
    /// Meshtastic when both are up, else the radio that is set up.
    var defaultNetwork: MeshFirmware {
        if let up = activeLinks.first { return up.firmware }
        if let busy = links.first(where: { $0.connection != .idle && $0.isRemembered }) { return busy.firmware }
        if let remembered = rememberedLinks.first { return remembered.firmware }
        if let withData = links.first(where: { $0.radio.myNodeNum != 0 }) { return withData.firmware }
        return .meshtastic
    }

    /// The radio that connecting to `device` would replace: one per firmware.
    func replaced(by device: MeshDiscoveredDevice) -> MeshLink? {
        if link(forDevice: device.id) != nil { return nil }
        let l = link(device.firmware)
        return l.isRemembered ? l : nil
    }

    // MARK: - Public API

    /// Brings Bluetooth up and reconnects to the radios the user had
    /// connected. Safe to call more than once.
    func start() {
        guard !started else { return }
        started = true
        transport.start()
        for l in links where l.autoReconnect {
            guard let id = l.deviceID else { continue }
            l.connection = .reconnecting(name: l.displayName)
            transport.connect(id: id)
        }
    }

    func startScan() {
        start()
        discovered.removeAll()
        isScanning = true
        transport.startScan()
    }

    func stopScan() {
        isScanning = false
        transport.stopScan()
    }

    /// Connects to a radio. If a radio of the same firmware is already set up,
    /// it is disconnected and forgotten (the UI asks first): one per firmware.
    func connect(to device: MeshDiscoveredDevice) {
        connect(id: device.id, name: device.name, firmware: device.firmware)
    }

    /// Reconnects every remembered radio that isn't connected.
    func reconnectLast() {
        for l in rememberedLinks where !l.connection.isLinkUp { reconnect(l.firmware) }
    }

    func reconnect(_ network: MeshFirmware) {
        let l = link(network)
        guard let id = l.deviceID else { return }
        connect(id: id, name: l.displayName, firmware: network)
    }

    /// Disconnects one radio, or every radio when `network` is nil. The radio
    /// stays remembered (offered as "Reconnect") but won't reconnect by itself.
    func disconnect(_ network: MeshFirmware? = nil) {
        for l in links where network == nil || l.firmware == network {
            l.autoReconnect = false
            saveSlot(l)
            l.handshakeTimeout?.cancel()
            coreReset(l)
            if let id = l.deviceID, l.connection != .idle { transport.disconnect(id: id) }
            l.isReading = false
            l.connection = .idle
        }
    }

    /// Forgets one remembered radio, or all of them when `network` is nil.
    func forgetDevice(_ network: MeshFirmware? = nil) {
        disconnect(network)
        for l in links where network == nil || l.firmware == network {
            l.deviceID = nil
            l.deviceName = nil
            saveSlot(l)
        }
    }

    /// Aggregate link state: the best state of any radio (connected beats
    /// syncing beats connecting...), naming every radio in that state.
    var connection: MeshConnectionState {
        func named(_ match: (MeshConnectionState) -> Bool) -> String? {
            let names = links.filter { match($0.connection) }.map(\.displayName)
            return names.isEmpty ? nil : names.joined(separator: " + ")
        }
        if let n = named({ $0.isConnected }) { return .connected(name: n) }
        if let n = named({ if case .syncing = $0 { true } else { false } }) { return .syncing(name: n) }
        if let n = named({ if case .connecting = $0 { true } else { false } }) { return .connecting(name: n) }
        if let n = named({ if case .reconnecting = $0 { true } else { false } }) { return .reconnecting(name: n) }
        if let failed = links.lazy.compactMap({ l -> String? in if case .failed(let why) = l.connection { why } else { nil } }).first {
            return .failed(failed)
        }
        return isScanning ? .scanning : .idle
    }

    /// The default network's radio settings (see `link(_:)` for a given one).
    var radio: MeshRadioInfo { link(defaultNetwork).radio }
    var myNodeNum: UInt32 { link(defaultNetwork).radio.myNodeNum }
    var myNode: MeshNode? { link(defaultNetwork).myNode }

    /// The default network's remembered radio.
    var lastDeviceID: UUID? { link(defaultNetwork).deviceID }
    var lastDeviceName: String? { link(defaultNetwork).deviceName }

    /// Every node on every network, by number (the default network wins the
    /// unlikely clash of the same number on both).
    var nodes: [UInt32: MeshNode] {
        var out = link(defaultNetwork).nodes
        for l in links where l.firmware != defaultNetwork {
            out.merge(l.nodes) { mine, _ in mine }
        }
        return out
    }

    /// Other radios on both networks, most recently heard first.
    var sortedNodes: [MeshNode] {
        links.flatMap { l in l.nodes.values.filter { $0.num != l.radio.myNodeNum } }
            .filter { !blocked.contains($0.num) }
            .sorted { ($0.lastHeard ?? .distantPast) > ($1.lastHeard ?? .distantPast) }
    }

    /// Blocked radios, for the list where they can be unblocked.
    var blockedNodes: [MeshNode] {
        blocked.map { node($0) }.sorted { $0.displayName < $1.displayName }
    }

    func isBlocked(_ num: UInt32) -> Bool { blocked.contains(num) }

    /// Hides a radio on this phone: deletes its messages, drops new ones and takes it
    /// off the node list. Nothing is sent; the other radio can't tell.
    func block(_ num: UInt32) {
        guard num != 0, !links.contains(where: { $0.radio.myNodeNum == num }) else { return }
        blocked.insert(num)
        messages.removeAll { $0.from == num || ($0.isOutgoing && $0.to == num) }
        saveBlocked()
        scheduleSave()
    }

    func unblock(_ num: UInt32) {
        blocked.remove(num)
        saveBlocked()
    }

    private func saveBlocked() {
        defaults.set(blocked.map { Int($0) }, forKey: Keys.blocked)
    }

    /// A node on a network, or on any network when `network` is nil.
    func node(_ num: UInt32, network: MeshFirmware? = nil) -> MeshNode {
        if let network { return link(network).nodes[num] ?? MeshNode(num: num, network: network) }
        return nodes[num] ?? MeshNode(num: num)
    }

    /// One thread on one network (the default network when nil).
    func messages(in conversation: MeshConversation, network: MeshFirmware? = nil) -> [MeshMessage] {
        let net = network ?? defaultNetwork
        let myNum = link(net).radio.myNodeNum
        return messages.filter { $0.network == net && Self.conversation(of: $0, myNum: myNum) == conversation }
    }

    /// Direct-message threads on both networks, newest first.
    var directThreads: [(node: MeshNode, last: MeshMessage, network: MeshFirmware)] {
        var latest: [String: (UInt32, MeshMessage, MeshFirmware)] = [:]
        for m in messages {
            let net = m.network ?? defaultNetwork
            guard case .direct(let n)? = Self.conversation(of: m, myNum: link(net).radio.myNodeNum) else { continue }
            let key = MeshConversation.direct(n).key(on: net)
            if let cur = latest[key], cur.1.date > m.date { continue }
            latest[key] = (n, m, net)
        }
        return latest.values.map { (node: node($0.0, network: $0.2), last: $0.1, network: $0.2) }
            .sorted { $0.last.date > $1.last.date }
    }

    func unreadCount(_ conversation: MeshConversation, network: MeshFirmware? = nil) -> Int {
        let net = network ?? defaultNetwork
        let since = lastRead[conversation.key(on: net)] ?? .distantPast
        return messages(in: conversation, network: net).filter { !$0.isOutgoing && $0.date > since }.count
    }

    var totalUnread: Int {
        var total = networksInUse.reduce(0) { $0 + unreadCount(.channel(0), network: $1) }
        for t in directThreads { total += unreadCount(.direct(t.node.num), network: t.network) }
        return total
    }

    func markRead(_ conversation: MeshConversation, network: MeshFirmware? = nil) {
        lastRead[conversation.key(on: network ?? defaultNetwork)] = Date()
        scheduleSave()
    }

    /// Some radio is connected and can send.
    var canSend: Bool { links.contains { $0.connection.isConnected } }

    func canSend(on network: MeshFirmware) -> Bool { link(network).connection.isConnected }

    /// Which firmware the default radio runs.
    var firmware: MeshFirmware { defaultNetwork }

    var maxTextBytes: Int { defaultNetwork.maxTextBytes }

    /// Sends a text on one network (the default network when nil). Returns
    /// false when that radio isn't connected or the text is too long.
    @discardableResult
    func send(_ rawText: String, to conversation: MeshConversation, network: MeshFirmware? = nil) -> Bool {
        let l = link(network ?? defaultNetwork)
        let text = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, MeshText.byteCount(text) <= l.firmware.maxTextBytes else { return false }
        guard l.connection.isLinkUp, let device = l.deviceID else {
            notice = rememberedLinks.count > 1 ? "Not connected to the \(l.firmware.displayName) radio." : "Not connected to a radio."
            return false
        }
        if l.firmware == .meshcore { return coreSend(text, to: conversation, on: l) }
        let (to, channel): (UInt32, UInt32) = {
            switch conversation {
            case .channel(let i): return (MeshConstants.broadcastNum, i)
            case .direct(let n): return (n, 0)
            }
        }()
        let id = Self.newPacketID()
        let packet = MeshPBPacket(
            to: to,
            channel: channel,
            decoded: MeshPBData(portnum: MeshPort.textMessage.rawValue, payload: Data(text.utf8)),
            id: id,
            hopLimit: l.radio.hopLimit.flatMap { $0 > 0 ? $0 : nil } ?? MeshConstants.defaultHopLimit,
            wantAck: true
        )
        transport.write(MeshPBToRadio.packet(packet).encode(), to: device)
        messages.append(MeshMessage(packetID: id, from: l.radio.myNodeNum, to: to, channel: channel,
                                    text: text, date: Date(), isOutgoing: true, delivery: .sent, network: .meshtastic))
        trimHistory()
        scheduleSave()
        startReading(l)
        return true
    }

    /// Text that the SOS button will send (without location).
    var sosPreview: String {
        MeshSOS.text(latitude: nil, longitude: nil, senderName: myNode?.longName)
    }

    var locationDenied: Bool { locationProvider.isDenied }

    /// Sends the SOS on the primary channel of every connected radio, with a
    /// GPS fix if allowed. Returns the first text sent, or nil if none went out.
    func sendSOS(includeLocation: Bool) async -> String? {
        await sendSOSEverywhere(includeLocation: includeLocation).first(where: \.sent)?.text
    }

    /// Sends the SOS on every connected radio and says how each one did.
    func sendSOSEverywhere(includeLocation: Bool) async -> [MeshSOSResult] {
        var lat: Double?
        var lon: Double?
        if includeLocation, let loc = await locationProvider.currentLocation() {
            lat = loc.coordinate.latitude
            lon = loc.coordinate.longitude
        }
        return sendSOS(latitude: lat, longitude: lon)
    }

    /// The SOS with given coordinates (or none), on every radio with a link up.
    /// Each network gets its own text: its own limit and its own radio's name.
    func sendSOS(latitude: Double?, longitude: Double?) -> [MeshSOSResult] {
        activeLinks.map { l in
            let text = MeshText.truncated(MeshSOS.text(latitude: latitude, longitude: longitude, senderName: l.myNode?.longName),
                                          maxBytes: l.firmware.maxTextBytes)
            let ok = send(text, to: .channel(0), network: l.firmware)
            return MeshSOSResult(network: l.firmware, radioName: l.displayName, text: ok ? text : nil)
        }
    }

    // MARK: Drive-test pings

    /// Meshtastic's private app port: other apps ignore it, so pings never show up as chat.
    static let pingPort: UInt32 = 256
    static let pingText = "Pocket Nomad ping"

    /// Sends one drive-test ping from one radio (the default network when nil)
    /// and reports what came back. With no target (Meshtastic only) it asks
    /// whether any radio repeats it: only neighbours relay it once (hop limit
    /// 1), so it doesn't flood the mesh. With a target, `.reached` means that
    /// radio acknowledged it. MeshCore pings are a short direct text, so they
    /// need a target and show up on that radio.
    /// Returns false if the ping could not be sent.
    func ping(_ target: UInt32?, on network: MeshFirmware? = nil, done: @escaping (MeshPingOutcome) -> Void) -> Bool {
        let l = link(network ?? defaultNetwork)
        guard case .connected = l.connection, let device = l.deviceID else { return false }
        if l.firmware == .meshcore {
            // A repeater answers a trace without a login; anyone else gets a direct text.
            if let target, let hash = traceHash(for: target, on: l) {
                let tag = UInt32.random(in: 1 ... .max)
                coreEnqueue(l, MeshCoreCommand.sendTrace(tag: tag, path: hash), finishedBy: [6]) { [weak self] reply in
                    guard let self else { return }
                    if case .sent(_, _, let timeoutMS) = reply {
                        let wait = min(30, max(10, Double(timeoutMS) / 1000 * 2))
                        self.trackPing(tag, on: l, target: target, wait: wait, done: done)
                    } else {
                        done(.none)
                    }
                }
                return true
            }
            guard let target, let prefix = l.nodes[target]?.publicKeyPrefix else { return false }
            coreEnqueue(l, MeshCoreCommand.sendText(Self.pingText, toKeyPrefix: prefix, date: Date()), finishedBy: [6]) { [weak self] reply in
                guard let self else { return }
                if case .sent(_, let ack, let timeoutMS) = reply, ack != 0 {
                    let wait = min(60, max(20, Double(timeoutMS) / 1000 * 1.5))
                    self.trackPing(ack, on: l, target: target, wait: wait, done: done)
                } else {
                    done(.none)
                }
            }
            return true
        }
        let id = Self.newPacketID()
        let packet = MeshPBPacket(
            to: target ?? MeshConstants.broadcastNum,
            channel: 0,
            decoded: MeshPBData(portnum: Self.pingPort, payload: Data("pn-ping".utf8)),
            id: id,
            hopLimit: target == nil ? 1 : (l.radio.hopLimit.flatMap { $0 > 0 ? $0 : nil } ?? MeshConstants.defaultHopLimit),
            wantAck: true
        )
        transport.write(MeshPBToRadio.packet(packet).encode(), to: device)
        // The radio retries for about 30 s before giving up.
        trackPing(id, on: l, target: target, wait: 45, done: done)
        startReading(l)
        return true
    }

    /// How long a repeater counts as "in range" for an automatic drive-test ping.
    static let neighborWindow: TimeInterval = 10 * 60

    /// The repeater to ping on a network when none was chosen: the one heard most recently.
    func autoPingTarget(on network: MeshFirmware) -> UInt32? {
        network == .meshcore ? link(network).latestNeighbor(within: Self.neighborWindow) : nil
    }

    /// Path hash for a trace ping: a repeater heard in range, or a repeater contact
    /// (whose hash is the first byte of its key, MeshCore's default hash size).
    private func traceHash(for target: UInt32, on l: MeshLink) -> Data? {
        if let n = l.neighbors[target] { return n.hash }
        if let node = l.nodes[target], node.meshCoreType == .repeater, let prefix = node.publicKeyPrefix, !prefix.isEmpty {
            return prefix.prefix(1)
        }
        return nil
    }

    private func trackPing(_ id: UInt32, on l: MeshLink, target: UInt32?, wait: Double, done: @escaping (MeshPingOutcome) -> Void) {
        l.pendingPings[id] = MeshLink.PendingPing(target: target, done: done, timeout: Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            self?.finishPing(id, on: l)
        })
    }

    private func finishPing(_ id: UInt32, on l: MeshLink, _ outcome: MeshPingOutcome? = nil) {
        guard let p = l.pendingPings.removeValue(forKey: id) else { return }
        p.timeout?.cancel()
        p.done(outcome ?? p.best)
    }

    /// Routing answers for a ping. Returns true if the packet was a ping's.
    private func handlePingRouting(_ p: MeshPBPacket, _ data: MeshPBData, on l: MeshLink) -> Bool {
        guard var pending = l.pendingPings[data.requestID] else { return false }
        guard let routing = try? MeshPBRouting.decode(data.payload), let reason = routing.errorReason else { return true }
        if reason == MeshRoutingError.none.rawValue {
            if let target = pending.target, p.from == target {
                finishPing(data.requestID, on: l, .reached)
            } else if pending.target == nil {
                finishPing(data.requestID, on: l, .relayed)
            } else {
                pending.best = .relayed // a neighbour repeated it; keep waiting for the target
                l.pendingPings[data.requestID] = pending
            }
        } else {
            finishPing(data.requestID, on: l)
        }
        return true
    }

    func clearPairingHint() { pairingHint = nil }

    /// Deletes every stored message and node from this phone. The radios keep
    /// their own node lists; reconnecting downloads those again.
    func deleteHistory() {
        messages.removeAll()
        for l in links {
            l.nodes = l.nodes.filter { $0.key == l.radio.myNodeNum && $0.key != 0 }
        }
        lastRead.removeAll()
        saveNow()
    }

    // MARK: - Transport events

    func handle(_ event: MeshTransportEvent) {
        switch event {
        case .availability(let a):
            #if DEBUG
            // The demo radios stay "connected" on a simulator, which has no Bluetooth.
            if demoRadios { availability = .poweredOn; return }
            #endif
            availability = a
            if a != .poweredOn {
                for l in links {
                    l.isReading = false
                    if l.connection.isLinkUp { l.connection = .reconnecting(name: l.displayName) }
                }
            }

        case .discovered(let d):
            if let i = discovered.firstIndex(where: { $0.id == d.id }) {
                discovered[i] = d
            } else {
                discovered.append(d)
            }
            discovered.sort { ($0.rssi == 0 ? -999 : $0.rssi) > ($1.rssi == 0 ? -999 : $1.rssi) }

        case .connecting(let id, let name):
            guard let l = link(forDevice: id) else { return } // e.g. restored by iOS: adopted when ready
            l.deviceName = name
            l.connection = .connecting(name: name)

        case .ready(let id, let name, let firmware):
            let l = adopt(id, name: name, firmware: firmware)
            pairingHint = nil
            if isScanning { stopScan() }
            l.radio.firmware = firmware
            if firmware == .meshcore { coreBeginHandshake(l) } else { beginHandshake(l) }

        case .frame(let id, let data):
            guard let l = link(forDevice: id) else { return }
            coreHandle(data, on: l)

        case .disconnected(let id, let reason, let willReconnect):
            guard let l = link(forDevice: id) else { return }
            l.isReading = false
            l.handshakeTimeout?.cancel()
            coreReset(l)
            radioLinkDropped(l)
            if willReconnect {
                l.connection = .reconnecting(name: l.displayName)
            } else {
                l.connection = .idle
                if let reason { notice = labelled(reason, l) }
            }

        case .connectFailed(let id, let reason):
            guard let l = link(forDevice: id) else { return }
            notice = labelled(reason, l)
            l.connection = l.autoReconnect ? .reconnecting(name: l.displayName) : .failed(reason)

        case .fromRadio(let id, let data):
            guard let l = link(forDevice: id) else { return }
            if data.isEmpty {
                l.isReading = false
                if l.readAgain {
                    l.readAgain = false
                    startReading(l)
                } else if !l.handshakeDone, l.connection.isLinkUp {
                    // Radio may still be preparing its config; poll briefly.
                    Task { [weak self] in
                        try? await Task.sleep(for: .milliseconds(300))
                        self?.startReading(l)
                    }
                }
                return
            }
            do {
                let msg = try MeshPBFromRadio.decode(data)
                handleFromRadio(msg, on: l)
            } catch {
                // Corrupt or partial packet: skip it and keep draining.
            }
            transport.readFromRadio(id: id)

        case .fromNumChanged(let id):
            guard let l = link(forDevice: id) else { return }
            startReading(l)

        case .pairingProblem(let id, let msg):
            pairingHint = link(forDevice: id).map { labelled(msg, $0) } ?? msg

        case .writeFailed(let id, let msg):
            let l = link(forDevice: id)
            notice = "Could not send to the radio: \(msg)"
            if let i = messages.lastIndex(where: { $0.isOutgoing && $0.delivery == .sent && (l == nil || $0.network == l?.firmware) }) {
                messages[i].delivery = .failed("Not sent")
            }
        }
    }

    /// Prefixes a radio's name when two are set up, so it's clear which one spoke.
    private func labelled(_ text: String, _ l: MeshLink) -> String {
        rememberedLinks.count > 1 ? "\(l.displayName): \(text)" : text
    }

    /// Puts a ready radio in its firmware's slot. Normally it is already there;
    /// it moves if it runs the other firmware than its advert suggested, or if
    /// iOS restored a radio this app version didn't know about.
    private func adopt(_ id: UUID, name: String, firmware: MeshFirmware) -> MeshLink {
        let target = link(firmware)
        if let previous = link(forDevice: id), previous !== target {
            previous.deviceID = nil
            previous.deviceName = nil
            previous.connection = .idle
            saveSlot(previous)
        }
        if let old = target.deviceID, old != id {
            // One radio per firmware: the newcomer replaces the old one.
            notice = "Replaced \(target.displayName) with \(name): one \(firmware.displayName) radio at a time."
            dropLink(target)
        }
        target.deviceID = id
        target.deviceName = name
        target.autoReconnect = true
        saveSlot(target)
        return target
    }

    // MARK: - Handshake and reading

    private func connect(id: UUID, name: String, firmware: MeshFirmware) {
        start()
        // A radio already in a slot stays there (its slot was decided by its real firmware).
        let l = link(forDevice: id) ?? link(firmware)
        if let old = l.deviceID, old != id { dropLink(l) }
        l.deviceID = id
        l.deviceName = name
        l.autoReconnect = true
        saveSlot(l)
        l.connection = .connecting(name: name)
        transport.connect(id: id)
    }

    /// Disconnects a slot's current radio before another takes its place.
    private func dropLink(_ l: MeshLink) {
        l.handshakeTimeout?.cancel()
        coreReset(l)
        for id in l.pendingPings.keys { finishPing(id, on: l, MeshPingOutcome.none) }
        if let old = l.deviceID { transport.disconnect(id: old) }
        l.isReading = false
        l.connection = .idle
    }

    private func saveSlot(_ l: MeshLink) {
        if let id = l.deviceID {
            defaults.set(id.uuidString, forKey: Keys.deviceID(l.firmware))
        } else {
            defaults.removeObject(forKey: Keys.deviceID(l.firmware))
        }
        if let name = l.deviceName {
            defaults.set(name, forKey: Keys.deviceName(l.firmware))
        } else {
            defaults.removeObject(forKey: Keys.deviceName(l.firmware))
        }
        defaults.set(l.autoReconnect, forKey: Keys.autoReconnect(l.firmware))
    }

    private func beginHandshake(_ l: MeshLink) {
        guard let device = l.deviceID else { return }
        l.handshakeDone = false
        l.handshakeAttempts += 1
        l.connection = .syncing(name: l.displayName)
        l.configID = Self.newPacketID()
        transport.write(MeshPBToRadio.wantConfigID(l.configID).encode(), to: device)
        startReading(l)

        l.handshakeTimeout?.cancel()
        let attempt = l.handshakeAttempts
        l.handshakeTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard let self, !Task.isCancelled, !l.handshakeDone, attempt == l.handshakeAttempts else { return }
            if attempt < 2 {
                self.beginHandshake(l)
            } else {
                // Messages may still flow; let the user try.
                l.handshakeDone = true
                l.connection = .connected(name: l.displayName)
                self.notice = self.labelled("The radio did not finish sending its settings. Messages may still work.", l)
            }
        }
    }

    private func startReading(_ l: MeshLink) {
        guard l.connection.isLinkUp, let device = l.deviceID else { return }
        if l.isReading {
            l.readAgain = true
            return
        }
        l.isReading = true
        transport.readFromRadio(id: device)
    }

    private func handleFromRadio(_ msg: MeshPBFromRadio, on l: MeshLink) {
        switch msg.variant {
        case .myInfo(let info)?:
            l.radio.myNodeNum = info.myNodeNum
        case .nodeInfo(let info)?:
            upsert(info, on: l)
        case .channel(let ch)?:
            if ch.role == MeshPBChannel.Role.disabled.rawValue {
                l.radio.channelNames[ch.index] = nil
            } else {
                l.radio.channelNames[ch.index] = ch.name
                if ch.role == MeshPBChannel.Role.primary.rawValue { l.radio.primaryChannelName = ch.name }
            }
        case .metadata(let meta)?:
            l.radio.firmwareVersion = meta.firmwareVersion
        case .loraConfig(let lora)?:
            store(lora, on: l)
        case .configCompleteID(let id)?:
            guard id == l.configID else { return }
            l.handshakeDone = true
            l.handshakeAttempts = 0
            l.handshakeTimeout?.cancel()
            l.connection = .connected(name: l.displayName)
            if l.awaitingRestart, let lora = l.lastLoRa {
                finishLoRaApply(lora, on: l)
            } else if l.radio.region == 0 {
                notice = labelled("This radio has no region set, so it will not transmit. Open its Radio settings and choose your region (US 915 MHz in the United States).", l)
            }
            scheduleSave()
        case .rebooted?:
            if l.connection.isLinkUp { beginHandshake(l) }
        case .queueStatus(let q)?:
            if q.res != 0, q.meshPacketID != 0,
               let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == q.meshPacketID && $0.network == .meshtastic }) {
                messages[i].delivery = .failed("Radio is busy, try again")
                scheduleSave()
            }
        case .clientNotification(let text)?:
            if !text.isEmpty { notice = labelled(text, l) }
        case .packet(let p)?:
            handlePacket(p, on: l)
        case .otherConfig?, .logRecord?, .unhandled?, nil:
            break
        }
    }

    private func upsert(_ info: MeshPBNodeInfo, on l: MeshLink) {
        guard info.num != 0 else { return }
        l.update(info.num) { n in
            if let u = info.user { apply(u, to: &n) }
            if let p = info.position, p.hasFix { apply(p, to: &n) }
            if info.lastHeard != 0 {
                let heard = Date(timeIntervalSince1970: TimeInterval(info.lastHeard))
                n.lastHeard = max(heard, n.lastHeard ?? .distantPast)
            }
            if info.snr != 0 { n.snr = info.snr }
            if let h = info.hopsAway { n.hopsAway = h }
            if let b = info.deviceMetrics?.batteryLevel { n.batteryLevel = b }
        }
        scheduleSave()
    }

    private func apply(_ u: MeshPBUser, to n: inout MeshNode) {
        if !u.id.isEmpty { n.userID = u.id }
        if !u.longName.isEmpty { n.longName = u.longName }
        if !u.shortName.isEmpty { n.shortName = u.shortName }
        if u.hwModel != 0 { n.hwModel = u.hwModel }
        if !u.publicKey.isEmpty { n.hasPublicKey = true }
    }

    private func apply(_ p: MeshPBPosition, to n: inout MeshNode) {
        n.latitude = p.latitude
        n.longitude = p.longitude
        n.altitude = p.altitude
    }

    private func handlePacket(_ p: MeshPBPacket, on l: MeshLink) {
        // Any packet tells us the sender is alive.
        if p.from != 0, p.from != l.radio.myNodeNum {
            l.update(p.from) { n in
                n.lastHeard = p.rxTime != 0 ? Date(timeIntervalSince1970: TimeInterval(p.rxTime)) : Date()
                if p.rxSNR != 0 { n.snr = p.rxSNR }
                if let h = p.hopsAway { n.hopsAway = h }
            }
            // Packets the radio stored while the phone was away arrive with old
            // receive times; only fresh ones say where the phone is now.
            if p.rxTime == 0 || Date().timeIntervalSince1970 - TimeInterval(p.rxTime) < 120 {
                onHeard?(MeshHeard(node: p.from, snr: p.rxSNR != 0 ? p.rxSNR : nil, hops: p.hopsAway, firmware: .meshtastic))
            }
        }
        guard let data = p.decoded else { return } // still encrypted: not for us
        switch MeshPort(rawValue: data.portnum) {
        case .textMessage?:
            handleText(p, data, on: l)
        case .position?:
            if let pos = try? MeshPBPosition.decode(data.payload), pos.hasFix, p.from != 0 {
                l.update(p.from) { apply(pos, to: &$0) }
            }
        case .nodeInfo?:
            if let user = try? MeshPBUser.decode(data.payload), p.from != 0 {
                l.update(p.from) { apply(user, to: &$0) }
            }
        case .routing?:
            handleRouting(p, data, on: l)
        case .admin?:
            handleAdmin(p, data, on: l)
        default:
            break
        }
        scheduleSave()
    }

    private func handleText(_ p: MeshPBPacket, _ data: MeshPBData, on l: MeshLink) {
        let myNum = l.radio.myNodeNum
        // Drop duplicates (the radio replays its queue after a reconnect).
        if p.id != 0, messages.contains(where: { $0.packetID == p.id && $0.from == p.from && $0.network == .meshtastic }) { return }
        // Only broadcasts and messages addressed to us (or sent by us), never from a blocked radio.
        guard p.to == MeshConstants.broadcastNum || p.to == myNum || p.from == myNum else { return }
        guard !blocked.contains(p.from) else { return }
        let text = String(decoding: data.payload, as: UTF8.self)
        let date = p.rxTime != 0 ? Date(timeIntervalSince1970: TimeInterval(p.rxTime)) : Date()
        messages.append(MeshMessage(
            packetID: p.id, from: p.from, to: p.to, channel: p.channel, text: text,
            date: min(date, Date()), isOutgoing: p.from == myNum && myNum != 0,
            delivery: p.from == myNum ? .sent : .received,
            rxSNR: p.rxSNR != 0 ? p.rxSNR : nil, hopsAway: p.hopsAway, network: .meshtastic
        ))
        // No bridging: a message heard on one network is never repeated on the
        // other. A future, opt-in bridge would hook in here (and in coreReceive),
        // with its own loop and rate limits.
        trimHistory()
    }

    private func handleRouting(_ p: MeshPBPacket, _ data: MeshPBData, on l: MeshLink) {
        if data.requestID != 0, handlePingRouting(p, data, on: l) { return }
        if data.requestID != 0, data.requestID == l.adminSetID {
            handleAdminAck(data, on: l)
            return
        }
        guard data.requestID != 0,
              let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == data.requestID && $0.network == .meshtastic }),
              let routing = try? MeshPBRouting.decode(data.payload),
              let reason = routing.errorReason else { return }
        var m = messages[i]
        if reason == MeshRoutingError.none.rawValue {
            if !m.isBroadcast && p.from == m.to {
                m.delivery = .delivered
            } else if m.delivery != .delivered {
                // Implicit ACK: our radio heard a neighbour repeat it.
                m.delivery = .heardByMesh
            }
        } else if m.delivery != .delivered && m.delivery != .heardByMesh {
            // A broadcast that ran out of retries was still transmitted; the radio
            // just never heard anyone repeat it. Only direct messages truly fail.
            m.delivery = m.isBroadcast && reason == MeshRoutingError.maxRetransmit.rawValue
                ? .notRepeated
                : .failed(MeshRoutingError.describe(reason))
        }
        messages[i] = m
    }

    // MARK: - MeshCore

    /// MeshCore startup: identify, learn limits, set the clock, then fetch
    /// contacts, channels and any queued messages.
    private func coreBeginHandshake(_ l: MeshLink) {
        coreReset(l)
        l.handshakeDone = false
        l.handshakeAttempts += 1
        l.connection = .syncing(name: l.displayName)

        coreEnqueue(l, MeshCoreCommand.appStart(appName: "Pocket Nomad"), finishedBy: [5])
        coreEnqueue(l, MeshCoreCommand.deviceQuery(), finishedBy: [13]) { [weak self] frame in
            guard let self, case .deviceInfo(let info) = frame else { return }
            if !info.version.isEmpty { l.radio.firmwareVersion = "MeshCore \(info.version)" }
            // Channel slots vary by board (1, 8 or 40); list the first eight.
            for i in 0 ..< UInt8(min(max(info.maxChannels, 1), 8)) {
                self.coreEnqueue(l, MeshCoreCommand.getChannel(i), finishedBy: [18])
            }
        }
        coreEnqueue(l, MeshCoreCommand.setDeviceTime(Date()), finishedBy: [0])
        coreEnqueue(l, MeshCoreCommand.getContacts(), finishedBy: [4], timeout: .seconds(20)) { [weak self] frame in
            guard let self, case .endOfContacts = frame else { return }
            l.handshakeDone = true
            l.handshakeAttempts = 0
            l.handshakeTimeout?.cancel()
            l.connection = .connected(name: l.displayName)
            self.coreSyncMessages(l)
            self.scheduleSave()
        }

        l.handshakeTimeout?.cancel()
        let attempt = l.handshakeAttempts
        l.handshakeTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(45))
            guard let self, !Task.isCancelled, !l.handshakeDone, attempt == l.handshakeAttempts else { return }
            l.handshakeDone = true
            l.connection = .connected(name: l.displayName)
            self.notice = self.labelled("The radio did not finish sending its contacts. Messages may still work.", l)
        }
    }

    private func coreEnqueue(_ l: MeshLink, _ frame: Data, finishedBy: Set<UInt8>, timeout: Duration = .seconds(5),
                             onReply: ((MeshCoreFrame) -> Void)? = nil, onTimeout: (() -> Void)? = nil) {
        l.coreQueue.append(MeshLink.CoreCommand(frame: frame, finishedBy: finishedBy, timeout: timeout,
                                                onReply: onReply, onTimeout: onTimeout))
        coreSendNext(l)
    }

    private func coreSendNext(_ l: MeshLink) {
        guard l.corePending == nil, !l.coreQueue.isEmpty, l.connection.isLinkUp, let device = l.deviceID else { return }
        let command = l.coreQueue.removeFirst()
        l.corePending = command
        transport.write(command.frame, to: device)
        l.coreTimer?.cancel()
        l.coreTimer = Task { [weak self] in
            try? await Task.sleep(for: command.timeout)
            guard let self, !Task.isCancelled else { return }
            // No answer: give up on this command and move on.
            l.corePending = nil
            command.onTimeout?()
            self.coreSendNext(l)
        }
    }

    private func coreReset(_ l: MeshLink) {
        l.coreQueue.removeAll()
        l.corePending = nil
        l.coreTimer?.cancel()
    }

    /// Pulls every queued message off the radio, one SYNC at a time.
    private func coreSyncMessages(_ l: MeshLink) {
        let syncCode = MeshCoreCommand.Code.syncNextMessage
        guard !l.coreQueue.contains(where: { $0.frame.first == syncCode }), l.corePending?.frame.first != syncCode else { return }
        coreEnqueue(l, MeshCoreCommand.syncNextMessage(), finishedBy: [7, 8, 10, 16, 17, 27]) { [weak self] frame in
            if case .noMoreMessages = frame { return }
            self?.coreSyncMessages(l)
        }
    }

    private func coreHandle(_ data: Data, on l: MeshLink) {
        guard let code = data.first, let frame = MeshCoreFrame.decode(data) else { return }
        switch frame {
        case .selfInfo(let me):
            let num = MeshCoreContact.nodeNum(for: me.publicKey)
            l.radio.myNodeNum = num
            l.radio.regionName = String(format: "%.3f MHz", locale: Locale(identifier: "en_US_POSIX"), me.frequencyMHz)
            l.radio.meshCoreParams = me.radioParams
            l.radio.txPowerDBm = me.txPowerDBm
            l.radio.maxTxPowerDBm = me.maxTxPowerDBm
            if l.awaitingRestart { finishCoreApply(me, on: l) }
            l.update(num) { n in
                n.longName = me.name
                n.shortName = String(me.name.prefix(4))
                n.publicKeyPrefix = me.publicKey.prefix(6)
                n.hasPublicKey = true
                if let lat = me.latitude, let lon = me.longitude { n.latitude = lat; n.longitude = lon }
            }
        case .contact(let c):
            coreUpsert(c, on: l)
        case .newAdvert(let c):
            coreUpsert(c, on: l)
            onHeard?(MeshHeard(node: c.nodeNum, snr: nil, hops: c.pathLength.map(UInt32.init), firmware: .meshcore))
        case .advert(let key):
            let num = MeshCoreContact.nodeNum(for: key)
            if l.nodes[num] != nil { l.update(num) { $0.lastHeard = Date() } }
            onHeard?(MeshHeard(node: num, snr: nil, hops: nil, firmware: .meshcore))
        case .channelInfo(let ch):
            let index = Int32(ch.index)
            l.radio.channelNames[index] = ch.isEmpty ? nil : ch.name
            if ch.index == 0 { l.radio.primaryChannelName = ch.name }
        case .directMessage(let m):
            coreReceive(from: coreNode(forKeyPrefix: m.senderKeyPrefix, on: l), to: l.radio.myNodeNum, channel: 0,
                        timestamp: m.timestamp, text: m.text, senderName: nil, snr: m.snr, path: m.pathLength, on: l)
        case .channelMessage(let m):
            let sender = m.senderName.flatMap { name in l.nodes.values.first { $0.longName == name }?.num } ?? 0
            coreReceive(from: sender, to: MeshConstants.broadcastNum, channel: UInt32(m.channel),
                        timestamp: m.timestamp, text: m.text, senderName: m.senderName, snr: m.snr, path: m.pathLength, on: l)
        case .sendConfirmed(let ack, _) where l.pendingPings[ack] != nil:
            finishPing(ack, on: l, .reached)
        case .sendConfirmed(let ack, _):
            if let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == ack && ack != 0 && $0.network == .meshcore }) {
                messages[i].delivery = .delivered
                scheduleSave()
            }
        case .messagesWaiting:
            coreSyncMessages(l)
        case .rxLog(let log):
            coreHeardPacket(log, on: l)
        case .traceData(let tag, _) where l.pendingPings[tag] != nil:
            finishPing(tag, on: l, .reached)
        case .traceData:
            break
        case .ok, .error, .contactsStart, .endOfContacts, .sent, .noMoreMessages, .deviceInfo, .other:
            break
        }
        // Push frames (0x80 and up) arrive at any time; anything else answers the pending command.
        guard code < 0x80, let pending = l.corePending else { return }
        let finished = pending.finishedBy.contains(code) || code == 1
        if finished {
            l.coreTimer?.cancel()
            l.corePending = nil
        }
        pending.onReply?(frame)
        if finished { coreSendNext(l) }
    }

    private func coreUpsert(_ c: MeshCoreContact, on l: MeshLink) {
        let num = c.nodeNum
        guard num != 0 else { return }
        l.update(num) { n in
            n.longName = c.name
            n.shortName = String(c.name.prefix(4))
            n.userID = c.publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
            n.publicKeyPrefix = c.publicKey.prefix(6)
            n.hasPublicKey = true
            n.meshCoreType = c.type
            if let heard = c.lastAdvert { n.lastHeard = max(heard, n.lastHeard ?? .distantPast) }
            if let lat = c.latitude, let lon = c.longitude { n.latitude = lat; n.longitude = lon }
            if let hops = c.pathLength { n.hopsAway = UInt32(hops) }
        }
        scheduleSave()
    }

    /// Every packet the radio hears is a coverage point, whoever it was for: the
    /// sender is the repeater that passed it to us (or, for a straight-from-the-source
    /// advert, the node itself).
    private func coreHeardPacket(_ log: MeshCoreRxLog, on l: MeshLink) {
        guard let packet = log.packet else { return }
        let node: UInt32
        switch packet.heardFrom {
        case .repeater(let hash)?:
            node = l.nodes.values.first { $0.publicKeyPrefix?.starts(with: hash) == true }?.num
                ?? MeshCoreContact.nodeNum(forHash: hash)
            l.neighbors[MeshCoreContact.nodeNum(forHash: hash)] = MeshLink.Neighbor(hash: hash, lastHeard: Date(), snr: log.snr)
        case .node(let key)?:
            node = MeshCoreContact.nodeNum(for: key)
        case nil:
            node = 0
        }
        if node != 0, l.nodes[node] != nil {
            l.update(node) { n in
                n.lastHeard = Date()
                n.snr = log.snr
            }
        }
        onHeard?(MeshHeard(node: node, snr: log.snr, hops: UInt32(packet.path.count), firmware: .meshcore))
    }

    private func coreNode(forKeyPrefix prefix: Data, on l: MeshLink) -> UInt32 {
        l.nodes.values.first { $0.publicKeyPrefix == prefix }?.num ?? MeshCoreContact.nodeNum(for: prefix)
    }

    private func coreReceive(from: UInt32, to: UInt32, channel: UInt32, timestamp: UInt32, text: String,
                             senderName: String?, snr: Float?, path: UInt8, on l: MeshLink) {
        // The radio's timestamp plus sender and text identify a message well enough to drop repeats.
        if messages.contains(where: { $0.packetID == timestamp && $0.from == from && $0.text == text && $0.network == .meshcore }) { return }
        if blocked.contains(from) { return }
        let sent = Date(timeIntervalSince1970: TimeInterval(timestamp))
        messages.append(MeshMessage(
            packetID: timestamp, from: from, to: to, channel: channel, text: text,
            date: timestamp == 0 ? Date() : min(sent, Date()), isOutgoing: false, delivery: .received,
            rxSNR: snr, hopsAway: path == 0xFF ? nil : UInt32(path & 0x3F), senderName: senderName, network: .meshcore
        ))
        // No bridging to the Meshtastic network (see handleText).
        if from != 0, l.nodes[from] != nil {
            l.update(from) { n in
                n.lastHeard = Date()
                if let snr { n.snr = snr }
            }
        }
        // Messages queued on the radio while the phone was away are old; skip those.
        if timestamp == 0 || abs(Date().timeIntervalSince1970 - TimeInterval(timestamp)) < 120 {
            onHeard?(MeshHeard(node: from, snr: snr, hops: path == 0xFF ? nil : UInt32(path & 0x3F), firmware: .meshcore))
        }
        trimHistory()
        scheduleSave()
    }

    private func coreSend(_ text: String, to conversation: MeshConversation, on l: MeshLink) -> Bool {
        let now = Date()
        let frame: Data
        let (to, channel): (UInt32, UInt32)
        switch conversation {
        case .channel(let i):
            frame = MeshCoreCommand.sendChannelText(text, channel: UInt8(clamping: i), date: now)
            (to, channel) = (MeshConstants.broadcastNum, i)
        case .direct(let n):
            guard let prefix = l.nodes[n]?.publicKeyPrefix else {
                notice = "This contact has no MeshCore key yet. Wait until its radio is heard, then try again."
                return false
            }
            frame = MeshCoreCommand.sendText(text, toKeyPrefix: prefix, date: now)
            (to, channel) = (n, 0)
        }
        let message = MeshMessage(packetID: 0, from: l.radio.myNodeNum, to: to, channel: channel,
                                  text: text, date: now, isOutgoing: true, delivery: .sent, network: .meshcore)
        let id = message.id
        messages.append(message)
        coreEnqueue(l, frame, finishedBy: [6]) { [weak self] reply in
            guard let self, let i = self.messages.firstIndex(where: { $0.id == id }) else { return }
            switch reply {
            case .sent(_, let ack, _):
                // Direct messages are acknowledged later with this code.
                self.messages[i].packetID = ack
            case .error:
                self.messages[i].delivery = .failed("Radio is busy, try again")
            default:
                break
            }
            self.scheduleSave()
        }
        trimHistory()
        scheduleSave()
        return true
    }

    // MARK: - Radio settings

    /// Puts a MeshCore radio on new radio settings (and TX power), then
    /// restarts it and checks what it reports after reconnecting.
    ///
    /// CMD_SET_RADIO_PARAMS already saves and retunes the radio before it
    /// answers OK (MyMesh.cpp, companion-v1.18.0), so the restart is not
    /// strictly needed; it is done so the radio comes back up cleanly on the
    /// new channel and the read-back proves the settings were saved.
    func applyMeshCoreRadio(_ params: MeshCoreRadioParams, txPowerDBm: Int8? = nil) {
        let l = link(.meshcore)
        guard l.connection.isConnected, l.deviceID != nil else {
            l.radioApply = .failed("Connect the radio first.")
            return
        }
        if let problem = params.validationError {
            l.radioApply = .failed(problem)
            return
        }
        if let tx = txPowerDBm, let maxTx = l.radio.maxTxPowerDBm,
           tx < MeshCoreRadioParams.minTxPowerDBm || tx > maxTx {
            l.radioApply = .failed("TX power must be \(MeshCoreRadioParams.minTxPowerDBm) to \(maxTx) dBm on this radio.")
            return
        }
        l.applyTimer?.cancel()
        l.awaitingRestart = false
        l.wantedCore = params
        l.wantedTxPower = txPowerDBm == l.radio.txPowerDBm ? nil : txPowerDBm
        l.radioApply = .working("Sending \(params.fullSummary)…")
        coreEnqueue(l, MeshCoreCommand.setRadioParams(params), finishedBy: [0], onReply: { [weak self] reply in
            guard let self else { return }
            switch reply {
            case .ok: self.coreSetTxPowerThenRestart(l)
            case .error(let code): self.failApply(l, Self.describeCoreError(code))
            default: break
            }
        }, onTimeout: { [weak self] in
            self?.failApply(l, "The radio did not answer. Nothing was changed. Try again.")
        })
    }

    private func coreSetTxPowerThenRestart(_ l: MeshLink) {
        guard let tx = l.wantedTxPower else { return coreRestartAfterApply(l) }
        l.radioApply = .working("Setting TX power to \(tx) dBm…")
        coreEnqueue(l, MeshCoreCommand.setRadioTxPower(tx), finishedBy: [0], onReply: { [weak self] reply in
            guard let self else { return }
            if case .error(let code) = reply {
                self.notice = self.labelled("TX power not changed: \(Self.describeCoreError(code))", l)
                l.wantedTxPower = nil
            }
            self.coreRestartAfterApply(l)
        }, onTimeout: { [weak self] in
            // The radio settings are saved already; restart anyway and read back.
            self?.coreRestartAfterApply(l)
        })
    }

    private func coreRestartAfterApply(_ l: MeshLink) {
        l.awaitingRestart = true
        l.radioApply = .waitingForRadio("Saved. The radio is restarting to use them; it reconnects by itself in about 10–20 seconds.")
        // CMD_REBOOT has no reply; the short timeout frees the queue if the radio ignores it.
        coreEnqueue(l, MeshCoreCommand.reboot(), finishedBy: [], timeout: .seconds(3))
        watchForRestart(l)
    }

    /// Restarts a MeshCore radio (no settings change).
    func restartMeshCoreRadio() {
        let l = link(.meshcore)
        guard l.connection.isConnected else { return }
        coreEnqueue(l, MeshCoreCommand.reboot(), finishedBy: [], timeout: .seconds(3))
    }

    /// After a restart: compare what the radio reports with what was asked for.
    private func finishCoreApply(_ me: MeshCoreSelfInfo, on l: MeshLink) {
        guard let want = l.wantedCore else { return }
        l.applyTimer?.cancel()
        l.awaitingRestart = false
        let got = me.radioParams
        var problems: [String] = []
        if !got.sameChannel(as: want) { problems.append("it reports \(got.fullSummary) instead of \(want.fullSummary)") }
        if let tx = l.wantedTxPower, tx != me.txPowerDBm { problems.append("TX power is \(me.txPowerDBm) dBm, not \(tx) dBm") }
        l.wantedCore = nil
        l.wantedTxPower = nil
        l.radioApply = problems.isEmpty
            ? .done("The radio confirmed \(got.fullSummary) · \(me.txPowerDBm) dBm.")
            : .failed("The radio did not take every setting: \(problems.joined(separator: "; ")).")
        scheduleSave()
    }

    nonisolated static func describeCoreError(_ code: UInt8?) -> String {
        switch code {
        case 1?: return "This radio's firmware can't change that setting from the app."
        case 6?: return "The radio refused these settings (out of its range)."
        case 4?: return "The radio is busy. Try again in a moment."
        default: return "The radio refused the change (error \(code.map { String($0) } ?? "unknown"))."
        }
    }

    /// Puts a Meshtastic radio on a region and modem preset. Reads the radio's
    /// whole LoRa config first (which also hands out the admin session key),
    /// changes only region, modem_preset and use_preset, and writes it back.
    func applyMeshtasticLoRa(region: UInt32, modemPreset: UInt32) {
        let l = link(.meshtastic)
        guard l.connection.isConnected, l.deviceID != nil, l.radio.myNodeNum != 0 else {
            l.radioApply = .failed("Connect the radio first.")
            return
        }
        guard region != MeshtasticRegion.unset else {
            l.radioApply = .failed("Choose your region.")
            return
        }
        l.applyTimer?.cancel()
        l.awaitingRestart = false
        l.adminSetID = 0
        l.wantedLoRa = (region, modemPreset)
        l.radioApply = .working("Reading the radio's current settings…")
        requestLoRaConfig(on: l)
    }

    private func requestLoRaConfig(on l: MeshLink) {
        let id = Self.newPacketID()
        l.adminGetID = id
        sendAdmin(.getLoRaConfig(), id: id, wantResponse: true, on: l)
        applyAfter(l, .seconds(15)) { [weak self] in
            guard let self, l.adminGetID == id else { return }
            l.adminGetID = 0
            self.failApply(l, "The radio did not answer. Nothing was changed. Try again.")
        }
    }

    /// An AdminMessage to our own radio (MeshPacket.to = my node number,
    /// portnum ADMIN_APP), the way Meshtastic's own apps send local settings.
    private func sendAdmin(_ admin: MeshPBAdmin, id: UInt32, wantResponse: Bool, on l: MeshLink) {
        guard let device = l.deviceID else { return }
        let packet = MeshPBPacket(
            to: l.radio.myNodeNum,
            channel: 0,
            decoded: MeshPBData(portnum: MeshPort.admin.rawValue, payload: admin.encode(), wantResponse: wantResponse),
            id: id,
            wantAck: true
        )
        transport.write(MeshPBToRadio.packet(packet).encode(), to: device)
        startReading(l)
    }

    private func handleAdmin(_ p: MeshPBPacket, _ data: MeshPBData, on l: MeshLink) {
        guard let admin = try? MeshPBAdmin.decode(data.payload),
              case .getConfigResponse(let lora?)? = admin.variant else { return }
        store(lora, on: l)
        // Only the answer to our own request (request_id = its packet id).
        guard l.adminGetID != 0, data.requestID == l.adminGetID else { return }
        l.adminGetID = 0
        l.applyTimer?.cancel()
        if l.awaitingRestart {
            // Read back after a change that needed no restart.
            finishLoRaApply(lora, on: l)
            return
        }
        guard let want = l.wantedLoRa else { return }
        let updated = lora.with(region: want.region, modemPreset: want.preset)
        if updated == lora {
            finishLoRaApply(lora, on: l)
            return
        }
        let id = Self.newPacketID()
        l.adminSetID = id
        l.radioApply = .working("Saving \(MeshtasticRegion.shortName(want.region)) · \(MeshtasticModemPreset.shortName(want.preset))…")
        // set_config must carry the session key from the get_config_response.
        sendAdmin(.setLoRaConfig(updated, passkey: admin.sessionPasskey), id: id, wantResponse: false, on: l)
        applyAfter(l, .seconds(20)) { [weak self] in
            // No ACK and no restart seen: read the config back to find out.
            guard let self, l.adminSetID == id else { return }
            l.adminSetID = 0
            l.awaitingRestart = true
            self.requestLoRaConfig(on: l)
        }
    }

    private func handleAdminAck(_ data: MeshPBData, on l: MeshLink) {
        guard let reason = (try? MeshPBRouting.decode(data.payload))?.errorReason else { return }
        l.adminSetID = 0
        if reason != MeshRoutingError.none.rawValue {
            failApply(l, "The radio refused the change: \(MeshRoutingError.describe(reason)).")
            return
        }
        l.awaitingRestart = true
        l.radioApply = .waitingForRadio("Saved. The radio restarts to use the new settings and reconnects by itself in about 10–30 seconds.")
        applyAfter(l, .seconds(30)) { [weak self] in
            // Still connected after 30 s: no restart was needed, so read back now.
            guard let self, l.awaitingRestart, l.connection.isConnected else { return }
            self.requestLoRaConfig(on: l)
        }
    }

    private func finishLoRaApply(_ lora: MeshPBLoRaConfig, on l: MeshLink) {
        guard let want = l.wantedLoRa else { return }
        l.applyTimer?.cancel()
        l.awaitingRestart = false
        l.wantedLoRa = nil
        let got = "\(MeshtasticRegion.shortName(lora.region)) · \(lora.usePreset ? MeshtasticModemPreset.shortName(lora.modemPreset) : "Custom")"
        if lora.region == want.region, lora.usePreset, lora.modemPreset == want.preset {
            l.radioApply = .done("The radio confirmed \(got).")
        } else {
            l.radioApply = .failed("The radio reports \(got), not \(MeshtasticRegion.shortName(want.region)) · \(MeshtasticModemPreset.shortName(want.preset)). This firmware may not support that choice.")
        }
        scheduleSave()
    }

    private func store(_ lora: MeshPBLoRaConfig, on l: MeshLink) {
        l.lastLoRa = lora
        l.radio.region = lora.region
        l.radio.regionName = lora.regionName
        l.radio.hopLimit = lora.hopLimit
        l.radio.modemPreset = lora.modemPreset
        l.radio.usePreset = lora.usePreset
    }

    /// The Bluetooth link went down while a change was in progress.
    private func radioLinkDropped(_ l: MeshLink) {
        if l.firmware == .meshtastic, l.adminSetID != 0 {
            // set_config sent, no ACK yet: a restart is the likely reason.
            l.adminSetID = 0
            l.awaitingRestart = true
        }
        l.adminGetID = 0
        if l.awaitingRestart {
            l.radioApply = .waitingForRadio("The radio is restarting. It reconnects by itself; the new settings are checked then.")
            watchForRestart(l)
        } else if case .working = l.radioApply {
            failApply(l, "The radio disconnected before it confirmed. Check the settings here once it reconnects.")
        }
    }

    /// Gives a restarting radio 90 seconds to come back before saying so.
    private func watchForRestart(_ l: MeshLink) {
        applyAfter(l, .seconds(90)) {
            guard l.awaitingRestart else { return }
            l.awaitingRestart = false
            l.radioApply = .failed("The radio hasn't come back yet. It reconnects by itself when it's ready; then check its settings here.")
        }
    }

    private func failApply(_ l: MeshLink, _ why: String) {
        l.applyTimer?.cancel()
        l.awaitingRestart = false
        l.wantedCore = nil
        l.wantedTxPower = nil
        l.wantedLoRa = nil
        l.adminGetID = 0
        l.adminSetID = 0
        l.radioApply = .failed(why)
    }

    private func applyAfter(_ l: MeshLink, _ delay: Duration, _ body: @escaping @MainActor () -> Void) {
        l.applyTimer?.cancel()
        l.applyTimer = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            body()
        }
    }

    /// Clears a finished or failed result (the screen calls this when it opens).
    func clearRadioApply(_ network: MeshFirmware) {
        let l = link(network)
        if !l.radioApply.isBusy { l.radioApply = .idle }
    }

    #if DEBUG
    /// Screenshots and demos: nodes with positions around downtown Denver, on both networks.
    func loadDemoNodes() {
        let demo: [(String, Double, Double, MeshCoreContactType?)] = [
            ("Ridge Repeater", 39.7480, -104.9970, .repeater),
            ("Ana", 39.7560, -104.9870, nil),
            ("Base Camp", 39.7410, -105.0060, .chat),
            ("Cherry Creek", 39.7170, -104.9530, nil),
        ]
        for (i, d) in demo.enumerated() {
            link(d.3 == nil ? .meshtastic : .meshcore).update(UInt32(0xA000 + i)) { n in
                n.longName = d.0
                n.shortName = String(d.0.prefix(4))
                n.latitude = d.1
                n.longitude = d.2
                n.lastHeard = Date().addingTimeInterval(-Double(i) * 420)
                n.meshCoreType = d.3
            }
        }
    }

    /// Set by `loadDemoRadios`; Bluetooth state changes are then ignored.
    private var demoRadios = false

    /// Screenshots (debug builds only): both radios shown as connected, with a message
    /// on each network. There is no real radio behind either link.
    func loadDemoRadios() {
        demoRadios = true
        availability = .poweredOn
        let demo: [(MeshFirmware, String, UInt32, String, UInt32?, String, String)] = [
            (.meshtastic, "Demo Meshtastic", 0xB001, "LongFast", 84, "Ana", "Signal check from the creek"),
            (.meshcore, "Demo MeshCore", 0xB002, "Public", nil, "Ridge Repeater", "Repeater back up after the storm"),
        ]
        for (firmware, name, num, channel, battery, sender, text) in demo {
            let l = link(firmware)
            l.deviceID = UUID()
            l.deviceName = name
            l.radio.firmware = firmware
            l.radio.myNodeNum = num
            l.radio.primaryChannelName = channel
            if firmware == .meshcore {
                // Fresh from flashing: still on the firmware's default (European) settings.
                l.radio.meshCoreParams = .firmwareDefault
                l.radio.txPowerDBm = 10
                l.radio.maxTxPowerDBm = 22
            } else {
                l.radio.region = MeshtasticRegion.us
                l.radio.modemPreset = MeshtasticModemPreset.longFast
                l.radio.usePreset = true
            }
            l.update(num) { n in
                n.longName = name
                n.shortName = String(name.suffix(4))
                n.batteryLevel = battery
            }
            l.connection = .connected(name: name)
            messages.append(MeshMessage(packetID: num, from: 0xA000, to: MeshConstants.broadcastNum, channel: 0,
                                        text: text, date: Date().addingTimeInterval(-600), isOutgoing: false,
                                        delivery: .received, senderName: firmware == .meshcore ? sender : nil, network: firmware))
        }
    }
    #endif

    // MARK: - Helpers

    static func conversation(of m: MeshMessage, myNum: UInt32) -> MeshConversation? {
        if m.to == MeshConstants.broadcastNum { return .channel(m.channel) }
        if m.isOutgoing || (myNum != 0 && m.from == myNum) { return .direct(m.to) }
        return .direct(m.from)
    }

    static func newPacketID() -> UInt32 {
        UInt32.random(in: 1...UInt32.max - 1)
    }

    private func trimHistory() {
        if messages.count > MeshStore.maxMessages {
            messages = MeshStore.capped(messages)
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        // The default network also goes in the old single-radio fields, so an
        // older version of the app can still read the file.
        let main = link(defaultNetwork)
        let snap = MeshSnapshot(messages: messages, nodes: Array(main.nodes.values), radio: main.radio, lastRead: lastRead,
                                networks: links.filter(\.hasData).map {
                                    MeshNetworkSnapshot(firmware: $0.firmware, nodes: Array($0.nodes.values), radio: $0.radio)
                                })
        try? store.save(snap)
    }
}
