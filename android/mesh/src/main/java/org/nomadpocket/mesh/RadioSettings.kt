// RadioSettings.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// What the Radio settings screen offers, with no Android or Bluetooth in it so
// it is all unit tested: MeshCore radio parameters (frequency, bandwidth,
// spreading factor, coding rate) with their limits and presets, "match the
// radios near you" from the downloaded community map, the radio card's hint,
// and Meshtastic regions and modem presets.
//
// Sources (read at the versions named):
// - MeshCore companion firmware, tag companion-v1.18.0 (MIT):
//   examples/companion_radio/MyMesh.cpp, CMD_SET_RADIO_PARAMS handler: accepts
//   freq 150000..2500000 kHz, bw 7000..500000 Hz, sf 5..12, cr 5..8;
//   CMD_SET_RADIO_TX_POWER accepts -9..MAX_LORA_TX_POWER dBm.
//   Defaults: platformio.ini LORA_FREQ=869.618, LORA_BW=62.5, LORA_SF=8, and
//   MyMesh.h LORA_CR 5 (the Heltec V4 also sets LORA_TX_POWER=10, max 22).
// - RadioLib (pinned by that firmware) SX126x::setBandwidth: the LoRa chip only
//   takes 7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250 and 500 kHz.
// - MeshCore's suggested presets: docs/radio_presets.md points at
//   https://api.meshcore.nz/api/v1/config; the list below is that list as
//   fetched on 2026-11-04 (built in, so it works offline).
// - Meshtastic regions and presets: field numbers and values from
//   meshtastic/protobufs config.proto (Config.LoRaConfig.RegionCode and
//   ModemPreset); frequency ranges from the firmware's region table
//   (src/mesh/RadioInterface.cpp, RDEF entries). Names are our own wording.

package org.nomadpocket.mesh

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One MeshCore radio setting: everything two radios must share to hear each other. */
data class MeshCoreRadioParams(
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
) {
    /** What the firmware stores and sends: MHz x 1000. */
    val frequencyKHz: Long get() = (frequencyMHz * 1000).roundToLong()
    /** kHz x 1000. */
    val bandwidthHz: Long get() = (bandwidthKHz * 1000).roundToLong()

    /** Same settings on the air (to the kHz and to the bandwidth step). */
    fun sameAs(other: MeshCoreRadioParams): Boolean =
        frequencyKHz == other.frequencyKHz && sameBandwidth(bandwidthKHz, other.bandwidthKHz) &&
            spreadingFactor == other.spreadingFactor && codingRate == other.codingRate

    /** "910.525 MHz · 62.5 kHz · SF7 · CR5" */
    val summary: String get() = "${formatMHz(frequencyMHz)} · ${formatKHz(bandwidthKHz)} · SF$spreadingFactor · CR$codingRate"

    /** Rounded to what the radio can hold, so map data like 910.52502 groups together. */
    fun normalized(): MeshCoreRadioParams = copy(
        frequencyMHz = frequencyKHz / 1000.0,
        bandwidthKHz = MeshCoreRadioLimits.snapBandwidth(bandwidthKHz) ?: bandwidthKHz,
    )

    companion object {
        /** The firmware's own defaults (companion-v1.18.0), which most regions do not use. */
        val FIRMWARE_DEFAULT = MeshCoreRadioParams(869.618, 62.5, 8, 5)

        fun formatMHz(mhz: Double): String = String.format(Locale.ROOT, "%.3f MHz", mhz)
        fun formatKHz(khz: Double): String {
            val s = if (abs(khz - Math.round(khz)) < 0.001) Math.round(khz).toString()
            else String.format(Locale.ROOT, "%.2f", khz).trimEnd('0').trimEnd('.')
            return "$s kHz"
        }

        private fun sameBandwidth(a: Double, b: Double): Boolean {
            val sa = MeshCoreRadioLimits.snapBandwidth(a)
            val sb = MeshCoreRadioLimits.snapBandwidth(b)
            return if (sa != null && sb != null) sa == sb else abs(a - b) < 0.01
        }
    }
}

