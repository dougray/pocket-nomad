package org.nomadpocket.wear

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Full-screen SOS light: white flashes in the Morse SOS rhythm (· · · — — — · · ·)
 * with a buzz on each flash. Keeps the screen on at full brightness while open.
 * Works with no phone. Like Watch/Sources/SignalView.swift.
 */
@Composable
fun SosScreen(onStop: () -> Unit) {
    val context = LocalContext.current
    var isOn by remember { mutableStateOf(false) }
    // Debug screenshots can pin the flash on or off with `-e sosFrame on|off`.
    val pinned = remember { (context as? Activity)?.intent?.getStringExtra("sosFrame")?.takeIf { BuildConfig.DEBUG } }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val oldBrightness = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (oldBrightness != null) window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            vibrator(context)?.cancel()
        }
    }

    LaunchedEffect(pinned) {
        if (pinned != null) {
            isOn = pinned == "on"
            return@LaunchedEffect
        }
        val buzz = vibrator(context)
        val frames = SosSignal.frames()
        while (isActive) {
            for (frame in frames) {
                isOn = frame.on
                if (frame.on && buzz?.hasVibrator() == true) {
                    buzz.vibrate(VibrationEffect.createOneShot(SosSignal.vibrationMillis(frame), VibrationEffect.DEFAULT_AMPLITUDE))
                }
                delay(frame.millis)
            }
        }
    }

    ScreenScaffold(timeText = {}) {
        Column(
            Modifier
                .fillMaxSize()
                .background(if (isOn) Color.White else Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop)
                .semantics { contentDescription = "Flashing SOS. Tap to stop." }
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("SOS", color = if (isOn) WearBrand.danger else WearBrand.amber, fontSize = 44.sp, fontWeight = FontWeight.Black)
            Text("· · · — — — · · ·", color = if (isOn) Color.Black else Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            Text("Tap the screen to stop", color = if (isOn) Color.Black else Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun vibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
