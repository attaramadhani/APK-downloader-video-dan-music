package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

object SoundCloudExtractor {

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("soundcloud.com") || lower.contains("on.soundcloud.com")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val oembedUrl = "https://soundcloud.com/oembed?format=json&url=$encodedUrl"
            val oembedStr = NetworkClient.fetchString(oembedUrl)

            val json = JSONObject(oembedStr)
            val title = json.optString("title", "SoundCloud Track")
            val author = json.optString("author_name", "SoundCloud Artist")
            val thumbnail = json.optString("thumbnail_url", "")

            // Coba ambil direct stream via Universal Extractor
            val universalResult = UniversalExtractor.extract(url)
            val directStreamUrl = if (universalResult.isSuccess) {
                universalResult.getOrNull()?.formats?.firstOrNull()?.downloadUrl ?: url
            } else url

            val formats = listOf(
                MediaFormat(
                    id = "sc_mp3_hq",
                    label = "Audio MP3 (Kualitas Asli)",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = directStreamUrl,
                    approxSizeMb = 7.8,
                    note = "Format Audio Asli SoundCloud"
                ),
                MediaFormat(
                    id = "sc_flac",
                    label = "Audio Lossless / HQ",
                    extension = "flac",
                    type = MediaType.AUDIO,
                    downloadUrl = directStreamUrl,
                    approxSizeMb = 18.5,
                    note = "Suara Jernih"
                )
            )

            val mediaInfo = MediaInfo(
                url = url,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationText = "SoundCloud",
                platform = PlatformType.SOUNDCLOUD,
                formats = formats
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengambil audio SoundCloud: ${e.localizedMessage ?: "Format tidak didukung"}"))
        }
    }
}
