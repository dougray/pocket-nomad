package org.nomadpocket.wear

import org.nomadpocket.core.CoordinateFormat
import java.util.Locale
import kotlin.math.abs

/** What the position card says, from a fix (or the lack of one). Pure, so it is unit-tested. */
sealed interface PositionReport {
    data class Fix(val rows: List<Pair<String, String>>, val stale: Boolean) : PositionReport
    data object Denied : PositionReport
    data object NoGps : PositionReport
    data object Searching : PositionReport

    companion object {
        /** A fix older than this is shown, but marked as old. */
        const val STALE_MILLIS = 2 * 60 * 1000L

        fun of(
            permitted: Boolean,
            hasGps: Boolean,
            latitude: Double?,
            longitude: Double?,
            altitudeMeters: Double?,
            accuracyMeters: Float?,
            ageMillis: Long,
        ): PositionReport {
            if (!permitted) return Denied
            if (latitude == null || longitude == null) return if (hasGps) Searching else NoGps
            val rows = mutableListOf("Decimal" to CoordinateFormat.decimal(latitude, longitude))
            CoordinateFormat.mgrs(latitude, longitude)?.let { rows += "MGRS" to it }
            rows += "DMS" to CoordinateFormat.dms(latitude, longitude)
            altitudeMeters?.let { rows += "Altitude" to String.format(Locale.US, "%.0f ft (%.0f m)", it * 3.28084, it) }
            accuracyMeters?.let { rows += "Accuracy" to String.format(Locale.US, "±%.0f m", it) }
            return Fix(rows, ageMillis > STALE_MILLIS)
        }
    }
}

/** Compass maths shared by the sensor listener and its tests. */
object Heading {
    /** Smallest angle between two headings, 0–180°. */
    fun diff(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)

    /** Azimuth from [android.hardware.SensorManager.getOrientation] (radians, −π..π) to 0–360°. */
    fun fromAzimuth(radians: Double): Double = (Math.toDegrees(radians) + 360) % 360

    /** Magnetic to true north, given the local declination (east positive). */
    fun trueHeading(magnetic: Double, declination: Double): Double = ((magnetic + declination) % 360 + 360) % 360

    /** The heading as shown: "312° NW". */
    fun label(degrees: Double): String =
        "${CoordinateFormat.wholeDegrees(degrees) % 360}° ${CoordinateFormat.cardinal(degrees)}"
}
