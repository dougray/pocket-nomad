// MeshService.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Owns the radio link, the handshake (Meshtastic or MeshCore), the node list
// and chat history. Views observe `MeshService.shared`.
//
// Call `MeshService.shared.start()` once at app launch (before the first
// scene renders) so CoreBluetooth state restoration can hand back the radio
// connection when iOS relaunches the app in the background.

import Foundation
import Observation

@Observable
@MainActor
final class MeshService {
    static let shared = MeshService()

    // MARK: Observable state

    private(set) var availability: MeshBluetoothAvailability = .unknown
    private(set) var connection: MeshConnectionState = .idle
    private(set) var discovered: [MeshDiscoveredDevice] = []
    private(set) var nodes: [UInt32: MeshNode] = [:]
    private(set) var messages: [MeshMessage] = []
    private(set) var radio = MeshRadioInfo()
    private(set) var lastRead: [String: Date] = [:]
    /// Shown when pairing is needed or went wrong.
    private(set) var pairingHint: String?
    /// One-line notice from the radio or about a failure. UI may clear it.
    var notice: String?
    private(set) var isScanning = false

    private(set) var lastDeviceID: UUID?
    private(set) var lastDeviceName: String?

    // MARK: Internals

    private let transport: MeshTransport
    private let store: MeshStore
    private let defaults: UserDefaults
    private let location: () -> MeshLocationProvider
    private var started = false
    private var autoReconnect: Bool
    private var configID: UInt32 = 0
    private var handshakeDone = false
    private var handshakeAttempts = 0
    private var isReading = false
    private var readAgain = false
    private var currentDeviceName = ""
    private var saveTask: Task<Void, Never>?
    private var handshakeTimeout: Task<Void, Never>?
    @ObservationIgnored private lazy var locationProvider: MeshLocationProvider = location()
    /// Called each time the radio hears another node live (for the coverage scanner).
    @ObservationIgnored var onHeard: ((MeshHeard) -> Void)?

    // MeshCore: one command in flight at a time, each waiting for its reply.
    private struct CoreCommand {
        let frame: Data
        /// Response codes that finish this command (errors always do).
        let finishedBy: Set<UInt8>
        var timeout: Duration = .seconds(5)
        var onReply: ((MeshCoreFrame) -> Void)?
    }
    @ObservationIgnored private var coreQueue: [CoreCommand] = []
    @ObservationIgnored private var corePending: CoreCommand?
    @ObservationIgnored private var coreTimer: Task<Void, Never>?

    private enum Keys {
        static let lastDeviceID = "mesh.lastDeviceID"
        static let lastDeviceName = "mesh.lastDeviceName"
        static let autoReconnect = "mesh.autoReconnect"
    }

    init(transport: MeshTransport? = nil,
         store: MeshStore = MeshStore(),
         defaults: UserDefaults = .standard,
         location: @escaping () -> MeshLocationProvider = { MeshLocationProvider() }) {
        self.transport = transport ?? MeshBLETransport()
        self.store = store
        self.defaults = defaults
        self.location = location
        self.autoReconnect = defaults.bool(forKey: Keys.autoReconnect)
        if let s = defaults.string(forKey: Keys.lastDeviceID) { lastDeviceID = UUID(uuidString: s) }
        lastDeviceName = defaults.string(forKey: Keys.lastDeviceName)

        let snap = store.load()
        messages = snap.messages
        nodes = Dictionary(snap.nodes.map { ($0.num, $0) }, uniquingKeysWith: { _, b in b })
        radio = snap.radio
        lastRead = snap.lastRead

        self.transport.onEvent = { [weak self] event in self?.handle(event) }
    }

    // MARK: - Public API

    /// Brings Bluetooth up and reconnects to the last radio if the user had
    /// one connected. Safe to call more than once.
    func start() {
        guard !started else { return }
        started = true
        transport.start()
        if autoReconnect, let id = lastDeviceID {
            connection = .reconnecting(name: lastDeviceName ?? "radio")
            transport.connect(id: id)
        }
    }

    func startScan() {
        start()
        discovered.removeAll()
        isScanning = true
        if case .idle = connection { connection = .scanning }
        transport.startScan()
    }

