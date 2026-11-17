// HamService.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Everything the Ham tab shows: the TNC link, the packet monitor, APRS
// stations, messages and bulletins, Winlink mail and the packet terminal.
//
// Listen-only is enforced here: `transmit` refuses every frame unless the
// settings have a valid callsign and the licence confirmation.

import CoreLocation
import Foundation
import Observation

@MainActor
@Observable
final class HamService {
    static let shared = HamService(transport: HamBLETransport(), settings: .shared, files: HamFiles(directory: HamFiles.standard))

    enum TNCState: Equatable {
        case idle
        case connecting(String)
        case connected(String)
        case failed(String)

        var isConnected: Bool { if case .connected = self { true } else { false } }
    }

    enum WinlinkState: Equatable {
        case idle
        case connecting(String)
        case exchanging(String)
        case done(String)
        case failed(String)

        var busy: Bool {
            switch self {
            case .connecting, .exchanging: true
            default: false
            }
        }
    }

    // MARK: State

    private(set) var tnc: TNCState = .idle
    private(set) var availability: MeshBluetoothAvailability = .unknown
    private(set) var devices: [HamDevice] = []
    private(set) var scanning = false
    private(set) var monitor: [HamMonitorEntry] = []
    private(set) var packetsHeard = 0
    private(set) var stations: [HamStation] = [] { didSet { files.save(stations, "stations.json") } }
    private(set) var chats: [APRSChat] = [] { didSet { files.save(chats, "aprs-messages.json") } }
    private(set) var bulletins: [APRSBulletin] = [] { didSet { files.save(bulletins, "bulletins.json") } }
    private(set) var inbox: [WinlinkMessage] = [] { didSet { files.save(inbox, "winlink-inbox.json") } }
    private(set) var outbox: [WinlinkMessage] = [] { didSet { files.save(outbox, "winlink-outbox.json") } }
    private(set) var sent: [WinlinkMessage] = [] { didSet { files.save(sent, "winlink-sent.json") } }
    private(set) var winlink: WinlinkState = .idle
    private(set) var winlinkLog: [String] = []
    private(set) var winlinkProgress: (label: String, done: Int, total: Int)?
    private(set) var terminalText = ""
    private(set) var terminalState: AX25Link.State = .disconnected
    private(set) var terminalPeer: String?
    private(set) var myLocation: CLLocation?
    private(set) var lastBeacon: Date?
    var notice: String?
    /// Unread APRS messages, per peer.
    private(set) var unread: [String: Int] = [:]
    var unreadTotal: Int { unread.values.reduce(0, +) }
    var unreadMail: Int { inbox.filter { !readMIDs.contains($0.mid) }.count }
    private(set) var readMIDs: Set<String> = [] { didSet { files.save(readMIDs, "winlink-read.json") } }

    let settings: HamSettings
    private let transport: HamTransport
    private let files: HamFiles
    private var decoder = KISSDecoder()
    private var links: [String: AX25Link] = [:]
    private var winlinkLink: AX25Link?
    private var terminalLink: AX25Link?
    private var winlinkTask: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var seenMessages: [String: Date] = [:]
    private var messageCounter: Int {
        get { UserDefaults.standard.integer(forKey: "ham.msgCounter") }
        set { UserDefaults.standard.set(newValue, forKey: "ham.msgCounter") }
    }
    private let location = MeshLocationProvider()
    /// Test hook: the clock.
    var now: () -> Date = { Date() }

    init(transport: HamTransport, settings: HamSettings, files: HamFiles) {
        self.transport = transport
        self.settings = settings
        self.files = files
        stations = files.load("stations.json") ?? []
        chats = files.load("aprs-messages.json") ?? []
        bulletins = files.load("bulletins.json") ?? []
        inbox = files.load("winlink-inbox.json") ?? []
        outbox = files.load("winlink-outbox.json") ?? []
        sent = files.load("winlink-sent.json") ?? []
        readMIDs = files.load("winlink-read.json") ?? []
        transport.onEvent = { [weak self] e in self?.handle(e) }
    }

