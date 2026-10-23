package com.downloader.allinone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.downloader.allinone.MainActivity
import com.downloader.allinone.downloader.DownloadResult
import com.downloader.allinone.downloader.DownloadStateTracker
import com.downloader.allinone.downloader.FileDownloader
import com.downloader.allinone.model.DownloadStatus
import com.downloader.allinone.model.DownloadTask
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import com.downloader.allinone.util.DownloadHistoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object DownloadRepository {
    // Dipisahkan: Task aktif vs Task riwayat agar menu Riwayat 100% cepat & tidak lag saat download berjalan
    private val _activeTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val activeTasks = _activeTasks.asStateFlow()

    private val _historyTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val historyTasks = _historyTasks.asStateFlow()

    fun updateTask(task: DownloadTask) {
        val isHistory = task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED
        if (isHistory) {
            // Pindahkan dari aktif ke riwayat
            _activeTasks.update { list ->
                if (list.any { it.id == task.id }) list.filterNot { it.id == task.id } else list
            }
            _historyTasks.update { list ->
                val index = list.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    list.toMutableList().apply { set(index, task) }
                } else {
                    listOf(task) + list
                }
            }
        } else {
            // Task aktif (DOWNLOADING, PAUSED, QUEUED, PROCESSING)
            // Hanya hapus jika task sebelumnya tidak sengaja ada di riwayat
            _historyTasks.update { list ->
                if (list.any { it.id == task.id }) list.filterNot { it.id == task.id } else list
            }
            _activeTasks.update { list ->
                val index = list.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    list.toMutableList().apply { set(index, task) }
                } else {
                    listOf(task) + list
                }
            }
        }
    }

    fun removeTask(id: String) {
        _activeTasks.update { list -> list.filterNot { it.id == id } }
        _historyTasks.update { list -> list.filterNot { it.id == id } }
    }

    fun setTasks(allTasks: List<DownloadTask>) {
        val (history, active) = allTasks.partition {
            it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
        }
        _historyTasks.value = history
        _activeTasks.value = active
    }

    fun getAllTasks(): List<DownloadTask> {
        return _activeTasks.value + _historyTasks.value
    }
}

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "anydrop_download_channel"
        const val COMPLETE_CHANNEL_ID = "anydrop_complete_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "action_start"
        const val ACTION_PAUSE = "action_pause"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AUTHOR = "extra_author"
        const val EXTRA_THUMB = "extra_thumb"
        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_FORMAT_ID = "extra_format_id"
        const val EXTRA_FORMAT_LABEL = "extra_format_label"
        const val EXTRA_FORMAT_EXT = "extra_format_ext"
        const val EXTRA_FORMAT_TYPE = "extra_format_type"
        const val EXTRA_FORMAT_URL = "extra_format_url"
        const val EXTRA_FORMAT_AUDIO_URL = "extra_format_audio_url"
        const val EXTRA_FORMAT_SIZE = "extra_format_size"
        const val EXTRA_FORMAT_JSON = "extra_format_json"

        private val activeJobs = ConcurrentHashMap<String, Job>()

        fun enqueueDownload(context: Context, task: DownloadTask) {
            DownloadStateTracker.clearPause(task.id)

            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_ID, task.id)
                putExtra(EXTRA_TITLE, task.title)
                putExtra(EXTRA_AUTHOR, task.author)
                putExtra(EXTRA_THUMB, task.thumbnailUrl)
                putExtra(EXTRA_PLATFORM, task.platform.name)
                putExtra(EXTRA_FORMAT_ID, task.selectedFormat.id)
                putExtra(EXTRA_FORMAT_LABEL, task.selectedFormat.label)
                putExtra(EXTRA_FORMAT_EXT, task.selectedFormat.extension)
                putExtra(EXTRA_FORMAT_TYPE, task.selectedFormat.type.name)
                putExtra(EXTRA_FORMAT_URL, task.selectedFormat.downloadUrl)
                task.selectedFormat.audioUrl?.let { putExtra(EXTRA_FORMAT_AUDIO_URL, it) }
                task.selectedFormat.approxSizeMb?.let { putExtra(EXTRA_FORMAT_SIZE, it) }
                // Kirim format LENGKAP (termasuk header Referer/Cookie/User-Agent) agar tidak hilang
                putExtra(EXTRA_FORMAT_JSON, task.selectedFormat.toJson().toString())
            }
            // Simpan task ke repository lebih dulu supaya service bisa mengambil format aslinya
            if (DownloadRepository.getAllTasks().none { it.id == task.id }) {
                DownloadRepository.updateTask(task)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context, taskId: String) {
            DownloadStateTracker.requestPause(taskId)
            val job = activeJobs.remove(taskId)
            job?.cancel()

            val existing = DownloadRepository.getAllTasks().find { it.id == taskId }
            if (existing != null) {
                val pausedTask = existing.copy(
                    status = DownloadStatus.PAUSED,
                    speedText = "Dijeda",
                    etaText = "Tekan ▶ untuk lanjut"
                )
                DownloadRepository.updateTask(pausedTask)
                DownloadHistoryManager.saveHistory(context.applicationContext, DownloadRepository.getAllTasks())
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY

        if (action == ACTION_PAUSE) {
            pauseDownload(applicationContext, taskId)
            return START_NOT_STICKY
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Media Download"
        val author = intent.getStringExtra(EXTRA_AUTHOR) ?: ""
        val thumb = intent.getStringExtra(EXTRA_THUMB) ?: ""
        val platformStr = intent.getStringExtra(EXTRA_PLATFORM) ?: PlatformType.GENERIC.name
        val platform = try { PlatformType.valueOf(platformStr) } catch (_: Exception) { PlatformType.GENERIC }

        val existingFormat = DownloadRepository.getAllTasks().find { it.id == taskId }?.selectedFormat
        val format = MediaFormat.fromJsonString(intent.getStringExtra(EXTRA_FORMAT_JSON))
            ?: existingFormat?.takeIf { it.downloadUrl.isNotBlank() }
            ?: MediaFormat(
            id = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "fmt_default",
            label = intent.getStringExtra(EXTRA_FORMAT_LABEL) ?: "HD",
            extension = intent.getStringExtra(EXTRA_FORMAT_EXT) ?: "mp4",
            type = try { MediaType.valueOf(intent.getStringExtra(EXTRA_FORMAT_TYPE) ?: "VIDEO") } catch (_: Exception) { MediaType.VIDEO },
            downloadUrl = intent.getStringExtra(EXTRA_FORMAT_URL) ?: "",
            audioUrl = intent.getStringExtra(EXTRA_FORMAT_AUDIO_URL),
            approxSizeMb = if (intent.hasExtra(EXTRA_FORMAT_SIZE)) intent.getDoubleExtra(EXTRA_FORMAT_SIZE, 0.0) else null
        )

        val existingTask = DownloadRepository.getAllTasks().find { it.id == taskId }
        val task = existingTask?.copy(
            status = DownloadStatus.DOWNLOADING,
            speedText = "Menghubungkan...",
            etaText = "Melanjutkan..."
        ) ?: DownloadTask(
            id = taskId,
            title = title,
            author = author,
            thumbnailUrl = thumb,
            platform = platform,
            selectedFormat = format,
            progress = 0.05f,
            status = DownloadStatus.DOWNLOADING,
            speedText = "Menghubungkan...",
            etaText = "Menyiapkan file..."
        )

        val initialNotification = buildProgressNotification(title, task.progress, "Menghubungkan...", "AnyDrop Berjalan")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        DownloadRepository.updateTask(task)

        // Batalkan job lama bila ada
        activeJobs[taskId]?.cancel()

        val job = serviceScope.launch {
            try {
                val result = FileDownloader.download(
                    context = applicationContext,
                    taskId = taskId,
                    title = title,
                    format = format
                ) { progressInfo ->
                    if (!DownloadStateTracker.isPaused(taskId)) {
                        val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                        val updatedTask = current.copy(
                            progress = progressInfo.progress,
                            status = if (progressInfo.isComplete) DownloadStatus.COMPLETED else DownloadStatus.DOWNLOADING,
                            speedText = progressInfo.speedText,
                            etaText = progressInfo.etaText,
                            downloadedBytes = progressInfo.downloadedBytes,
                            totalBytes = progressInfo.totalBytes,
                            outputFilePath = progressInfo.outputUri?.toString()
                        )
                        DownloadRepository.updateTask(updatedTask)

                        if (!progressInfo.isComplete) {
                            val notif = buildProgressNotification(
                                title = title,
                                progress = progressInfo.progress,
                                speed = progressInfo.speedText,
                                eta = progressInfo.etaText
                            )
                            notificationManager.notify(NOTIFICATION_ID, notif)
                        }
                    }
                }

                when (result) {
                    is DownloadResult.Success -> {
                        val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                        val completedTask = current.copy(
                            progress = 1.0f,
                            status = DownloadStatus.COMPLETED,
                            speedText = "Selesai",
                            etaText = "Tersimpan di Galeri",
                            outputFilePath = result.uri.toString()
                        )
                        DownloadRepository.updateTask(completedTask)
                        val completeNotif = buildCompleteNotification(title)
                        notificationManager.notify(System.currentTimeMillis().toInt(), completeNotif)
                    }
                    is DownloadResult.Paused -> {
                        val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                        val pausedTask = current.copy(
                            status = DownloadStatus.PAUSED,
                            speedText = "Dijeda",
                            etaText = "Tekan ▶ untuk lanjut"
                        )
                        DownloadRepository.updateTask(pausedTask)
                    }
                    is DownloadResult.Error -> {
                        if (!DownloadStateTracker.isPaused(taskId)) {
                            val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                            val failedTask = current.copy(
                                status = DownloadStatus.FAILED,
                                errorMessage = result.message
                            )
                            DownloadRepository.updateTask(failedTask)
                        }
                    }
                }
            } catch (_: CancellationException) {
                val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                DownloadRepository.updateTask(
                    current.copy(
                        status = DownloadStatus.PAUSED,
                        speedText = "Dijeda",
                        etaText = "Tekan ▶ untuk lanjut"
                    )
                )
            } catch (e: Exception) {
                if (!DownloadStateTracker.isPaused(taskId)) {
                    val current = DownloadRepository.getAllTasks().find { it.id == taskId } ?: task
                    val failedTask = current.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Terjadi kesalahan koneksi."
                    )
                    DownloadRepository.updateTask(failedTask)
                }
            } finally {
                activeJobs.remove(taskId)
                DownloadHistoryManager.saveHistory(applicationContext, DownloadRepository.getAllTasks())
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }

        activeJobs[taskId] = job
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "Proses Unduhan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan progress unduhan media"
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Unduhan Selesai",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pemberitahuan saat file berhasil diunduh"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completeChannel)
        }
    }

    private fun buildProgressNotification(
        title: String,
        progress: Float,
        speed: String,
        eta: String
    ): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$speed • $eta")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildCompleteNotification(title: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setContentTitle("Unduhan Berhasil!")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }
}
