package org.nomadpocket.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp (MIT) on the phone's CPU, behind a small JNI wrapper (`src/main/cpp`).
 * Everything runs on the device: the model is a .gguf file the user downloaded once.
 *
 * Only one native call runs at a time, on [dispatcher]: a llama.cpp model and
 * context are not safe to share between threads.
 */
object Llama {
    /** True when the native library loaded (an APK built without it, or an unsupported ABI, has none). */
    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("nomadllm"); nativeInit(); true }.getOrDefault(false)
    }

    /** One thread for every native call. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /** llama.cpp's build and CPU features, for the self-test. */
    fun systemInfo(): String = if (isAvailable) nativeSystemInfo() else "llama.cpp not available"

    /** Loads a GGUF model. Takes a few seconds for a 1–2 GB file; keep the result for later questions. */
    suspend fun load(file: File): LlamaModel = withContext(dispatcher + NonCancellable) {
        // Not cancellable: the native load can't be interrupted, and a cancelled
        // withContext would drop the loaded model without freeing it.
        if (!isAvailable) throw LlamaException(LlamaException.Kind.UNAVAILABLE)
        if (!file.isFile) throw LlamaException(LlamaException.Kind.LOAD_FAILED)
        val handle = nativeLoad(file.absolutePath)
        if (handle == 0L) throw LlamaException(LlamaException.Kind.LOAD_FAILED)
        LlamaModel(handle, file)
    }

    /** Threads for generation: leave the phone's small cores something, as on iOS. */
    internal val threads: Int get() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeSystemInfo(): String
    @JvmStatic private external fun nativeLoad(path: String): Long
    // Kotlin mangles the JVM names of `internal` functions, which breaks JNI's lookup,
    // so the natives are private and these wrappers are internal.
    internal fun free(handle: Long) = nativeFree(handle)
    internal fun generate(handle: Long, system: String, user: String, maxTokens: Int, contextLength: Int, threads: Int, seed: Int, sink: TokenSink) =
        nativeGenerate(handle, system, user, maxTokens, contextLength, threads, seed, sink)

    @JvmStatic private external fun nativeFree(handle: Long)
    @JvmStatic private external fun nativeGenerate(
        handle: Long, system: String, user: String, maxTokens: Int, contextLength: Int, threads: Int, seed: Int, sink: TokenSink,
    ): Int
}

/** Receives each generated piece of UTF-8 from native code. Return false to stop. */
interface TokenSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/** A loaded model. Free it with [close] (or keep it loaded between questions). */
class LlamaModel internal constructor(private var handle: Long, val file: File) : Closeable {
    private val closed = AtomicBoolean(false)

    val name: String get() = file.nameWithoutExtension

    /**
     * Answers [user] following [system], streaming the whole answer so far each time it
     * grows. Qwen3's `<think>…</think>` block is hidden. Cancelling the collector stops
     * generation within a token.
     */
    fun generate(
        system: String,
        user: String,
        maxTokens: Int = 700,
        contextLength: Int = 4096,
    ): Flow<String> = callbackFlow {
        val text = Utf8Text()
        val stop = AtomicBoolean(false)
        val sink = object : TokenSink {
            override fun onPiece(bytes: ByteArray): Boolean {
                if (text.append(bytes)) trySend(Thinking.strip(text.value))
                return !stop.get() && isActive
            }
        }
        val rc = withContext(Llama.dispatcher) {
            check(!closed.get()) { "model closed" }
            Llama.generate(handle, system, user, maxTokens, contextLength, Llama.threads, (Math.random() * Int.MAX_VALUE).toInt(), sink)
        }
        if (rc < 0) throw LlamaException(LlamaException.Kind.fromCode(rc))
        send(Thinking.strip(text.value).trim())
        close()
        awaitClose { stop.set(true) }
    }.conflate().flowOn(Dispatchers.Default)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val h = handle
            handle = 0
            // Free on the native thread, after any generation still finishing.
            Thread { kotlinx.coroutines.runBlocking(Llama.dispatcher) { Llama.free(h) } }.start()
        }
    }
}

/** What went wrong, in words for the person asking. Messages match the iOS app. */
class LlamaException(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val code: Int, val message: String) {
        UNAVAILABLE(0, "The AI engine isn't available on this phone."),
        LOAD_FAILED(-1, "The AI model file couldn't be loaded. It may be damaged or too big for this phone's memory."),
        CONTEXT_FAILED(-2, "Not enough memory to run the AI model. Close other apps and try again."),
        DECODE_FAILED(-3, "The AI model stopped unexpectedly."),
        TEMPLATE_FAILED(-4, "This AI model's chat format isn't supported.");

        companion object {
            fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: DECODE_FAILED
        }
    }
}

/** Qwen3 and similar models may emit a `<think>…</think>` block; hide it. */
object Thinking {
    fun strip(s: String): String {
        val open = s.indexOf("<think>")
        if (open < 0) return s
        val close = s.indexOf("</think>", open + 7)
        if (close < 0) return s.substring(0, open)
        return (s.substring(0, open) + s.substring(close + 8)).trim()
    }
}

/**
 * Collects UTF-8 bytes that arrive a token at a time and exposes only complete
 * characters: one emoji or accented letter can be split across two tokens.
 */
class Utf8Text {
    private val bytes = java.io.ByteArrayOutputStream()
    var value: String = ""
        private set

    /** Adds [piece]; returns true when [value] grew. */
    fun append(piece: ByteArray): Boolean {
        if (piece.isEmpty()) return false
        bytes.write(piece)
        val all = bytes.toByteArray()
        val end = completeLength(all)
        val next = String(all, 0, end, Charsets.UTF_8)
        if (next == value) return false
        value = next
        return true
    }

    companion object {
        /** Length of [b] without a trailing incomplete UTF-8 sequence. */
        fun completeLength(b: ByteArray): Int {
            var i = b.size - 1
            var back = 0
            // Walk back over at most 3 continuation bytes to the lead byte.
            while (i >= 0 && back < 4 && (b[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i < 0) return b.size
            val lead = b[i].toInt() and 0xFF
            val need = when {
                lead < 0x80 -> 1
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                else -> 1
            }
            return if (b.size - i >= need) b.size else i
        }
    }
}
