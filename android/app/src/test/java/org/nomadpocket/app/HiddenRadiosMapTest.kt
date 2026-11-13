// "This is mine: hide it from the map" on the community-map layer, and the
// one-time switch that turns the drive-trail coverage layer off.
package org.nomadpocket.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nomadpocket.app.map.CoverageLayer
import org.nomadpocket.app.map.KnownRadio
import org.nomadpocket.app.map.KnownRadioParser
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.HiddenRadios
import org.nomadpocket.mesh.MemoryMeshPrefs
import org.nomadpocket.mesh.OwnRadios
import org.nomadpocket.mesh.plus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HiddenRadiosMapTest {
    private val radios = listOf(
        KnownRadio("t1002094074", Firmware.MESHTASTIC, "My Other Phone (test)", KnownRadio.Role.CLIENT, 39.74, -104.99),
        KnownRadio("t55", Firmware.MESHTASTIC, "Someone", KnownRadio.Role.CLIENT, 39.75, -104.98),
        KnownRadio("c3F09101112131415", Firmware.MESHCORE, "My Hill Repeater (test)", KnownRadio.Role.REPEATER, 39.73, -104.97),
        KnownRadio("c3f0a000000000000", Firmware.MESHCORE, "Same first byte", KnownRadio.Role.REPEATER, 39.72, -104.96),
    )

    @Test fun communityIdsBecomeHiddenEntries() {
        val t = KnownRadioParser.hiddenRadio(radios[0])!!
        assertEquals(Firmware.MESHTASTIC, t.firmware)
        assertEquals(1002094074L, t.num)
        assertNull(t.keyHex)
        val c = KnownRadioParser.hiddenRadio(radios[2])!!
        assertEquals(Firmware.MESHCORE, c.firmware)
        assertEquals("3f09101112131415", c.keyHex)
        assertEquals(0x1110_093FL, c.num)
        assertNull(KnownRadioParser.hiddenRadio(KnownRadio("tx", Firmware.MESHTASTIC, "Bad", KnownRadio.Role.CLIENT, 0.0, 0.0)))
        assertNull(KnownRadioParser.hiddenRadio(KnownRadio("c3f", Firmware.MESHCORE, "Short", KnownRadio.Role.CLIENT, 0.0, 0.0)))
    }

    @Test fun hiddenCommunityRadiosAreFiltered() {
        val hidden = HiddenRadios(MemoryMeshPrefs())
        assertEquals(radios, KnownRadioParser.withoutOwn(radios, OwnRadios() + hidden.own))
        hidden.hide(KnownRadioParser.hiddenRadio(radios[0])!!)
        hidden.hide(KnownRadioParser.hiddenRadio(radios[2])!!)
        // t<num> by number; c<hex> by lowercase key prefix, never just a shared first byte.
        assertEquals(listOf("t55", "c3f0a000000000000"), KnownRadioParser.withoutOwn(radios, OwnRadios() + hidden.own).map { it.id })

        // A MeshCore radio hidden from a contact (6-byte key) still matches its longer community id.
        val fromContact = HiddenRadios(MemoryMeshPrefs())
        fromContact.hide(org.nomadpocket.mesh.HiddenRadio(Firmware.MESHCORE, 0x1110_093FL, "My Hill Repeater", "3F0910111213"))
        assertEquals(listOf("t1002094074", "t55", "c3f0a000000000000"),
            KnownRadioParser.withoutOwn(radios, fromContact.own).map { it.id })
        // Unhiding brings it back.
        hidden.unhide(Firmware.MESHTASTIC, 1002094074L)
        assertTrue(KnownRadioParser.withoutOwn(radios, hidden.own).any { it.id == "t1002094074" })
    }

    @Test fun trailTurnsOffOnceThenRespectsTheChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("map-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(CoverageLayer.PREF_KEY, "recorded").commit()

        assertEquals("Where I drove (private)", CoverageLayer.RECORDED.label)
        // First read after the update: forced off, flag set.
        assertEquals(CoverageLayer.OFF, CoverageLayer.load(prefs))
        assertTrue(prefs.getBoolean(CoverageLayer.MIGRATED_KEY, false))
        assertEquals("off", prefs.getString(CoverageLayer.PREF_KEY, null))
        // The user turns it back on: that sticks.
        CoverageLayer.save(prefs, CoverageLayer.RECORDED)
        assertEquals(CoverageLayer.RECORDED, CoverageLayer.load(prefs))
        assertEquals(CoverageLayer.RECORDED, CoverageLayer.load(prefs))
        CoverageLayer.save(prefs, CoverageLayer.SHARED)
        assertEquals(CoverageLayer.SHARED, CoverageLayer.load(prefs))

        // A fresh install starts off, too.
        prefs.edit().clear().commit()
        assertEquals(CoverageLayer.OFF, CoverageLayer.load(prefs))
        assertFalse(prefs.getString(CoverageLayer.PREF_KEY, null) != "off")
    }
}
