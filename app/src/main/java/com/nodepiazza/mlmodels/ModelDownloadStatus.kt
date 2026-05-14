package com.nodepiazza.mlmodels

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Public-facing state of a model download. Drives the in-app banner & picker so the user can tell
 * at a glance whether anything is happening behind the scenes.
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

    fun observe(context: Context, spec: ModelSpec): Flow<ModelDownloadState> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(ModelBootstrap.workName(spec))
            .map { infos -> infos.pickRelevant()?.toState() ?: ModelDownloadState.Idle }

    /**
     * A unique work name can map to several [WorkInfo] rows: with [ExistingWorkPolicy.KEEP] a
     * re-enqueue replaces a terminal run but the old row lingers, and retried/process-killed runs
     * leave stale generations behind. The list order isn't stable, so picking the first row makes
     * progress flip between generations. Prefer the live run that's furthest along.
     */
    private fun List<WorkInfo>.pickRelevant(): WorkInfo? {
        val running = filter { it.state == WorkInfo.State.RUNNING }
        if (running.isNotEmpty()) {
            return running.maxBy { it.progress.getLong(ModelDownloadWorker.KEY_PROGRESS_BYTES, 0L) }
        }
        return firstOrNull { !it.state.isFinished } ?: firstOrNull()
    }

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