object MeshCoreRadioLimits {
    /** The range the screen allows: sub-GHz LoRa. The firmware itself accepts 150-2500 MHz. */
    const val MIN_MHZ = 150.0
    const val MAX_MHZ = 960.0
    val SPREADING_FACTORS = 5..12
    val CODING_RATES = 5..8
    const val MIN_TX_DBM = -9
    /** The LoRa chip's bandwidth steps, in kHz. */
    val BANDWIDTHS = listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)

    /** The allowed step closest to `khz`, if it is within 0.1 kHz of one. */
    fun snapBandwidth(khz: Double): Double? = BANDWIDTHS.minByOrNull { abs(it - khz) }?.takeIf { abs(it - khz) < 0.1 }

    /** Accepts "910.525" or "910,525". */
    fun parseMHz(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    /**
     * Plain-word problems with these settings; empty means they can be sent.
     * `maxTxDbm` is the radio's own limit (from SELF_INFO); null skips the power check.
     */
    fun problems(p: MeshCoreRadioParams, txPowerDbm: Int? = null, maxTxDbm: Int? = null): List<String> {
        val out = mutableListOf<String>()
        if (p.frequencyMHz.isNaN() || p.frequencyMHz < MIN_MHZ || p.frequencyMHz > MAX_MHZ) {
            out += "Frequency must be between ${MIN_MHZ.toInt()} and ${MAX_MHZ.toInt()} MHz."
        }
        if (snapBandwidth(p.bandwidthKHz) == null) out += "Pick one of the listed bandwidths."
        if (p.spreadingFactor !in SPREADING_FACTORS) out += "Spreading factor must be 5 to 12."
        if (p.codingRate !in CODING_RATES) out += "Coding rate must be 5 to 8."
        if (txPowerDbm != null && maxTxDbm != null && (txPowerDbm < MIN_TX_DBM || txPowerDbm > maxTxDbm)) {
            out += "Transmit power must be $MIN_TX_DBM to $maxTxDbm dBm on this radio."
        }
        return out
    }
}

data class MeshCorePreset(val title: String, val params: MeshCoreRadioParams)

/** MeshCore's community-suggested presets (api.meshcore.nz/api/v1/config, 2026-11-04). */
object MeshCorePresets {
    private fun p(title: String, f: Double, bw: Double, sf: Int, cr: Int) = MeshCorePreset(title, MeshCoreRadioParams(f, bw, sf, cr))

    val USA = p("USA", 910.525, 62.5, 7, 5)

    val official: List<MeshCorePreset> = listOf(
        USA,
        p("USA - Southern California", 927.875, 62.5, 7, 5),
        p("Canada", 910.525, 62.5, 7, 5),
        p("Costa Rica", 910.525, 125.0, 11, 5),
        p("Brazil", 923.125, 62.5, 8, 8),
        p("EU/UK (Narrow)", 869.618, 62.5, 8, 8),
        p("EU/UK (Deprecated)", 869.525, 250.0, 11, 5),
        p("EU 433MHz (Long Range)", 433.650, 250.0, 11, 5),
        p("EU 433MHz (Narrow)", 433.650, 62.5, 8, 8),
        p("Czech Republic (Narrow)", 869.432, 62.5, 7, 5),
        p("Hungary", 869.618, 62.5, 7, 5),
        p("Netherlands", 869.618, 62.5, 7, 5),
        p("Netherlands (Limburg)", 869.618, 62.5, 8, 8),
        p("Portugal 433", 433.375, 62.5, 9, 6),
        p("Portugal 868", 869.618, 62.5, 7, 6),
        p("Slovakia", 869.618, 62.5, 7, 5),
        p("Switzerland", 869.618, 62.5, 8, 8),
        p("Australia", 915.800, 250.0, 10, 5),
        p("Australia (Narrow)", 916.575, 62.5, 7, 7),
        p("Australia (Mid)", 915.075, 125.0, 9, 5),
        p("Australia: SA, WA", 923.125, 62.5, 8, 8),
        p("Australia: QLD", 923.125, 62.5, 8, 5),
        p("New Zealand (Narrow)", 917.375, 62.5, 7, 5),
        p("New Zealand (Gisborne)", 917.375, 250.0, 11, 5),
        p("Thailand", 921.0, 62.5, 7, 5),
        p("Vietnam (Narrow)", 920.250, 62.5, 8, 5),
        p("Vietnam (Deprecated)", 920.250, 250.0, 11, 5),
    )

    /** The first preset with exactly these settings. */
    fun matching(params: MeshCoreRadioParams): MeshCorePreset? = official.firstOrNull { it.params.sameAs(params) }
}

/** The most common settings among nearby MeshCore radios. */
data class NearbyRadioMatch(
    val params: MeshCoreRadioParams,
    /** Radios using exactly these settings. */
    val count: Int,
    /** Nearby radios whose settings are known. */
    val total: Int,
) {
    val text: String get() = "Used by $count of $total nearby radios"
}

