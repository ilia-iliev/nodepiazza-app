package com.nodepiazza.mlmodels

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * Owns the default model download. `ensureDefault` is the auto-bootstrap path (Wi-Fi only,
 * skipped once any model is present). `enqueueDefault` is the user-initiated path from the
 * picker — pass `requireUnmetered = false` after the user has acknowledged the cellular cost.
 */
object ModelBootstrap {

    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    const val DEFAULT_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    const val DEFAULT_MODEL_DISPLAY_NAME = "Gemma 4 E2B (default)"
    const val DEFAULT_MODEL_APPROX_BYTES = 1_200_000_000L

    const val WORK_NAME = "model_bootstrap_download"

    suspend fun ensureDefault(context: Context, registry: ModelRegistry, folder: File) {
        if (registry.listModels().isNotEmpty()) return
        enqueue(context, folder, requireUnmetered = true, replaceExisting = false)
    }

    /** User-initiated download from the picker — replaces any pending Wi-Fi-only enqueue. */
    fun enqueueDefault(context: Context, folder: File, requireUnmetered: Boolean) {
        enqueue(context, folder, requireUnmetered, replaceExisting = true)
    }

    private fun enqueue(
        context: Context,
        folder: File,
        requireUnmetered: Boolean,
        replaceExisting: Boolean,
    ) {
        folder.mkdirs()
        val target = File(folder, DEFAULT_MODEL_FILENAME)
        val networkType = if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to DEFAULT_MODEL_URL,
                    ModelDownloadWorker.KEY_TARGET to target.absolutePath,
                )
            )
            .build()
        val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
