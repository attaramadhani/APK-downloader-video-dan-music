package com.downloader.allinone.model

enum class DownloadStatus {
    IDLE,
    QUEUED,
    EXTRACTING,
    DOWNLOADING,
    PAUSED,
    PROCESSING, // Muxing or tagging
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val platform: PlatformType = PlatformType.GENERIC,
    val selectedFormat: MediaFormat,
    val progress: Float = 0f, // 0.0 to 1.0
    val speedText: String = "",
    val etaText: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val outputFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
