package org.nomadpocket.ham

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors HamBLEProfileTests.swift. */
class HamBleProfileTest {
    private fun svc(uuid: String, vararg c: Triple<String, Boolean, Boolean>) =
        HamBleServiceInfo(uuid, c.map { HamBleChar(it.first, it.second, it.third) })
    private val gap = svc("1800", Triple("2A00", false, true))
    private val battery = svc("180F", Triple("2A19", true, false))

    @Test fun bleKissWins() {
        val p = HamBleProfile.choose(listOf(gap,
            svc("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", Triple("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", false, true), Triple("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", true, false)),
            svc("00000001-ba2a-46c9-ae49-01b0961f68bb", Triple("00000002-ba2a-46c9-ae49-01b0961f68bb", false, true), Triple("00000003-ba2a-46c9-ae49-01b0961f68bb", true, false))))
        assertEquals(HamBleProfile.BLE_KISS, p)
    }

    @Test fun nordicAndHm10() {
        assertEquals(HamBleProfile.NORDIC_UART, HamBleProfile.choose(listOf(gap,
            svc("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", Triple("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", false, true), Triple("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", true, false)))))
        assertEquals(HamBleProfile.HM10, HamBleProfile.choose(listOf(battery, svc("FFE0", Triple("FFE1", true, true)))))
    }

    @Test fun genericFallback() {
        val p = HamBleProfile.choose(listOf(gap, battery, svc("A1B2C3D4-0000-1000-8000-00AABBCCDDEE",
            Triple("A1B2C3D5-0000-1000-8000-00AABBCCDDEE", true, false), Triple("A1B2C3D6-0000-1000-8000-00AABBCCDDEE", false, true))))!!
        assertEquals("A1B2C3D5-0000-1000-8000-00AABBCCDDEE", p.notify)
        assertEquals("A1B2C3D6-0000-1000-8000-00AABBCCDDEE", p.write)
        assertTrue(p.name.startsWith("Generic serial"))
    }

    @Test fun standardOnlyIsNotATnc() {
        assertNull(HamBleProfile.choose(listOf(gap, battery)))
        assertTrue(HamBleProfile.isStandard("180A"))
        assertFalse(HamBleProfile.isStandard("FFE0"))
        assertEquals(listOf("Service 0000FFE0-0000-1000-8000-00805F9B34FB: FFE1[wn]"), HamBleProfile.describe(listOf(svc("FFE0", Triple("FFE1", true, true)))))
    }
}