    // MARK: TNC

    /// Show every named Bluetooth device, not only known TNC services (for radios with an unusual service).
    var showAllDevices = false

    func startScan() {
        devices = []
        scanning = true
        transport.startScan(showAll: showAllDevices)
    }

    func stopScan() {
        scanning = false
        transport.stopScan()
    }

    func connect(_ device: HamDevice) {
        stopScan()
        settings.lastDevice = device.id
        tnc = .connecting(device.name)
        transport.connect(device.id)
    }

    func reconnectLast() {
        guard let id = settings.lastDevice, !tnc.isConnected else { return }
        tnc = .connecting("TNC")
        transport.connect(id)
    }

    func disconnect() {
        abortWinlink()
        disconnectTerminal()
        settings.lastDevice = nil
        transport.disconnect()
        tnc = .idle
        ticker?.cancel()
        ticker = nil
    }

    private func handle(_ e: HamTransportEvent) {
        switch e {
        case .availability(let a): availability = a
        case .discovered(let d):
            if let i = devices.firstIndex(where: { $0.id == d.id }) { devices[i] = d } else { devices.append(d) }
        case .connecting(let name): tnc = .connecting(name)
        case .ready(_, let name):
            tnc = .connected(name)
            decoder = KISSDecoder()
            if settings.canTransmit {
                transport.write(KISS.parameterFrames(txDelay: UInt8(clamping: settings.txDelay),
                                                     persistence: UInt8(clamping: settings.persistence),
                                                     slotTime: UInt8(clamping: settings.slotTime)))
            }
            log("Connected to \(name)", outgoing: false)
            startTicker()
        case .disconnected(let reason):
            if let reason { tnc = .failed(reason) } else { tnc = .idle }
            for link in links.values { link.disconnect(now: now(), flush: false) }
        case .log(let line):
            log(line, outgoing: false)
        case .bytes(let b):
            for f in decoder.feed(b) where f.command == KISS.Command.data.rawValue {
                receive(f.payload)
            }
        }
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.tick()
            }
        }
    }

    // MARK: Frames

    /// Test hook and transport path: one AX.25 frame from the TNC.
    func receive(_ bytes: [UInt8]) {
        guard let f = AX25Frame.decode(bytes) else {
            log("[undecodable frame, \(bytes.count) bytes]", outgoing: false)
            return
        }
        packetsHeard += 1
        let aprs = APRS.decode(f)
        log(f.monitorLine, outgoing: false, kind: aprs?.kind)

        if case .ui = f.control {
            if let aprs { handle(aprs, frame: f) }
            return
        }
        // Connected-mode traffic for us.
        guard let me = settings.stationAddress, f.destination == me else { return }
        let key = f.source.base.description
        if let link = links[key] {
            link.receive(f, now: now())
            return
        }
        if case .sabm = f.control, settings.acceptIncoming, terminalLink == nil {
            let link = makeLink(remote: f.source.base, path: f.replyPath)
            terminalLink = link
            terminalPeer = key
            terminalText += "\n*** \(key) connected to you\n"
            wireTerminal(link)
            link.accept(f, now: now())
            return
        }
        if f.isCommand {
            let poll: Bool = switch f.control {
            case .sabm(let p), .disc(let p), .i(_, _, let p): p
            default: false
            }
            transmit(AX25Frame(destination: f.source.base, source: me, path: f.replyPath, isCommand: false,
                               control: .dm(final: poll)))
        }
    }

    private func requireTransmit() -> Bool {
        guard settings.canTransmit else {
            notice = "Listen only: add your callsign and confirm your licence in Ham settings to transmit."
            return false
        }
        return true
    }

    private func requireTNC() -> Bool {
        guard tnc.isConnected else { notice = "Connect a TNC first."; return false }
        return true
    }

    @discardableResult
    func transmit(_ f: AX25Frame) -> Bool {
        guard settings.canTransmit else {
            notice = "Listen only: add your callsign and confirm your licence in Ham settings to transmit."
            return false
        }
        guard tnc.isConnected else {
            notice = "Connect a TNC first."
            return false
        }
        transport.write(KISS.encode(f.encoded()))
        log(f.monitorLine, outgoing: true, kind: APRS.decode(f)?.kind)
        return true
    }

    private func log(_ line: String, outgoing: Bool, kind: APRSPacket.Kind? = nil) {
        monitor.append(HamMonitorEntry(date: now(), line: line, outgoing: outgoing, kind: kind))
        if monitor.count > 600 { monitor.removeFirst(monitor.count - 500) }
    }

    func clearMonitor() { monitor = [] }

    // MARK: APRS in

    private func isMe(_ addressee: String?) -> Bool {
        guard let a = addressee?.uppercased(), settings.callsignValid else { return false }
        if let aprs = settings.aprsAddress, a == aprs.description { return true }
        return a == settings.baseCall
    }

    func handle(_ p: APRSPacket, frame: AX25Frame? = nil) {
        let t = now()
        switch p.kind {
        case .message where isMe(p.addressee):
            let key = "\(p.source)|\(p.messageID ?? p.text ?? "")"
            let dup = seenMessages[key].map { t.timeIntervalSince($0) < 1800 } ?? false
            if !dup {
                seenMessages[key] = t
                chats.append(APRSChat(peer: p.source, outgoing: false, text: p.text ?? "", msgID: p.messageID, date: t, status: .received))
                unread[p.source, default: 0] += 1
            }
            if let id = p.messageID, settings.autoAck, settings.canTransmit, tnc.isConnected {
                sendAPRS(APRS.ackInfo(to: p.source, id: id))
            }
        case .ack, .reject:
            guard isMe(p.addressee), let id = p.messageID else { break }
            if let i = chats.lastIndex(where: { $0.outgoing && $0.msgID == id && $0.peer.uppercased() == p.source.uppercased() }) {
                chats[i].status = p.kind == .ack ? .acked : .rejected
                chats[i].nextTry = nil
            }
        case .bulletin:
            let b = APRSBulletin(source: p.source, addressee: p.addressee ?? "", text: p.text ?? "", date: t)
            bulletins.removeAll { $0.id == b.id }
            bulletins.insert(b, at: 0)
            if bulletins.count > 200 { bulletins.removeLast() }
        default:
            break
        }
        updateStation(p, frame: frame, at: t)
    }

    private func updateStation(_ p: APRSPacket, frame: AX25Frame?, at t: Date) {
        guard p.position != nil || p.kind == .status || p.weather != nil else { return }
        let id = p.subject
        var s = stations.first(where: { $0.id == id }) ?? HamStation(id: id, source: p.source, lastHeard: t, firstHeard: t)
        s.source = p.source
        s.lastHeard = t
        s.packets += 1
        s.path = p.path
        s.direct = frame.map { $0.path.allSatisfy { !$0.repeated } } ?? false
        if let pos = p.position {
            s.latitude = pos.latitude
            s.longitude = pos.longitude
            s.symbol = String(pos.symbolTable) + String(pos.symbolCode)
            s.courseDegrees = p.courseDegrees
            s.speedKnots = p.speedKnots
            if let a = p.altitudeFeet { s.altitudeFeet = a }
            if !p.comment.isEmpty { s.comment = p.comment }
        }
        if p.kind == .status { s.status = p.comment }
        if let w = p.weather { s.weather = w.summary }
        if let m = p.micEStatus { s.micEStatus = m }
        s.isObject = p.objectName != nil
        s.emergency = p.isEmergency
        stations.removeAll { $0.id == id }
        stations.insert(s, at: 0)
        if stations.count > 1000 { stations.removeLast(stations.count - 1000) }
    }

    func forgetStations() { stations = [] }

    // MARK: APRS out

    private func sendAPRS(_ info: String) {
        guard let me = settings.aprsAddress else { return }
        let path = AX25Path.parse(settings.aprsPath) ?? []
        transmit(AX25Frame(destination: AX25Address(call: APRS.tocall), source: me, path: path,
                           control: .ui(poll: false), info: Array(info.utf8)))
    }

    func sendMessage(to peer: String, text: String) {
        let peer = peer.uppercased().trimmingCharacters(in: .whitespaces)
        guard !peer.isEmpty, !text.isEmpty else { return }
        guard requireTransmit() else { return }
        messageCounter = messageCounter % 99_999 + 1
        let id = String(messageCounter)
        // APRS text is up to 67 characters; longer messages go as several.
        var parts: [String] = []
        var rest = Substring(text)
        while !rest.isEmpty { parts.append(String(rest.prefix(67))); rest = rest.dropFirst(67) }
        for (n, part) in parts.enumerated() {
            let pid = n == 0 ? id : String((Int(id)! + n - 1) % 99_999 + 1)
            if n > 0 { messageCounter = Int(pid)! }
            chats.append(APRSChat(peer: peer, outgoing: true, text: part, msgID: pid, date: now(), status: .sending,
                                  tries: 1, nextTry: now() + 30))
            sendAPRS(APRS.messageInfo(to: peer, text: part, id: pid))
        }
    }

    func markRead(_ peer: String) { unread[peer] = nil }

    func deleteConversation(_ peer: String) {
        chats.removeAll { $0.peer == peer }
        unread[peer] = nil
    }

    var conversations: [(peer: String, last: APRSChat)] {
        var latest: [String: APRSChat] = [:]
        for c in chats where (latest[c.peer]?.date ?? .distantPast) <= c.date { latest[c.peer] = c }
        return latest.map { ($0.key, $0.value) }.sorted { $0.last.date > $1.last.date }
    }

    /// Sends our position now (licensed and with a location fix).
    func beacon() async {
        guard requireTransmit(), requireTNC() else { return }
        guard let loc = await location.currentLocation(timeout: 20) else {
            notice = "No location fix. Allow location for Pocket Nomad, or step outside for a GPS fix."
            return
        }
        myLocation = loc
        let sym = Array(settings.symbol)
        let info = APRS.positionInfo(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                                     table: sym.first ?? "/", code: sym.count > 1 ? sym[1] : "[",
                                     courseDegrees: loc.course >= 0 ? Int(loc.course) : nil,
                                     speedKnots: loc.speed >= 0 ? loc.speed * 1.943844 : nil,
                                     altitudeFeet: loc.verticalAccuracy > 0 ? loc.altitude * 3.28084 : nil,
                                     comment: settings.beaconComment)
        sendAPRS(info)
        lastBeacon = now()
    }

    func refreshLocation() async {
        if let l = await location.currentLocation(timeout: 10) { myLocation = l }
    }

    // MARK: Ticks: links, message retries, beacons

    func tick() {
        let t = now()
        for link in links.values { link.tick(now: t) }
        for i in chats.indices where chats[i].status == .sending {
            guard let due = chats[i].nextTry, t >= due else { continue }
            if chats[i].tries >= 5 {
                chats[i].status = .failed
                chats[i].nextTry = nil
            } else if tnc.isConnected, settings.canTransmit {
                chats[i].tries += 1
                chats[i].nextTry = t + 30 * pow(2, Double(chats[i].tries - 1))
                sendAPRS(APRS.messageInfo(to: chats[i].peer, text: chats[i].text, id: chats[i].msgID))
            }
        }
        if settings.beaconMinutes > 0, settings.canTransmit, tnc.isConnected,
           t.timeIntervalSince(lastBeacon ?? .distantPast) >= Double(settings.beaconMinutes) * 60 {
            lastBeacon = t
            Task { await beacon() }
        }
    }

    // MARK: Connected-mode links

    private func makeLink(remote: AX25Address, path: [AX25Address]) -> AX25Link {
        let link = AX25Link(local: settings.stationAddress ?? AX25Address(call: "NOCALL"), remote: remote, path: path,
                            config: settings.linkConfig)
        link.send = { [weak self] f in self?.transmit(f) }
        links[remote.base.description] = link
        return link
    }

    private func dropLink(_ link: AX25Link) {
        if links[link.remote.description] === link { links[link.remote.description] = nil }
    }

    private func waitForConnection(_ link: AX25Link) async -> Bool {
        while link.state == .connecting { try? await Task.sleep(for: .milliseconds(250)) }
        return link.state == .connected
    }

    // MARK: Terminal (BBS, nodes, keyboard-to-keyboard)

    func connectTerminal(call: String, path: String) {
        guard requireTransmit() else { return }
        guard terminalLink == nil, let remote = AX25Address(call), let via = AX25Path.parse(path) else {
            notice = "Check the callsign and path (for example KF0XYZ-1 via WIDE1-1)."
            return
        }
        guard links[remote.base.description] == nil else { notice = "Already connected to \(remote)."; return }
        let link = makeLink(remote: remote.base, path: via)
        terminalLink = link
        terminalPeer = remote.base.description
        terminalText += "\n*** Connecting to \(remote)\(via.isEmpty ? "" : " via " + AX25Path.text(via))…\n"
        wireTerminal(link)
        link.connect(now: now())
    }

    private func wireTerminal(_ link: AX25Link) {
        terminalState = link.state
        link.onData = { [weak self] bytes in
            guard let self else { return }
            let s = String(decoding: bytes, as: UTF8.self).replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            terminalText += s
            if terminalText.count > 60_000 { terminalText = String(terminalText.suffix(50_000)) }
        }
        link.onStateChange = { [weak self, weak link] st in
            guard let self, let link else { return }
            terminalState = st
            switch st {
            case .connected: terminalText += "*** Connected to \(link.remote)\n"
            case .disconnected:
                terminalText += "*** Disconnected\(link.endReason.map { ": " + $0 } ?? "")\n"
                dropLink(link)
                if terminalLink === link { terminalLink = nil }
            default: break
            }
        }
    }

    func sendTerminal(_ line: String) {
        guard let link = terminalLink, link.state == .connected else { return }
        terminalText += line + "\n"
        link.write(Array((line + "\r").utf8), now: now())
    }

    func disconnectTerminal() {
        terminalLink?.disconnect(now: now())
    }

    func clearTerminal() { terminalText = "" }

    // MARK: Winlink

    func compose(to: [String], cc: [String], subject: String, body: String) -> Bool {
        guard settings.callsignValid else {
            notice = "Add your callsign in Ham settings first."
            return false
        }
        let m = WinlinkMessage.compose(from: settings.baseCall, to: to, cc: cc, subject: subject, body: body, date: now())
        outbox.append(m)
        return true
    }

    func delete(_ m: WinlinkMessage, from folder: WinlinkFolder) {
        switch folder {
        case .inbox: inbox.removeAll { $0.mid == m.mid }
        case .outbox: outbox.removeAll { $0.mid == m.mid }
        case .sent: sent.removeAll { $0.mid == m.mid }
        }
    }

    func markRead(mid: String) { readMIDs.insert(mid) }

    func messages(in folder: WinlinkFolder) -> [WinlinkMessage] {
        switch folder {
        case .inbox: inbox.sorted { $0.date > $1.date }
        case .outbox: outbox
        case .sent: sent.sorted { $0.date > $1.date }
        }
    }

    func connectWinlink(gateway: HamSavedStation) {
        guard !winlink.busy else { return }
        guard requireTransmit() else { return }
        guard tnc.isConnected else { notice = "Connect a TNC first."; return }
        guard let remote = AX25Address(gateway.call), let path = AX25Path.parse(gateway.path) else {
            notice = "Check the gateway callsign and path."
            return
        }
        guard links[remote.base.description] == nil else { return }
        winlinkLog = []
        winlinkProgress = nil
        winlink = .connecting(remote.description)
        let link = makeLink(remote: remote.base, path: path)
        winlinkLink = link
        let channel = HamByteChannel()
        link.onData = { channel.feed($0) }
        link.onStateChange = { [weak self, weak link] st in
            guard let self, let link, st == .disconnected else { return }
            channel.close(link.endReason ?? "The gateway ended the connection.")
            dropLink(link)
            if winlinkLink === link { winlinkLink = nil }
        }
        link.connect(now: now())
        let password = settings.winlinkPassword
        let mycall = settings.baseCall
        let grid = myLocation.map { HamGeo.maidenhead(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) } ?? ""
        winlinkTask = Task { [weak self] in
            guard let self else { return }
            guard await waitForConnection(link) else {
                winlink = .failed(link.endReason ?? "Could not connect to \(remote).")
                return
            }
            winlink = .exchanging(remote.description)
            let session = B2FSession(mycall: mycall, password: password, locator: String(grid.prefix(6)), target: remote.description,
                                     outbound: outbox, input: channel, write: { [weak link] b in link?.write(b, now: Date()) })
            session.knownMIDs = Set(inbox.map(\.mid) + sent.map(\.mid))
            session.log = { [weak self] l in self?.winlinkLog.append(l) }
            session.progress = { [weak self] label, done, total in self?.winlinkProgress = (label, done, total) }
            session.onReceived = { [weak self] m in
                guard let self, !inbox.contains(where: { $0.mid == m.mid }) else { return }
                inbox.append(m)
            }
            session.onSent = { [weak self] mid in
                guard let self, let i = outbox.firstIndex(where: { $0.mid == mid }) else { return }
                sent.append(outbox.remove(at: i))
            }
            do {
                let out = try await session.run()
                let got = out.received.count, gone = out.sent.count + out.alreadyDelivered.count
                winlink = .done("Received \(got) message\(got == 1 ? "" : "s"), sent \(gone).")
                link.disconnect(now: now())
            } catch {
                if case .exchanging = winlink { winlink = .failed(error.localizedDescription) }
                link.disconnect(now: now(), flush: false)
            }
            winlinkProgress = nil
        }
    }

    func abortWinlink() {
        winlinkTask?.cancel()
        if let l = winlinkLink {
            l.disconnect(now: now(), flush: false)
            if winlink.busy { winlink = .failed("Stopped.") }
        }
    }

    // MARK: Data

    func deleteAllData() {
        stations = []; chats = []; bulletins = []; inbox = []; outbox = []; sent = []; readMIDs = []
        monitor = []; terminalText = ""; unread = [:]
        files.removeAll()
    }

    // MARK: Demo (screenshots and the simulator)

    func loadDemo() {
        let base = Date()
        let lines = [
            "KF0ABC-9>SY4TTP,WIDE1-1,WIDE2-1:`p WD\u{1E}\u{1E}O>/]Heading to the trailhead=",
            "N0DEN-10>APRS,WIDE2-1:!3945.12N/10459.02W#PHG5130 Denver digi",
            "WX0DEN>APRS:=3944.10N/10458.50W_220/004g010t045r000p000P000h40b10150Weather",
            "KF0XYZ-7>APRS::BLN1     :ARES net tonight 19:00 on 146.52",
            "KF0EOC>APRS:;SHELTER  *092345z3943.80N/10457.90W;Red Cross shelter, cots + water",
        ]
        for l in lines {
            if let p = APRS.decode(tnc2: l) {
                handle(p)
                log(l, outgoing: false, kind: p.kind)
            }
        }
        chats.append(APRSChat(peer: "KF0ABC-9", outgoing: false, text: "Made it to the lot, all good", msgID: "12", date: base - 300, status: .received))
        if inbox.isEmpty {
            inbox.append(WinlinkMessage.compose(from: "KF0XYZ", to: ["N0CALL"], subject: "Check-in received",
                                                body: "Thanks for the check-in. Net control logged you at 18:05.", date: base - 3600))
        }
    }
}
