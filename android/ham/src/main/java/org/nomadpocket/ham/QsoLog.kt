// QsoLog.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// A simple log of radio contacts (QSOs), kept on the phone only and exported as ADIF, the standard
// file every logging program reads. Nothing is uploaded anywhere. Port of QsoLog.swift.

package org.nomadpocket.ham

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class Qso(
    val id: String = UUID.randomUUID().toString(),
    val call: String,
    /** When the contact started, UTC (epoch millis). */
    val whenMillis: Long,
    val mhz: Double,
    val mode: String,
    val rstSent: String = "", val rstRcvd: String = "",
    val grid: String = "", val name: String = "", val comment: String = "",
    /** Your own callsign at the time (from Station settings). */
    val myCall: String = "", val myGrid: String = "",
    /** Park or summit reference, for award programmes. */
    val sig: String = "", val sigInfo: String = "",
    /** Transmit power in watts (the stat that makes a contact worth bragging about). */
    val watts: Double? = null,
) {
    val band: String get() = QsoBands.band(mhz) ?: ""

    /** Great-circle distance between the two grid squares in kilometres (null unless both grids are known). */
    val distanceKm: Double? get() {
        val a = HamGeo.fromMaidenhead(myGrid) ?: return null
        val b = HamGeo.fromMaidenhead(grid) ?: return null
        return HamGeo.distanceKm(a.first, a.second, b.first, b.second)
    }

    /** Miles of range for each watt of power: 5 W reaching 84 miles is 16.8. */
    val milesPerWatt: Double? get() = distanceKm?.let { d -> watts?.takeIf { it > 0 }?.let { d / 1.609344 / it } }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("call", call).put("t", whenMillis).put("mhz", mhz).put("mode", mode).put("rs", rstSent).put("rr", rstRcvd)
        .put("grid", grid).put("name", name).put("note", comment).put("my", myCall).put("mygrid", myGrid).put("sig", sig).put("info", sigInfo).apply { watts?.let { put("w", it) } }

    companion object {
        fun fromJson(o: JSONObject) = Qso(o.getString("id"), o.getString("call"), o.getLong("t"), o.optDouble("mhz", 0.0), o.optString("mode"), o.optString("rs"), o.optString("rr"),
            o.optString("grid"), o.optString("name"), o.optString("note"), o.optString("my"), o.optString("mygrid"), o.optString("sig"), o.optString("info"), if (o.has("w")) o.getDouble("w") else null)
    }
}

object QsoBands {
    private val table = listOf(
        Triple("2190m", 0.1357, 0.1378), Triple("630m", 0.472, 0.479), Triple("160m", 1.8, 2.0), Triple("80m", 3.5, 4.0), Triple("60m", 5.06, 5.45), Triple("40m", 7.0, 7.3),
        Triple("30m", 10.1, 10.15), Triple("20m", 14.0, 14.35), Triple("17m", 18.068, 18.168), Triple("15m", 21.0, 21.45), Triple("12m", 24.89, 24.99), Triple("10m", 28.0, 29.7),
        Triple("6m", 50.0, 54.0), Triple("4m", 70.0, 71.0), Triple("2m", 144.0, 148.0), Triple("1.25m", 222.0, 225.0), Triple("70cm", 420.0, 450.0), Triple("33cm", 902.0, 928.0),
        Triple("23cm", 1240.0, 1300.0), Triple("13cm", 2300.0, 2450.0),
    )
    fun band(mhz: Double): String? = table.firstOrNull { mhz >= it.second && mhz <= it.third }?.first
    fun midpoint(band: String): Double? = table.firstOrNull { it.first.equals(band, true) }?.let { (it.second + it.third) / 2 }
    val modes = listOf("FT8", "FT4", "SSB", "CW", "FM", "AM", "RTTY", "PSK31", "JS8", "WSPR", "JT65", "PACKET", "WINLINK", "SSTV", "OTHER")
}

object Adif {
    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun field(name: String, value: String) = if (value.isEmpty()) "" else "<$name:${value.toByteArray(Charsets.UTF_8).size}>$value "

    private fun trimmed(v: Double): String = "%.6f".format(Locale.US, v).trimEnd('0').trimEnd('.')

    private fun adifMode(m: String) = when (m.uppercase()) { "PACKET", "WINLINK" -> "PKT"; "OTHER" -> ""; else -> m.uppercase() }