object NearbyRadioParams {
    /**
     * Groups the radios' settings and returns the most used. Radios without
     * (valid) settings are left out of both counts. Ties go to a setting that
     * is one of MeshCore's presets, then the lower frequency, so the answer is
     * the same every time.
     */
    fun mostCommon(radios: List<MeshCoreRadioParams?>): NearbyRadioMatch? {
        val valid = radios.filterNotNull().map { it.normalized() }.filter { MeshCoreRadioLimits.problems(it).isEmpty() }
        if (valid.isEmpty()) return null
        val groups = valid.groupBy { Key(it.frequencyKHz, it.bandwidthHz, it.spreadingFactor, it.codingRate) }
        val best = groups.entries.sortedWith(
            compareByDescending<Map.Entry<Key, List<MeshCoreRadioParams>>> { it.value.size }
                .thenByDescending { MeshCorePresets.matching(it.value.first()) != null }
                .thenBy { it.key.freqKHz }
                .thenBy { it.key.bwHz }
                .thenBy { it.key.sf }
                .thenBy { it.key.cr },
        ).first()
        return NearbyRadioMatch(best.value.first(), best.value.size, valid.size)
    }

    private data class Key(val freqKHz: Long, val bwHz: Long, val sf: Int, val cr: Int)

    /** At least this many nearby radios must agree before the radio card says anything. */
    const val HINT_MIN_RADIOS = 3

    /**
     * The radio card's hint, or null. Shown when enough nearby radios agree on
     * one setting and this radio's frequency (or any other part) differs.
     */
    fun hint(current: MeshCoreRadioParams?, match: NearbyRadioMatch?): String? {
        if (current == null || match == null || match.count < HINT_MIN_RADIOS) return null
        if (current.sameAs(match.params)) return null
        val near = match.params
        return if (current.frequencyKHz != near.frequencyKHz) {
            "Most radios near you (${match.count} of ${match.total}) use ${MeshCoreRadioParams.formatMHz(near.frequencyMHz)}. " +
                "This radio is on ${MeshCoreRadioParams.formatMHz(current.frequencyMHz)}, so it won't hear them."
        } else {
            "Most radios near you (${match.count} of ${match.total}) use ${near.summary}. This radio uses ${current.summary}, so it may not hear them."
        }
    }
}

/** A Meshtastic region: Config.LoRaConfig.RegionCode. */
data class MeshtasticRegion(val code: Long, val key: String, val name: String, val range: String) {
    val label: String get() = "$name ($range)"
}

object MeshtasticRegions {
    const val UNSET = 0L

    /**
     * Every region the firmware's region table knows (RadioInterface.cpp). Newer
     * codes in config.proto (27 and up: EU 866/874/917, narrow EU 868, amateur
     * bands) are not in the firmware's table yet, so they are left out.
     */
    val all: List<MeshtasticRegion> = listOf(
        MeshtasticRegion(1, "US", "United States", "902–928 MHz"),
        MeshtasticRegion(2, "EU_433", "Europe 433", "433–434 MHz"),
        MeshtasticRegion(3, "EU_868", "Europe 868", "869.4–869.65 MHz"),
        MeshtasticRegion(4, "CN", "China", "470–510 MHz"),
        MeshtasticRegion(5, "JP", "Japan", "920.5–923.5 MHz"),
        MeshtasticRegion(6, "ANZ", "Australia and New Zealand", "915–928 MHz"),
        MeshtasticRegion(7, "KR", "Korea", "920–923 MHz"),
        MeshtasticRegion(8, "TW", "Taiwan", "920–925 MHz"),
        MeshtasticRegion(9, "RU", "Russia", "868.7–869.2 MHz"),
        MeshtasticRegion(10, "IN", "India", "865–867 MHz"),
        MeshtasticRegion(11, "NZ_865", "New Zealand 865", "864–868 MHz"),
        MeshtasticRegion(12, "TH", "Thailand", "920–925 MHz"),
        MeshtasticRegion(13, "LORA_24", "2.4 GHz (worldwide)", "2400–2483.5 MHz"),
        MeshtasticRegion(14, "UA_433", "Ukraine 433", "433–434.7 MHz"),
        MeshtasticRegion(15, "UA_868", "Ukraine 868", "868–868.6 MHz"),
        MeshtasticRegion(16, "MY_433", "Malaysia 433", "433–435 MHz"),
        MeshtasticRegion(17, "MY_919", "Malaysia 919", "919–924 MHz"),
        MeshtasticRegion(18, "SG_923", "Singapore", "917–925 MHz"),
        MeshtasticRegion(19, "PH_433", "Philippines 433", "433–434.7 MHz"),
        MeshtasticRegion(20, "PH_868", "Philippines 868", "868–869.4 MHz"),
        MeshtasticRegion(21, "PH_915", "Philippines 915", "915–918 MHz"),
        MeshtasticRegion(22, "ANZ_433", "Australia and New Zealand 433", "433.05–434.79 MHz"),
        MeshtasticRegion(23, "KZ_433", "Kazakhstan 433", "433.075–434.775 MHz"),
        MeshtasticRegion(24, "KZ_863", "Kazakhstan 863", "863–868 MHz"),
        MeshtasticRegion(25, "NP_865", "Nepal", "865–868 MHz"),
        MeshtasticRegion(26, "BR_902", "Brazil", "902–907.5 MHz"),
    )

