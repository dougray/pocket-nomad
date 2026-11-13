package org.nomadpocket.app.map

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.mesh.CoverageData
import org.nomadpocket.mesh.Firmware
import org.nomadpocket.mesh.PingOutcome
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.cos

/** A saved place. A port of MapPin in App/Sources/Map/OfflineMapView.swift. */
data class MapPin(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: Kind,
    val latitude: Double,
    val longitude: Double,
    val created: Long = System.currentTimeMillis(),
) {
    enum class Kind(val id: String, val label: String, val color: Color) {
        WATER("water", "Water", Color(0xFF3FA9F5)),
        SHELTER("shelter", "Shelter", Color(0xFFB98A5E)),
        RALLY("rally", "Rally point", Color(0xFFFC9712)),
        MEDICAL("medical", "Medical", Color(0xFFF24236)),
        SUPPLIES("supplies", "Supplies", Color(0xFFA77BF3)),
        DANGER("danger", "Danger", Color(0xFFFFD60A)),
        OTHER("other", "Other", Color(0xFFBDBDBD));

        companion object {
            fun from(id: String) = entries.firstOrNull { it.id == id } ?: OTHER
        }
    }

    companion object {
        fun toJson(pins: List<MapPin>): String = JSONArray(pins.map {
            JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.id)
                .put("latitude", it.latitude).put("longitude", it.longitude).put("created", it.created)
        }).toString()

        fun fromJson(json: String): List<MapPin> {
            val a = JSONArray(json)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MapPin(o.getString("id"), o.getString("name"), Kind.from(o.getString("kind")),
                    o.getDouble("latitude"), o.getDouble("longitude"), o.optLong("created"))
            }
        }

        private fun file(context: Context) = File(context.filesDir, "pins.json")
        fun load(context: Context): List<MapPin> = runCatching { fromJson(file(context).readText()) }.getOrDefault(emptyList())
        fun save(context: Context, pins: List<MapPin>) {
            val f = file(context)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(toJson(pins))
            tmp.renameTo(f)
        }
    }
}

/**
 * What the coverage layer shows. "Recorded" draws hexagons where you drove
 * (where this phone was when your radios heard someone), so it is off by
 * default: the map is for other people's radios, not your route.
 */
enum class CoverageLayer(val id: String, val label: String) {
    OFF("off", "Off"), RECORDED("recorded", "Where I drove (private)"), SHARED("shared", "What sharing would publish");

    companion object {
        /** The persisted choice, in the "map" prefs (iOS `map.coverage`). */
        const val PREF_KEY = "coverage"
        /** Set once the one-time "drive trail off" migration has run. */
        const val MIGRATED_KEY = "map.trailOffMigrated"

        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: OFF

        /**
         * The saved layer. The first read after the update that relabelled the
         * private layer turns it off once (people read the hexagons along their
         * drive as their radios being mapped); after that the user's choice stands.
         */
        fun load(prefs: SharedPreferences): CoverageLayer {
            if (!prefs.getBoolean(MIGRATED_KEY, false)) {
                prefs.edit().putString(PREF_KEY, OFF.id).putBoolean(MIGRATED_KEY, true).apply()
                return OFF
            }
            return from(prefs.getString(PREF_KEY, null))
        }

        fun save(prefs: SharedPreferences, layer: CoverageLayer) {
            prefs.edit().putString(PREF_KEY, layer.id).putBoolean(MIGRATED_KEY, true).apply()
        }
    }
}

/**
 * Which network's coverage the map shows. With two radios (Meshtastic and
 * MeshCore) recording at once, both are drawn, each ringed in its colour.
 */
enum class CoverageNetwork(val id: String, val label: String, val firmware: Firmware?) {
    BOTH("both", "Both networks", null),
    MESHTASTIC("meshtastic", "Meshtastic only", Firmware.MESHTASTIC),
    MESHCORE("meshcore", "MeshCore only", Firmware.MESHCORE);

    fun includes(f: Firmware) = firmware == null || firmware == f

    /** Only this filter's samples and pings. */
    fun filter(data: CoverageData) = if (firmware == null) data
        else CoverageData(data.samples.filter { it.firmware == firmware }, data.pings.filter { it.firmware == firmware })

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: BOTH

        /** "meshtastic", "meshcore" or "both": the network key a hexagon is outlined by. */
        fun key(firmwares: Set<Firmware>): String = when {
            firmwares.size > 1 -> "both"
            firmwares.contains(Firmware.MESHCORE) -> "meshcore"
            else -> "meshtastic"
        }
    }
}

/** Drive-test ping colours: answered, repeated only, nothing. */
object PingColors {
    val reached = Color(0.20f, 0.75f, 0.30f)
    val relayed = Color(0.99f, 0.75f, 0.10f)
    val none = Color(0.85f, 0.16f, 0.16f)
    fun color(outcome: PingOutcome) = when (outcome) {
        PingOutcome.REACHED -> reached
        PingOutcome.RELAYED -> relayed
        PingOutcome.NONE -> none
    }
    fun key(outcome: PingOutcome) = when (outcome) {
        PingOutcome.REACHED -> "reached"
        PingOutcome.RELAYED -> "relayed"
        PingOutcome.NONE -> "none"
    }
}

/** Signal colours, weak (red) to strong (green), by SNR in dB. */
object CoverageColors {
    val stops: List<Pair<Double, Color>> = listOf(
        -15.0 to Color(0.85f, 0.16f, 0.16f),
        -7.0 to Color(0.99f, 0.52f, 0.05f),
        0.0 to Color(0.99f, 0.80f, 0.10f),
        8.0 to Color(0.30f, 0.78f, 0.33f),
    )

    /** The same linear interpolation MapLibre draws, for the legend and tests. */
    fun color(snr: Double): Color {
        if (snr <= stops.first().first) return stops.first().second
        if (snr >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= snr }
        val (a, ca) = stops[i]
        val (b, cb) = stops[i + 1]
        val t = ((snr - a) / (b - a)).toFloat()
        return Color(ca.red + (cb.red - ca.red) * t, ca.green + (cb.green - ca.green) * t, ca.blue + (cb.blue - ca.blue) * t)
    }
}

/** One colour per mesh system, shared by the map dots, live node icons and legend. */
object RadioColors {
    val meshtastic = Color(0xFF66D978)
    val meshcore = Color(0xFF408CFA)
    fun color(firmware: Firmware) = if (firmware == Firmware.MESHCORE) meshcore else meshtastic
}

/** Web-mercator tile counts, for the offline-region size estimate. */
object TileMath {
    fun lonToX(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToY(lat: Double, z: Int): Int {
        val clamped = max(-85.05112878, min(85.05112878, lat))
        val r = Math.toRadians(clamped)
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** Tiles covering the box at every zoom from [minZoom] to [maxZoom]. */
    fun tileCount(south: Double, west: Double, north: Double, east: Double, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val xs = (lonToX(east, z) - lonToX(west, z) + 1).toLong()
            val ys = (latToY(south, z) - latToY(north, z) + 1).toLong()
            total += xs * ys
        }
        return total
    }

    /**
     * A rough download size: OpenFreeMap vector tiles average about 30 KB each
     * across zooms, plus about 2 MB of style, fonts and icons.
     */
    fun estimateBytes(tiles: Long): Long = tiles * 30_000L + 2_000_000L

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> "${(bytes / 1e6).toInt()} MB"
        bytes >= 1_000 -> "${(bytes / 1e3).toInt()} KB"
        else -> "$bytes bytes"
    }
}
