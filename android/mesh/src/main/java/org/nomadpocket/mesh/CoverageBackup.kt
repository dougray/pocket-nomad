// CoverageBackup.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// One file that holds everything the coverage scanner has learned, so a
// reinstall or a new phone doesn't start from nothing: recordings, drive-test
// pings, private zones, the coverage switches and drive-mode settings. The iOS
// app writes and reads the same file.
//
// It contains location history, so it stays on the phone: the automatic
// backup only goes to a folder on local storage, and a copy only leaves when the
// user exports one themselves. Restoring merges: records
// already on the phone are kept, duplicates are skipped, and nothing is lost.
//
// Format (version 1), top-level keys:
//   version   1
//   app       who wrote it, e.g. "Pocket Nomad Android 0.4.0"
//   createdAt ISO-8601 UTC time
//   samples   [{date, latitude, longitude, accuracy, firmware, node, snr?, hops?}]
//   pings     [{date, latitude, longitude, accuracy, firmware, outcome}]
//   zones     [{id, name, latitude, longitude, radius}]
//   settings  {recording, sharing, retentionDays}
//   drive     {pinging, keepScreenOn, targets: {meshtastic?, meshcore?}}
// `date` is milliseconds since 1970 (as in the Android coverage files). The
// reader also takes seconds since 1970, Apple's seconds since 2001 and
// ISO-8601 strings, so a file from any version of either app reads.

package org.nomadpocket.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToLong

object CoverageBackup {
    const val VERSION = 1
    const val FILE_NAME = "pocket-nomad-backup.json"
    const val MIME_TYPE = "application/json"

    /**
     * Android's local storage provider: internal storage and memory cards. The
     * automatic backup folder must come from it, so the backup never leaves the
     * phone (Google Drive and other cloud providers have their own authorities).
     */
    const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val NOT_LOCAL = "Pick a folder on this phone (internal storage), not Google Drive or another cloud service."

    /** Where the folder picker opens: Documents on internal storage. */
    const val SUGGESTED_FOLDER_DOCUMENT_ID = "primary:Documents"

    /** True only for a folder from the phone's own storage. */
    fun isLocalFolder(authority: String?): Boolean = authority == LOCAL_STORAGE_AUTHORITY

    /** Automatic backups after a drive wait this long since the last one. */
    const val MIN_INTERVAL_MILLIS = 10 * 60_000L

    /** Seconds between 1970-01-01 and Apple's reference date, 2001-01-01. */
    private const val APPLE_EPOCH_SECONDS = 978_307_200L

    data class Settings(val recording: Boolean = false, val sharing: Boolean = false, val retentionDays: Int = 30)

    data class DriveSettings(
        val pinging: Boolean = true,
        val keepScreenOn: Boolean = true,
        /** Chosen ping target per network; a missing network means "automatic". */
        val targets: Map<Firmware, Long> = emptyMap(),
    )

    data class Contents(
        val version: Int = VERSION,
        val app: String,
        val createdAtMillis: Long,
        val samples: List<CoverageRecord>,
        val pings: List<PingRecord>,
        val zones: List<CoverageZone>,
        val settings: Settings,
        val drive: DriveSettings,
    ) {
        val isEmpty: Boolean get() = samples.isEmpty() && pings.isEmpty() && zones.isEmpty()
    }

    /** What a restore added (the rest were already on the phone). */
    data class MergeResult(val samples: Int, val pings: Int, val zones: Int) {
        val total: Int get() = samples + pings + zones
    }

    class FormatException(message: String) : Exception(message)

    // MARK: Writing

    fun encode(c: Contents): String = JSONObject().apply {
        put("version", c.version)
        put("app", c.app)
        put("createdAt", Instant.ofEpochMilli(c.createdAtMillis).toString())
        put("samples", JSONArray().apply {
            c.samples.forEach { s ->
                put(JSONObject().put("date", s.dateMillis).put("latitude", s.latitude).put("longitude", s.longitude)
                    .put("accuracy", s.accuracy).put("firmware", s.firmware.rawValue).put("node", s.node).apply {
                        s.snr?.let { put("snr", it.toDouble()) }
                        s.hops?.let { put("hops", it) }
                    })
            }
        })
        put("pings", JSONArray().apply {
            c.pings.forEach { p ->
                put(JSONObject().put("date", p.dateMillis).put("latitude", p.latitude).put("longitude", p.longitude)
                    .put("accuracy", p.accuracy).put("firmware", p.firmware.rawValue).put("outcome", p.outcome.rawValue))
            }
        })
        put("zones", JSONArray().apply {
            c.zones.forEach { z ->
                put(JSONObject().put("id", z.id).put("name", z.name).put("latitude", z.latitude)
                    .put("longitude", z.longitude).put("radius", z.radius))
            }
        })
        put("settings", JSONObject().put("recording", c.settings.recording).put("sharing", c.settings.sharing)
            .put("retentionDays", c.settings.retentionDays))
        put("drive", JSONObject().put("pinging", c.drive.pinging).put("keepScreenOn", c.drive.keepScreenOn)
            .put("targets", JSONObject().apply { c.drive.targets.forEach { (fw, n) -> put(fw.rawValue, n) } }))
    }.toString()