    fun of(code: Long?): MeshtasticRegion? = all.firstOrNull { it.code == code }

    fun name(code: Long?): String = when (code) {
        null -> "Unknown"
        UNSET -> "Not set"
        else -> of(code)?.name ?: "Region $code"
    }

    private val EU = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
        "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "NO", "CH", "IS", "LI", "RS", "BA",
        "ME", "MK", "AL", "MD",
    )

    /** A region to suggest from the phone's country (ISO 3166 code), or null. */
    fun suggested(country: String?): MeshtasticRegion? {
        val c = country?.uppercase(Locale.ROOT) ?: return null
        val key = when {
            c in setOf("US", "CA", "MX", "PR") -> "US"
            c in EU -> "EU_868"
            c == "AU" -> "ANZ"
            c == "NZ" -> "ANZ"
            c == "BR" -> "BR_902"
            c == "JP" -> "JP"
            c == "KR" -> "KR"
            c == "TW" -> "TW"
            c == "CN" -> "CN"
            c == "RU" -> "RU"
            c == "IN" -> "IN"
            c == "TH" -> "TH"
            c == "UA" -> "UA_433"
            c == "MY" -> "MY_919"
            c == "SG" -> "SG_923"
            c == "PH" -> "PH_915"
            c == "KZ" -> "KZ_863"
            c == "NP" -> "NP_865"
            else -> return null
        }
        return all.firstOrNull { it.key == key }
    }
}

/** A Meshtastic modem preset: Config.LoRaConfig.ModemPreset. */
data class MeshtasticModemPreset(val code: Long, val key: String, val name: String)

object MeshtasticModemPresets {
    const val LONG_FAST = 0L

    /** Current presets (the deprecated Long Slow and Very Long Slow are left out). */
    val all: List<MeshtasticModemPreset> = listOf(
        MeshtasticModemPreset(0, "LONG_FAST", "Long Fast (default)"),
        MeshtasticModemPreset(7, "LONG_MODERATE", "Long Moderate"),
        MeshtasticModemPreset(9, "LONG_TURBO", "Long Turbo"),
        MeshtasticModemPreset(3, "MEDIUM_SLOW", "Medium Slow"),
        MeshtasticModemPreset(4, "MEDIUM_FAST", "Medium Fast"),
        MeshtasticModemPreset(5, "SHORT_SLOW", "Short Slow"),
        MeshtasticModemPreset(6, "SHORT_FAST", "Short Fast"),
        MeshtasticModemPreset(8, "SHORT_TURBO", "Short Turbo"),
    )

    fun name(code: Long?): String = when (code) {
        null -> "Unknown"
        1L -> "Long Slow (old)"
        2L -> "Very Long Slow (old)"
        else -> all.firstOrNull { it.code == code }?.name?.removeSuffix(" (default)") ?: "Preset $code"
    }
}

/** What one Apply on the Radio settings screen is doing. */
sealed class RadioApplyState {
    object Idle : RadioApplyState()
    data class Working(val step: String) : RadioApplyState()
    /** The radio restarts to use new settings; the app reconnects by itself. */
    object Restarting : RadioApplyState()
    data class Done(val confirmed: String) : RadioApplyState()
    data class Failed(val reason: String) : RadioApplyState()

    val isBusy: Boolean get() = this is Working || this is Restarting
}
