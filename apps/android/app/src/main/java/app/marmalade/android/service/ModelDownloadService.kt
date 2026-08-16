package app.marmalade.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.marmalade.android.MainActivity
import app.marmalade.android.R
import app.marmalade.android.speech.STTModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Foreground service that downloads STT model files in the background.
 *
 * Receives a model ID via [EXTRA_MODEL_ID], looks up its file list from
 * [STTModelManager], downloads each file sequentially via OkHttp, and
 * reports progress through [STTModelManager.updateDownloadProgress].
 *
 * Shows a persistent notification with download progress. On completion
 * or failure, updates the model status and stops itself.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"
        const val EXTRA_MODEL_ID = "extra_model_id"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "model_downloads"

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var lastNotificationTime = 0L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (modelId == null) {
            Log.w(TAG, "No model ID provided, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createProgressNotification("Preparing download...", 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, createProgressNotification("Preparing download...", 0))
        }

        // Cancel any existing download
        downloadJob?.cancel()

        downloadJob = scope.launch {
            downloadModel(modelId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun downloadModel(modelId: String) {
        val modelManager = STTModelManager.getInstance(this)
        val model = STTModelManager.findModelById(modelId)

        if (model == null) {
            Log.e(TAG, "Unknown model: $modelId")
            stopSelf()
            return
        }

        if (model.files.isEmpty()) {
            Log.w(TAG, "Model $modelId has no files to download (bundled)")
            stopSelf()
            return
        }

        val outputDir = File(filesDir, "models/$modelId")
        outputDir.mkdirs()

        // Calculate total bytes across all files for cumulative progress
        val totalBytes = model.files.sumOf { it.sizeBytes }
        var downloadedBytes = 0L

        try {
            for (modelFile in model.files) {
                val finalFile = File(outputDir, modelFile.filename)
                val tempFile = File(outputDir, "${modelFile.filename}.tmp")

                // Skip already downloaded files
                if (finalFile.exists() && finalFile.length() > 0) {
                    downloadedBytes += modelFile.sizeBytes
                    val progress = downloadedBytes.toFloat() / totalBytes
                    modelManager.updateDownloadProgress(modelId, progress.coerceIn(0f, 0.99f))
                    continue
                }

                Log.i(TAG, "Downloading ${modelFile.filename} from ${modelFile.downloadUrl}")

                val request = Request.Builder()
                    .url(modelFile.downloadUrl)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code} for ${modelFile.filename}")
                }

                val body = response.body
                    ?: throw RuntimeException("Empty response for ${modelFile.filename}")

                // Stream to temp file with progress tracking
                val contentLength = body.contentLength()
                var fileDownloaded = 0L

                tempFile.outputStream().use { output ->
                    val source = body.source()
                    val buffer = ByteArray(8192)

                    while (true) {
                        val bytesRead = source.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        fileDownloaded += bytesRead

                        val cumulativeProgress =
                            (downloadedBytes + fileDownloaded).toFloat() / totalBytes
                        val clampedProgress = cumulativeProgress.coerceIn(0f, 0.99f)

                        modelManager.updateDownloadProgress(modelId, clampedProgress)

                        // Throttle notification updates to 1/second
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationTime >= 1000) {
                            lastNotificationTime = now
                            val percent = (clampedProgress * 100).toInt()
                            updateNotification(
                                "Downloading ${model.displayName}... $percent%",
                                percent,
                            )
                        }
                    }
                }

                // Rename temp to final
                tempFile.renameTo(finalFile)
                downloadedBytes += fileDownloaded

                Log.i(TAG, "Completed ${modelFile.filename}")
            }

            // Download complete
            modelManager.updateDownloadProgress(modelId, 1.0f) // Removes from progress map
            updateNotification("${model.displayName} downloaded", 100)
            Log.i(TAG, "All files downloaded for $modelId")

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelId", e)

            // Clean up partial files
            outputDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp")) file.delete()
            }

            // Report error so UI shows Error state with Retry button
            val errorMsg = e.message ?: "Download failed"
            modelManager.reportDownloadError(modelId, errorMsg)

            // Show error notification
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Download failed")
                    .setContentText("${model.displayName}: $errorMsg")
                    .setSmallIcon(R.drawable.ic_mic)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = "STT model download progress"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createProgressNotification(text: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model Download")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, progress == 0)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createProgressNotification(text, progress))
    }
}
