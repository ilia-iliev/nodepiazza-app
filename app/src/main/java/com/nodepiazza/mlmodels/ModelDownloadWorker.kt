package com.nodepiazza.mlmodels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot foreground download into the configured models folder. Errors on filename collision
 * (per spec). Resumes from a `<name>.part` sidecar if the server honors a Range request; otherwise
 * starts over. Retries on transient failure via WorkManager.
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
        ensureChannel()
        setForeground(makeForegroundInfo(0, 0))

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
                            setForeground(makeForegroundInfo(written, totalLength))
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) Result.failure() else Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = applicationContext.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun makeForegroundInfo(written: Long, total: Long): ForegroundInfo {
        val pct = if (total > 0) (written * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model")
            .setContentText(if (total > 0) "$pct%" else "Starting…")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TARGET = "target"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 0xCAFE
    }
}
