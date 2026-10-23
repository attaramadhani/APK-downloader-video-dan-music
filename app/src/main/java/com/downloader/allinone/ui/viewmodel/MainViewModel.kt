package com.downloader.allinone.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.downloader.allinone.model.DownloadStatus
import com.downloader.allinone.model.DownloadTask
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import com.downloader.allinone.service.DownloadRepository
import com.downloader.allinone.service.DownloadService
import com.downloader.allinone.util.DownloadHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _detectedPlatform = MutableStateFlow(PlatformType.GENERIC)
    val detectedPlatform: StateFlow<PlatformType> = _detectedPlatform.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _extractedMedia = MutableStateFlow<MediaInfo?>(null)
    val extractedMedia: StateFlow<MediaInfo?> = _extractedMedia.asStateFlow()

    private val _selectedFormat = MutableStateFlow<MediaFormat?>(null)
    val selectedFormat: StateFlow<MediaFormat?> = _selectedFormat.asStateFlow()

    // Dipisahkan untuk performa maksimal 120 FPS tanpa lag:
    val activeDownloads: StateFlow<List<DownloadTask>> = DownloadRepository.activeTasks
    val historyDownloads: StateFlow<List<DownloadTask>> = DownloadRepository.historyTasks
    val downloadQueue: StateFlow<List<DownloadTask>> = DownloadRepository.activeTasks

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun onUrlChanged(newUrl: String) {
        _urlInput.value = newUrl
        if (newUrl.isNotBlank()) {
            _detectedPlatform.value = PlatformType.detectFromUrl(newUrl)
        } else {
            _detectedPlatform.value = PlatformType.GENERIC
        }
    }

    fun clearUrl() {
        _urlInput.value = ""
        _detectedPlatform.value = PlatformType.GENERIC
        _extractedMedia.value = null
        _selectedFormat.value = null
        _errorMessage.value = null
    }

    fun selectFormat(format: MediaFormat) {
        _selectedFormat.value = format
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private val _showTelegramLoginModal = MutableStateFlow(false)
    val showTelegramLoginModal: StateFlow<Boolean> = _showTelegramLoginModal.asStateFlow()

    fun openTelegramLogin() {
        _showTelegramLoginModal.value = true
    }

    fun closeTelegramLogin() {
        _showTelegramLoginModal.value = false
    }

    fun onTelegramLoginSuccess() {
        _showTelegramLoginModal.value = false
        analyzeUrl()
    }

    fun analyzeUrl() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) {
            _errorMessage.value = "Silakan masukkan atau tempel link URL terlebih dahulu."
            return
        }

        val platform = PlatformType.detectFromUrl(url)
        _detectedPlatform.value = platform
        _isAnalyzing.value = true
        _errorMessage.value = null
        _extractedMedia.value = null

        viewModelScope.launch {
            try {
                val result = com.downloader.allinone.engine.MediaExtractorManager.extract(url)
                if (result.isSuccess) {
                    val mediaInfo = result.getOrThrow()
                    _extractedMedia.value = mediaInfo
                    // Utamakan memilih format VIDEO secara default agar pengguna mendapatkan video MP4
                    _selectedFormat.value = mediaInfo.formats.firstOrNull { it.type == MediaType.VIDEO }
                        ?: mediaInfo.formats.firstOrNull()
                } else {
                    val error = result.exceptionOrNull()
                    if (error is com.downloader.allinone.engine.TelegramPrivateAuthRequiredException) {
                        _showTelegramLoginModal.value = true
                    } else {
                        _errorMessage.value = error?.message ?: "Gagal mengambil data video dari link tersebut."
                    }
                }
            } catch (e: Exception) {
                if (e is com.downloader.allinone.engine.TelegramPrivateAuthRequiredException) {
                    _showTelegramLoginModal.value = true
                } else {
                    _errorMessage.value = "Terjadi kesalahan: ${e.localizedMessage ?: "Format tidak didukung"}"
                }
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun loadSavedHistory(context: android.content.Context) {
        if (DownloadRepository.historyTasks.value.isEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val saved = DownloadHistoryManager.loadHistory(context.applicationContext)
                if (saved.isNotEmpty()) {
                    DownloadRepository.setTasks(saved)
                }
            }
        }
    }

    fun startDownload(context: android.content.Context) {
        val media = _extractedMedia.value ?: return
        val format = _selectedFormat.value ?: return

        if (format.downloadUrl.isBlank()) {
            _errorMessage.value = "Link streaming video tidak valid."
            return
        }

        val taskId = UUID.randomUUID().toString()
        val newTask = DownloadTask(
            id = taskId,
            title = media.title,
            author = media.author,
            thumbnailUrl = media.thumbnailUrl,
            platform = media.platform,
            selectedFormat = format,
            progress = 0.05f,
            status = DownloadStatus.DOWNLOADING,
            speedText = "Menghubungkan...",
            etaText = "Menyiapkan latar belakang..."
        )

        DownloadService.enqueueDownload(context.applicationContext, newTask)
    }

    fun pauseDownload(context: android.content.Context, taskId: String) {
        DownloadService.pauseDownload(context.applicationContext, taskId)
    }

    fun resumeDownload(context: android.content.Context, task: DownloadTask) {
        val updatedTask = task.copy(
            status = DownloadStatus.DOWNLOADING,
            speedText = "Melanjutkan...",
            etaText = "Menyiapkan file..."
        )
        DownloadService.enqueueDownload(context.applicationContext, updatedTask)
    }

    fun retryDownload(context: android.content.Context, task: DownloadTask) {
        val updatedTask = task.copy(
            status = DownloadStatus.DOWNLOADING,
            progress = 0.05f,
            errorMessage = null,
            speedText = "Menyambung ulang...",
            etaText = "Menyiapkan file..."
        )
        DownloadService.enqueueDownload(context.applicationContext, updatedTask)
    }

    fun removeTask(context: android.content.Context, id: String) {
        DownloadService.pauseDownload(context.applicationContext, id)
        com.downloader.allinone.downloader.DownloadStateTracker.cleanup(context.applicationContext, id)
        DownloadRepository.removeTask(id)
        DownloadHistoryManager.saveHistory(context.applicationContext, DownloadRepository.getAllTasks())
    }

    fun clearAllHistory(context: android.content.Context) {
        val activeTasks = DownloadRepository.activeTasks.value
        DownloadRepository.setTasks(activeTasks)
        DownloadHistoryManager.saveHistory(context.applicationContext, activeTasks)
    }
}
