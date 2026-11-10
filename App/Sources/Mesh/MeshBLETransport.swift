// MeshBLETransport.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// CoreBluetooth implementation of MeshTransport for Meshtastic and MeshCore
// radios. Meshtastic UUIDs are from its public client API documentation;
// MeshCore uses the Nordic UART service (docs/companion_protocol.md).
//
// One central manager holds every radio (CoreBluetooth allows several
// connected peripherals per manager); each has its own characteristics and
// ready flag, and every event carries the peripheral's identifier.
//
// The central manager runs on the main queue, so every delegate callback is
// already on the main actor. State restoration is enabled so iOS can relaunch
// the app in the background when the radio has data (needs the
// `bluetooth-central` background mode).

@preconcurrency import CoreBluetooth
import Foundation

enum MeshBLEUUID {
    static let service = CBUUID(string: "6BA1B218-15A8-461F-9FA8-5DCAE273EAFD")
    /// write: one ToRadio protobuf per write.
    static let toRadio = CBUUID(string: "F75C76D2-129E-4DAD-A1DD-7866124401E7")
    /// read: one FromRadio protobuf per read, empty when drained.
    /// Some firmware also allows notify on it; we subscribe when offered.
    static let fromRadio = CBUUID(string: "2C55E69E-4993-11ED-B878-0242AC120002")
    /// read/notify/write: packet counter, notifies when FromRadio has data.
    static let fromNum = CBUUID(string: "ED9DA18C-A800-4F66-A670-AA7547E34453")
    /// notify: LogRecord protobufs (debug logs). We subscribe but ignore them.
    static let logRecord = CBUUID(string: "5A3D6E49-06E6-4423-9944-E9DE8CDF9547")

    static let meshCoreService = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    /// write: one command frame per write.
    static let meshCoreRX = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    /// notify: one response or push frame per notification.
    static let meshCoreTX = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    static let allServices = [service, meshCoreService]
}

@MainActor
final class MeshBLETransport: NSObject, MeshTransport {
    static let restoreIdentifier = "org.nomadpocket.mesh.central"

    var onEvent: ((MeshTransportEvent) -> Void)?
    private(set) var availability: MeshBluetoothAvailability = .unknown

    /// One connected (or connecting) radio and the characteristics found on it.
    private struct Link {
        var peripheral: CBPeripheral
        var toRadio: CBCharacteristic?
        var fromRadio: CBCharacteristic?
        var fromNum: CBCharacteristic?
        var meshCoreRX: CBCharacteristic?
        var meshCoreTX: CBCharacteristic?
        var readyAnnounced = false

        mutating func clear() {
            toRadio = nil
            fromRadio = nil
            fromNum = nil
            meshCoreRX = nil
            meshCoreTX = nil
            readyAnnounced = false
        }
    }

    private var central: CBCentralManager?
    private var links: [UUID: Link] = [:]
    private var seen: [UUID: CBPeripheral] = [:]
    /// The devices we want to stay connected to. A user disconnect removes one.
    private var wanted: Set<UUID> = []
    private var wantsScan = false
    /// Scanning only to find a wanted radio that isn't connected (see updateReconnectScan).
    private var reconnectScanning = false

