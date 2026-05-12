package com.nodepiazza.phase3.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * If the configured folder has no models, enqueues a one-shot download of the default model.
 * No-op once any model file is present (registry decides what counts). Idempotent — safe to call
 * on every app start.
 */
object ModelBootstrap {

    private const val DEFAULT_MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    private const val DEFAULT_MODEL_FILENAME = "gemma-4-E2B-it.litertlm"

    private const val WORK_NAME = "model_bootstrap_download"

    suspend fun ensureDefault(context: Context, registry: ModelRegistry, folder: File) {
        if (registry.listModels().isNotEmpty()) return
        folder.mkdirs()
        val target = File(folder, DEFAULT_MODEL_FILENAME)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to DEFAULT_MODEL_URL,
                    ModelDownloadWorker.KEY_TARGET to target.absolutePath,
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
