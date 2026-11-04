package org.nomadpocket.app.tools

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Household bleach dosing from the EPA/CDC emergency disinfection guidance:
 * per gallon of clear water, 8 drops of 6% bleach or 6 drops of 8.25% bleach;
 * double it for cloudy, colored or very cold water; wait 30 minutes.
 * A port of the logic in App/Sources/Tools/WaterCalculatorView.swift.
 */
object WaterCalculator {
    enum class Unit(val label: String) { GALLONS("gallons"), LITERS("liters") }

    enum class Strength(val label: String, val dropsPerGallon: Double) {
        SIX("6%", 8.0), EIGHT("8.25%", 6.0)
    }

    /** Above this altitude water needs a 3-minute boil (5,000 ft, per EPA). */
    const val HIGH_ALTITUDE_METERS = 1524.0

    fun gallons(amount: Double, unit: Unit): Double = if (unit == Unit.GALLONS) amount else amount / 3.785

    fun drops(gallons: Double, strength: Strength, cloudy: Boolean): Int =
        ceil(gallons * strength.dropsPerGallon * (if (cloudy) 2 else 1)).toInt()

    /** What the "Add" section shows under the drop count. CDC: 1/8 teaspoon is about 8 drops. */
    fun teaspoons(drops: Int): String {
        val tsp = drops / 64.0
        if (tsp < 0.125) return "Use a clean dropper or the tip of a spoon."
        return "About ${kitchenMeasure(tsp)} (${max(1, (tsp * 4.93).roundToInt())} mL)"
    }

    /** 0.125 -> "1/8 teaspoon", 1.5 -> "1 1/2 teaspoons", 7 -> "2 1/3 tablespoons". */
    fun kitchenMeasure(tsp: Double): String {
        val useTbsp = tsp >= 3
        val amount = if (useTbsp) tsp / 3 else tsp
        val fractions = listOf(0.0 to "", 0.125 to "1/8", 0.25 to "1/4", 1.0 / 3 to "1/3", 0.5 to "1/2", 2.0 / 3 to "2/3", 0.75 to "3/4", 1.0 to "")
        var whole = amount.toInt()
        val rest = amount - whole
        val nearest = fractions.minBy { abs(it.first - rest) }
        if (nearest.first == 1.0) whole += 1
        val fraction = if (nearest.first == 1.0) "" else nearest.second
        val number = listOf(if (whole > 0) "$whole" else "", fraction).filter { it.isNotEmpty() }.joinToString(" ")
        val unit = if (useTbsp) "tablespoon" else "teaspoon"
        return "$number $unit${if (amount > 1.01) "s" else ""}"
    }

    /** The boil advice, using the phone's altitude when GPS has one. */
    fun boilText(altitudeMeters: Double?): String {
        if (altitudeMeters != null) {
            val high = altitudeMeters > HIGH_ALTITUDE_METERS
            return "Bring to a rolling boil for ${if (high) "3 minutes" else "1 minute"}. You are at about " +
                "${(altitudeMeters * 3.28084).toInt()} ft${if (high) ", which needs the longer boil" else ""}."
        }
        return "Bring to a rolling boil for 1 minute, or 3 minutes above 5,000 ft (1,524 m)."
    }

    private val numberWords = mapOf(
        "half a" to 0.5, "half an" to 0.5, "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0,
        "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
    )
    private val amountPattern = Regex(
        """\b(\d+(?:\.\d+)?|half an?|an?|one|two|three|four|five|six|seven|eight|nine|ten)\s+(gallons?|gal|liters?|litres?|l\b|quarts?|qt)|(\d+(?:\.\d+)?)\s*(gallons?|gal|liters?|litres?|l\b|quarts?|qt)"""
    )

    /**
     * Reads "5 gallons of cloudy water with 8.25% bleach" into an exact dose, so an
     * answer can show arithmetic the app did instead of arithmetic a model guessed.
     */
    fun dose(question: String): String? {
        val q = question.lowercase()
        if (!q.contains("bleach")) return null
        val amount: Double
        val unit: String
        val match = amountPattern.find(q)
        if (match != null) {
            val number = match.groups[1]?.value ?: match.groups[3]?.value ?: ""
            amount = number.toDoubleOrNull() ?: numberWords[number] ?: return null
            unit = match.groups[2]?.value ?: match.groups[4]?.value ?: return null
        } else if (q.contains("water") && listOf("purif", "disinfect", "drink", "safe", "treat").any { q.contains(it) }) {
            // "How much bleach makes water safe?" has no amount: answer per gallon.
            amount = 1.0
            unit = "gallon"
        } else {
            return null
        }
        val gallons = when {
            unit.startsWith("gal") -> amount
            unit.startsWith("q") -> amount / 4
            else -> amount / 3.785
        }
        val strength = if (q.contains("8.25")) Strength.EIGHT else Strength.SIX
        val cloudy = listOf("cloudy", "murky", "muddy", "colored", "coloured", "cold").any { q.contains(it) }
        val n = drops(gallons, strength, cloudy)
        val tsp = n / 64.0
        val measure = if (tsp >= 0.125) " (about ${kitchenMeasure(tsp)})" else ""
        val unitName = (if (unit.startsWith("l")) "liter" else if (unit.startsWith("q")) "quart" else "gallon") +
            (if (amount == 1.0) "" else "s")
        return "${formatAmount(amount)} $unitName of ${if (cloudy) "cloudy" else "clear"} water with ${strength.label} bleach: " +
            "$n drops$measure. Stir, wait 30 minutes."
    }

    /** 5.0 -> "5", 0.5 -> "0.5", 0.25 -> "0.25": like Swift's `formatted()`. */
    fun formatAmount(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}
