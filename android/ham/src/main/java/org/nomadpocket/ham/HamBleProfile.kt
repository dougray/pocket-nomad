// HamBleProfile.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Which Bluetooth LE service carries KISS on a given TNC or radio. After
// connecting we look at every service and pick, in order: BLE KISS
// (Mobilinkd, Benshi-family radios), Nordic UART, Microchip transparent UART,
// HM-10 FFE0/FFE1 serial, then any vendor service with a notify and a write
// characteristic. Port of HamBLEProfile.swift; pure logic, unit-tested.

package org.nomadpocket.ham

data class HamBleChar(val uuid: String, val notify: Boolean, val write: Boolean)
data class HamBleServiceInfo(val uuid: String, val characteristics: List<HamBleChar>)

data class HamBleProfile(val name: String, val service: String, val write: String, val notify: String) {
    companion object {
        val BLE_KISS = HamBleProfile("BLE KISS", "00000001-BA2A-46C9-AE49-01B0961F68BB",
            "00000002-BA2A-46C9-AE49-01B0961F68BB", "00000003-BA2A-46C9-AE49-01B0961F68BB")
        val NORDIC_UART = HamBleProfile("Nordic UART", "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            "6E400002-B5A3-F393-E0A9-E50E24DCCA9E", "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val MICROCHIP_UART = HamBleProfile("Microchip UART", "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
            "49535343-8841-43F4-A8D4-ECBE34729BB3", "49535343-1E4D-4BD9-BA61-23C647249616")
        val HM10 = HamBleProfile("Serial (FFE0)", "0000FFE0-0000-1000-8000-00805F9B34FB",
            "0000FFE1-0000-1000-8000-00805F9B34FB", "0000FFE1-0000-1000-8000-00805F9B34FB")
        val KNOWN = listOf(BLE_KISS, NORDIC_UART, MICROCHIP_UART, HM10)

        fun normalize(uuid: String): String {
            val u = uuid.uppercase()
            return when (u.length) {
                4 -> "0000$u-0000-1000-8000-00805F9B34FB"
                8 -> "$u-0000-1000-8000-00805F9B34FB"
                else -> u
            }
        }

        /** Bluetooth SIG services (GAP, GATT, battery, device info...) never carry KISS. FExx/FFxx are vendor-assigned. */
        fun isStandard(uuid: String): Boolean {
            val u = normalize(uuid)
            if (!u.endsWith("-0000-1000-8000-00805F9B34FB") || !u.startsWith("0000")) return false
            val short = u.substring(4, 8)
            return !(short.startsWith("FE") || short.startsWith("FF"))
        }

        fun choose(services: List<HamBleServiceInfo>): HamBleProfile? {
            val norm = services.map { s -> HamBleServiceInfo(normalize(s.uuid), s.characteristics.map { it.copy(uuid = normalize(it.uuid)) }) }
            for (p in KNOWN) {
                val s = norm.firstOrNull { it.uuid == p.service } ?: continue
                if (s.characteristics.any { it.uuid == p.write && it.write } && s.characteristics.any { it.uuid == p.notify && it.notify }) return p
            }
            for (s in norm) {
                if (isStandard(s.uuid)) continue
                val n = s.characteristics.firstOrNull { it.notify } ?: continue
                val w = s.characteristics.firstOrNull { it.write && it.uuid != n.uuid } ?: s.characteristics.firstOrNull { it.write } ?: continue
                return HamBleProfile("Generic serial (${s.uuid.take(8)})", s.uuid, w.uuid, n.uuid)
            }
            return null
        }

        fun describe(services: List<HamBleServiceInfo>): List<String> = services.map { s ->
            "Service ${normalize(s.uuid)}: " + s.characteristics.joinToString(" ") { c ->
                c.uuid + "[" + (if (c.write) "w" else "") + (if (c.notify) "n" else "") + "]"
            }
        }
    }
}
