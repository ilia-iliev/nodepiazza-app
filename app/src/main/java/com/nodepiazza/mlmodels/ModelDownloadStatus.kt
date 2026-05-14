package com.nodepiazza.mlmodels

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Public-facing state of the default model download. Drives the in-app banner & picker chip so
 * the user can tell at a glance whether anything is happening behind the scenes.
 */
sealed interface ModelDownloadState {
    /** Nothing in the queue. */
    object Idle : ModelDownloadState
    /** Enqueued but blocked on constraints (typically waiting for Wi-Fi). */
    object WaitingForNetwork : ModelDownloadState
    data class Running(val bytes: Long, val total: Long) : ModelDownloadState
    object Succeeded : ModelDownloadState
    object Failed : ModelDownloadState
    object OutOfSpace : ModelDownloadState
}

object ModelDownloadStatus {

    fun observe(context: Context): Flow<ModelDownloadState> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(ModelBootstrap.WORK_NAME)
            .map { infos -> infos.firstOrNull()?.toState() ?: ModelDownloadState.Idle }

    private fun WorkInfo.toState(): ModelDownloadState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadState.WaitingForNetwork
        WorkInfo.State.RUNNING -> {
            val bytes = progress.getLong(ModelDownloadWorker.KEY_PROGRESS_BYTES, 0L)
            val total = progress.getLong(ModelDownloadWorker.KEY_PROGRESS_TOTAL, 0L)
            ModelDownloadState.Running(bytes, total)
        }
        WorkInfo.State.SUCCEEDED -> ModelDownloadState.Succeeded
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
            val reason = outputData.getString(ModelDownloadWorker.KEY_FAILURE_REASON)
            if (reason == ModelDownloadWorker.FAILURE_OUT_OF_SPACE) ModelDownloadState.OutOfSpace
            else ModelDownloadState.Failed
        }
    }
}
