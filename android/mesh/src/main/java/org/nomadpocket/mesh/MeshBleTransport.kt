// MeshBleTransport.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Android Bluetooth LE implementation of MeshTransport for Meshtastic and
// MeshCore radios. Port of MeshBLETransport.swift. Meshtastic UUIDs are from
// its public client API documentation; MeshCore uses the Nordic UART service
// (docs/companion_protocol.md).
//
// Android allows one GATT operation at a time, so reads, writes and
// notification subscriptions go through a small queue. Every callback is moved
// to the main thread before it touches state or emits an event.
//
// NOT YET TESTED ON A REAL RADIO: the logic mirrors the iOS transport, which
// has been, but Android BLE stacks differ by phone. Watch for status 133 on
// connect, pairing prompts and MTU negotiation when testing.

package org.nomadpocket.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

object MeshBleUuid {
    val SERVICE: UUID = UUID.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD")
    /** write: one ToRadio protobuf per write. */
    val TO_RADIO: UUID = UUID.fromString("F75C76D2-129E-4DAD-A1DD-7866124401E7")
    /** read: one FromRadio protobuf per read, empty when drained. Some firmware also notifies. */
    val FROM_RADIO: UUID = UUID.fromString("2C55E69E-4993-11ED-B878-0242AC120002")
    /** read/notify/write: packet counter, notifies when FromRadio has data. */
    val FROM_NUM: UUID = UUID.fromString("ED9DA18C-A800-4F66-A670-AA7547E34453")
    /** notify: LogRecord protobufs (debug logs). Not subscribed on Android. */
    val LOG_RECORD: UUID = UUID.fromString("5A3D6E49-06E6-4423-9944-E9DE8CDF9547")

    val MESHCORE_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    /** write: one command frame per write. */
    val MESHCORE_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    /** notify: one response or push frame per notification. */
    val MESHCORE_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Client Characteristic Configuration descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Which firmware a set of advertised service UUIDs points to (auto-detect). */
    fun firmwareFor(services: Collection<UUID>): Firmware? = when {
        services.contains(MESHCORE_SERVICE) && !services.contains(SERVICE) -> Firmware.MESHCORE
        services.contains(SERVICE) -> Firmware.MESHTASTIC
        else -> null
    }
}

@SuppressLint("MissingPermission") // every entry point checks hasConnect()/hasScan() first
class MeshBleTransport(context: Context) : MeshTransport {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager: BluetoothManager? = this.context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    override var onEvent: ((MeshTransportEvent) -> Unit)? = null
    override var availability = MeshBluetoothAvailability.UNKNOWN
        private set

    private var started = false
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var linkUp = false
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null
    private var coreRX: BluetoothGattCharacteristic? = null
    private var coreTX: BluetoothGattCharacteristic? = null
    private var coreNotifying = false
    private val seen = mutableMapOf<String, BluetoothDevice>()
    /** The device we want to stay connected to. null after a user disconnect. */
    private var wantedID: String? = null
    private var wantsScan = false
    private var scanning = false
    private var readyAnnounced = false
    private var servicesRequested = false

    // MARK: GATT operation queue

    private sealed class Op {
        class Write(val c: BluetoothGattCharacteristic, val value: ByteArray) : Op()
        class Read(val c: BluetoothGattCharacteristic) : Op()
        class Notify(val c: BluetoothGattCharacteristic) : Op()
    }
    private val ops = ArrayDeque<Op>()
    private var current: Op? = null
    // A stuck operation must not block the link; a lost FromRadio read ends that read loop.
    private val watchdog = Runnable {
        (current as? Op.Read)?.let { if (it.c.uuid == MeshBleUuid.FROM_RADIO) emit(MeshTransportEvent.FromRadio(ByteArray(0))) }
        finishOp()
    }

    private fun enqueue(op: Op) {
        ops.addLast(op)
        runNext()
    }

