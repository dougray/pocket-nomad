package org.nomadpocket.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nomadpocket.app.ask.ModelStore
import org.nomadpocket.llm.Llama
import java.io.File
import java.util.Locale

/**
 * Debug builds only, like ModelSelfTest in the iOS app: loads one model, answers one
 * question and writes timings and free memory to files/selftest.txt. Used to check
 * which models a given phone can run, since the JNI layer can't be unit-tested on
 * the JVM. Start it with
 * `adb shell am start -n org.nomadpocket.app.debug/org.nomadpocket.app.MainActivity -e modelSelfTest Qwen3-1.7B-Q4_K_M.gguf`
 * (the file must be in files/models/), or put the file name in files/selftest-request.txt
 * to run it the next time the app opens. Read the result with
 * `adb shell run-as org.nomadpocket.app.debug cat files/selftest.txt`.
 */
object ModelSelfTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun runIfRequested(context: Context, extra: String?) {
        val request = File(context.filesDir, "selftest-request.txt")
        var name = extra
        if (name == null && request.exists()) {
            name = request.readText().trim()
            request.delete()   // run once
        }
        if (name.isNullOrBlank()) return
        val app = context.applicationContext
        scope.launch { run(app, name) }
    }

    private suspend fun run(context: Context, name: String) {
        val out = File(context.filesDir, "selftest.txt")
        val lines = mutableListOf("model: $name", "engine: ${Llama.systemInfo()}", "free before load: ${mb(ModelStore.freeMemory(context))} MB")
        fun save() = out.writeText(lines.joinToString("\n") + "\n")
        save()
        val file = ModelStore.file(name)
        if (!file.isFile) { lines += "result: FAIL file not found in files/models/"; save(); return }
        val start = System.nanoTime()
        var first: Double? = null
        try {
            val model = Llama.load(file)
            lines += "loaded after: %.1f s".format(Locale.US, seconds(start))
            lines += "free after load: ${mb(ModelStore.freeMemory(context))} MB"
            save()
            var answer = ""
            model.generate(
                system = "You answer first-aid questions in short numbered steps.",
                user = "How do I treat a minor burn on my hand?\n/no_think",
                maxTokens = 120,
            ).collect { text ->
                if (first == null && text.isNotEmpty()) first = seconds(start)
                answer = text
            }
            val total = seconds(start)
            lines += listOf(
                "free after answer: ${mb(ModelStore.freeMemory(context))} MB",
                "first text after: %.1f s (includes loading)".format(Locale.US, first ?: total),
                "total: %.1f s for about %d words".format(Locale.US, total, answer.split(Regex("\\s+")).count { it.isNotEmpty() }),
                "result: OK",
                "answer:", answer,
            )
            model.close()
        } catch (e: Exception) {
            lines += listOf("free after failure: ${mb(ModelStore.freeMemory(context))} MB", "result: FAIL ${e.message}")
        }
        save()
    }

    private fun seconds(start: Long) = (System.nanoTime() - start) / 1e9
    private fun mb(bytes: Long) = bytes / 1_048_576
}