    func stopScan() {
        isScanning = false
        transport.stopScan()
        if case .scanning = connection { connection = .idle }
    }

    func connect(to device: MeshDiscoveredDevice) {
        connect(id: device.id, name: device.name)
    }

    func reconnectLast() {
        guard let id = lastDeviceID else { return }
        connect(id: id, name: lastDeviceName ?? "radio")
    }

    func disconnect() {
        autoReconnect = false
        defaults.set(false, forKey: Keys.autoReconnect)
        handshakeTimeout?.cancel()
        coreReset()
        transport.disconnect()
        connection = .idle
    }

    /// Forgets the remembered radio entirely.
    func forgetDevice() {
        disconnect()
        lastDeviceID = nil
        lastDeviceName = nil
        defaults.removeObject(forKey: Keys.lastDeviceID)
        defaults.removeObject(forKey: Keys.lastDeviceName)
    }

    var myNodeNum: UInt32 { radio.myNodeNum }
    var myNode: MeshNode? { nodes[radio.myNodeNum] }

    /// Other radios, most recently heard first.
    var sortedNodes: [MeshNode] {
        nodes.values
            .filter { $0.num != radio.myNodeNum }
            .sorted { ($0.lastHeard ?? .distantPast) > ($1.lastHeard ?? .distantPast) }
    }

    func node(_ num: UInt32) -> MeshNode {
        nodes[num] ?? MeshNode(num: num)
    }

    func messages(in conversation: MeshConversation) -> [MeshMessage] {
        messages.filter { Self.conversation(of: $0, myNum: radio.myNodeNum) == conversation }
    }

    /// Direct-message threads, newest first.
    var directThreads: [(node: MeshNode, last: MeshMessage)] {
        var latest: [UInt32: MeshMessage] = [:]
        for m in messages {
            guard case .direct(let n)? = Self.conversation(of: m, myNum: radio.myNodeNum) else { continue }
            if let cur = latest[n], cur.date > m.date { continue }
            latest[n] = m
        }
        return latest.map { (node: node($0.key), last: $0.value) }.sorted { $0.last.date > $1.last.date }
    }

    func unreadCount(_ conversation: MeshConversation) -> Int {
        let since = lastRead[conversation.key] ?? .distantPast
        return messages(in: conversation).filter { !$0.isOutgoing && $0.date > since }.count
    }

    var totalUnread: Int {
        var keys: Set<MeshConversation> = [.channel(0)]
        for t in directThreads { keys.insert(.direct(t.node.num)) }
        return keys.reduce(0) { $0 + unreadCount($1) }
    }

    func markRead(_ conversation: MeshConversation) {
        lastRead[conversation.key] = Date()
        scheduleSave()
    }

    var canSend: Bool { connection.isConnected }

    /// Which firmware the current (or last) radio runs.
    var firmware: MeshFirmware { radio.firmware ?? .meshtastic }

    var maxTextBytes: Int { firmware.maxTextBytes }

