package com.nodepiazza.mlmodels

/**
 * A model the app knows how to download and run. [filename] is also the on-disk identity used by
 * [ModelRegistry] scans and [ModelPreferences] selection.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val filename: String,
    val approxBytes: Long,
)

/** The fixed set of models the user can choose between. */
object ModelCatalog {

    val E2B = ModelSpec(
        id = "e2b",
        displayName = "Gemma 4 E2B",
        description = "The lighter option.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        filename = "gemma-4-E2B-it.litertlm",
        approxBytes = 2_590_000_000L,
    )

    val E4B = ModelSpec(
        id = "e4b",
        displayName = "Gemma 4 E4B",
        description = "More capable but larger and slower.",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        filename = "gemma-4-E4B-it.litertlm",
        approxBytes = 3_660_000_000L,
    )

    val ALL: List<ModelSpec> = listOf(E2B, E4B)

    /** Free space kept in reserve on top of a download's size so the device isn't left at 0 B. */
    const val SPACE_SAFETY_MARGIN = 64L * 1024 * 1024

    fun byFilename(filename: String?): ModelSpec? = ALL.firstOrNull { it.filename == filename }
}
