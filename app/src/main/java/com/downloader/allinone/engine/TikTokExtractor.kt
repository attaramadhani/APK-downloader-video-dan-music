package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

object TikTokExtractor {

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com") || lower.contains("vt.tiktok.com") || lower.contains("vm.tiktok.com")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://www.tikwm.com/api/?url=$encodedUrl&hd=1"
            val responseStr = NetworkClient.fetchString(apiUrl)

            val json = JSONObject(responseStr)
            val code = json.optInt("code", -1)
            if (code != 0) {
                return@withContext Result.failure(Exception("Gagal membaca video TikTok (${json.optString("msg", "Video tidak ditemukan")})"))
            }

            val data = json.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Data video TikTok tidak valid."))

            val title = data.optString("title", "TikTok Video").take(90).ifBlank { "TikTok Video" }
            val author = data.optJSONObject("author")?.optString("nickname", "TikTok Creator") ?: "TikTok Creator"
            val cover = data.optString("cover", "")
            val durationSec = data.optInt("duration", 0)

            val rawVideoUrl = data.optString("hdplay", "").ifBlank { data.optString("play", "") }
            val rawMusicUrl = data.optString("music", "")

            val videoNoWatermarkHd = if (rawVideoUrl.isNotBlank() && !rawVideoUrl.startsWith("http")) "https://www.tikwm.com$rawVideoUrl" else rawVideoUrl
            val musicUrl = if (rawMusicUrl.isNotBlank() && !rawMusicUrl.startsWith("http")) "https://www.tikwm.com$rawMusicUrl" else rawMusicUrl

            val formats = mutableListOf<MediaFormat>()

            if (videoNoWatermarkHd.isNotBlank()) {
                val sizeBytes = data.optLong("hd_size", 0L).let { if (it > 0) it else data.optLong("size", 0L) }
                val sizeMb = if (sizeBytes > 0) sizeBytes / (1024.0 * 1024.0) else 18.5

                formats.add(
                    MediaFormat(
                        id = "tiktok_hd_nowm",
                        label = "Video HD (Tanpa Watermark)",
                        resolution = "HD",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = videoNoWatermarkHd,
                        approxSizeMb = sizeMb,
                        note = "Kualitas Jernih Asli"
                    )
                )
            }

            if (musicUrl.isNotBlank()) {
                formats.add(
                    MediaFormat(
                        id = "tiktok_audio",
                        label = "Audio Musik MP3",
                        extension = "mp3",
                        type = MediaType.AUDIO,
                        downloadUrl = musicUrl,
                        approxSizeMb = 4.5,
                        note = "Audio Sound TikTok"
                    )
                )
            }

            val durationMinutes = durationSec / 60
            val durationRemainingSec = durationSec % 60
            val durationText = String.format("%02d:%02d", durationMinutes, durationRemainingSec)

            val mediaInfo = MediaInfo(
                url = url,
                title = title,
                author = author,
                thumbnailUrl = cover,
                durationText = durationText,
                platform = PlatformType.TIKTOK,
                formats = formats
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengekstrak video TikTok: ${e.localizedMessage ?: "Format tidak didukung"}"))
        }
    }
}
