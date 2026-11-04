// MeshTransport.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The link between MeshService and a radio. The real one is Bluetooth LE
// (MeshBleTransport); tests use a fake that plays back a scripted radio.
//
// Contract, matching Meshtastic's BLE client API:
//  - `write` sends one encoded ToRadio (Meshtastic) or one command frame (MeshCore).
//  - `readFromRadio` asks for one FromRadio; the answer arrives as
//    `FromRadio(data)`. Empty data means the radio's queue is drained.
//  - `FromNumChanged` means the radio has new data waiting: read again.
//
// Events must be delivered on the main thread.

package org.nomadpocket.mesh

sealed class MeshTransportEvent {
    data class Availability(val availability: MeshBluetoothAvailability) : MeshTransportEvent()
    data class Discovered(val device: MeshDiscoveredDevice) : MeshTransportEvent()
    data class Connecting(val id: String, val name: String) : MeshTransportEvent()
    /** Characteristics found and notifications on: ready for the handshake. */
    data class Ready(val id: String, val name: String, val firmware: Firmware = Firmware.MESHTASTIC) : MeshTransportEvent()
    data class Disconnected(val id: String, val reason: String?, val willReconnect: Boolean) : MeshTransportEvent()
    data class ConnectFailed(val id: String, val reason: String) : MeshTransportEvent()
    class FromRadio(val data: ByteArray) : MeshTransportEvent()
    object FromNumChanged : MeshTransportEvent()
    /** MeshCore: one companion-protocol frame from the radio. */
    class Frame(val data: ByteArray) : MeshTransportEvent()
    /**
     * Bluetooth refused a read/write because the device is not paired yet, or
     * the pairing was removed on one side.
     */
    data class PairingProblem(val message: String) : MeshTransportEvent()
    data class WriteFailed(val message: String) : MeshTransportEvent()
}

interface MeshTransport {
    var onEvent: ((MeshTransportEvent) -> Unit)?
    val availability: MeshBluetoothAvailability

    /** Brings the transport up. Safe to call more than once. */
    fun start()
    fun startScan()
    fun stopScan()
    /** Connects and keeps trying until the device appears. */
    fun connect(id: String)
    /** Drops the link and stops any automatic reconnect. */
    fun disconnect()
    /** Meshtastic: one ToRadio. MeshCore: one command frame. */
    fun write(toRadio: ByteArray)
    fun readFromRadio()
}