    /// Sends a text. Returns false when not connected or the text is too long.
    @discardableResult
    func send(_ rawText: String, to conversation: MeshConversation) -> Bool {
        let text = rawText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, MeshText.byteCount(text) <= maxTextBytes else { return false }
        guard connection.isLinkUp else {
            notice = "Not connected to a radio."
            return false
        }
        if firmware == .meshcore { return coreSend(text, to: conversation) }
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
            hopLimit: radio.hopLimit.flatMap { $0 > 0 ? $0 : nil } ?? MeshConstants.defaultHopLimit,
            wantAck: true
        )
        transport.write(MeshPBToRadio.packet(packet).encode())
        messages.append(MeshMessage(packetID: id, from: radio.myNodeNum, to: to, channel: channel,
                                    text: text, date: Date(), isOutgoing: true, delivery: .sent))
        trimHistory()
        scheduleSave()
        startReading()
        return true
    }

    /// Text that the SOS button will send (without location).
    var sosPreview: String {
        MeshSOS.text(latitude: nil, longitude: nil, senderName: myNode?.longName)
    }

    var locationDenied: Bool { locationProvider.isDenied }

    /// Sends the SOS to everyone on the primary channel, with a GPS fix if
    /// allowed. Returns the text sent, or nil if it could not be sent.
    func sendSOS(includeLocation: Bool) async -> String? {
        var lat: Double?
        var lon: Double?
        if includeLocation, let loc = await locationProvider.currentLocation() {
            lat = loc.coordinate.latitude
            lon = loc.coordinate.longitude
        }
        let text = MeshText.truncated(MeshSOS.text(latitude: lat, longitude: lon, senderName: myNode?.longName),
                                      maxBytes: maxTextBytes)
        return send(text, to: .channel(0)) ? text : nil
    }

    func clearPairingHint() { pairingHint = nil }

    /// Deletes every stored message and node from this phone. The radio keeps
    /// its own node list; reconnecting downloads that again.
    func deleteHistory() {
        messages.removeAll()
        nodes = nodes.filter { $0.key == radio.myNodeNum && $0.key != 0 }
        lastRead.removeAll()
        saveNow()
    }

    // MARK: - Transport events

    func handle(_ event: MeshTransportEvent) {
        switch event {
        case .availability(let a):
            availability = a
            if a != .poweredOn {
                isReading = false
                if connection.isLinkUp { connection = .reconnecting(name: currentDeviceName) }
            }

        case .discovered(let d):
            if let i = discovered.firstIndex(where: { $0.id == d.id }) {
                discovered[i] = d
            } else {
                discovered.append(d)
            }
            discovered.sort { ($0.rssi == 0 ? -999 : $0.rssi) > ($1.rssi == 0 ? -999 : $1.rssi) }

        case .connecting(_, let name):
            currentDeviceName = name
            connection = .connecting(name: name)

        case .ready(let id, let name, let firmware):
            currentDeviceName = name
            rememberDevice(id: id, name: name)
            pairingHint = nil
            if isScanning { stopScan() }
            radio.firmware = firmware
            if firmware == .meshcore { coreBeginHandshake() } else { beginHandshake() }

        case .frame(let data):
            coreHandle(data)

        case .disconnected(_, let reason, let willReconnect):
            isReading = false
            handshakeTimeout?.cancel()
            coreReset()
            if willReconnect {
                connection = .reconnecting(name: currentDeviceName)
            } else {
                connection = .idle
                if let reason { notice = reason }
            }

        case .connectFailed(_, let reason):
            notice = reason
            connection = autoReconnect ? .reconnecting(name: currentDeviceName) : .failed(reason)

        case .fromRadio(let data):
            if data.isEmpty {
                isReading = false
                if readAgain {
                    readAgain = false
                    startReading()
                } else if !handshakeDone, connection.isLinkUp {
                    // Radio may still be preparing its config; poll briefly.
                    Task { [weak self] in
                        try? await Task.sleep(for: .milliseconds(300))
                        self?.startReading()
                    }
                }
                return
            }
            do {
                let msg = try MeshPBFromRadio.decode(data)
                handleFromRadio(msg)
            } catch {
                // Corrupt or partial packet: skip it and keep draining.
            }
            transport.readFromRadio()

        case .fromNumChanged:
            startReading()

        case .pairingProblem(let msg):
            pairingHint = msg

        case .writeFailed(let msg):
            notice = "Could not send to the radio: \(msg)"
            if let i = messages.lastIndex(where: { $0.isOutgoing && $0.delivery == .sent }) {
                messages[i].delivery = .failed("Not sent")
            }
        }
    }

    // MARK: - Handshake and reading

    private func connect(id: UUID, name: String) {
        start()
        autoReconnect = true
        defaults.set(true, forKey: Keys.autoReconnect)
        rememberDevice(id: id, name: name)
        currentDeviceName = name
        connection = .connecting(name: name)
        transport.connect(id: id)
    }

    private func rememberDevice(id: UUID, name: String) {
        lastDeviceID = id
        lastDeviceName = name
        defaults.set(id.uuidString, forKey: Keys.lastDeviceID)
        defaults.set(name, forKey: Keys.lastDeviceName)
    }

    private func beginHandshake() {
        handshakeDone = false
        handshakeAttempts += 1
        connection = .syncing(name: currentDeviceName)
        configID = Self.newPacketID()
        transport.write(MeshPBToRadio.wantConfigID(configID).encode())
        startReading()

        handshakeTimeout?.cancel()
        let attempt = handshakeAttempts
        handshakeTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(30))
            guard let self, !Task.isCancelled, !self.handshakeDone, attempt == self.handshakeAttempts else { return }
            if attempt < 2 {
                self.beginHandshake()
            } else {
                // Messages may still flow; let the user try.
                self.handshakeDone = true
                self.connection = .connected(name: self.currentDeviceName)
                self.notice = "The radio did not finish sending its settings. Messages may still work."
            }
        }
    }

    private func startReading() {
        guard connection.isLinkUp else { return }
        if isReading {
            readAgain = true
            return
        }
        isReading = true
        transport.readFromRadio()
    }

    private func handleFromRadio(_ msg: MeshPBFromRadio) {
        switch msg.variant {
        case .myInfo(let info)?:
            radio.myNodeNum = info.myNodeNum
        case .nodeInfo(let info)?:
            upsert(info)
        case .channel(let ch)?:
            if ch.role == MeshPBChannel.Role.disabled.rawValue {
                radio.channelNames[ch.index] = nil
            } else {
                radio.channelNames[ch.index] = ch.name
                if ch.role == MeshPBChannel.Role.primary.rawValue { radio.primaryChannelName = ch.name }
            }
        case .metadata(let meta)?:
            radio.firmwareVersion = meta.firmwareVersion
        case .loraConfig(let lora)?:
            radio.region = lora.region
            radio.regionName = lora.regionName
            radio.hopLimit = lora.hopLimit
        case .configCompleteID(let id)?:
            guard id == configID else { return }
            handshakeDone = true
            handshakeAttempts = 0
            handshakeTimeout?.cancel()
            connection = .connected(name: currentDeviceName)
            if radio.region == 0 {
                notice = "This radio has no region set. Set it to US 915 MHz in the Meshtastic app, or it will not transmit."
            }
            scheduleSave()
        case .rebooted?:
            if connection.isLinkUp { beginHandshake() }
        case .queueStatus(let q)?:
            if q.res != 0, q.meshPacketID != 0,
               let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == q.meshPacketID }) {
                messages[i].delivery = .failed("Radio is busy, try again")
                scheduleSave()
            }
        case .clientNotification(let text)?:
            if !text.isEmpty { notice = text }
        case .packet(let p)?:
            handlePacket(p)
        case .otherConfig?, .logRecord?, .unhandled?, nil:
            break
        }
    }

    private func upsert(_ info: MeshPBNodeInfo) {
        guard info.num != 0 else { return }
        var n = nodes[info.num] ?? MeshNode(num: info.num)
        if let u = info.user { apply(u, to: &n) }
        if let p = info.position, p.hasFix { apply(p, to: &n) }
        if info.lastHeard != 0 {
            let heard = Date(timeIntervalSince1970: TimeInterval(info.lastHeard))
            n.lastHeard = max(heard, n.lastHeard ?? .distantPast)
        }
        if info.snr != 0 { n.snr = info.snr }
        if let h = info.hopsAway { n.hopsAway = h }
        if let b = info.deviceMetrics?.batteryLevel { n.batteryLevel = b }
        nodes[info.num] = n
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

    private func handlePacket(_ p: MeshPBPacket) {
        // Any packet tells us the sender is alive.
        if p.from != 0, p.from != radio.myNodeNum {
            var n = nodes[p.from] ?? MeshNode(num: p.from)
            n.lastHeard = p.rxTime != 0 ? Date(timeIntervalSince1970: TimeInterval(p.rxTime)) : Date()
            if p.rxSNR != 0 { n.snr = p.rxSNR }
            if let h = p.hopsAway { n.hopsAway = h }
            nodes[p.from] = n
            // Packets the radio stored while the phone was away arrive with old
            // receive times; only fresh ones say where the phone is now.
            if p.rxTime == 0 || Date().timeIntervalSince1970 - TimeInterval(p.rxTime) < 120 {
                onHeard?(MeshHeard(node: p.from, snr: p.rxSNR != 0 ? p.rxSNR : nil, hops: p.hopsAway, firmware: .meshtastic))
            }
        }
        guard let data = p.decoded else { return } // still encrypted: not for us
        switch MeshPort(rawValue: data.portnum) {
        case .textMessage?:
            handleText(p, data)
        case .position?:
            if let pos = try? MeshPBPosition.decode(data.payload), pos.hasFix, p.from != 0 {
                var n = nodes[p.from] ?? MeshNode(num: p.from)
                apply(pos, to: &n)
                nodes[p.from] = n
            }
        case .nodeInfo?:
            if let user = try? MeshPBUser.decode(data.payload), p.from != 0 {
                var n = nodes[p.from] ?? MeshNode(num: p.from)
                apply(user, to: &n)
                nodes[p.from] = n
            }
        case .routing?:
            handleRouting(p, data)
        default:
            break
        }
        scheduleSave()
    }

    private func handleText(_ p: MeshPBPacket, _ data: MeshPBData) {
        let myNum = radio.myNodeNum
        // Drop duplicates (the radio replays its queue after a reconnect).
        if p.id != 0, messages.contains(where: { $0.packetID == p.id && $0.from == p.from }) { return }
        // Only broadcasts and messages addressed to us (or sent by us).
        guard p.to == MeshConstants.broadcastNum || p.to == myNum || p.from == myNum else { return }
        let text = String(decoding: data.payload, as: UTF8.self)
        let date = p.rxTime != 0 ? Date(timeIntervalSince1970: TimeInterval(p.rxTime)) : Date()
        messages.append(MeshMessage(
            packetID: p.id, from: p.from, to: p.to, channel: p.channel, text: text,
            date: min(date, Date()), isOutgoing: p.from == myNum && myNum != 0,
            delivery: p.from == myNum ? .sent : .received,
            rxSNR: p.rxSNR != 0 ? p.rxSNR : nil, hopsAway: p.hopsAway
        ))
        trimHistory()
    }

    private func handleRouting(_ p: MeshPBPacket, _ data: MeshPBData) {
        guard data.requestID != 0,
              let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == data.requestID }),
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
    private func coreBeginHandshake() {
        coreReset()
        handshakeDone = false
        handshakeAttempts += 1
        connection = .syncing(name: currentDeviceName)

        coreEnqueue(MeshCoreCommand.appStart(appName: "Pocket Nomad"), finishedBy: [5])
        coreEnqueue(MeshCoreCommand.deviceQuery(), finishedBy: [13]) { [weak self] frame in
            guard let self, case .deviceInfo(let info) = frame else { return }
            if !info.version.isEmpty { self.radio.firmwareVersion = "MeshCore \(info.version)" }
            // Channel slots vary by board (1, 8 or 40); list the first eight.
            for i in 0 ..< UInt8(min(max(info.maxChannels, 1), 8)) {
                self.coreEnqueue(MeshCoreCommand.getChannel(i), finishedBy: [18])
            }
        }
        coreEnqueue(MeshCoreCommand.setDeviceTime(Date()), finishedBy: [0])
        coreEnqueue(MeshCoreCommand.getContacts(), finishedBy: [4], timeout: .seconds(20)) { [weak self] frame in
            guard let self, case .endOfContacts = frame else { return }
            self.handshakeDone = true
            self.handshakeAttempts = 0
            self.handshakeTimeout?.cancel()
            self.connection = .connected(name: self.currentDeviceName)
            self.coreSyncMessages()
            self.scheduleSave()
        }

        handshakeTimeout?.cancel()
        let attempt = handshakeAttempts
        handshakeTimeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(45))
            guard let self, !Task.isCancelled, !self.handshakeDone, attempt == self.handshakeAttempts else { return }
            self.handshakeDone = true
            self.connection = .connected(name: self.currentDeviceName)
            self.notice = "The radio did not finish sending its contacts. Messages may still work."
        }
    }

    private func coreEnqueue(_ frame: Data, finishedBy: Set<UInt8>, timeout: Duration = .seconds(5),
                             onReply: ((MeshCoreFrame) -> Void)? = nil) {
        coreQueue.append(CoreCommand(frame: frame, finishedBy: finishedBy, timeout: timeout, onReply: onReply))
        coreSendNext()
    }

    private func coreSendNext() {
        guard corePending == nil, !coreQueue.isEmpty, connection.isLinkUp else { return }
        let command = coreQueue.removeFirst()
        corePending = command
        transport.write(command.frame)
        coreTimer?.cancel()
        coreTimer = Task { [weak self] in
            try? await Task.sleep(for: command.timeout)
            guard let self, !Task.isCancelled else { return }
            // No answer: give up on this command and move on.
            self.corePending = nil
            self.coreSendNext()
        }
    }

    private func coreReset() {
        coreQueue.removeAll()
        corePending = nil
        coreTimer?.cancel()
    }

    /// Pulls every queued message off the radio, one SYNC at a time.
    private func coreSyncMessages() {
        let syncCode = MeshCoreCommand.Code.syncNextMessage
        guard !coreQueue.contains(where: { $0.frame.first == syncCode }), corePending?.frame.first != syncCode else { return }
        coreEnqueue(MeshCoreCommand.syncNextMessage(), finishedBy: [7, 8, 10, 16, 17, 27]) { [weak self] frame in
            if case .noMoreMessages = frame { return }
            self?.coreSyncMessages()
        }
    }

    private func coreHandle(_ data: Data) {
        guard let code = data.first, let frame = MeshCoreFrame.decode(data) else { return }
        switch frame {
        case .selfInfo(let me):
            let num = MeshCoreContact.nodeNum(for: me.publicKey)
            radio.myNodeNum = num
            radio.regionName = String(format: "%.3f MHz", locale: Locale(identifier: "en_US_POSIX"), me.frequencyMHz)
            var n = nodes[num] ?? MeshNode(num: num)
            n.longName = me.name
            n.shortName = String(me.name.prefix(4))
            n.publicKeyPrefix = me.publicKey.prefix(6)
            n.hasPublicKey = true
            if let lat = me.latitude, let lon = me.longitude { n.latitude = lat; n.longitude = lon }
            nodes[num] = n
        case .contact(let c):
            coreUpsert(c)
        case .newAdvert(let c):
            coreUpsert(c)
            onHeard?(MeshHeard(node: c.nodeNum, snr: nil, hops: c.pathLength.map(UInt32.init), firmware: .meshcore))
        case .advert(let key):
            let num = MeshCoreContact.nodeNum(for: key)
            if var n = nodes[num] { n.lastHeard = Date(); nodes[num] = n }
            onHeard?(MeshHeard(node: num, snr: nil, hops: nil, firmware: .meshcore))
        case .channelInfo(let ch):
            let index = Int32(ch.index)
            radio.channelNames[index] = ch.isEmpty ? nil : ch.name
            if ch.index == 0 { radio.primaryChannelName = ch.name }
        case .directMessage(let m):
            coreReceive(from: coreNode(forKeyPrefix: m.senderKeyPrefix), to: radio.myNodeNum, channel: 0,
                        timestamp: m.timestamp, text: m.text, senderName: nil, snr: m.snr, path: m.pathLength)
        case .channelMessage(let m):
            let sender = m.senderName.flatMap { name in nodes.values.first { $0.longName == name }?.num } ?? 0
            coreReceive(from: sender, to: MeshConstants.broadcastNum, channel: UInt32(m.channel),
                        timestamp: m.timestamp, text: m.text, senderName: m.senderName, snr: m.snr, path: m.pathLength)
        case .sendConfirmed(let ack, _):
            if let i = messages.lastIndex(where: { $0.isOutgoing && $0.packetID == ack && ack != 0 }) {
                messages[i].delivery = .delivered
                scheduleSave()
            }
        case .messagesWaiting:
            coreSyncMessages()
        case .ok, .error, .contactsStart, .endOfContacts, .sent, .noMoreMessages, .deviceInfo, .other:
            break
        }
        // Push frames (0x80 and up) arrive at any time; anything else answers the pending command.
        guard code < 0x80, let pending = corePending else { return }
        let finished = pending.finishedBy.contains(code) || code == 1
        if finished {
            coreTimer?.cancel()
            corePending = nil
        }
        pending.onReply?(frame)
        if finished { coreSendNext() }
    }

    private func coreUpsert(_ c: MeshCoreContact) {
        let num = c.nodeNum
        guard num != 0 else { return }
        var n = nodes[num] ?? MeshNode(num: num)
        n.longName = c.name
        n.shortName = String(c.name.prefix(4))
        n.userID = c.publicKey.prefix(6).map { String(format: "%02x", $0) }.joined()
        n.publicKeyPrefix = c.publicKey.prefix(6)
        n.hasPublicKey = true
        n.meshCoreType = c.type
        if let heard = c.lastAdvert { n.lastHeard = max(heard, n.lastHeard ?? .distantPast) }
        if let lat = c.latitude, let lon = c.longitude { n.latitude = lat; n.longitude = lon }
        if let hops = c.pathLength { n.hopsAway = UInt32(hops) }
        nodes[num] = n
        scheduleSave()
    }

    private func coreNode(forKeyPrefix prefix: Data) -> UInt32 {
        nodes.values.first { $0.publicKeyPrefix == prefix }?.num ?? MeshCoreContact.nodeNum(for: prefix)
    }

    private func coreReceive(from: UInt32, to: UInt32, channel: UInt32, timestamp: UInt32, text: String,
                             senderName: String?, snr: Float?, path: UInt8) {
        // The radio's timestamp plus sender and text identify a message well enough to drop repeats.
        if messages.contains(where: { $0.packetID == timestamp && $0.from == from && $0.text == text }) { return }
        let sent = Date(timeIntervalSince1970: TimeInterval(timestamp))
        messages.append(MeshMessage(
            packetID: timestamp, from: from, to: to, channel: channel, text: text,
            date: timestamp == 0 ? Date() : min(sent, Date()), isOutgoing: false, delivery: .received,
            rxSNR: snr, hopsAway: path == 0xFF ? nil : UInt32(path & 0x3F), senderName: senderName
        ))
        if from != 0, var n = nodes[from] {
            n.lastHeard = Date()
            if let snr { n.snr = snr }
            nodes[from] = n
        }
        // Messages queued on the radio while the phone was away are old; skip those.
        if timestamp == 0 || abs(Date().timeIntervalSince1970 - TimeInterval(timestamp)) < 120 {
            onHeard?(MeshHeard(node: from, snr: snr, hops: path == 0xFF ? nil : UInt32(path & 0x3F), firmware: .meshcore))
        }
        trimHistory()
        scheduleSave()
    }

    private func coreSend(_ text: String, to conversation: MeshConversation) -> Bool {
        let now = Date()
        let frame: Data
        let (to, channel): (UInt32, UInt32)
        switch conversation {
        case .channel(let i):
            frame = MeshCoreCommand.sendChannelText(text, channel: UInt8(clamping: i), date: now)
            (to, channel) = (MeshConstants.broadcastNum, i)
        case .direct(let n):
            guard let prefix = nodes[n]?.publicKeyPrefix else {
                notice = "This contact has no MeshCore key yet. Wait until its radio is heard, then try again."
                return false
            }
            frame = MeshCoreCommand.sendText(text, toKeyPrefix: prefix, date: now)
            (to, channel) = (n, 0)
        }
        let message = MeshMessage(packetID: 0, from: radio.myNodeNum, to: to, channel: channel,
                                  text: text, date: now, isOutgoing: true, delivery: .sent)
        let id = message.id
        messages.append(message)
        coreEnqueue(frame, finishedBy: [6]) { [weak self] reply in
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

    #if DEBUG
    /// Screenshots and demos: nodes with positions around downtown Denver.
    func loadDemoNodes() {
        let demo: [(String, Double, Double, MeshCoreContactType?)] = [
            ("Ridge Repeater", 39.7480, -104.9970, .repeater),
            ("Ana", 39.7560, -104.9870, nil),
            ("Base Camp", 39.7410, -105.0060, .chat),
            ("Cherry Creek", 39.7170, -104.9530, nil),
        ]
        for (i, d) in demo.enumerated() {
            var n = MeshNode(num: UInt32(0xA000 + i))
            n.longName = d.0
            n.shortName = String(d.0.prefix(4))
            n.latitude = d.1
            n.longitude = d.2
            n.lastHeard = Date().addingTimeInterval(-Double(i) * 420)
            n.meshCoreType = d.3
            nodes[n.num] = n
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
        let snap = MeshSnapshot(messages: messages, nodes: Array(nodes.values), radio: radio, lastRead: lastRead)
        try? store.save(snap)
    }
}
