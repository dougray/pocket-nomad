package org.nomadpocket.ham

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class QsoLogTest {
    private fun date(s: String) = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    private fun store() = QsoLogStore(File.createTempFile("qso", ".json").also { it.delete() })

    @Test fun bands() {
        assertEquals("20m", QsoBands.band(14.074)); assertEquals("2m", QsoBands.band(146.52)); assertEquals("70cm", QsoBands.band(446.0)); assertNull(QsoBands.band(100.0))
    }

    @Test fun adifExportExactFormat() {
        val q = Qso(call = "W1AW", whenMillis = date("20261118171147"), mhz = 14.074, mode = "FT8", rstSent = "-12", rstRcvd = "-08", grid = "FN31", myCall = "N0CALL", sig = "POTA", sigInfo = "US-0001", comment = "Café")
        val adif = Adif.export(listOf(q))
        assertTrue(adif.contains("<EOH>"))
        assertTrue(adif.contains("<CALL:4>W1AW <QSO_DATE:8>20261118 <TIME_ON:6>171147 <BAND:3>20m <FREQ:6>14.074 <MODE:3>FT8 "))
        assertTrue(adif.contains("<SIG:4>POTA <SIG_INFO:7>US-0001 <EOR>"))
        assertTrue("lengths count bytes", adif.contains("<COMMENT:5>Café "))
    }

    @Test fun roundTripAndTolerantImport() {
        val q = Qso(call = "K5ABC", whenMillis = date("20261118120000"), mhz = 7.074, mode = "FT8", name = "Dana", comment = "<tag> inside & text")
        val back = Adif.parse(Adif.export(listOf(q)))
        assertEquals(1, back.size)
        assertEquals("K5ABC", back[0].call); assertEquals(q.whenMillis, back[0].whenMillis); assertEquals(7.074, back[0].mhz, 1e-9)
        assertEquals("<tag> inside & text", back[0].comment); assertEquals("Dana", back[0].name)
        val other = "WSJT-X ADIF Export\n<adif_ver:5>3.1.0\n<eoh>\n<call:5>VE3XY <qso_date:8>20261101 <band:3>40m <mode:3>CW <operator:6>N0CALL <app_foo:3>bar <eor>\n<call:0> <qso_date:8>20261101 <eor>\n"
        val imported = Adif.parse(other)
        assertEquals(1, imported.size)
        assertEquals("VE3XY", imported[0].call); assertEquals("40m", imported[0].band); assertEquals("N0CALL", imported[0].myCall)
    }

    @Test fun storeMergeSkipsDuplicatesAndSearches() {
        val s = store()
        val a = Qso(call = "W1AW", whenMillis = date("20261118120000"), mhz = 14.074, mode = "FT8")
        s.add(a)
        assertEquals(1, s.merge(listOf(a, Qso(call = "W1AW", whenMillis = date("20261118120030"), mhz = 14.075, mode = "FT8"), Qso(call = "K5ABC", whenMillis = date("20261118130000"), mhz = 7.1, mode = "SSB"))))
        assertEquals(2, s.log.size); assertEquals(1, s.search("k5").size); assertEquals("K5ABC", s.newestFirst.first().call)
    }

    @Test fun powerDistanceAndMilesPerWatt() {
        val q = Qso(call = "K5ABC", whenMillis = date("20261118120000"), mhz = 7.078, mode = "JS8", myGrid = "EM10", grid = "EM13", watts = 5.0)
        val km = q.distanceKm!!
        assertEquals(334.0, km, 25.0)
        assertEquals(km / 1.609344 / 5, q.milesPerWatt!!, 1e-9)
        val adif = Adif.export(listOf(q))
        assertTrue(adif.contains("<TX_PWR:1>5 ")); assertTrue(adif.contains("<DISTANCE:3>"))
        assertEquals(5.0, Adif.parse(adif).first().watts!!, 0.0)
        val none = q.copy(watts = null, grid = "")
        assertNull(none.distanceKm); assertNull(none.milesPerWatt); assertNull(qsoRangeLine(none))
        assertTrue(qsoRangeLine(q)!!.startsWith("5 W · "))
        assertEquals(30.5, HamGeo.fromMaidenhead("EM10")!!.first, 0.01); assertNull(HamGeo.fromMaidenhead("ZZ99"))
    }
}