    fun export(log: List<Qso>, program: String = "Pocket Nomad", version: String = "1.0"): String {
        val d = fmt("yyyyMMdd"); val t = fmt("HHmmss")
        val sb = StringBuilder("Pocket Nomad QSO log\n<ADIF_VER:5>3.1.4 ").append(field("PROGRAMID", program)).append(field("PROGRAMVERSION", version)).append("<EOH>\n\n")
        for (q in log.sortedBy { it.whenMillis }) {
            val dt = Date(q.whenMillis)
            sb.append(field("CALL", q.call)).append(field("QSO_DATE", d.format(dt))).append(field("TIME_ON", t.format(dt)))
                .append(field("BAND", q.band)).append(field("FREQ", if (q.mhz > 0) trimmed(q.mhz) else "")).append(field("MODE", adifMode(q.mode)))
                .append(field("RST_SENT", q.rstSent)).append(field("RST_RCVD", q.rstRcvd)).append(field("GRIDSQUARE", q.grid)).append(field("NAME", q.name))
                .append(field("STATION_CALLSIGN", q.myCall)).append(field("MY_GRIDSQUARE", q.myGrid)).append(field("COMMENT", q.comment))
                .append(field("SIG", q.sig)).append(field("SIG_INFO", q.sigInfo)).append(field("TX_PWR", q.watts?.let { trimmed(it) } ?: ""))
                .append(field("DISTANCE", q.distanceKm?.let { "%.0f".format(Locale.US, it) } ?: "")).append("<EOR>\n")
        }
        return sb.toString()
    }

    /** Reads QSO records from an ADIF file (the header and unknown fields are skipped). */
    fun parse(text: String): List<Qso> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val eoh = Regex("<eoh>", RegexOption.IGNORE_CASE).find(text)
        var i = if (eoh != null) text.substring(0, eoh.range.last + 1).toByteArray(Charsets.UTF_8).size else 0
        val records = ArrayList<Map<String, String>>()
        var cur = HashMap<String, String>()
        while (i < bytes.size) {
            if (bytes[i] != '<'.code.toByte()) { i++; continue }
            var close = i
            while (close < bytes.size && bytes[close] != '>'.code.toByte()) close++
            if (close >= bytes.size) break
            val parts = String(bytes, i + 1, close - i - 1, Charsets.UTF_8).split(":")
            val name = parts[0].uppercase()
            i = close + 1
            if (name == "EOR") { if (cur.isNotEmpty()) records.add(cur); cur = HashMap(); continue }
            val len = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (len < 0 || i + len > bytes.size) continue
            cur[name] = String(bytes, i, len, Charsets.UTF_8)
            i += len
        }
        val dt = fmt("yyyyMMddHHmmss")
        return records.mapNotNull { r ->
            val call = r["CALL"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val d = r["QSO_DATE"]?.takeIf { it.length == 8 } ?: return@mapNotNull null
            val t = (r["TIME_ON"] ?: "000000").padEnd(6, '0').take(6)
            val millis = runCatching { dt.parse(d + t)!!.time }.getOrNull() ?: return@mapNotNull null
            var mhz = r["FREQ"]?.toDoubleOrNull() ?: 0.0
            if (mhz == 0.0) r["BAND"]?.let { b -> QsoBands.midpoint(b)?.let { mhz = it } }
            Qso(call = call.uppercase(), whenMillis = millis, mhz = mhz, mode = (r["MODE"] ?: "").uppercase(), rstSent = r["RST_SENT"] ?: "", rstRcvd = r["RST_RCVD"] ?: "",
                grid = r["GRIDSQUARE"] ?: "", name = r["NAME"] ?: "", comment = r["COMMENT"] ?: r["NOTES"] ?: "", myCall = r["STATION_CALLSIGN"] ?: r["OPERATOR"] ?: "",
                myGrid = r["MY_GRIDSQUARE"] ?: "", sig = r["SIG"] ?: "", sigInfo = r["SIG_INFO"] ?: "", watts = r["TX_PWR"]?.toDoubleOrNull())
        }
    }
}

class QsoLogStore(private val file: File) {
    var log: List<Qso> = load()
        private set

    private fun load(): List<Qso> = try { JSONArray(file.readText()).let { a -> (0 until a.length()).map { Qso.fromJson(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
    private fun save() { runCatching { file.parentFile?.mkdirs(); file.writeText(JSONArray(log.map { it.toJson() }).toString()) } }

    val newestFirst get() = log.sortedByDescending { it.whenMillis }

    fun add(q: Qso) { log = log + q; save() }
    fun update(q: Qso) { log = log.map { if (it.id == q.id) q else it }; save() }
    fun remove(id: String) { log = log.filterNot { it.id == id }; save() }

    /** Adds imported contacts, skipping ones already logged (same call, minute and band). */
    fun merge(incoming: List<Qso>): Int {
        var added = 0
        for (q in incoming) if (log.none { it.call == q.call && Math.abs(it.whenMillis - q.whenMillis) < 60_000 && it.band == q.band }) { log = log + q; added++ }
        if (added > 0) save()
        return added
    }

    fun search(text: String): List<Qso> {
        val q = text.trim().uppercase()
        return if (q.isEmpty()) newestFirst else newestFirst.filter { it.call.contains(q) || it.name.uppercase().contains(q) || it.sigInfo.uppercase().contains(q) || it.grid.uppercase().contains(q) }
    }

    fun exportAdif() = Adif.export(log)

    companion object {
        @Volatile private var shared: QsoLogStore? = null
        fun get(context: android.content.Context): QsoLogStore =
            shared ?: synchronized(this) { shared ?: QsoLogStore(File(context.applicationContext.filesDir, "qso-log.json")).also { shared = it } }
    }
}
