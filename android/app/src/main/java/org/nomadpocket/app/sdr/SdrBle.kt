// SdrBle.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0
//
// Bluetooth LE link to an SDR box: the same JSON-lines protocol as the Wi-Fi link, over a GATT service.
// Not tested with a real box yet. Port of SdrBle.swift.

package org.nomadpocket.app.sdr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
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

data class SdrBleDevice(val address: String, val name: String, val rssi: Int)

/** Splits one protocol line into pieces the link can carry per write. */
object SdrChunker {
    fun split(data: ByteArray, size: Int): List<ByteArray> {
        val n = maxOf(20, size)
        if (data.isEmpty()) return listOf(data)
        return (data.indices step n).map { data.copyOfRange(it, minOf(it + n, data.size)) }
    }
}

@SuppressLint("MissingPermission")   // the caller checks the Nearby devices permission before scanning or connecting
class SdrBle(private val context: Context, private val onDevices: (List<SdrBleDevice>) -> Unit, private val onData: (ByteArray) -> Unit,
             private val onState: (SdrLink) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, Pair<BluetoothDevice, SdrBleDevice>>()
    private var gatt: BluetoothGatt? = null
    private var commands: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var scanning = false
    private val writes = ArrayDeque<ByteArray>()
    private var writing = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: "SDR box"
            found[r.device.address] = r.device to SdrBleDevice(r.device.address, name, r.rssi)
            onDevices(found.values.map { it.second }.sortedByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) { main.post { onState(SdrLink.Failed("Bluetooth scan failed ($errorCode).")) } }
    }

    fun startScan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run { onState(SdrLink.Failed("Bluetooth is off.")); return }
        found.clear()
        if (scanning) scanner.stopScan(scanCallback)
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        scanning = true
    }

    fun stopScan() { if (scanning) { runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }; scanning = false } }

    fun connect(device: SdrBleDevice) {
        val d = found[device.address]?.first ?: run { onState(SdrLink.Failed("That box isn't in range any more. Scan again.")); return }
        stopScan()
        onState(SdrLink.Connecting)
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; commands = null; writes.clear(); writing = false
    }

    /** Write one protocol line, split to the negotiated size. */
    fun write(data: ByteArray) {
        SdrChunker.split(data, mtu - 3).forEach { writes.addLast(it) }
        drain()
    }

    private fun drain() {
        if (writing) return
        val g = gatt ?: return
        val c = commands ?: return
        val next = writes.removeFirstOrNull() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = next; g.writeCharacteristic(c) }
        if (!ok) { writing = false; writes.addFirst(next); main.postDelayed({ drain() }, 20) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) g.requestMtu(185)
            else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                main.post { gatt = null; commands = null; onState(if (status == 0) SdrLink.Idle else SdrLink.Failed("The box disconnected.")) }
                g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { if (status == 0) this@SdrBle.mtu = mtu; g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(SERVICE) ?: run { main.post { onState(SdrLink.Failed("That device isn't an SDR box.")) }; return }
            commands = s.getCharacteristic(COMMANDS)
            val events = s.getCharacteristic(EVENTS) ?: return
            g.setCharacteristicNotification(events, true)
            val cccd = events.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { onState(if (status == 0) SdrLink.Connected else SdrLink.Failed("Bluetooth: couldn't turn on notifications.")) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { onData(value) }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) onData(c.value ?: return) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writing = false; drain() }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("5d000001-7d1c-4a6e-8b52-0f3e9a4c6d21")
        val EVENTS: UUID = UUID.fromString("5d000002-7d1c-4a6e-8b52-0f3e9a4c6d21")
        val COMMANDS: UUID = UUID.fromString("5d000003-7d1c-4a6e-8b52-0f3e9a4c6d21")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
