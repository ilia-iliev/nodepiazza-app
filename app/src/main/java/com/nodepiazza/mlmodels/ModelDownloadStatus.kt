package com.nodepiazza.mlmodels

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

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
            .scan(ModelDownloadState.Idle as ModelDownloadState, ::clampForward)

    /**
     * [pickRelevant] is stateless, so a momentary progress reset (a retried run hasn't re-reported
     * yet) or a stale process-killed generation lingering in the list can make the chosen row's
     * byte count jump backwards — the percentage visibly jitters. Hold progress to a high-water
     * mark for the lifetime of one download; any non-[Running] state (or a different total, i.e. a
     * fresh download) clears it.
     */
    private fun clampForward(prev: ModelDownloadState, next: ModelDownloadState): ModelDownloadState =
        if (prev is ModelDownloadState.Running && next is ModelDownloadState.Running &&
            prev.total == next.total && next.bytes < prev.bytes
        ) {
            prev
        } else {
            next
        }

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
