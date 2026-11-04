package org.nomadpocket.app.tools

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.LocalChromeHidden
import org.nomadpocket.app.ui.SubScreen
import org.nomadpocket.app.ui.accent
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives the camera flash, the screen and an audio tone with the same Morse
 * timing, so a signal can be seen at night or heard through walls.
 * Turning on the flash needs no camera permission on Android.
 */
class SignalPlayer(context: Context, private val scope: CoroutineScope) {
    var useTorch by mutableStateOf(true)
    var useScreen by mutableStateOf(false)
    var useSound by mutableStateOf(false)
    var wpm by mutableDoubleStateOf(8.0)
    var isOn by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val torchId: String? = runCatching {
        cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    val hasTorch: Boolean get() = torchId != null
    private val tone = ToneGenerator()
    private var job: Job? = null

    fun play(text: String, repeating: Boolean) {
        stop()
        val steps = Morse.timeline(text)
        val unit = Morse.unitMillis(wpm)
        isPlaying = true
        job = scope.launch {
            try {
                do {
                    for (step in steps) {
                        if (!isActive) break
                        set(step.on)
                        delay(unit * step.units)
                    }
                } while (repeating && isActive)
            } finally {
                set(false)
                isPlaying = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        set(false)
        isPlaying = false
    }

    fun release() {
        stop()
        tone.release()
    }

    private fun set(on: Boolean) {
        isOn = on
        if (useTorch) torch(on)
        if (useSound && on) tone.start() else tone.stop()
    }

    private fun torch(on: Boolean) {
        val id = torchId ?: return
        runCatching { cameras.setTorchMode(id, on) }
    }
}

/** A plain sine tone around 800 Hz, the usual pitch for Morse. */
class ToneGenerator {
    @Volatile private var playing = false
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        playing = true
        if (running) return
        running = true
        thread = Thread {
            val rate = 44_100
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val buffer = ShortArray(256)
            var phase = 0.0
            val step = 2 * PI * 800 / rate
            while (running) {
                val on = playing
                for (i in buffer.indices) {
                    buffer[i] = if (on) (sin(phase) * 0.6 * Short.MAX_VALUE).toInt().toShort() else 0
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(buffer, 0, buffer.size)
            }
            track.stop()
            track.release()
        }.apply { isDaemon = true; start() }
    }

    fun stop() { playing = false }

    fun release() {
        playing = false
        running = false
        thread = null
    }
}

@Composable
fun SignalLightScreen(initialText: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { SignalPlayer(context, scope) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var repeating by rememberSaveable { mutableStateOf(true) }
    val chromeHidden = LocalChromeHidden.current
    val view = LocalView.current
    val fullScreen = player.useScreen && player.isPlaying

    DisposableEffect(Unit) { onDispose { player.release(); chromeHidden.value = false; view.keepScreenOn = false } }
    DisposableEffect(player.isPlaying) {
        view.keepScreenOn = player.isPlaying
        onDispose { }
    }
    DisposableEffect(fullScreen) {
        chromeHidden.value = fullScreen
        val window = (context as? Activity)?.window
        val old = window?.attributes?.screenBrightness
        if (fullScreen && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            if (fullScreen && window != null && old != null) window.attributes = window.attributes.apply { screenBrightness = old }
        }
    }

    if (fullScreen) {
        Box(
            Modifier.fillMaxSize().background(if (player.isOn) Color.White else Color.Black).clickable { player.stop() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text("Tap to stop", color = Color.Gray, modifier = Modifier.navigationBarsPadding().padding(bottom = 40.dp))
        }
        return
    }

    SubScreen(title, onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            FormSection {
                Button(
                    onClick = { if (player.isPlaying) player.stop() else player.play(text, repeating) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (player.isPlaying) Color.Gray else Brand.danger,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(if (player.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (player.isPlaying) "Stop" else "Send ${text.uppercase()}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).background(if (player.isOn) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f), CircleShape))
                }
            }
            FormSection("Message") {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("Text to send") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(Morse.encode(text), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow("Repeat until stopped", repeating) { repeating = it }
            }
            FormSection("Signal with") {
                ToggleRow("Flashlight", player.useTorch && player.hasTorch, enabled = player.hasTorch) { player.useTorch = it }
                ToggleRow("Whole screen (white flash)", player.useScreen) { player.useScreen = it }
                ToggleRow("Sound", player.useSound) { player.useSound = it }
                Text("Speed: ${player.wpm.toInt()} words per minute")
                Slider(value = player.wpm.toFloat(), onValueChange = { player.wpm = it.toInt().toDouble() }, valueRange = 4f..20f, steps = 15)
            }
            FormSection {
                Footnote("SOS is three short, three long, three short (· · · — — — · · ·), repeated. Any signal in threes (flashes, whistle blasts, fires) means distress.")
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun MorseScreen(modifier: Modifier, onSend: () -> Unit) {
    val letters = Morse.table.entries.sortedBy { it.key }
    LazyColumn(modifier) {
        item {
            ListItem(
                headlineContent = { Text("Send a message by light or sound", color = accent) },
                modifier = Modifier.clickable(onClick = onSend),
            )
            HorizontalDivider()
            Text(
                "ALPHABET", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(letters, key = { it.key }) { (letter, code) ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(letter.toString(), Modifier.width(36.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(code.replace(".", "·").replace("-", "—"), fontFamily = FontFamily.Monospace, fontSize = 20.sp)
            }
        }
    }
}
