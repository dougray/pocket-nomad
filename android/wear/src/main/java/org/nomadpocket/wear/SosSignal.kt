package org.nomadpocket.wear

import org.nomadpocket.core.Morse

/**
 * The SOS rhythm for the screen and the vibration motor: · · · — — — · · ·
 * then a word gap, repeated. One Morse unit is 150 ms (about 8 words a minute),
 * slow enough to read from a distance, as on the Apple Watch.
 */
object SosSignal {
    const val UNIT_MILLIS = 150L

    data class Frame(val on: Boolean, val millis: Long)

    /** One full SOS, from [Morse.timeline] so the phone, iPhone and watches all agree. */
    fun frames(unitMillis: Long = UNIT_MILLIS): List<Frame> =
        Morse.timeline("SOS").map { Frame(it.on, it.units * unitMillis) }

    /** Length of one SOS including the gap before the next. */
    fun cycleMillis(unitMillis: Long = UNIT_MILLIS): Long = frames(unitMillis).sumOf { it.millis }

    /**
     * How long to buzz for a flash: the whole flash, but a little shorter on a
     * dash so separate dashes still feel separate on a slow motor.
     */
    fun vibrationMillis(frame: Frame): Long = if (!frame.on) 0 else if (frame.millis > UNIT_MILLIS) frame.millis - 50 else frame.millis
}
