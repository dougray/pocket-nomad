// RadioAudioListener.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens to a radio through the phone's microphone or headset input and decodes 1200 baud APRS, NOAA weather alerts and FT8 from the audio.
 * Receive only: nothing is ever sent, and no sound is recorded or kept. The audio goes through [AfskDemodulator] and is thrown away;
 * only the decoded packets reach [Ham.service] and weather alerts reach [onAlert]. Works while the app is open.
 */
object RadioAudioListener {
    private const val RATE = 48000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())

    /** Where a weather alert heard on the radio goes (the app shows it with the other weather radio alerts). Set once at startup. */
    @Volatile var onAlert: ((SameAlert) -> Unit)? = null

    /** Called with each batch of FT8 messages decoded at the end of a 15 second slot (the app turns the ones with a grid square into spots). */
    @Volatile var onFt8: ((List<Ft8Message>) -> Unit)? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _level = MutableStateFlow(0f)
    /** Loudness of the incoming audio, 0 to 1, so you can set the radio's volume. */
    val level: StateFlow<Float> = _level
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _frames = MutableStateFlow(0)
    /** Packets decoded from audio since you turned it on. */
    val frames: StateFlow<Int> = _frames

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(context: Context, deliver: (ByteArray) -> Unit = { f -> main.post { if (Ham.isInitialized) Ham.service.receive(f) } }) {
        stop()
        _error.value = null; _frames.value = 0; _running.value = true
        val app = context.applicationContext
        if (!hasPermission(app)) { _error.value = "Allow the microphone to listen to a radio."; _running.value = false; return }
        job = scope.launch {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // UNPROCESSED skips the phone's noise suppression and gain control, which wreck data tones; fall back if a phone lacks it.
            val rec = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
                runCatching { AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }
                    .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }
            if (rec == null) { _error.value = "Couldn't open the microphone."; _running.value = false; return@launch }
            val demod = AfskDemodulator(RATE) { f -> _frames.value += 1; deliver(f) }
            val same = SameDecoder(RATE) { a -> _frames.value += 1; onAlert?.invoke(a) }
            val ft8 = Ft8SlotRunner(RATE) { list -> _frames.value += list.size; Ft8Heard.add(list); onFt8?.invoke(list) }
            val buf = ShortArray(RATE / 10)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) { _error.value = "The microphone stopped."; break } else continue }
                    var peak = 0
                    for (i in 0 until n) peak = maxOf(peak, Math.abs(buf[i].toInt()))
                    _level.value = peak / 32768f
                    demod.feed(buf, n); same.feed(buf, n); ft8.feed(buf, n)
                }
            } catch (e: Exception) {
                _error.value = "The microphone stopped."
            } finally {
                runCatching { rec.stop() }; rec.release(); _running.value = false; _level.value = 0f
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _running.value = false; _level.value = 0f }
}
