// Ft8Heard.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** FT8 messages decoded from radio audio, newest first. Kept only in memory for this run of the app. */
object Ft8Heard {
    data class Line(val text: String, val snr: Int, val atMillis: Long)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    fun add(messages: List<Ft8Message>, now: Long = System.currentTimeMillis()) {
        if (messages.isEmpty()) return
        _lines.value = (messages.map { Line(it.text, it.snr.toInt(), now) } + _lines.value).take(100)
    }

    fun clear() { _lines.value = emptyList() }
}
