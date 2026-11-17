// HamTransport.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The Bluetooth link to a KISS TNC. Two kinds:
//  - Bluetooth LE "BLE KISS" (Mobilinkd TNC3/4; Benshi-family radios such as
//    the BTech UV-Pro, Vero VR-N76, Radioddity GA-5WB): write to ...0002,
//    notifications from ...0003.
//  - Classic Bluetooth serial (SPP / RFCOMM): Kenwood TH-D74/D75, older
//    Mobilinkd, many hardware TNCs. Pair it in Android's Bluetooth settings first.
// Every callback is moved to the main thread before it reaches the service.
//
// NOT YET TESTED ON A REAL TNC: written to the published UUIDs and SPP.

package org.nomadpocket.ham

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

data class HamDevice(val address: String, val name: String, val rssi: Int, val classic: Boolean)

sealed class HamTransportEvent {
    data class Discovered(val device: HamDevice) : HamTransportEvent()
    data class Connecting(val name: String) : HamTransportEvent()
    data class Ready(val address: String, val name: String) : HamTransportEvent()
    data class Disconnected(val reason: String?) : HamTransportEvent()
    class Bytes(val bytes: ByteArray) : HamTransportEvent()
    /** Diagnostics for the packet monitor (the services a new radio offers). */
    data class Log(val line: String) : HamTransportEvent()
}

interface HamTransport {
    var onEvent: (HamTransportEvent) -> Unit
    /** showAll: every named BLE device, not only known TNC services. */
    fun startScan(showAll: Boolean = false)
    fun stopScan()
    fun connect(device: HamDevice)
    fun disconnect()
    fun write(bytes: ByteArray)
}

object HamBleUuid {
    val SERVICE: UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
    val TX: UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")
    val RX: UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}

@SuppressLint("MissingPermission")
class HamBluetoothTransport(context: Context) : HamTransport {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    override var onEvent: (HamTransportEvent) -> Unit = {}
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var notifyUuid: UUID? = null
    private var showAll = false
    private var mtu = 23
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false
    private var socket: BluetoothSocket? = null
    private var wanted: HamDevice? = null
    private var scanning = false

