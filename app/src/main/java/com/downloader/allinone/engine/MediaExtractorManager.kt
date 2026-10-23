package com.downloader.allinone.engine

import com.downloader.allinone.AllInOneApp
import com.downloader.allinone.model.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object MediaExtractorManager {

    /**
     * Urutan ekstraksi:
     * 1. Ekstraktor khusus platform (cepat, tanpa proses tambahan)
     * 2. yt-dlp (1000+ situs, selalu bisa di-update)
     * 3. Universal/Deep Sniffer (HTML, iframe, WebView headless) untuk situs yang tidak dikenal yt-dlp
     * Semua pesan error dikumpulkan agar pengguna tahu penyebab sebenarnya.
     */
    suspend fun extract(rawUrl: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanUrl(rawUrl)
        val context = AllInOneApp.instance
        val errors = mutableListOf<String>()
        var result: Result<MediaInfo>? = null

        if (hasSpecificExtractor(cleanUrl)) {
            val r = runCatching { runSpecific(cleanUrl) }.getOrElse { Result.failure(it) }
            if (r.isUsable()) result = r else r.exceptionOrNull()?.message?.let { errors.add(it) }
        }

        // Spotify tidak didukung yt-dlp (DRM) — SpotifyExtractor sudah punya pencarian sendiri.
        val allowYtDlp = !SpotifyExtractor.canHandle(cleanUrl)
        if (result == null && allowYtDlp) {
            val r = YtDlpEngine.extract(context, cleanUrl)
            if (r.isUsable()) result = r else r.exceptionOrNull()?.message?.let { errors.add(it) }
        }

        if (result == null && !SpotifyExtractor.canHandle(cleanUrl)) {
            val r = runCatching { UniversalExtractor.extract(cleanUrl) }.getOrElse { Result.failure(it) }
            if (r.isUsable()) result = r else r.exceptionOrNull()?.message?.let { errors.add(it) }
        }

        if (result == null) {
            val detail = errors.distinct().firstOrNull { it.isNotBlank() }
            return@withContext Result.failure(
                Exception(
                    buildString {
                        append("Tidak dapat mengambil video dari tautan ini.")
                        if (detail != null) append("\nPenyebab: ").append(detail)
                        append("\nPastikan link publik, bisa diputar di browser, dan tidak memerlukan login.")
                    }
                )
            )
        }

        val mediaInfo = result.getOrThrow()
        val enrichedFormats = coroutineScope {
            mediaInfo.formats.map { fmt ->
                async(Dispatchers.IO) {
                    // Simpan URL halaman asli agar downloader bisa memakai yt-dlp sebagai cadangan
                    var f = if (fmt.sourcePageUrl == null) fmt.copy(sourcePageUrl = cleanUrl) else fmt
                    if (f.approxSizeMb == null && f.downloadUrl.isNotBlank() &&
                        !f.downloadUrl.contains(".m3u8", ignoreCase = true) &&
                        !f.downloadUrl.contains(".mpd", ignoreCase = true)
                    ) {
                        val realSize = NetworkClient.fetchContentLengthMb(f.downloadUrl, f.customHeaders)
                        // Ukuran tidak diketahui dibiarkan null (lebih jujur daripada angka tebakan)
                        if (realSize != null && realSize > 0.1) f = f.copy(approxSizeMb = realSize)
                    }
                    f
                }
            }.awaitAll()
        }
        Result.success(mediaInfo.copy(formats = enrichedFormats))
    }

    private fun hasSpecificExtractor(url: String): Boolean =
        TwitterExtractor.canHandle(url) || YouTubeExtractor.canHandle(url) || TikTokExtractor.canHandle(url) ||
            TelegramExtractor.canHandle(url) || SpotifyExtractor.canHandle(url) || SoundCloudExtractor.canHandle(url)

    private suspend fun runSpecific(url: String): Result<MediaInfo> = when {
        TwitterExtractor.canHandle(url) -> TwitterExtractor.extract(url)
        YouTubeExtractor.canHandle(url) -> YouTubeExtractor.extract(url)
        TikTokExtractor.canHandle(url) -> TikTokExtractor.extract(url)
        TelegramExtractor.canHandle(url) -> TelegramExtractor.extract(url)
        SpotifyExtractor.canHandle(url) -> SpotifyExtractor.extract(url)
        SoundCloudExtractor.canHandle(url) -> SoundCloudExtractor.extract(url)
        else -> Result.failure(Exception("Tidak ada ekstraktor khusus."))
    }

    private fun Result<MediaInfo>.isUsable(): Boolean =
        isSuccess && getOrNull()?.formats?.any { it.downloadUrl.isNotBlank() || it.ytdlpFormatId != null } == true

    private fun cleanUrl(url: String): String {
        val trimmed = url.trim()
        val urlMatcher = java.util.regex.Pattern.compile("(https?://[^\\s\"'<>]+)").matcher(trimmed)
        if (urlMatcher.find()) {
            return urlMatcher.group(1) ?: trimmed
        }
        return trimmed
    }
}
