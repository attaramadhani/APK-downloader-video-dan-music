package com.downloader.allinone.model

data class MediaInfo(
    val url: String,
    val title: String,
    val author: String = "",
    val thumbnailUrl: String = "",
    val durationText: String = "",
    val platform: PlatformType = PlatformType.GENERIC,
    val formats: List<MediaFormat> = emptyList()
)