    private fun runNext() {
        if (current != null) return
        val g = gatt ?: run { ops.clear(); return }
        val op = ops.removeFirstOrNull() ?: return
        current = op
        main.postDelayed(watchdog, 10_000)
        val ok = try {
            when (op) {
                is Op.Write -> writeCharacteristic(g, op.c, op.value)
                is Op.Read -> g.readCharacteristic(op.c)
                is Op.Notify -> {
                    g.setCharacteristicNotification(op.c, true)
                    val d = op.c.getDescriptor(MeshBleUuid.CCCD)
                    if (d == null) false else writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
        } catch (_: SecurityException) { false }
        if (!ok) {
            when (op) {
                is Op.Write -> emit(MeshTransportEvent.WriteFailed("The radio did not accept the data."))
                is Op.Read -> if (op.c.uuid == MeshBleUuid.FROM_RADIO) emit(MeshTransportEvent.FromRadio(ByteArray(0)))
                is Op.Notify -> notifyDone(op.c, ok = false, status = BluetoothGatt.GATT_FAILURE)
            }
            finishOp()
        }
    }

    private fun finishOp() {
        main.removeCallbacks(watchdog)
        current = null
        runNext()
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // with response, as on iOS
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    // MARK: MeshTransport

    override fun start() {
        if (started) { updateAvailability(); return }
        started = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        updateAvailability()
    }

    override fun startScan() {
        start()
        wantsScan = true
        if (availability != MeshBluetoothAvailability.POWERED_ON || !hasScan()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        val filters = listOf(MeshBleUuid.SERVICE, MeshBleUuid.MESHCORE_SERVICE).map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (_: SecurityException) {
            updateAvailability()
        }
        // Radios already linked to the phone (for example, by another app) do not
        // advertise, so list those too when the phone remembers their services.
        try {
            for (d in manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()) {
                val fw = MeshBleUuid.firmwareFor(d.uuids.orEmpty().map { it.uuid }) ?: continue
                seen[d.address] = d
                emit(MeshTransportEvent.Discovered(MeshDiscoveredDevice(d.address, d.name ?: "${fw.displayName} radio",
                    0, System.currentTimeMillis(), fw)))
            }
        } catch (_: SecurityException) {}
    }

    override fun stopScan() {
        wantsScan = false
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    override fun connect(id: String) {
        start()
        wantedID = id
        if (availability != MeshBluetoothAvailability.POWERED_ON || !hasConnect()) return // retried when Bluetooth comes on
        val current = device
        if (current != null && current.address != id) {
            closeGatt()
        }
        val target = seen[id] ?: if (BluetoothAdapter.checkBluetoothAddress(id)) adapter?.getRemoteDevice(id) else null
        if (target == null) {
            emit(MeshTransportEvent.ConnectFailed(id, "That radio is not nearby. Turn it on and scan again."))
            return
        }
        device = target
        emit(MeshTransportEvent.Connecting(id, nameOf(target, "Mesh radio")))
        if (gatt != null && linkUp) {
            discover()
            return
        }
        // A radio seen in this scan connects directly; otherwise ask Android to
        // connect whenever it comes into range (like iOS's pending connect).
        openGatt(target, autoConnect = !seen.containsKey(id))
    }

    override fun disconnect() {
        wantedID = null
        val id = device?.address
        closeGatt()
        if (id != null) emit(MeshTransportEvent.Disconnected(id, null, false))
        device = null
    }

    override fun write(toRadio: ByteArray) {
        val target = this.toRadio ?: coreRX
        if (gatt == null || target == null) {
            emit(MeshTransportEvent.WriteFailed("Not connected to a radio."))
            return
        }
        enqueue(Op.Write(target, toRadio))
    }

    override fun readFromRadio() {
        val c = fromRadio ?: return
        if (gatt == null) return
        enqueue(Op.Read(c))
    }

    // MARK: Helpers

    private fun emit(event: MeshTransportEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) onEvent?.invoke(event) else main.post { onEvent?.invoke(event) }
    }

    private fun hasConnect(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScan(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun nameOf(d: BluetoothDevice, fallback: String): String =
        try { d.name } catch (_: SecurityException) { null } ?: fallback

    /** Re-checks Bluetooth state and permission (call after the user grants permission). */
    fun updateAvailability() {
        val a = adapter
        val new = when {
            a == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> MeshBluetoothAvailability.UNSUPPORTED
            !hasConnect() -> MeshBluetoothAvailability.UNAUTHORIZED
            else -> when (a.state) {
                BluetoothAdapter.STATE_ON -> MeshBluetoothAvailability.POWERED_ON
                BluetoothAdapter.STATE_OFF -> MeshBluetoothAvailability.POWERED_OFF
                BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> MeshBluetoothAvailability.RESETTING
                else -> MeshBluetoothAvailability.UNKNOWN
            }
        }
        val changed = new != availability
        availability = new
        emit(MeshTransportEvent.Availability(new))
        if (!changed) return
        if (new == MeshBluetoothAvailability.POWERED_ON) {
            if (wantsScan) startScan()
            wantedID?.let { if (gatt == null) connect(it) }
        } else {
            scanning = false
            if (new == MeshBluetoothAvailability.POWERED_OFF || new == MeshBluetoothAvailability.RESETTING) {
                val id = device?.address
                val hadLink = gatt != null
                closeGatt()
                if (hadLink && id != null) emit(MeshTransportEvent.Disconnected(id, "Bluetooth turned off.", wantedID == id))
            }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { main.post { updateAvailability() } }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onScan(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { onScan(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false }
        }
    }

    private fun onScan(result: ScanResult) {
        val d = result.device
        seen[d.address] = d
        val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        val fw = MeshBleUuid.firmwareFor(services) ?: Firmware.MESHTASTIC
        val name = result.scanRecord?.deviceName ?: nameOf(d, "${fw.displayName} radio")
        emit(MeshTransportEvent.Discovered(MeshDiscoveredDevice(d.address, name, result.rssi, System.currentTimeMillis(), fw)))
    }

    private fun openGatt(target: BluetoothDevice, autoConnect: Boolean) {
        closeGatt()
        device = target
        gatt = try {
            @Suppress("DEPRECATION") // the replacement needs API 37; this works on 26+
            target.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            updateAvailability()
            null
        }
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        clearLink()
        try { g?.disconnect(); g?.close() } catch (_: SecurityException) {}
    }

    private fun clearLink() {
        linkUp = false
        toRadio = null; fromRadio = null; fromNum = null; coreRX = null; coreTX = null
        coreNotifying = false
        readyAnnounced = false
        servicesRequested = false
        ops.clear()
        current = null
        main.removeCallbacks(watchdog)
    }

    private fun discover() {
        if (servicesRequested) return
        servicesRequested = true
        try { gatt?.discoverServices() } catch (_: SecurityException) {}
    }

    private fun announceReadyIfPossible() {
        if (readyAnnounced) return
        val d = device ?: return
        if (toRadio != null && fromRadio != null) {
            readyAnnounced = true
            emit(MeshTransportEvent.Ready(d.address, nameOf(d, "Meshtastic radio"), Firmware.MESHTASTIC))
        } else if (coreRX != null && coreNotifying) {
            readyAnnounced = true
            emit(MeshTransportEvent.Ready(d.address, nameOf(d, "MeshCore radio"), Firmware.MESHCORE))
        }
    }

    private fun notifyDone(c: BluetoothGattCharacteristic, ok: Boolean, status: Int) {
        if (!ok) pairingMessage(status)?.let { emit(MeshTransportEvent.PairingProblem(it)) }
        when (c.uuid) {
            // Without notifications we can still poll; carry on.
            MeshBleUuid.FROM_NUM -> announceReadyIfPossible()
            MeshBleUuid.MESHCORE_TX -> if (ok) { coreNotifying = true; announceReadyIfPossible() }
        }
    }

    /** Explains Bluetooth security errors so the UI can help with pairing. */
    private fun pairingMessage(status: Int): String? = when (status) {
        5, 8, 15 -> "Pairing did not finish. Enter the PIN shown on the radio's screen when Android asks (radios with no screen often use 123456). If it keeps failing, open Settings > Connected devices, forget the radio, and connect again."
        137 -> "Pairing failed. The radio may have forgotten this phone: open Settings > Connected devices, forget the radio, then connect again."
        else -> null
    }

    // MARK: GATT callbacks (binder thread: hop to main first)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { onConnectionState(g, status, newState) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g == gatt) discover() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { if (g == gatt) onServices(g, status) }
        }

        @Deprecated("Below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val value = c.value ?: ByteArray(0)
            main.post { if (g == gatt) onRead(c, value, status) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (g == gatt) onRead(c, value, status) }
        }

        @Deprecated("Below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val value = c.value ?: ByteArray(0)
            main.post { if (g == gatt) onChanged(c, value) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g == gatt) onChanged(c, value) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emit(pairingMessage(status)?.let { MeshTransportEvent.PairingProblem(it) }
                        ?: MeshTransportEvent.WriteFailed("Bluetooth error $status"))
                }
                finishOp()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                notifyDone(d.characteristic, status == BluetoothGatt.GATT_SUCCESS, status)
                finishOp()
            }
        }
    }

    private fun onConnectionState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g != gatt) { try { g.close() } catch (_: SecurityException) {}; return }
        val d = device ?: return
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (d.address != wantedID) { closeGatt(); return }
            clearLink()
            linkUp = true
            try {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // Meshtastic packets need more than the default 23-byte MTU.
                if (!g.requestMtu(512)) discover()
                // Some phones never answer the MTU request; carry on regardless.
                main.postDelayed({ if (g == gatt && linkUp) discover() }, 3_000)
            } catch (_: SecurityException) {}
            return
        }
        if (newState != BluetoothProfile.STATE_DISCONNECTED) return
        val wasLinked = linkUp
        closeGatt()
        val reconnect = d.address == wantedID
        pairingMessage(status)?.let { emit(MeshTransportEvent.PairingProblem(it)) }
        if (wasLinked) {
            emit(MeshTransportEvent.Disconnected(d.address, if (status == BluetoothGatt.GATT_SUCCESS) null else "Link lost (Bluetooth status $status).", reconnect))
        } else {
            emit(MeshTransportEvent.ConnectFailed(d.address, "Could not connect (Bluetooth status $status)."))
        }
        // Keep trying in the background: Android finishes it when the radio is back in range.
        if (reconnect && availability == MeshBluetoothAvailability.POWERED_ON) openGatt(d, autoConnect = true)
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        val d = device ?: return
        val core = g.getService(MeshBleUuid.MESHCORE_SERVICE)
        val mesh = g.getService(MeshBleUuid.SERVICE)
        if (core != null && mesh == null) {
            coreRX = core.getCharacteristic(MeshBleUuid.MESHCORE_RX)
            coreTX = core.getCharacteristic(MeshBleUuid.MESHCORE_TX)
            val tx = coreTX
            if (coreRX == null || tx == null) {
                emit(MeshTransportEvent.ConnectFailed(d.address, "This MeshCore radio is missing its companion service. Flash the Companion (Bluetooth) firmware."))
                return
            }
            // Subscribing is what makes Android ask for the radio's PIN.
            enqueue(Op.Notify(tx))
            return
        }
        if (mesh == null) {
            emit(MeshTransportEvent.ConnectFailed(d.address,
                if (status != BluetoothGatt.GATT_SUCCESS) "Bluetooth error $status" else "This device is not a Meshtastic or MeshCore radio."))
            return
        }
        toRadio = mesh.getCharacteristic(MeshBleUuid.TO_RADIO)
        fromRadio = mesh.getCharacteristic(MeshBleUuid.FROM_RADIO)
        fromNum = mesh.getCharacteristic(MeshBleUuid.FROM_NUM)
        if (toRadio == null || fromRadio == null) {
            emit(MeshTransportEvent.ConnectFailed(d.address, "This radio's firmware is too old or not Meshtastic."))
            return
        }
        fromRadio?.let { if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) enqueue(Op.Notify(it)) }
        // The characteristic is encrypted, so this is usually what makes Android
        // show the pairing (PIN) prompt. Ready is announced once it completes.
        fromNum?.let { enqueue(Op.Notify(it)) } ?: announceReadyIfPossible()
    }

    private fun onRead(c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pairingMessage(status)?.let { emit(MeshTransportEvent.PairingProblem(it)) }
            if (c.uuid == MeshBleUuid.FROM_RADIO) emit(MeshTransportEvent.FromRadio(ByteArray(0))) // end this read loop
        } else if (c.uuid == MeshBleUuid.FROM_RADIO) {
            emit(MeshTransportEvent.FromRadio(value))
        }
        finishOp()
    }

    private fun onChanged(c: BluetoothGattCharacteristic, value: ByteArray) {
        when (c.uuid) {
            MeshBleUuid.FROM_RADIO -> if (value.isNotEmpty()) emit(MeshTransportEvent.FromRadio(value))
            MeshBleUuid.FROM_NUM -> emit(MeshTransportEvent.FromNumChanged)
            MeshBleUuid.MESHCORE_TX -> if (value.isNotEmpty()) emit(MeshTransportEvent.Frame(value))
        }
    }
}
