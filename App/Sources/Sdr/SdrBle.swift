// SdrBle.swift
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// Bluetooth LE link to an SDR box: the same JSON-lines protocol as the Wi-Fi link, over a GATT service.
// Not tested with a real box yet: the framing is shared with (and tested through) the Wi-Fi link.

@preconcurrency import CoreBluetooth
import Foundation

struct SdrBleDevice: Identifiable, Equatable {
    let id: UUID
    let name: String
    let rssi: Int
}

@MainActor
final class SdrBle: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "5D000001-7D1C-4A6E-8B52-0F3E9A4C6D21")
    static let events = CBUUID(string: "5D000002-7D1C-4A6E-8B52-0F3E9A4C6D21")
    static let commands = CBUUID(string: "5D000003-7D1C-4A6E-8B52-0F3E9A4C6D21")

    var onDevices: (([SdrBleDevice]) -> Void)?
    var onData: ((Data) -> Void)?
    /// Called with .connected once notifications are on, .failed(message) on a problem, .idle when disconnected.
    var onState: ((SdrClient.Link) -> Void)?

    private var central: CBCentralManager?
    private var found: [UUID: (CBPeripheral, Int)] = [:]
    private var peripheral: CBPeripheral?
    private var commandsChar: CBCharacteristic?
    private var wantScan = false

    private func manager() -> CBCentralManager {
        if let c = central { return c }
        let c = CBCentralManager(delegate: self, queue: .main)
        central = c
        return c
    }

    func startScan() {
        wantScan = true
        found = [:]
        let c = manager()
        if c.state == .poweredOn { c.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]) }
    }

    func stopScan() { wantScan = false; central?.stopScan() }

    func connect(_ device: SdrBleDevice) {
        guard let (p, _) = found[device.id] else { onState?(.failed("That box isn't in range any more. Scan again.")); return }
        stopScan()
        peripheral = p
        p.delegate = self
        onState?(.connecting)
        manager().connect(p)
    }

    func disconnect() {
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil; commandsChar = nil
    }

    /// Write one protocol line, split to what the link allows per write.
    func write(_ data: Data) {
        guard let p = peripheral, let c = commandsChar else { return }
        let size = max(20, p.maximumWriteValueLength(for: .withoutResponse))
        var i = 0
        while i < data.count {
            p.writeValue(data.subdata(in: i..<min(i + size, data.count)), for: c, type: .withoutResponse)
            i += size
        }
    }

    // MARK: Central

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn: if wantScan { startScan() }
            case .unauthorized: onState?(.failed("Allow Bluetooth for Pocket Nomad in Settings."))
            case .poweredOff: onState?(.failed("Bluetooth is off."))
            default: break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let rssi = RSSI.intValue
        nonisolated(unsafe) let peripheral = peripheral   // CoreBluetooth objects are only touched on the main queue
        MainActor.assumeIsolated {
            found[peripheral.identifier] = (peripheral, rssi)
            let name = advName ?? peripheral.name ?? "SDR box"
            onDevices?(found.map { SdrBleDevice(id: $0.key, name: ($0.value.0.name ?? name), rssi: $0.value.1) }.sorted { $0.rssi > $1.rssi })
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        MainActor.assumeIsolated { peripheral.discoverServices([Self.service]) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { onState?(.failed("Couldn't connect to the box.")) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { self.peripheral = nil; commandsChar = nil; onState?(error == nil ? .idle : .failed("The box disconnected.")) }
    }

    // MARK: Peripheral

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let s = peripheral.services?.first(where: { $0.uuid == Self.service }) else { onState?(.failed("That device isn't an SDR box.")); return }
            peripheral.discoverCharacteristics([Self.events, Self.commands], for: s)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] {
                if c.uuid == Self.events { peripheral.setNotifyValue(true, for: c) }
                if c.uuid == Self.commands { commandsChar = c }
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if characteristic.uuid == Self.events, characteristic.isNotifying { onState?(.connected) }
            else if let error { onState?(.failed("Bluetooth: \(error.localizedDescription)")) }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated { if let d = characteristic.value, characteristic.uuid == Self.events { onData?(d) } }
    }
}
