package org.nomadpocket.app.ask

/** An AI model Ask can download. */
data class CatalogModel(
    /** The file name on disk, as on iOS. */
    val id: String,
    val title: String,
    val summary: String,
    val size: Long,
    val url: String,
    /** SHA-256 of the whole file (Hugging Face's LFS object id). */
    val sha256: String,
    /** Free memory needed to load it and answer with a 4,096-token context. */
    val minFreeBytes: Long,
)

/**
 * Apache-2.0 models that run well with llama.cpp on a phone: the same two files as
 * ModelCatalog in App/Sources/Packs/Catalog.swift. The URLs name a Hugging Face commit
 * instead of `main`, so the checksum can't go stale if the repository is updated.
 */
object ModelCatalog {
    private const val GB = 1_000_000_000L

    val small = CatalogModel(
        id = "Qwen3-1.7B-Q4_K_M.gguf",
        title = "Qwen3 1.7B (small, fast)",
        summary = "Needs about 1.8 GB of free memory (most phones with 6 GB or more). About 1.1 GB.",
        size = 1_107_409_472,
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/Qwen3-1.7B-Q4_K_M.gguf",
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        minFreeBytes = 1_800_000_000,
    )

    val large = CatalogModel(
        id = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        title = "Qwen3 4B (better answers)",
        summary = "Needs a phone with 8 GB of memory or more (about 3.5 GB free). About 2.5 GB.",
        size = 2_497_281_120,
        url = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/a06e946bb6b655725eafa393f4a9745d460374c9/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
        minFreeBytes = (3.5 * GB).toLong(),
    )

    val items = listOf(small, large)

    fun byId(id: String): CatalogModel? = items.firstOrNull { it.id == id }

    /** Memory a model file needs: the catalog's figure, or a rough guess for a file the user added. */
    fun neededBytes(fileName: String, fileSize: Long): Long =
        byId(fileName)?.minFreeBytes ?: (fileSize * 1.3).toLong() + 600_000_000
}

/** A .gguf file in the app's models folder. */
data class ModelFile(val name: String, val path: String, val size: Long) {
    val displayName: String get() = name.removeSuffix(".gguf")
}

/** What Ask will answer with, and why when it isn't what was asked for. */
data class ModelChoice(val model: ModelFile?, val note: String? = null)

/**
 * Picks the model for a question. "auto" takes the biggest model that fits in free
 * memory; a named model is used if it fits, else the next one down; "none" means
 * passages only. With nothing that fits, Ask shows the passages instead.
 */
object ModelChooser {
    const val AUTO = "auto"
    const val NONE = "none"

    fun choose(preferred: String, models: List<ModelFile>, freeBytes: Long): ModelChoice {
        if (preferred == NONE || models.isEmpty()) return ModelChoice(null)
        val bySize = models.sortedByDescending { it.size }
        fun fits(m: ModelFile) = ModelCatalog.neededBytes(m.name, m.size) <= freeBytes
        val wanted = models.firstOrNull { it.name == preferred }
        if (wanted != null && fits(wanted)) return ModelChoice(wanted)
        val fallback = bySize.firstOrNull { (wanted == null || it.size < wanted.size) && fits(it) }
        val free = "%.1f GB".format(java.util.Locale.US, freeBytes / 1e9)
        return when {
            wanted != null && fallback != null ->
                ModelChoice(fallback, "Only $free of memory is free, not enough for ${wanted.displayName}, so ${fallback.displayName} answers instead.")
            wanted != null ->
                ModelChoice(null, "Only $free of memory is free, not enough for ${wanted.displayName}. Close other apps and try again; for now, here are the best passages.")
            fallback != null -> ModelChoice(fallback)
            else -> ModelChoice(null, "Only $free of memory is free, not enough to run an AI model. Close other apps and try again; for now, here are the best passages.")
        }
    }
}
