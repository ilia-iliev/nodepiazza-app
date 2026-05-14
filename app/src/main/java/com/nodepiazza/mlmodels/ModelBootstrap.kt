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
 * Enqueues model downloads. Every download is user-initiated from the picker — there is no
 * auto-bootstrap, so a model only ever arrives after the user explicitly chose it. Each model
 * gets its own unique work so downloads are tracked independently. Pass `requireUnmetered = false`
 * once the user has acknowledged the cellular cost.
 */
object ModelBootstrap {

    fun workName(spec: ModelSpec): String = "model_download_${spec.id}"

    fun enqueue(context: Context, folder: File, spec: ModelSpec, requireUnmetered: Boolean) {
        folder.mkdirs()
        val target = File(folder, spec.filename)
        val networkType = if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to spec.url,
                    ModelDownloadWorker.KEY_TARGET to target.absolutePath,
                )
            )
            .build()
        // KEEP leaves a running/queued download alone but lets a retry replace a terminal one.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(spec), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, spec: ModelSpec) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(spec))
    }
}
