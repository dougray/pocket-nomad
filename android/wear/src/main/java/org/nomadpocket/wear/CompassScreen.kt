package org.nomadpocket.wear

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlin.math.cos
import kotlin.math.sin

/**
 * Heading and position from the watch's own sensors, in the same formats the
 * phones use (:core CoordinateFormat). Says plainly when there is no fix.
 * Like Watch/Sources/WatchCompassView.swift.
 */
@Composable
fun CompassScreen() {
    KeepLocationRunning()
    val context = LocalContext.current
    val reading by WatchLocation.heading.collectAsState()
    val location by WatchLocation.location.collectAsState()
    val permitted by WatchLocation.permitted.collectAsState()
    val hasCompass = remember { WatchLocation.hasCompass(context) }
    val hasGps = remember { WatchLocation.hasGps(context) }
    val state = rememberTransformingLazyColumnState()
    // Debug `-e screen position` opens scrolled down to the coordinates.
    val startAtPosition = remember {
        BuildConfig.DEBUG && (context as? android.app.Activity)?.intent?.getStringExtra(MainActivity.EXTRA_SCREEN) == "position"
    }
    LaunchedEffect(startAtPosition) { if (startAtPosition) state.scrollToItem(3) }

    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { Dial(reading?.degrees) }
            item {
                val r = reading
                when {
                    r != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Heading.label(r.degrees), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(if (r.trueNorth) "True north" else "Magnetic north (true north after a GPS fix)",
                            color = WearBrand.secondaryText, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    hasCompass -> Footnote("Calibrating… move the watch in a figure 8.")
                    else -> Footnote("No compass on this watch.")
                }
            }
            val loc = location
            val report = PositionReport.of(
                permitted = permitted,
                hasGps = hasGps,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                altitudeMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                ageMillis = loc?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 } ?: 0,
            )
            // One item per row, centred, so nothing runs into the round bezel and the crown steps through them.
            when (report) {
                is PositionReport.Fix -> {
                    if (report.stale) item { Footnote("Last fix, over 2 minutes old") }
                    report.rows.forEach { (label, value) -> item { PositionRow(label, value) } }
                }
                PositionReport.Denied -> item { PositionMessage("No position: location is off for Pocket Nomad. Allow it in Settings › Apps › Pocket Nomad › Permissions.") }
                PositionReport.NoGps -> item { PositionMessage("No position fix. This watch has no GPS of its own, so it may only get one from its other location sources.") }
                PositionReport.Searching -> item { PositionMessage("No GPS fix yet… go outside with a view of the sky.") }
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, Modifier.fillMaxWidth(), color = WearBrand.secondaryText, fontSize = 12.sp, textAlign = TextAlign.Center)
}

@Composable
private fun PositionRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(WearBrand.card, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = WearBrand.amber, fontSize = 11.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PositionMessage(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(WearBrand.card, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 12.sp,
        color = WearBrand.secondaryText,
        textAlign = TextAlign.Center,
    )
}

/** A dial that turns so north points north, with a fixed amber marker for the watch's 12 o'clock. */
@Composable
private fun Dial(heading: Double?) {
    val rotation by animateFloatAsState(-(heading ?: 0.0).toFloat(), tween(200), label = "dial")
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(124.dp)) {
            val r = size.minDimension / 2
            val c = center
            // Fixed lubber line at the top.
            val marker = Path().apply {
                moveTo(c.x - 6.dp.toPx(), 0f); lineTo(c.x + 6.dp.toPx(), 0f); lineTo(c.x, 9.dp.toPx()); close()
            }
            drawPath(marker, WearBrand.amber)
            rotate(rotation, c) {
                drawCircle(Color.Gray.copy(alpha = 0.5f), r - 12.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 36) {
                    val angle = Math.toRadians(i * 10.0 - 90)
                    val major = i % 3 == 0
                    val outer = r - 12.dp.toPx()
                    val inner = outer - (if (major) 8 else 4).dp.toPx()
                    drawLine(
                        if (i % 9 == 0) WearBrand.amber else Color.Gray,
                        Offset(c.x + inner * cos(angle).toFloat(), c.y + inner * sin(angle).toFloat()),
                        Offset(c.x + outer * cos(angle).toFloat(), c.y + outer * sin(angle).toFloat()),
                        strokeWidth = (if (major) 2 else 1).dp.toPx(),
                    )
                }
                listOf("N", "E", "S", "W").forEachIndexed { i, label ->
                    val angle = Math.toRadians(i * 90.0 - 90)
                    val dist = r - 34.dp.toPx()
                    val text = measurer.measure(label, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    val at = Offset(c.x + dist * cos(angle).toFloat(), c.y + dist * sin(angle).toFloat())
                    // Letters stay upright as the dial turns.
                    rotate(-rotation, at) {
                        drawText(text, color = if (i == 0) WearBrand.danger else Color.White,
                            topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
                    }
                }
            }
        }
    }
}