    // MARK: Reading

    /**
     * Reads a backup. Throws [FormatException] for anything that isn't one (or is
     * from a newer app). Single records that can't be read are skipped, not fatal.
     */
    fun decode(json: String): Contents {
        val o = try { JSONObject(json) } catch (e: Exception) { throw FormatException("This isn't a Pocket Nomad backup.") }
        if (!o.has("version") || !o.has("samples")) throw FormatException("This isn't a Pocket Nomad backup.")
        val version = o.optInt("version", -1)
        if (version < 1) throw FormatException("This isn't a Pocket Nomad backup.")
        if (version > VERSION) throw FormatException("This backup is from a newer version of Pocket Nomad. Update the app, then try again.")
        val settings = o.optJSONObject("settings")
        val drive = o.optJSONObject("drive")
        return Contents(
            version = version,
            app = o.optString("app", ""),
            createdAtMillis = o.opt("createdAt")?.let { dateMillis(it) } ?: 0L,
            samples = objects(o.optJSONArray("samples")).mapNotNull { s ->
                runCatching {
                    val fw = firmwareOf(s.optString("firmware")) ?: return@mapNotNull null
                    CoverageRecord(dateMillis(s.get("date")) ?: return@mapNotNull null,
                        s.getDouble("latitude"), s.getDouble("longitude"), s.optDouble("accuracy", 0.0),
                        fw, s.getLong("node"),
                        if (s.has("snr") && !s.isNull("snr")) s.getDouble("snr").toFloat() else null,
                        if (s.has("hops") && !s.isNull("hops")) s.getLong("hops") else null)
                }.getOrNull()
            },
            pings = objects(o.optJSONArray("pings")).mapNotNull { p ->
                runCatching {
                    val fw = firmwareOf(p.optString("firmware")) ?: return@mapNotNull null
                    val outcome = pingOutcomeOf(p.optString("outcome")) ?: return@mapNotNull null
                    PingRecord(dateMillis(p.get("date")) ?: return@mapNotNull null,
                        p.getDouble("latitude"), p.getDouble("longitude"), p.optDouble("accuracy", 0.0), fw, outcome)
                }.getOrNull()
            },
            zones = objects(o.optJSONArray("zones")).mapNotNull { z ->
                runCatching {
                    CoverageZone(z.optString("id").ifEmpty { UUID.randomUUID().toString() }, z.getString("name"),
                        z.getDouble("latitude"), z.getDouble("longitude"), z.getDouble("radius"))
                }.getOrNull()
            },
            settings = Settings(
                recording = settings?.optBoolean("recording", false) ?: false,
                sharing = settings?.optBoolean("sharing", false) ?: false,
                retentionDays = settings?.optInt("retentionDays", 30)?.takeIf { it > 0 } ?: 30,
            ),
            drive = DriveSettings(
                pinging = drive?.optBoolean("pinging", true) ?: true,
                keepScreenOn = drive?.optBoolean("keepScreenOn", true) ?: true,
                targets = drive?.optJSONObject("targets")?.let { t ->
                    Firmware.entries.mapNotNull { fw ->
                        if (t.has(fw.rawValue) && !t.isNull(fw.rawValue)) t.optLong(fw.rawValue, 0).takeIf { it != 0L }?.let { fw to it } else null
                    }.toMap()
                } ?: emptyMap(),
            ),
        )
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /**
     * Milliseconds since 1970 from any date the apps have written: a number of
     * milliseconds or seconds since 1970, Apple's seconds since 2001 (Swift's
     * default), or an ISO-8601 string. Null if it's none of those.
     */
    fun dateMillis(v: Any?): Long? = when (v) {
        is Number -> {
            val d = v.toDouble()
            when {
                !d.isFinite() -> null
                d >= 1e11 -> d.roundToLong() // milliseconds since 1970
                // Seconds since 1970 for any date after 2001; Apple-epoch seconds are below this until 2032.
                d >= APPLE_EPOCH_SECONDS -> (d * 1000).roundToLong()
                else -> ((d + APPLE_EPOCH_SECONDS) * 1000).roundToLong()
            }
        }
        is String -> runCatching { Instant.parse(v).toEpochMilli() }.getOrNull() ?: v.toDoubleOrNull()?.let { dateMillis(it) }
        else -> null
    }

    // MARK: Merging

    /** Same moment, place and network: the same recording, whichever phone it came back from. */
    private fun key(date: Long, lat: Double, lon: Double, fw: Firmware) =
        "$date|${(lat * 1e7).roundToLong()}|${(lon * 1e7).roundToLong()}|${fw.rawValue}"

    fun sampleKey(s: CoverageRecord) = key(s.dateMillis, s.latitude, s.longitude, s.firmware)
    fun pingKey(p: PingRecord) = key(p.dateMillis, p.latitude, p.longitude, p.firmware)
    fun zoneKey(z: CoverageZone) = "${z.name.trim().lowercase()}|${(z.latitude * 1e6).roundToLong()}|${(z.longitude * 1e6).roundToLong()}"

    /** Everything in [existing] plus whatever in [incoming] isn't there yet, oldest first. Never drops a record. */
    fun mergeSamples(existing: List<CoverageRecord>, incoming: List<CoverageRecord>): List<CoverageRecord> =
        mergeBy(existing, incoming, ::sampleKey).sortedBy { it.dateMillis }

    fun mergePings(existing: List<PingRecord>, incoming: List<PingRecord>): List<PingRecord> =
        mergeBy(existing, incoming, ::pingKey).sortedBy { it.dateMillis }

    /** Zones keep their order: the phone's first, then new ones from the backup. */
    fun mergeZones(existing: List<CoverageZone>, incoming: List<CoverageZone>): List<CoverageZone> {
        val ids = existing.map { it.id }.toMutableSet()
        return mergeBy(existing, incoming, ::zoneKey).mapIndexed { i, z ->
            // A restored zone whose id is already taken by a different zone gets a new one.
            if (i >= existing.size && !ids.add(z.id)) z.copy(id = UUID.randomUUID().toString()) else z
        }
    }

    private fun <T> mergeBy(existing: List<T>, incoming: List<T>, key: (T) -> String): List<T> {
        val seen = existing.mapTo(HashSet(), key)
        return existing + incoming.filter { seen.add(key(it)) }
    }

    /**
     * Days to keep so that restored records aren't pruned straight away: the
     * current setting, the backup's, or enough to cover the oldest record,
     * whichever is longest. Picks one of the app's choices when one is long enough.
     */
    fun retentionCovering(current: Int, fromBackup: Int, oldestMillis: Long?, now: Long, choices: List<Int> = RETENTION_CHOICES): Int {
        val needed = oldestMillis?.let { ceil((now - it) / 86_400_000.0).toInt() + 1 } ?: 0
        val days = maxOf(current, fromBackup, needed)
        return choices.firstOrNull { it >= days } ?: days
    }

    val RETENTION_CHOICES = listOf(7, 30, 90, 365)

    // MARK: Automatic backups

    /** A cheap summary of the data; when it changes, there's something new to back up. */
    fun fingerprint(samples: List<CoverageRecord>, pings: List<PingRecord>, zones: List<CoverageZone>): String =
        "${samples.size}:${samples.lastOrNull()?.dateMillis ?: 0}:${pings.size}:${pings.lastOrNull()?.dateMillis ?: 0}:" +
            zones.joinToString(",") { zoneKey(it) }.hashCode()

    enum class Reason { DRIVE_STOPPED, BACKGROUND, MANUAL }

    /**
     * Whether an automatic backup should run now. Only with a folder chosen and
     * something new since the last one; after a drive, also only when the last
     * backup is at least 10 minutes old. "Back up now" always runs.
     */
    fun shouldBackUp(reason: Reason, hasFolder: Boolean, changed: Boolean, lastMillis: Long?, now: Long): Boolean = when {
        !hasFolder -> false
        reason == Reason.MANUAL -> true
        !changed -> false
        reason == Reason.DRIVE_STOPPED -> lastMillis == null || now - lastMillis >= MIN_INTERVAL_MILLIS
        else -> true
    }
}
