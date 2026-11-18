// LineOfSight.kt
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Is there a clear radio path between two points? Terrain profile from saved elevation tiles,
// Earth curvature (effective radius 4/3) and the first Fresnel zone. Terrain only: trees and
// buildings are not in the data, so "clear" means "possible". Port of LineOfSight.swift.

package org.nomadpocket.app.map

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RadioBand(val id: String, val name: String, val mhz: Double) {
    companion object {
        val ALL = listOf(
            RadioBand("lora915", "LoRa mesh 915 MHz", 915.0), RadioBand("lora868", "LoRa mesh 868 MHz", 868.0),
            RadioBand("lora433", "LoRa 433 MHz", 433.0), RadioBand("2m", "Ham 2 m (146 MHz)", 146.0),
            RadioBand("70cm", "Ham 70 cm (446 MHz)", 446.0), RadioBand("wifi", "Wi-Fi 2.4 GHz", 2437.0),
        )
    }
}

data class PathPoint(val distance: Double, val ground: Double, val bulge: Double, val line: Double, val fresnel: Double) {
    /** Radio line minus (terrain + bulge). Negative = blocked. */
    val clearance get() = line - (ground + bulge)
}

enum class PathVerdict(val label: String) {
    CLEAR("Clear path"), MARGINAL("Possible, but tight"), BLOCKED("Blocked by terrain"), UNKNOWN("No elevation data")
}

data class PathResult(val distance: Double, val points: List<PathPoint>, val verdict: PathVerdict,
                      val worstClearance: Double, val worstAt: Double, val worstFresnelRatio: Double, val missing: Int)

object LineOfSight {
    const val EARTH_RADIUS = 6_371_008.8
    const val K = 4.0 / 3.0

    fun wavelength(mhz: Double) = 299_792_458.0 / (mhz * 1e6)
    fun fresnelRadius(d1: Double, d2: Double, mhz: Double): Double { val t = d1 + d2; return if (t > 0) sqrt(wavelength(mhz) * d1 * d2 / t) else 0.0 }
    fun bulge(d1: Double, d2: Double) = d1 * d2 / (2 * K * EARTH_RADIUS)
    fun horizon(height: Double) = sqrt(2 * K * EARTH_RADIUS * max(0.0, height))

    fun analyze(a: ShapePoint, b: ShapePoint, heightA: Double, heightB: Double, mhz: Double, elevation: (Double, Double) -> Double?): PathResult {
        val total = KnownRadioParser.distance(a.latitude, a.longitude, b.latitude, b.longitude)
        val steps = max(20, min(400, (total / 100).toInt()))
        val samples = (0..steps).map { i ->
            val f = i.toDouble() / steps
            total * f to elevation(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
        }
        val missing = samples.count { it.second == null }
        val gA = samples.first().second ?: samples.firstNotNullOfOrNull { it.second }
        val gB = samples.last().second ?: samples.lastNotNullOfOrNull()
        if (gA == null || gB == null) return PathResult(total, emptyList(), PathVerdict.UNKNOWN, 0.0, 0.0, 0.0, missing)
        val topA = gA + heightA; val topB = gB + heightB
        val points = samples.mapNotNull { (d, g) ->
            g ?: return@mapNotNull null
            val f = if (total > 0) d / total else 0.0
            PathPoint(d, g, bulge(d, total - d), topA + (topB - topA) * f, fresnelRadius(d, total - d, mhz))
        }
        // Judge the interior: the ends are the antennas themselves.
        val inner = points.drop(1).dropLast(1)
        val worst = inner.minByOrNull { it.clearance }
            ?: return PathResult(total, points, if (points.isEmpty()) PathVerdict.UNKNOWN else PathVerdict.CLEAR, 0.0, 0.0, 1.0, missing)
        val worstRatio = inner.minOf { if (it.fresnel > 0) it.clearance / it.fresnel else 1.0 }
        val verdict = when { worst.clearance < 0 -> PathVerdict.BLOCKED; worstRatio < 0.6 -> PathVerdict.MARGINAL; else -> PathVerdict.CLEAR }
        return PathResult(total, points, verdict, worst.clearance, worst.distance, worstRatio, missing)
    }

    private fun List<Pair<Double, Double?>>.lastNotNullOfOrNull(): Double? = this.asReversed().firstNotNullOfOrNull { it.second }
}
