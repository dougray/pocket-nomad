package org.nomadpocket.app.tools

/** Morse code and its timing. A port of Shared/Morse.swift. */
object Morse {
    val table: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
        'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--",
        '?' to "..--..", '/' to "-..-.", '-' to "-....-", '@' to ".--.-.", '=' to "-...-",
    )

    data class Step(val on: Boolean, val units: Int)

    private fun words(text: String): List<String> = text.uppercase().split(' ').filter { it.isNotEmpty() }

    fun encode(text: String): String =
        words(text).joinToString(" / ") { word -> word.mapNotNull { table[it] }.joinToString(" ") }

    /**
     * On/off durations in Morse units: dot 1, dash 3, gap inside a letter 1,
     * between letters 3, between words 7. SOS is sent as one run (…---…).
     */
    fun timeline(text: String): List<Step> {
        val out = mutableListOf<Step>()
        val upper = text.uppercase()
        val words = if (upper == "SOS") listOf(listOf("...---...")) else words(upper).map { w -> w.mapNotNull { table[it] } }
        words.forEachIndexed { w, letters ->
            if (w > 0) out += Step(false, 7)
            letters.forEachIndexed { l, code ->
                if (l > 0) out += Step(false, 3)
                code.forEachIndexed { s, symbol ->
                    if (s > 0) out += Step(false, 1)
                    out += Step(true, if (symbol == '-') 3 else 1)
                }
            }
        }
        out += Step(false, 7)
        return out
    }

    /** PARIS standard: the length of one dot in milliseconds at [wpm] words per minute. */
    fun unitMillis(wpm: Double): Long = (1200.0 / wpm).toLong()
}