    private fun emit(e: HamTransportEvent) { main.post { onEvent(e) } }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: safeName(d)
            if (showAll && name == null) return
            emit(HamTransportEvent.Discovered(HamDevice(d.address, result.scanRecord?.deviceName ?: safeName(d) ?: "TNC", result.rssi, false)))
        }
    }

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    override fun startScan(showAll: Boolean) {
        this.showAll = showAll
        stopScan()
        val a = adapter ?: return emit(HamTransportEvent.Disconnected("This phone has no Bluetooth."))
        try {
            // Classic TNCs don't advertise: list the ones paired in Android's settings.
            for (d in a.bondedDevices.orEmpty()) {
                if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || d.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    emit(HamTransportEvent.Discovered(HamDevice(d.address, safeName(d) ?: d.address, 0, true)))
                }
            }
            val filters = if (showAll) emptyList() else HamBleProfile.KNOWN.map {
                ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(it.service))).build()
            }
            a.bluetoothLeScanner?.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true
        } catch (e: SecurityException) {
            emit(HamTransportEvent.Disconnected("Allow Nearby devices for Pocket Nomad to find TNCs."))
        }
    }

    override fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    override fun connect(device: HamDevice) {
        disconnectQuietly()
        wanted = device
        val d = try { adapter?.getRemoteDevice(device.address) } catch (_: Exception) { null }
            ?: return emit(HamTransportEvent.Disconnected("That TNC is not available."))
        emit(HamTransportEvent.Connecting(device.name))
        if (device.classic) connectClassic(d, device) else connectBle(d)
    }

    private fun connectClassic(d: BluetoothDevice, device: HamDevice) {
        Thread {
            try {
                stopScan()
                val s = d.createRfcommSocketToServiceRecord(HamBleUuid.SPP)
                s.connect()
                socket = s
                emit(HamTransportEvent.Ready(device.address, device.name))
                val buf = ByteArray(1024)
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) { val chunk = buf.copyOf(n); emit(HamTransportEvent.Bytes(chunk)) }
                }
                if (socket === s) emit(HamTransportEvent.Disconnected("The TNC closed the connection."))
            } catch (e: Exception) {
                if (wanted == device) emit(HamTransportEvent.Disconnected("Could not connect: ${e.message ?: "the TNC didn't answer"}. Pair it in Android's Bluetooth settings and turn on its KISS mode."))
            }
        }.apply { isDaemon = true; name = "ham-spp" }.start()
    }

    private fun connectBle(d: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= 23) d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
               else d.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    tx = null
                    g.close()
                    if (gatt === g) {
                        gatt = null
                        onEvent(HamTransportEvent.Disconnected(if (status == 0) "The TNC disconnected." else "Bluetooth error $status."))
                        // Reconnect when it's back in range.
                        wanted?.takeIf { !it.classic }?.let { w -> adapter?.getRemoteDevice(w.address)?.let { connectBle(it) } }
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post { mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23; g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val infos = g.services.map { svc ->
                    HamBleServiceInfo(svc.uuid.toString(), svc.characteristics.map { c ->
                        val p = c.properties
                        HamBleChar(c.uuid.toString(),
                            p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                            p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    })
                }
                for (line in HamBleProfile.describe(infos)) onEvent(HamTransportEvent.Log(line))
                val profile = HamBleProfile.choose(infos)
                if (profile == null) {
                    onEvent(HamTransportEvent.Disconnected("No KISS or serial service on this device. Check that the radio's KISS TNC mode is on."))
                    return@post
                }
                onEvent(HamTransportEvent.Log("Using ${profile.name}"))
                val s = g.getService(UUID.fromString(profile.service))
                tx = s?.getCharacteristic(UUID.fromString(profile.write))
                val rx = s?.getCharacteristic(UUID.fromString(profile.notify))
                notifyUuid = rx?.uuid
                if (rx == null || tx == null) {
                    onEvent(HamTransportEvent.Disconnected("This device's serial service is incomplete."))
                    return@post
                }
                g.setCharacteristicNotification(rx, true)
                val desc = rx.getDescriptor(HamBleUuid.CCCD)
                if (desc == null) { ready(g); return@post }
                val value = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(desc, value)
                else @Suppress("DEPRECATION") run { desc.value = value; g.writeDescriptor(desc) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { ready(g) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            if (Build.VERSION.SDK_INT < 33 && c.uuid == notifyUuid) emit(HamTransportEvent.Bytes(v.copyOf()))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == notifyUuid) emit(HamTransportEvent.Bytes(value.copyOf()))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { writing = false; drain() }
        }
    }

    private fun ready(g: BluetoothGatt) {
        stopScan()
        onEvent(HamTransportEvent.Ready(g.device.address, safeName(g.device) ?: "TNC"))
    }

    override fun write(bytes: ByteArray) {
        socket?.let { s ->
            Thread { try { s.outputStream.write(bytes); s.outputStream.flush() } catch (_: Exception) {} }.start()
            return
        }
        val chunk = maxOf(20, mtu - 3)
        var i = 0
        while (i < bytes.size) { val end = minOf(i + chunk, bytes.size); writes.addLast(bytes.copyOfRange(i, end)); i = end }
        drain()
    }

    private fun drain() {
        if (writing) return
        val g = gatt ?: return
        val c = tx ?: return
        val next = writes.removeFirstOrNull() ?: return
        writing = true
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, next, type) == 0
                 else @Suppress("DEPRECATION") run { c.writeType = type; c.value = next; g.writeCharacteristic(c) }
        if (!ok) { writing = false; writes.addFirst(next); main.postDelayed({ drain() }, 20) }
    }

    private fun disconnectQuietly() {
        val g = gatt
        gatt = null
        tx = null
        try { g?.disconnect(); g?.close() } catch (_: Exception) {}
        val s = socket
        socket = null
        try { s?.close() } catch (_: Exception) {}
        writes.clear()
        writing = false
    }

    override fun disconnect() {
        wanted = null
        disconnectQuietly()
        onEvent(HamTransportEvent.Disconnected(null))
    }
}
