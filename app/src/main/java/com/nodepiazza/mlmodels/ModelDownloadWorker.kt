package com.nodepiazza.mlmodels

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot background download into the configured models folder. Errors on filename collision
 * (per spec). Resumes from a `<name>.part` sidecar if the server honors a Range request; otherwise
 * starts over. Retries on transient failure via WorkManager — if the OS kills the worker mid-
 * download, the next run picks up where the `.part` file left off.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val targetPath = inputData.getString(KEY_TARGET) ?: return@withContext Result.failure()
        val target = File(targetPath)
        if (target.exists()) return@withContext Result.failure()
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")

        val resumeFrom = if (partial.exists()) partial.length() else 0L
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                connect()
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@withContext Result.failure()
            val resumed = responseCode == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (resumed) resumeFrom else 0L
            if (!resumed && resumeFrom > 0) partial.delete()
            val totalLength = startOffset + connection.contentLengthLong

            val parent = target.parentFile
            val remaining = totalLength - startOffset
            if (parent != null && remaining > 0 &&
                parent.usableSpace < remaining + ModelCatalog.SPACE_SAFETY_MARGIN) {
                partial.delete()
                return@withContext outOfSpace()
            }

            try {
                connection.inputStream.use { input ->
                    FileOutputStream(partial, resumed).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var written = startOffset
                        var lastReport = startOffset
                        while (true) {
                            if (isStopped) return@withContext Result.failure()
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            written += n
                            if (written - lastReport >= 1_000_000) {
                                lastReport = written
                                setProgress(
                                    workDataOf(
                                        KEY_PROGRESS_BYTES to written,
                                        KEY_PROGRESS_TOTAL to totalLength,
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (e.isOutOfSpace()) {
                    partial.delete()
                    return@withContext outOfSpace()
                }
                throw e
            }
            if (!partial.renameTo(target)) Result.failure() else Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    private fun outOfSpace(): Result =
        Result.failure(workDataOf(KEY_FAILURE_REASON to FAILURE_OUT_OF_SPACE))

    private fun IOException.isOutOfSpace(): Boolean {
        val msg = message.orEmpty()
        return msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space left", ignoreCase = true)
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TARGET = "target"
        const val KEY_PROGRESS_BYTES = "progress_bytes"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val FAILURE_OUT_OF_SPACE = "out_of_space"
    }
}
