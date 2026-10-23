package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

object TwitterExtractor {

    private val STATUS_ID_REGEX = Pattern.compile("(?:status(?:es)?/|\\b)(\\d{10,22})\\b", Pattern.CASE_INSENSITIVE)

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("t.co") ||
                (url.trim().matches(Regex("^\\d{10,22}$")) && STATUS_ID_REGEX.matcher(url).find())
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val matcher = STATUS_ID_REGEX.matcher(url)
            if (!matcher.find()) {
                return@withContext Result.failure(IllegalArgumentException("ID tweet tidak valid atau link tidak sesuai format Twitter/X."))
            }

            val tweetId = matcher.group(1) ?: return@withContext Result.failure(IllegalArgumentException("ID tweet tidak ditemukan."))

            // 1. Coba Twitter Syndication CDN API resmi
            try {
                val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en"
                val headers = mapOf(
                    "User-Agent" to NetworkClient.DEFAULT_USER_AGENT,
                    "Referer" to "https://platform.twitter.com/",
                    "Origin" to "https://platform.twitter.com"
                )
                val synStr = NetworkClient.fetchString(syndicationUrl, headers)
                if (synStr.isNotBlank()) {
                    val synJson = JSONObject(synStr)
                    val text = synJson.optString("text", "Twitter Video")
                    val userObj = synJson.optJSONObject("user")
                    val author = if (userObj != null) "${userObj.optString("name", "User")} (@${userObj.optString("screen_name", "")})" else "Twitter / X User"
                    
                    val mediaDetails = synJson.optJSONArray("mediaDetails")
                    if (mediaDetails != null && mediaDetails.length() > 0) {
                        val mediaObj = mediaDetails.getJSONObject(0)
                        val videoInfo = mediaObj.optJSONObject("video_info")
                        val variants = videoInfo?.optJSONArray("variants")
                        val thumb = mediaObj.optString("media_url_https", "")

                        if (variants != null && variants.length() > 0) {
                            val formats = mutableListOf<MediaFormat>()
                            for (i in 0 until variants.length()) {
                                val v = variants.getJSONObject(i)
                                val contentType = v.optString("content_type", "")
                                val vUrl = v.optString("url", "")
                                val bitrate = v.optInt("bitrate", 0)

                                if (contentType == "video/mp4" && vUrl.isNotBlank()) {
                                    val resolution = if (bitrate > 1000000) "1080p HD" else if (bitrate > 500000) "720p HD" else "SD"
                                    formats.add(
                                        MediaFormat(
                                            id = "tw_syn_$i",
                                            label = "Video MP4 ($resolution)",
                                            resolution = resolution,
                                            extension = "mp4",
                                            type = MediaType.VIDEO,
                                            downloadUrl = vUrl,
                                            approxSizeMb = if (bitrate > 0) (bitrate / (8.0 * 1024 * 1024) * 15).coerceIn(2.0, 50.0) else 12.0,
                                            note = "Kualitas Asli Twitter"
                                        )
                                    )
                                }
                            }

                            if (formats.isNotEmpty()) {
                                // Tambahkan audio option
                                formats.add(
                                    MediaFormat(
                                        id = "tw_syn_audio",
                                        label = "Audio MP3",
                                        extension = "mp3",
                                        type = MediaType.AUDIO,
                                        downloadUrl = formats[0].downloadUrl,
                                        approxSizeMb = 3.5,
                                        note = "Suara saja"
                                    )
                                )

                                return@withContext Result.success(
                                    MediaInfo(
                                        url = "https://x.com/i/status/$tweetId",
                                        title = cleanTweetTitle(text),
                                        author = author,
                                        thumbnailUrl = thumb,
                                        durationText = "Twitter Video",
                                        platform = PlatformType.TWITTER,
                                        formats = formats
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Fallback vxTwitter / fxTwitter API
            val apiEndpoints = listOf(
                "https://api.vxtwitter.com/Twitter/status/$tweetId",
                "https://api.fxtwitter.com/status/$tweetId"
            )

            for (apiUrl in apiEndpoints) {
                try {
                    val res = NetworkClient.fetchString(apiUrl)
                    if (res.isNotBlank() && (res.contains("media_extended") || res.contains("mediaURLs") || res.contains("tweet"))) {
                        val rawJson = JSONObject(res)
                        val json = rawJson.optJSONObject("tweet") ?: rawJson

                        val tweetText = json.optString("text", "Twitter Video")
                        val author = "${json.optString("user_name", "Twitter User")} (@${json.optString("user_screen_name", "")})"
                        val mediaExtended = json.optJSONArray("media_extended")

                        val formats = mutableListOf<MediaFormat>()
                        var videoThumbnail = ""

                        if (mediaExtended != null && mediaExtended.length() > 0) {
                            for (i in 0 until mediaExtended.length()) {
                                val mediaObj = mediaExtended.getJSONObject(i)
                                val type = mediaObj.optString("type")
                                if (type == "video" || type == "gif") {
                                    val videoUrl = mediaObj.optString("url")
                                    videoThumbnail = mediaObj.optString("thumbnail_url")

                                    formats.add(
                                        MediaFormat(
                                            id = "tw_vx_$i",
                                            label = "Video Kualitas Asli (HD MP4)",
                                            resolution = "HD",
                                            extension = "mp4",
                                            type = MediaType.VIDEO,
                                            downloadUrl = videoUrl,
                                            approxSizeMb = 14.5,
                                            note = "Video tanpa watermark"
                                        )
                                    )
                                    formats.add(
                                        MediaFormat(
                                            id = "tw_vx_audio_$i",
                                            label = "Audio MP3",
                                            extension = "mp3",
                                            type = MediaType.AUDIO,
                                            downloadUrl = videoUrl,
                                            approxSizeMb = 4.2,
                                            note = "Suara saja"
                                        )
                                    )
                                    break
                                }
                            }
                        }

                        if (formats.isNotEmpty()) {
                            return@withContext Result.success(
                                MediaInfo(
                                    url = "https://x.com/i/status/$tweetId",
                                    title = cleanTweetTitle(tweetText),
                                    author = author,
                                    thumbnailUrl = videoThumbnail,
                                    durationText = "Twitter Video",
                                    platform = PlatformType.TWITTER,
                                    formats = formats
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            Result.failure(Exception("Tidak dapat menemukan video pada tweet ini. Pastikan tweet memiliki video/GIF dan akun tidak dikunci/private."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanTweetTitle(raw: String): String {
        return raw.replace("https?://\\S+".toRegex(), "")
            .replace("[\r\n\t]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { "Twitter Video" }
    }
}
