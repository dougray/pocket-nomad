// HamTransport.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The Bluetooth link to a KISS TNC. iOS reaches Bluetooth LE TNCs only:
// the "BLE KISS" service Mobilinkd introduced and Benshi-family radios
// (BTech UV-Pro, Vero VR-N76, Radioddity GA-5WB, ...) also use. Classic
// Bluetooth serial TNCs (Kenwood TH-D74/D75) need MFi and are Android-only.

@preconcurrency import CoreBluetooth
import Foundation

struct HamDevice: Identifiable, Equatable, Sendable {
    var id: UUID
    var name: String
    var rssi: Int
}

enum HamTransportEvent: Sendable {
    case availability(MeshBluetoothAvailability)
    case discovered(HamDevice)
    case connecting(String)
    case ready(id: UUID, name: String)
    case disconnected(String?)
    case bytes([UInt8])
    /// Diagnostics for the packet monitor (the services a new radio offers).
    case log(String)
}

@MainActor
protocol HamTransport: AnyObject {
    var onEvent: ((HamTransportEvent) -> Void)? { get set }
    /// `showAll`: every Bluetooth LE device with a name, not just known TNC services.
    func startScan(showAll: Bool)
    func stopScan()
    func connect(_ id: UUID)
    func disconnect()
    func write(_ bytes: [UInt8])
}

@MainActor
final class HamBLETransport: NSObject, HamTransport {
    var onEvent: ((HamTransportEvent) -> Void)?
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var txChar: CBCharacteristic?
    private var profile: HamBLEProfile?
    private var pendingServices = 0
    private var wanted: UUID?
    private var scanning = false
    private var showAll = false
    private var seen: [UUID: CBPeripheral] = [:]

    private func start() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "org.nomadpocket.ham.central",
            CBCentralManagerOptionShowPowerAlertKey: false,
        ])
    }

    func startScan(showAll: Bool) {
        start()
        scanning = true
        self.showAll = showAll
        guard let central, central.state == .poweredOn else { return }
        central.stopScan()
        let services = HamBLEProfile.scanServices.map { CBUUID(string: $0) }
        central.scanForPeripherals(withServices: showAll ? nil : services, options: nil)
        for p in central.retrieveConnectedPeripherals(withServices: services) {
            seen[p.identifier] = p
            onEvent?(.discovered(HamDevice(id: p.identifier, name: p.name ?? "TNC", rssi: 0)))
        }
    }

    func stopScan() {
        scanning = false
        central?.stopScan()
    }

    func connect(_ id: UUID) {
        start()
        wanted = id
        guard let central, central.state == .poweredOn else { return }
        guard let p = seen[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
            onEvent?(.disconnected("That TNC is not nearby. Turn it on and scan again."))
            return
        }
        peripheral = p
        p.delegate = self
        onEvent?(.connecting(p.name ?? "TNC"))
        central.connect(p, options: nil)
    }

    func disconnect() {
        wanted = nil
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
        txChar = nil
        onEvent?(.disconnected(nil))
    }

    /// BLE writes are limited by the negotiated MTU; KISS frames are split to fit.
    func write(_ bytes: [UInt8]) {
        guard let p = peripheral, let c = txChar else { return }
        let type: CBCharacteristicWriteType = c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        let max = p.maximumWriteValueLength(for: type)
        var i = 0
        while i < bytes.count {
            let n = min(max > 0 ? max : 20, bytes.count - i)
            p.writeValue(Data(bytes[i..<(i + n)]), for: c, type: type)
            i += n
        }
    }

    /// Every service read: pick the one that carries KISS and subscribe to it.
    private func choose(on p: CBPeripheral) {
        let infos = (p.services ?? []).map { s in
            HamBLEServiceInfo(uuid: s.uuid.uuidString, characteristics: (s.characteristics ?? []).map { c in
                HamBLEChar(uuid: c.uuid.uuidString, notify: c.properties.contains(.notify) || c.properties.contains(.indicate),
                           write: c.properties.contains(.write) || c.properties.contains(.writeWithoutResponse))
            })
        }
        for line in HamBLEProfile.describe(infos) { onEvent?(.log(line)) }
        guard let chosen = HamBLEProfile.choose(infos) else {
            onEvent?(.disconnected("No KISS or serial service on this device. Check that the radio's KISS TNC mode is on."))
            return
        }
        profile = chosen
        onEvent?(.log("Using \(chosen.name)"))
        let all = (p.services ?? []).flatMap { $0.characteristics ?? [] }
        let svc = (p.services ?? []).first { HamBLEProfile.normalize($0.uuid.uuidString) == chosen.service }
        let chars = svc?.characteristics ?? all
        txChar = chars.first { HamBLEProfile.normalize($0.uuid.uuidString) == chosen.write }
        if let n = chars.first(where: { HamBLEProfile.normalize($0.uuid.uuidString) == chosen.notify }) {
            p.setNotifyValue(true, for: n)
        }
    }

    private func isNotifyChar(_ c: CBCharacteristic) -> Bool {
        guard let profile else { return false }
        return HamBLEProfile.normalize(c.uuid.uuidString) == profile.notify
    }
}

extension HamBLETransport: @preconcurrency CBCentralManagerDelegate {
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
            peripheral = p
            wanted = p.identifier
            p.delegate = self
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let a: MeshBluetoothAvailability = switch central.state {
        case .poweredOn: .poweredOn
        case .poweredOff: .poweredOff
        case .unauthorized: .unauthorized
        case .unsupported: .unsupported
        case .resetting: .resetting
        default: .unknown
        }
        onEvent?(.availability(a))
        guard central.state == .poweredOn else { return }
        if scanning { startScan(showAll: showAll) }
        if let wanted {
            if let p = peripheral, p.state == .connected { p.discoverServices(nil) } else { connect(wanted) }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        // With "show all", nameless devices (beacons, trackers) are noise.
        if showAll, name == nil { return }
        seen[peripheral.identifier] = peripheral
        onEvent?(.discovered(HamDevice(id: peripheral.identifier, name: name ?? "TNC", rssi: RSSI.intValue == 127 ? 0 : RSSI.intValue)))
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        txChar = nil
        profile = nil
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        onEvent?(.disconnected(error?.localizedDescription ?? "Could not connect to the TNC."))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        txChar = nil
        guard wanted == peripheral.identifier else { return }
        onEvent?(.disconnected(MeshBLETransport.dropReason(error) ?? "The TNC disconnected."))
        central.connect(peripheral, options: nil) // reconnects when it's back
    }
}

extension HamBLETransport: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let services = peripheral.services ?? []
        guard !services.isEmpty else {
            onEvent?(.disconnected(error?.localizedDescription ?? "This device offers no Bluetooth services."))
            return
        }
        pendingServices = services.count
        for s in services { peripheral.discoverCharacteristics(nil, for: s) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        pendingServices -= 1
        if pendingServices == 0 { choose(on: peripheral) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard isNotifyChar(characteristic) else { return }
        if characteristic.isNotifying, txChar != nil {
            onEvent?(.ready(id: peripheral.identifier, name: peripheral.name ?? "TNC"))
        } else if let error {
            onEvent?(.disconnected(error.localizedDescription))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard isNotifyChar(characteristic), let v = characteristic.value else { return }
        onEvent?(.bytes([UInt8](v)))
    }
}
