// The coverage backup file (CoverageBackup): writing and reading it back,
// merging without duplicates, a restore never losing anything, dates from
// either app, and when automatic backups run. Coordinates are made up (open ocean).
package org.nomadpocket.mesh

import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CoverageBackupTest {
    private val dir: File = Files.createTempDirectory("backup").toFile()
    private var clock = 1_800_000_000_000L
    private val day = 86_400_000L

    @After fun tearDown() { dir.deleteRecursively() }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private fun store(name: String = "a", prefs: MeshPrefs = MemoryMeshPrefs()) =
        CoverageStore(prefs, File(File(dir, name).apply { mkdirs() }, "coverage.json"), TestScope(), location = { null }, now = { clock })

    private fun sample(t: Long, lat: Double = 10.14, lon: Double = -20.39, fw: Firmware = Firmware.MESHTASTIC, node: Long = 0xABCD) =
        CoverageRecord(t, lat, lon, 6.0, fw, node, 4.5f, 1)

    private fun ping(t: Long, lat: Double = 10.15, outcome: PingOutcome = PingOutcome.REACHED) =
        PingRecord(t, lat, -20.40, 7.0, Firmware.MESHCORE, outcome)

    private fun contents(
        samples: List<CoverageRecord> = emptyList(), pings: List<PingRecord> = emptyList(), zones: List<CoverageZone> = emptyList(),
        retention: Int = 30,
    ) = CoverageBackup.Contents(app = "test", createdAtMillis = clock, samples = samples, pings = pings, zones = zones,
        settings = CoverageBackup.Settings(recording = true, sharing = false, retentionDays = retention),
        drive = CoverageBackup.DriveSettings(pinging = false, keepScreenOn = true, targets = mapOf(Firmware.MESHCORE to 0xC7L)))

    @Test fun roundTripKeepsEverything() {
        val c = contents(
            samples = listOf(sample(clock - 5000), sample(clock - 4000, fw = Firmware.MESHCORE).copy(snr = null, hops = null)),
            pings = listOf(ping(clock - 3000), ping(clock - 2000, outcome = PingOutcome.NONE)),
            zones = listOf(CoverageZone("z1", "Camp (test)", 10.10, -20.30, 500.0)),
            retention = 90,
        )
        val json = CoverageBackup.encode(c)
        val o = JSONObject(json)
        assertEquals(setOf("version", "app", "createdAt", "samples", "pings", "zones", "settings", "drive"), o.keyNames())
        assertEquals(1, o.getInt("version"))
        assertEquals(setOf("date", "latitude", "longitude", "accuracy", "firmware", "node", "snr", "hops"),
            o.getJSONArray("samples").getJSONObject(0).keyNames())
        assertEquals(setOf("date", "latitude", "longitude", "accuracy", "firmware", "outcome"),
            o.getJSONArray("pings").getJSONObject(0).keyNames())
        assertEquals(setOf("id", "name", "latitude", "longitude", "radius"), o.getJSONArray("zones").getJSONObject(0).keyNames())
        assertEquals(c, CoverageBackup.decode(json))
    }

    @Test fun samplesAndPingsUseTheCoverageFilesKeys() {
        // The backup's records read like the coverage files, so a sample saved by CoverageStore reads here too.
        val s = store()
        s.restore(contents(samples = listOf(sample(clock - 1000)), pings = listOf(ping(clock - 1000))))
        val fileSample = org.json.JSONArray(File(File(dir, "a"), "coverage.json").readText()).getJSONObject(0)
        val backupSample = JSONObject(CoverageBackup.encode(contents(samples = s.samples))).getJSONArray("samples").getJSONObject(0)
        assertEquals(fileSample.keyNames(), backupSample.keyNames())
    }

    @Test fun mergeSkipsDuplicatesButKeepsNearMisses() {
        val a = listOf(sample(clock - 3000), sample(clock - 2000))
        val b = listOf(
            sample(clock - 2000), // the same recording
            sample(clock - 2000, fw = Firmware.MESHCORE), // same moment and place, other network
            sample(clock - 2000, lat = 10.1401), // moved a little
            sample(clock - 1000),
        )
        val merged = CoverageBackup.mergeSamples(a, b)
        assertEquals(5, merged.size)
        assertEquals(merged.sortedBy { it.dateMillis }, merged)
        assertEquals(merged, CoverageBackup.mergeSamples(merged, b))
        assertEquals("merging a backup with itself adds nothing", merged, CoverageBackup.mergeSamples(merged, merged))

        val zones = listOf(CoverageZone("1", "Home (test)", 10.0, -20.0, 500.0))
        val incoming = listOf(
            CoverageZone("2", "home (test)", 10.0, -20.0, 300.0), // same name and place
            CoverageZone("1", "Work (test)", 10.5, -20.5, 300.0), // same id, different zone
        )
        val mz = CoverageBackup.mergeZones(zones, incoming)
        assertEquals(listOf("Home (test)", "Work (test)"), mz.map { it.name })
        assertEquals(2, mz.map { it.id }.toSet().size)
    }

    @Test fun restoreNeverDropsData() {
        val s = store()
        s.restore(contents(samples = listOf(sample(clock - 1000)), zones = listOf(CoverageZone("z", "Here (test)", 10.0, -20.0, 400.0))))
        assertEquals(1, s.samples.size)

        // An old backup: some records already here, some missing, some older than "keep for".
        val old = contents(
            samples = listOf(sample(clock - 1000), sample(clock - 200 * day), sample(clock - 50 * day)),
            pings = listOf(ping(clock - 120 * day)),
            zones = listOf(CoverageZone("z", "Here (test)", 10.0, -20.0, 400.0), CoverageZone("y", "There (test)", 11.0, -21.0, 400.0)),
            retention = 7,
        )
        val added = s.restore(old)
        assertEquals(CoverageBackup.MergeResult(samples = 2, pings = 1, zones = 1), added)
        assertEquals(3, s.samples.size)
        assertEquals(1, s.pings.size)
        assertEquals(2, s.zones.size)
        assertEquals("keeps data long enough for the oldest restored record", 365, s.retentionDays)

        // Saved: a fresh start reads all of it back (and pruning at launch keeps it).
        s.saveNow()
        val sameFiles = CoverageStore(MemoryMeshPrefs().apply { putInt("coverage.retentionDays", s.retentionDays) },
            File(File(dir, "a"), "coverage.json"), TestScope(), location = { null }, now = { clock })
        assertEquals(3, sameFiles.samples.size)
        assertEquals(1, sameFiles.pings.size)

        // Restoring an empty backup changes nothing; switches aren't flipped by a restore.
        assertEquals(0, s.restore(contents()).total)
        assertEquals(3, s.samples.size)
        assertFalse(s.recording)
    }

    @Test fun backupFromAStoreRestoresIntoAnother() {
        val prefs = MemoryMeshPrefs()
        val a = store("a", prefs)
        a.restore(contents(samples = listOf(sample(clock - 3000), sample(clock - 2000)), pings = listOf(ping(clock - 1000))))
        a.addZone("Camp (test)", 10.0, -20.0, 300.0)
        val json = CoverageBackup.encode(contents(samples = a.samples, pings = a.pings, zones = a.zones))

        val b = store("b")
        b.restore(contents(samples = listOf(sample(clock - 500))))
        val added = b.restore(CoverageBackup.decode(json))
        assertEquals(CoverageBackup.MergeResult(2, 1, 1), added)
        assertEquals(3, b.samples.size)
        assertEquals(a.zones.map { it.name }, b.zones.map { it.name })
        assertTrue(b.fingerprint != a.fingerprint)
    }

    @Test fun readsDatesFromEitherApp() {
        val ms = 1_800_000_000_123L
        assertEquals(ms, CoverageBackup.dateMillis(ms))
        assertEquals(1_800_000_000_000L, CoverageBackup.dateMillis(1_800_000_000L)) // seconds since 1970
        assertEquals(1_800_000_000_000L, CoverageBackup.dateMillis(1_800_000_000.0 - 978_307_200.0)) // Swift's default
        assertEquals(1_800_000_000_000L, CoverageBackup.dateMillis("2027-01-15T08:00:00Z"))
        assertNull(CoverageBackup.dateMillis("yesterday"))
        val json = """{"version":1,"app":"Pocket Nomad iOS","createdAt":"2027-01-15T08:00:00Z",
            "samples":[{"date":821692800,"latitude":10.1,"longitude":-20.1,"accuracy":5,"firmware":"meshcore","node":7},
                       {"date":1,"latitude":10.1,"longitude":-20.1,"accuracy":5,"firmware":"radio-from-the-future","node":8}],
            "pings":[],"zones":[],"settings":{},"drive":{}}"""
        val c = CoverageBackup.decode(json)
        assertEquals(1_800_000_000_000L, c.createdAtMillis)
        assertEquals("an unknown network is skipped, not fatal", 1, c.samples.size)
        assertEquals(1_800_000_000_000L, c.samples.single().dateMillis)
        assertEquals(CoverageBackup.Settings(), c.settings)
    }

    @Test fun refusesWhatIsNotABackup() {
        for (bad in listOf("", "[]", "{}", """{"type":"FeatureCollection"}""", """{"version":2,"samples":[]}""")) {
            try { CoverageBackup.decode(bad); fail("read $bad") } catch (_: CoverageBackup.FormatException) {}
        }
    }

    @Test fun automaticBackupsRunOnlyWhenUseful() {
        val m = 60_000L
        fun should(r: CoverageBackup.Reason, folder: Boolean = true, changed: Boolean = true, last: Long? = clock - 30 * m) =
            CoverageBackup.shouldBackUp(r, folder, changed, last, clock)
        assertFalse("no folder chosen", should(CoverageBackup.Reason.MANUAL, folder = false))
        assertTrue("back up now always runs", should(CoverageBackup.Reason.MANUAL, changed = false))
        assertFalse("nothing new", should(CoverageBackup.Reason.BACKGROUND, changed = false))
        assertTrue(should(CoverageBackup.Reason.BACKGROUND))
        assertTrue(should(CoverageBackup.Reason.DRIVE_STOPPED))
        assertTrue(should(CoverageBackup.Reason.DRIVE_STOPPED, last = null))
        assertFalse("within 10 minutes of the last", should(CoverageBackup.Reason.DRIVE_STOPPED, last = clock - 5 * m))
        assertFalse(should(CoverageBackup.Reason.DRIVE_STOPPED, changed = false))

        val s = store()
        val before = s.fingerprint
        s.restore(contents(samples = listOf(sample(clock - 1000))))
        assertTrue("new data changes the fingerprint", s.fingerprint != before)
    }

    @Test fun retentionCoversRestoredRecords() {
        assertEquals(30, CoverageBackup.retentionCovering(30, 7, clock - 2 * day, clock))
        assertEquals(90, CoverageBackup.retentionCovering(30, 90, null, clock))
        assertEquals(90, CoverageBackup.retentionCovering(7, 7, clock - 40 * day, clock))
        assertEquals("older than every choice: exactly enough days", 501, CoverageBackup.retentionCovering(30, 30, clock - 500 * day, clock))
    }

    @Test fun automaticBackupFolderMustBeOnThisPhone() {
        assertTrue(CoverageBackup.isLocalFolder("com.android.externalstorage.documents"))
        for (cloud in listOf("com.google.android.apps.docs.storage", "com.microsoft.skydrive.content.StorageAccessProvider",
                "com.dropbox.product.android.dbapp.document_provider.documents", "com.android.providers.downloads.documents", "", null)) {
            assertFalse("$cloud", CoverageBackup.isLocalFolder(cloud))
        }
        assertEquals("Pick a folder on this phone (internal storage), not Google Drive or another cloud service.", CoverageBackup.NOT_LOCAL)
    }
}
