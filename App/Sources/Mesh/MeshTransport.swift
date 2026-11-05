// MeshTransport.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The link between MeshService and its radios. The real one is Bluetooth LE
// (MeshBLETransport); tests use a fake that plays back scripted radios.
//
// One transport holds up to two radios at once (one Meshtastic, one MeshCore),
// so every call and every event names the radio by its Bluetooth identifier.
//
// Contract, matching Meshtastic's BLE client API:
//  - `write` sends one encoded ToRadio.
//  - `readFromRadio` asks for one FromRadio; the answer arrives as
//    `.fromRadio(id:data)`. Empty data means the radio's queue is drained.
//  - `.fromNumChanged` means the radio has new data waiting: read again.

import Foundation

enum MeshTransportEvent: Sendable {
    case availability(MeshBluetoothAvailability)
    case discovered(MeshDiscoveredDevice)
    case connecting(id: UUID, name: String)
    /// Characteristics found and notifications on: ready for the handshake.
    case ready(id: UUID, name: String, firmware: MeshFirmware = .meshtastic)
    case disconnected(id: UUID, reason: String?, willReconnect: Bool)
    case connectFailed(id: UUID, reason: String)
    case fromRadio(id: UUID, Data)
    case fromNumChanged(id: UUID)
    /// MeshCore: one companion-protocol frame from the radio.
    case frame(id: UUID, Data)
    /// Bluetooth refused a read/write because the device is not paired yet,
    /// or the pairing was removed on one side.
    case pairingProblem(id: UUID, String)
    case writeFailed(id: UUID, String)
}

@MainActor
protocol MeshTransport: AnyObject {
    var onEvent: ((MeshTransportEvent) -> Void)? { get set }
    var availability: MeshBluetoothAvailability { get }

    /// Brings the transport up (for BLE: creates the central manager, which
    /// also triggers state restoration). Safe to call more than once.
    func start()
    func startScan()
    func stopScan()
    /// Connects and keeps the connection pending until the device appears.
    /// Other connected radios stay connected.
    func connect(id: UUID)
    /// Drops that radio's link and stops its automatic reconnect.
    func disconnect(id: UUID)
    /// Meshtastic: one ToRadio. MeshCore: one command frame.
    func write(_ toRadio: Data, to id: UUID)
    func readFromRadio(id: UUID)
}
