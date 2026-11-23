// SdrSpectrumChart.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** A spectrum sweep as a line, with the peak-hold line behind it and a drag readout. */
@Composable
fun SdrSpectrumChart(frame: SdrSpectrumFrame, peak: List<Int>) {
    var touch by remember { mutableStateOf<Int?>(null) }
    val all = frame.db + peak
    val lo = ((all.minOrNull() ?: -80) - 3).toDouble()
    val hi = maxOf((all.maxOrNull() ?: -20) + 3, lo.toInt() + 20).toDouble()
    fun label(hz: Int) = "%.3f MHz".format(hz / 1e6)
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(200.dp).pointerInput(frame.db.size) {
            detectDragGestures(onDragStart = { o -> touch = idx(o.x, size.width.toFloat(), frame.db.size) }, onDrag = { c, _ -> touch = idx(c.position.x, size.width.toFloat(), frame.db.size) },
                onDragEnd = { touch = null }, onDragCancel = { touch = null })
        }.pointerInput(frame.db.size) { detectTapGestures(onPress = { o -> touch = idx(o.x, size.width.toFloat(), frame.db.size); tryAwaitRelease(); touch = null }) }) {
            fun path(v: List<Int>): Path = Path().also { p ->
                v.forEachIndexed { i, d ->
                    val x = size.width * i / (v.size - 1).coerceAtLeast(1)
                    val y = (size.height * (1 - (d - lo) / (hi - lo))).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
            }
            for (g in 0..4) drawLine(Color.Gray.copy(alpha = 0.25f), Offset(0f, size.height * g / 4), Offset(size.width, size.height * g / 4), strokeWidth = 1f)
            if (peak.size == frame.db.size) drawPath(path(peak), Color(0xFFFF9500), style = Stroke(width = 2.5f))
            drawPath(path(frame.db), Color.White, style = Stroke(width = 3f))
            touch?.let { t -> val x = size.width * t / (frame.db.size - 1).coerceAtLeast(1); drawLine(Color(0xFFFF9500), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(label(frame.lowHz), style = MaterialTheme.typography.labelSmall)
            val t = touch
            if (t != null && t < frame.db.size) Text("${label(frame.hzAt(t))}  ${frame.db[t]} dB", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9500))
            else Text(label((frame.lowHz + frame.highHz) / 2), style = MaterialTheme.typography.labelSmall)
            Text(label(frame.highHz), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun idx(x: Float, width: Float, n: Int): Int = ((x / width.coerceAtLeast(1f)) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
