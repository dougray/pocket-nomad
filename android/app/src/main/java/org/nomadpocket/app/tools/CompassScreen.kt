package org.nomadpocket.app.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nomadpocket.app.location.LocationService
import org.nomadpocket.app.location.rememberLocation
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.KeyValue
import org.nomadpocket.app.ui.accent
import org.nomadpocket.app.ui.cardColor
import java.util.Locale
import kotlin.math.abs

@Composable
fun CompassScreen(modifier: Modifier) {
    val context = LocalContext.current
    val location = rememberLocation()
    val rawHeading by LocationService.heading.collectAsState()
    val permitted by LocationService.permitted.collectAsState()
    var useTrueNorth by rememberSaveable { mutableStateOf(true) }
    var lockedBearing by rememberSaveable { mutableStateOf<Double?>(null) }
    var copied by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val heading: Double? = rawHeading?.let { h -> if (useTrueNorth && h.trueHeading >= 0) h.trueHeading else h.magnetic }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Dial(heading, lockedBearing)
        if (heading != null) {
            Text(
                "${Math.round(heading).toInt() % 360}° ${CoordinateFormat.cardinal(heading)}",
                fontSize = 44.sp, fontWeight = FontWeight.Bold,
            )
            lockedBearing?.let { bearing ->
                val diff = ((bearing - heading + 540) % 360) - 180
                Text(
                    if (abs(diff) < 3) "On bearing ${bearing.toInt()}°"
                    else "Turn ${if (diff > 0) "right" else "left"} ${abs(diff).toInt()}° to ${bearing.toInt()}°",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (abs(diff) < 3) Brand.green else accent,
                )
            }
            Button(onClick = { lockedBearing = if (lockedBearing == null) heading else null }) {
                Text(if (lockedBearing == null) "Lock this bearing" else "Clear bearing")
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(useTrueNorth, { useTrueNorth = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("True north") }
                SegmentedButton(!useTrueNorth, { useTrueNorth = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Magnetic") }
            }
        } else {
            Text(
                if (LocationService.hasCompass(context)) "Hold the phone flat and away from metal." else "This device has no compass sensor.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Your position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KeyValue("Decimal", CoordinateFormat.decimal(lat, lon))
                        KeyValue("DMS", CoordinateFormat.dms(lat, lon))
                        CoordinateFormat.mgrs(lat, lon)?.let { KeyValue("MGRS", it) }
                        if (location.hasAltitude()) {
                            KeyValue("Altitude", String.format(Locale.US, "%.0f ft (%.0f m)", location.altitude * 3.28084, location.altitude))
                        }
                        if (location.hasAccuracy()) KeyValue("Accuracy", String.format(Locale.US, "±%.0f m", location.accuracy))
                    }
                }
                OutlinedButton(onClick = {
                    val text = "${CoordinateFormat.decimal(lat, lon)} (${CoordinateFormat.mgrs(lat, lon) ?: ""})"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Position", text))
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy coordinates") }
                Footnote("Read these to rescuers over a radio or phone. GPS works with no cell service; it needs a view of the sky.")
            } else if (!permitted) {
                Text(
                    "Location is off for Pocket Nomad. Turn it on in Settings › Apps › Pocket Nomad › Permissions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Getting a GPS fix… go outside or near a window.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Dial(heading: Double?, lockedBearing: Double?) {
    val rotation by animateFloatAsState(-(heading ?: 0.0).toFloat(), tween(200), label = "dial")
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.onSurface
    val accentColor = accent
    Box(Modifier.padding(top = 20.dp).size(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2
            val c = center
            rotate(rotation, c) {
                drawCircle(secondary.copy(alpha = 0.4f), r - 2.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                for (i in 0 until 72) {
                    rotate(i * 5f, c) {
                        val major = i % 6 == 0
                        val len = (if (major) 16 else 8).dp.toPx()
                        val top = c.y - (128.dp.toPx() + len / 2)
                        drawLine(
                            if (i % 18 == 0) accentColor else secondary,
                            Offset(c.x, top), Offset(c.x, top + len),
                            strokeWidth = (if (major) 3 else 1).dp.toPx(),
                        )
                    }
                }
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 22.sp.toPx()
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                listOf("N", "E", "S", "W").forEachIndexed { i, label ->
                    val angle = Math.toRadians(i * 90.0)
                    val x = c.x + (100.dp.toPx() * Math.sin(angle)).toFloat()
                    val y = c.y - (100.dp.toPx() * Math.cos(angle)).toFloat()
                    paint.color = (if (i == 0) Brand.danger else primary).toArgb()
                    // Letters stay upright while the dial turns.
                    drawContext.canvas.nativeCanvas.save()
                    drawContext.canvas.nativeCanvas.rotate(-rotation, x, y)
                    drawContext.canvas.nativeCanvas.drawText(label, x, y + paint.textSize / 3, paint)
                    drawContext.canvas.nativeCanvas.restore()
                }
                if (lockedBearing != null) {
                    rotate(lockedBearing.toFloat(), c) {
                        val tip = c.y - 136.dp.toPx()
                        val p = Path().apply {
                            moveTo(c.x, tip)
                            lineTo(c.x - 9.dp.toPx(), tip - 14.dp.toPx())
                            lineTo(c.x + 9.dp.toPx(), tip - 14.dp.toPx())
                            close()
                        }
                        drawPath(p, Brand.green)
                    }
                }
            }
            // Fixed pointer at the top: the way the phone faces.
            val p = Path().apply {
                moveTo(c.x, 4.dp.toPx() + 18.dp.toPx())
                lineTo(c.x - 12.dp.toPx(), 4.dp.toPx() - 2.dp.toPx())
                lineTo(c.x + 12.dp.toPx(), 4.dp.toPx() - 2.dp.toPx())
                close()
            }
            drawPath(p, accentColor)
        }
    }
}
