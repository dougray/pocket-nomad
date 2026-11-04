// MeshTransport.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The link between MeshService and a radio. The real one is Bluetooth LE
// (MeshBLETransport); tests use a fake that plays back a scripted radio.
//
// Contract, matching Meshtastic's BLE client API:
//  - `write` sends one encoded ToRadio.
//  - `readFromRadio` asks for one FromRadio; the answer arrives as
//    `.fromRadio(data)`. Empty data means the radio's queue is drained.
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
    case fromRadio(Data)
    case fromNumChanged
    /// MeshCore: one companion-protocol frame from the radio.
    case frame(Data)
    /// Bluetooth refused a read/write because the device is not paired yet,
    /// or the pairing was removed on one side.
    case pairingProblem(String)
    case writeFailed(String)
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
    func connect(id: UUID)
    /// Drops the link and stops any automatic reconnect.
    func disconnect()
    /// Meshtastic: one ToRadio. MeshCore: one command frame.
    func write(_ toRadio: Data)
    func readFromRadio()
}
