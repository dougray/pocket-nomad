// MeshBLETransport.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// CoreBluetooth implementation of MeshTransport for Meshtastic and MeshCore
// radios. Meshtastic UUIDs are from its public client API documentation;
// MeshCore uses the Nordic UART service (docs/companion_protocol.md).
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

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var toRadioChar: CBCharacteristic?
    private var fromRadioChar: CBCharacteristic?
    private var fromNumChar: CBCharacteristic?
    private var meshCoreRXChar: CBCharacteristic?
    private var meshCoreTXChar: CBCharacteristic?
    private var seen: [UUID: CBPeripheral] = [:]
    /// The device we want to stay connected to. nil after a user disconnect.
    private var wantedID: UUID?
    private var wantsScan = false
    private var readyAnnounced = false

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
    }

    func connect(id: UUID) {
        start()
        wantedID = id
        guard let central, central.state == .poweredOn else { return } // retried on poweredOn
        if let current = peripheral, current.identifier != id {
            central.cancelPeripheralConnection(current)
            clearLink()
        }
        let target = seen[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first
        guard let target else {
            emit(.connectFailed(id: id, reason: "That radio is not nearby. Turn it on and scan again."))
            return
        }
        peripheral = target
        target.delegate = self
        emit(.connecting(id: id, name: target.name ?? "Mesh radio"))
        if target.state == .connected {
            target.discoverServices(MeshBLEUUID.allServices)
        } else {
            // A pending connect never times out; iOS completes it whenever the
            // radio comes back in range, even in the background.
            central.connect(target, options: nil)
        }
    }

    func disconnect() {
        wantedID = nil
        if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        if let id = peripheral?.identifier {
            emit(.disconnected(id: id, reason: nil, willReconnect: false))
        }
        clearLink()
        peripheral = nil
    }

    func write(_ toRadio: Data) {
        guard let peripheral, let target = toRadioChar ?? meshCoreRXChar else {
            emit(.writeFailed("Not connected to a radio."))
            return
        }
        peripheral.writeValue(toRadio, for: target, type: .withResponse)
    }

    func readFromRadio() {
        guard let peripheral, let fromRadioChar else { return }
        peripheral.readValue(for: fromRadioChar)
    }

    // MARK: - Helpers

    private func emit(_ event: MeshTransportEvent) {
        onEvent?(event)
    }

    private func clearLink() {
        toRadioChar = nil
        fromRadioChar = nil
        fromNumChar = nil
        meshCoreRXChar = nil
        meshCoreTXChar = nil
        readyAnnounced = false
    }

    private func announceReadyIfPossible() {
        guard !readyAnnounced, let peripheral else { return }
        if toRadioChar != nil, fromRadioChar != nil {
            readyAnnounced = true
            emit(.ready(id: peripheral.identifier, name: peripheral.name ?? "Meshtastic radio", firmware: .meshtastic))
        } else if meshCoreRXChar != nil, meshCoreTXChar?.isNotifying == true {
            readyAnnounced = true
            emit(.ready(id: peripheral.identifier, name: peripheral.name ?? "MeshCore radio", firmware: .meshcore))
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
        guard let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first else { return }
        peripheral = restored
        wantedID = restored.identifier
        restored.delegate = self
        seen[restored.identifier] = restored
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        availability = Self.availability(from: central.state)
        emit(.availability(availability))
        guard central.state == .poweredOn else {
            if central.state == .poweredOff || central.state == .resetting { clearLink() }
            return
        }
        if wantsScan { startScan() }
        if let restored = peripheral, restored.identifier == wantedID {
            emit(.connecting(id: restored.identifier, name: restored.name ?? "Mesh radio"))
            if restored.state == .connected {
                restored.discoverServices(MeshBLEUUID.allServices)
            } else {
                central.connect(restored, options: nil)
            }
        } else if let wantedID {
            connect(id: wantedID)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        seen[peripheral.identifier] = peripheral
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
        guard peripheral.identifier == wantedID else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        self.peripheral = peripheral
        peripheral.delegate = self
        clearLink()
        peripheral.discoverServices(MeshBLEUUID.allServices)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let reason = error.flatMap { pairingMessage(for: $0) } ?? error?.localizedDescription ?? "Could not connect."
        emit(.connectFailed(id: peripheral.identifier, reason: reason))
        if peripheral.identifier == wantedID {
            central.connect(peripheral, options: nil) // keep trying in the background
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let mine = peripheral.identifier == self.peripheral?.identifier
        guard mine else { return }
        clearLink()
        let reconnect = peripheral.identifier == wantedID
        if let error, let msg = pairingMessage(for: error) { emit(.pairingProblem(msg)) }
        emit(.disconnected(id: peripheral.identifier, reason: error?.localizedDescription, willReconnect: reconnect))
        if reconnect {
            central.connect(peripheral, options: nil)
        }
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
        if let error {
            emit(.connectFailed(id: peripheral.identifier, reason: pairingMessage(for: error) ?? error.localizedDescription))
            return
        }
        if service.uuid == MeshBLEUUID.meshCoreService {
            for c in service.characteristics ?? [] {
                if c.uuid == MeshBLEUUID.meshCoreRX { meshCoreRXChar = c }
                if c.uuid == MeshBLEUUID.meshCoreTX {
                    meshCoreTXChar = c
                    // Subscribing is what makes iOS ask for the radio's PIN.
                    peripheral.setNotifyValue(true, for: c)
                }
            }
            if meshCoreRXChar == nil || meshCoreTXChar == nil {
                emit(.connectFailed(id: peripheral.identifier, reason: "This MeshCore radio is missing its companion service. Flash the Companion (Bluetooth) firmware."))
            }
            return
        }
        for c in service.characteristics ?? [] {
            switch c.uuid {
            case MeshBLEUUID.toRadio: toRadioChar = c
            case MeshBLEUUID.fromRadio:
                fromRadioChar = c
                if c.properties.contains(.notify) { peripheral.setNotifyValue(true, for: c) }
            case MeshBLEUUID.fromNum:
                fromNumChar = c
                // The characteristic is encrypted, so this is usually what
                // makes iOS show the pairing (PIN) prompt.
                peripheral.setNotifyValue(true, for: c)
            case MeshBLEUUID.logRecord:
                peripheral.setNotifyValue(true, for: c)
            default: break
            }
        }
        guard toRadioChar != nil, fromRadioChar != nil else {
            emit(.connectFailed(id: peripheral.identifier, reason: "This radio's firmware is too old or not Meshtastic."))
            return
        }
        if fromNumChar == nil { announceReadyIfPossible() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            if let msg = pairingMessage(for: error) { emit(.pairingProblem(msg)) }
            if characteristic.uuid == MeshBLEUUID.fromNum {
                // Without notifications we can still poll; carry on.
                announceReadyIfPossible()
            }
            return
        }
        if characteristic.uuid == MeshBLEUUID.fromNum || characteristic.uuid == MeshBLEUUID.meshCoreTX {
            announceReadyIfPossible()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            if let msg = pairingMessage(for: error) { emit(.pairingProblem(msg)) }
            if characteristic.uuid == MeshBLEUUID.fromRadio {
                emit(.fromRadio(Data())) // end this read loop; next FromNum retries
            }
            return
        }
        switch characteristic.uuid {
        case MeshBLEUUID.fromRadio:
            emit(.fromRadio(characteristic.value ?? Data()))
        case MeshBLEUUID.fromNum:
            emit(.fromNumChanged)
        case MeshBLEUUID.meshCoreTX:
            if let value = characteristic.value, !value.isEmpty { emit(.frame(value)) }
        default:
            break // log records: ignored
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let error else { return }
        if let msg = pairingMessage(for: error) {
            emit(.pairingProblem(msg))
        } else {
            emit(.writeFailed(error.localizedDescription))
        }
    }
}
