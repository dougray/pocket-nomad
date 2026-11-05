package org.nomadpocket.app.ask

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nomadpocket.app.tools.WaterCalculator
import org.nomadpocket.llm.Llama
import org.nomadpocket.llm.LlamaModel
import java.io.File
import java.util.UUID

/** One question and its answer. */
data class Exchange(
    val question: String,
    val id: String = UUID.randomUUID().toString(),
    val sources: List<AskPassage> = emptyList(),
    val answer: String = "",
    val state: State = State.SEARCHING,
    val modelName: String? = null,
    /** Why the model isn't the one picked, or why there is none (low memory). */
    val note: String? = null,
    /** The exact bleach dose, worked out by the app instead of the model. */
    val dose: String? = null,
    val error: String? = null,
) {
    enum class State { SEARCHING, LOADING, ANSWERING, DONE, FAILED }

    fun failed(message: String) = copy(state = State.FAILED, error = message)
    val isBusy: Boolean get() = state == State.SEARCHING || state == State.LOADING || state == State.ANSWERING
}

/**
 * Ask: retrieval first, then generation (AskEngine in the iOS app). The model stays
 * loaded between questions; loading a 1–2 GB file takes a few seconds.
 */
class AskViewModel(app: Application) : AndroidViewModel(app) {
    private val _exchanges = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = _exchanges.asStateFlow()
    private var task: Job? = null
    private val loadLock = Mutex()
    private var loaded: LlamaModel? = null

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _preferred = MutableStateFlow(prefs.getString(PREF_MODEL, ModelChooser.AUTO) ?: ModelChooser.AUTO)
    /** "auto", "none" or a model file name. */
    val preferred: StateFlow<String> = _preferred.asStateFlow()

    fun setPreferred(value: String) {
        _preferred.value = value
        prefs.edit().putString(PREF_MODEL, value).apply()
    }

    val isBusy: Boolean get() = _exchanges.value.lastOrNull()?.isBusy == true

    /** What would answer right now, given the downloaded models and free memory. */
    fun currentChoice(): ModelChoice {
        if (!Llama.isAvailable) return ModelChoice(null)
        return ModelChooser.choose(_preferred.value, ModelStore.models.value, ModelStore.freeMemory(getApplication()))
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || isBusy) return
        // When the app can work out the exact answer itself (a bleach dose), it shows
        // that instead of letting a small model attempt the arithmetic.
        val dose = WaterCalculator.dose(q)
        val choice = if (dose == null) currentChoice() else ModelChoice(null)
        val model = choice.model
        _exchanges.update { it + Exchange(q, modelName = model?.displayName, note = choice.note, dose = dose) }
        val index = _exchanges.value.size - 1
        task = viewModelScope.launch {
            try {
                val hits = withContext(Dispatchers.Default) {
                    AskSearch(GuideIndex.get(getApplication()), AskSources.books).search(q, limit = 8)
                }
                val picked = AskEngine.pick(hits, if (model == null) 0 else SOURCE_BUDGET)
                set(index) { it.copy(sources = if (model == null) hits else picked) }
                if (model == null || picked.isEmpty()) {
                    set(index) { it.copy(state = Exchange.State.DONE) }
                    return@launch
                }
                set(index) { it.copy(state = Exchange.State.LOADING) }
                val llama = load(model)
                set(index) { it.copy(state = Exchange.State.ANSWERING) }
                // Qwen3 thinks out loud unless told not to; other models ignore the tag.
                llama.generate(AskEngine.INSTRUCTIONS, AskEngine.prompt(q, picked) + "\n/no_think", maxTokens = MAX_TOKENS)
                    .collect { text -> set(index) { it.copy(answer = AskEngine.tidy(text)) } }
                set(index) { it.copy(state = Exchange.State.DONE) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(index) { it.copy(state = Exchange.State.DONE) }
                throw e
            } catch (e: Exception) {
                set(index) { it.failed(e.message ?: "Something went wrong.") }
            }
        }
    }

    private suspend fun load(model: ModelFile): LlamaModel = loadLock.withLock {
        loaded?.takeIf { it.file.absolutePath == model.path }?.let { return it }
        loaded?.close()
        loaded = null
        Llama.load(File(model.path)).also { loaded = it }
    }

    private fun set(index: Int, change: (Exchange) -> Exchange) =
        _exchanges.update { list -> if (index in list.indices) list.toMutableList().also { it[index] = change(it[index]) } else list }

    fun stop() { task?.cancel() }

    fun clear() {
        stop()
        _exchanges.value = emptyList()
    }

    /** Frees the model, e.g. when it was deleted or the phone is low on memory. */
    fun unload() {
        loaded?.close()
        loaded = null
    }

    override fun onCleared() = unload()

    companion object {
        const val PREF_MODEL = "askModel"
        /** About how many characters of source text fit in the prompt (4,096-token context). */
        const val SOURCE_BUDGET = 7_000
        const val MAX_TOKENS = 700
    }
}

/** A question to ask when the Ask tab opens (debug `-e screen "ask:How do I…"`). */
object AskLaunch {
    @Volatile var question: String? = null

    fun take(): String? = question.also { question = null }
}