    func start() {
        guard central == nil else { return }
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier,
                CBCentralManagerOptionShowPowerAlertKey: false,
            ]
        )
    }

    func startScan() {
        start()
        wantsScan = true
        reconnectScanning = false
        guard let central, central.state == .poweredOn else { return }
        central.scanForPeripherals(
            withServices: MeshBLEUUID.allServices,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        // Radios the phone is already linked to (for example, by another app)
        // do not advertise, so list them too.
        for (uuid, firmware) in [(MeshBLEUUID.service, MeshFirmware.meshtastic), (MeshBLEUUID.meshCoreService, .meshcore)] {
            for p in central.retrieveConnectedPeripherals(withServices: [uuid]) {
                seen[p.identifier] = p
                emit(.discovered(MeshDiscoveredDevice(id: p.identifier, name: p.name ?? "\(firmware.displayName) radio",
                                                      rssi: 0, lastSeen: Date(), firmware: firmware)))
            }
        }
    }

    func stopScan() {
        wantsScan = false
        central?.stopScan()
        updateReconnectScan()
    }

    /// A pending connect alone can wait forever (seen on a real drive: the radio
    /// was back but iOS never completed it). While a wanted radio isn't
    /// connected, also scan for the radios' services, which keeps working in the
    /// background, and connect the moment it advertises. Stops once every
    /// wanted radio is connected; the user's own scan takes precedence.
    private func updateReconnectScan() {
        guard let central, central.state == .poweredOn, !wantsScan else { return }
        let missing = wanted.contains { links[$0]?.peripheral.state != .connected }
        if missing, !reconnectScanning {
            reconnectScanning = true
            central.scanForPeripherals(withServices: MeshBLEUUID.allServices, options: nil)
        } else if !missing, reconnectScanning {
            reconnectScanning = false
            central.stopScan()
        }
    }

    func connect(id: UUID) {
        start()
        wanted.insert(id)
        guard let central, central.state == .poweredOn else { return } // retried on poweredOn
        let target = links[id]?.peripheral ?? seen[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
        guard let target else {
            emit(.connectFailed(id: id, reason: "That radio is not nearby. Turn it on and scan again."))
            return
        }
        links[id] = links[id] ?? Link(peripheral: target)
        target.delegate = self
        emit(.connecting(id: id, name: target.name ?? "Mesh radio"))
        if target.state == .connected {
            links[id]?.clear()
            target.discoverServices(MeshBLEUUID.allServices)
        } else {
            // A pending connect never times out; iOS completes it whenever the
            // radio comes back in range, even in the background.
            central.connect(target, options: nil)
        }
        updateReconnectScan()
    }

    func disconnect(id: UUID) {
        wanted.remove(id)
        defer { updateReconnectScan() }
        guard let link = links.removeValue(forKey: id) else { return }
        central?.cancelPeripheralConnection(link.peripheral)
        emit(.disconnected(id: id, reason: nil, willReconnect: false))
    }

    func write(_ toRadio: Data, to id: UUID) {
        guard let link = links[id], let target = link.toRadio ?? link.meshCoreRX else {
            emit(.writeFailed(id: id, "Not connected to a radio."))
            return
        }
        link.peripheral.writeValue(toRadio, for: target, type: .withResponse)
    }

    func readFromRadio(id: UUID) {
        guard let link = links[id], let fromRadio = link.fromRadio else { return }
        link.peripheral.readValue(for: fromRadio)
    }

    // MARK: - Helpers

    private func emit(_ event: MeshTransportEvent) {
        onEvent?(event)
    }

    private func announceReadyIfPossible(_ id: UUID) {
        guard let link = links[id], !link.readyAnnounced else { return }
        let p = link.peripheral
        if link.toRadio != nil, link.fromRadio != nil {
            links[id]?.readyAnnounced = true
            emit(.ready(id: id, name: p.name ?? "Meshtastic radio", firmware: .meshtastic))
        } else if link.meshCoreRX != nil, link.meshCoreTX?.isNotifying == true {
            links[id]?.readyAnnounced = true
            emit(.ready(id: id, name: p.name ?? "MeshCore radio", firmware: .meshcore))
        }
    }

    private static func availability(from state: CBManagerState) -> MeshBluetoothAvailability {
        switch state {
        case .poweredOn: return .poweredOn
        case .poweredOff: return .poweredOff
        case .unauthorized: return .unauthorized
        case .unsupported: return .unsupported
        case .resetting: return .resetting
        case .unknown: return .unknown
        @unknown default: return .unknown
        }
    }

    /// Recognises Bluetooth security errors so the UI can explain pairing.
    private func pairingMessage(for error: Error) -> String? {
        let ns = error as NSError
        if ns.domain == CBErrorDomain, ns.code == CBError.Code.peerRemovedPairingInformation.rawValue {
            return "The radio forgot this phone. Open Settings > Bluetooth, tap the (i) next to the radio, choose Forget This Device, then connect again."
        }
        if ns.domain == CBATTErrorDomain,
           [CBATTError.Code.insufficientAuthentication.rawValue,
            CBATTError.Code.insufficientEncryption.rawValue,
            CBATTError.Code.insufficientAuthorization.rawValue].contains(ns.code) {
            return "Pairing did not finish. Enter the PIN shown on the radio's screen when iOS asks (radios with no screen often use 123456)."
        }
        if ns.domain == CBErrorDomain, ns.code == CBError.Code.encryptionTimedOut.rawValue {
            return "Pairing timed out. Try again and enter the PIN within 30 seconds."
        }
        return nil
    }
}

// MARK: - CBCentralManagerDelegate

extension MeshBLETransport: @preconcurrency CBCentralManagerDelegate {
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Called before centralManagerDidUpdateState when iOS relaunches us.
        // Every radio the app had (up to one per firmware) comes back here.
        for restored in dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? [] {
            links[restored.identifier] = Link(peripheral: restored)
            wanted.insert(restored.identifier)
            restored.delegate = self
            seen[restored.identifier] = restored
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        availability = Self.availability(from: central.state)
        emit(.availability(availability))
        guard central.state == .poweredOn else {
            if central.state == .poweredOff || central.state == .resetting {
                for id in links.keys { links[id]?.clear() }
            }
            return
        }
        reconnectScanning = false
        if wantsScan { startScan() }
        defer { updateReconnectScan() }
        for id in wanted.sorted(by: { $0.uuidString < $1.uuidString }) {
            if let restored = links[id]?.peripheral {
                emit(.connecting(id: id, name: restored.name ?? "Mesh radio"))
                if restored.state == .connected {
                    links[id]?.clear()
                    restored.discoverServices(MeshBLEUUID.allServices)
                } else {
                    central.connect(restored, options: nil)
                }
            } else {
                connect(id: id)
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        seen[peripheral.identifier] = peripheral
        if wanted.contains(peripheral.identifier), peripheral.state == .disconnected {
            links[peripheral.identifier]?.peripheral = peripheral
            if links[peripheral.identifier] == nil { links[peripheral.identifier] = Link(peripheral: peripheral) }
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
        if reconnectScanning { return } // not a user scan: don't fill the device list
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let firmware: MeshFirmware = services.contains(MeshBLEUUID.meshCoreService) ? .meshcore : .meshtastic
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name ?? "\(firmware.displayName) radio"
        let rssi = RSSI.intValue
        // 127 means "not available" in CoreBluetooth.
        emit(.discovered(MeshDiscoveredDevice(id: peripheral.identifier, name: name,
                                              rssi: rssi == 127 ? 0 : rssi, lastSeen: Date(), firmware: firmware)))
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let id = peripheral.identifier
        guard wanted.contains(id) else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        var link = links[id] ?? Link(peripheral: peripheral)
        link.peripheral = peripheral
        link.clear()
        links[id] = link
        peripheral.delegate = self
        peripheral.discoverServices(MeshBLEUUID.allServices)
        updateReconnectScan()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let reason = error.flatMap { pairingMessage(for: $0) } ?? error?.localizedDescription ?? "Could not connect."
        emit(.connectFailed(id: peripheral.identifier, reason: reason))
        if wanted.contains(peripheral.identifier) {
            central.connect(peripheral, options: nil) // keep trying in the background
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let id = peripheral.identifier
        guard links[id] != nil else { return } // already dropped by disconnect(id:)
        links[id]?.clear()
        let reconnect = wanted.contains(id)
        if !reconnect { links[id] = nil }
        if let error, let msg = pairingMessage(for: error) { emit(.pairingProblem(id: id, msg)) }
        emit(.disconnected(id: id, reason: Self.dropReason(error), willReconnect: reconnect))
        if reconnect {
            central.connect(peripheral, options: nil)
        }
        updateReconnectScan()
    }

    /// Why a link went down, in words for the drive log and notices.
    nonisolated static func dropReason(_ error: Error?) -> String? {
        guard let error else { return nil }
        if let cb = error as? CBError {
            switch cb.code {
            case .connectionTimeout: return "out of range (the link timed out)"
            case .peripheralDisconnected: return "the radio closed the link (restarted or switched off)"
            case .connectionLimitReached: return "too many Bluetooth connections"
            default: break
            }
        }
        return error.localizedDescription
    }
}

// MARK: - CBPeripheralDelegate

extension MeshBLETransport: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let core = peripheral.services?.first(where: { $0.uuid == MeshBLEUUID.meshCoreService }),
           !(peripheral.services ?? []).contains(where: { $0.uuid == MeshBLEUUID.service }) {
            peripheral.discoverCharacteristics([MeshBLEUUID.meshCoreRX, MeshBLEUUID.meshCoreTX], for: core)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == MeshBLEUUID.service }) else {
            emit(.connectFailed(id: peripheral.identifier,
                                reason: error?.localizedDescription ?? "This device is not a Meshtastic or MeshCore radio."))
            return
        }
        peripheral.discoverCharacteristics(
            [MeshBLEUUID.toRadio, MeshBLEUUID.fromRadio, MeshBLEUUID.fromNum, MeshBLEUUID.logRecord],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let id = peripheral.identifier
        if let error {
            emit(.connectFailed(id: id, reason: pairingMessage(for: error) ?? error.localizedDescription))
            return
        }
        guard links[id] != nil else { return }
        if service.uuid == MeshBLEUUID.meshCoreService {
            for c in service.characteristics ?? [] {
                if c.uuid == MeshBLEUUID.meshCoreRX { links[id]?.meshCoreRX = c }
                if c.uuid == MeshBLEUUID.meshCoreTX {
                    links[id]?.meshCoreTX = c
                    // Subscribing is what makes iOS ask for the radio's PIN.
                    peripheral.setNotifyValue(true, for: c)
                }
            }
            if links[id]?.meshCoreRX == nil || links[id]?.meshCoreTX == nil {
                emit(.connectFailed(id: id, reason: "This MeshCore radio is missing its companion service. Flash the Companion (Bluetooth) firmware."))
            }
            return
        }
        for c in service.characteristics ?? [] {
            switch c.uuid {
            case MeshBLEUUID.toRadio: links[id]?.toRadio = c
            case MeshBLEUUID.fromRadio:
                links[id]?.fromRadio = c
                if c.properties.contains(.notify) { peripheral.setNotifyValue(true, for: c) }
            case MeshBLEUUID.fromNum:
                links[id]?.fromNum = c
                // The characteristic is encrypted, so this is usually what
                // makes iOS show the pairing (PIN) prompt.
                peripheral.setNotifyValue(true, for: c)
            case MeshBLEUUID.logRecord:
                peripheral.setNotifyValue(true, for: c)
            default: break
            }
        }
        guard links[id]?.toRadio != nil, links[id]?.fromRadio != nil else {
            emit(.connectFailed(id: id, reason: "This radio's firmware is too old or not Meshtastic."))
            return
        }
        if links[id]?.fromNum == nil { announceReadyIfPossible(id) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        let id = peripheral.identifier
        if let error {
            if let msg = pairingMessage(for: error) { emit(.pairingProblem(id: id, msg)) }
            if characteristic.uuid == MeshBLEUUID.fromNum {
                // Without notifications we can still poll; carry on.
                announceReadyIfPossible(id)
            }
            return
        }
        if characteristic.uuid == MeshBLEUUID.fromNum || characteristic.uuid == MeshBLEUUID.meshCoreTX {
            announceReadyIfPossible(id)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let id = peripheral.identifier
        if let error {
            if let msg = pairingMessage(for: error) { emit(.pairingProblem(id: id, msg)) }
            if characteristic.uuid == MeshBLEUUID.fromRadio {
                emit(.fromRadio(id: id, Data())) // end this read loop; next FromNum retries
            }
            return
        }
        switch characteristic.uuid {
        case MeshBLEUUID.fromRadio:
            emit(.fromRadio(id: id, characteristic.value ?? Data()))
        case MeshBLEUUID.fromNum:
            emit(.fromNumChanged(id: id))
        case MeshBLEUUID.meshCoreTX:
            if let value = characteristic.value, !value.isEmpty { emit(.frame(id: id, value)) }
        default:
            break // log records: ignored
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let error else { return }
        if let msg = pairingMessage(for: error) {
            emit(.pairingProblem(id: peripheral.identifier, msg))
        } else {
            emit(.writeFailed(id: peripheral.identifier, error.localizedDescription))
        }
    }
}
