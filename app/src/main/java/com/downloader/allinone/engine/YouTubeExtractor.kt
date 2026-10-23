package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object YouTubeExtractor {

    private val isInitialized = AtomicBoolean(false)

    private fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                NewPipe.init(
                    NewPipeDownloader(),
                    Localization.fromLocale(Locale.ENGLISH),
                    ContentCountry("US")
                )
            } catch (_: Exception) {}
        }
    }

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanYouTubeUrl(url)
        val videoId = extractVideoId(cleanUrl)

        if (videoId.isBlank()) {
            return@withContext Result.failure(Exception("Link YouTube tidak valid. Pastikan menyalin link lengkap video."))
        }

        // 1. Ambil Metadata Resmi YouTube via OEmbed API
        val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        var title = "YouTube Video"
        var author = "YouTube Creator"
        var thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

        try {
            val oembedStr = NetworkClient.fetchString(oembedUrl)
            if (oembedStr.isNotBlank()) {
                val oembedJson = JSONObject(oembedStr)
                title = oembedJson.optString("title", title)
                author = oembedJson.optString("author_name", author)
                thumbnailUrl = oembedJson.optString("thumbnail_url", thumbnailUrl)
            }
        } catch (_: Exception) {}

        val formats = mutableListOf<MediaFormat>()
        val seenResolutions = mutableSetOf<String>()

        // 2. Ekstraksi Seluruh Ragam Format Video & Audio via NewPipe Engine
        try {
            ensureInitialized()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, cleanUrl)

            if (streamInfo.name != null && streamInfo.name.isNotBlank()) {
                title = streamInfo.name
            }
            if (streamInfo.uploaderName != null && streamInfo.uploaderName.isNotBlank()) {
                author = streamInfo.uploaderName
            }

            // Temukan audio AAC / M4A kualitas terbaik untuk penggabungan video otomatis
            val bestM4aAudioStream = streamInfo.audioStreams
                ?.filter { it.format?.suffix?.contains("m4a", ignoreCase = true) == true || it.format?.mimeType?.contains("mp4", ignoreCase = true) == true }
                ?.maxByOrNull { it.averageBitrate }
                ?: streamInfo.audioStreams?.maxByOrNull { it.averageBitrate }

            val bestAudioUrl = bestM4aAudioStream?.content

            // A. Ambil Video Progressive Streams (Pre-Muxed dengan audio bawaan YouTube)
            streamInfo.videoStreams?.forEachIndexed { index, vs ->
                if (!vs.content.isNullOrBlank()) {
                    val res = vs.resolution ?: "HD"
                    val cleanRes = res.replace("p", "").trim()
                    seenResolutions.add(cleanRes)

                    val label = formatResolutionLabel(res)
                    formats.add(
                        MediaFormat(
                            id = "yt_prog_${res}_$index",
                            label = "$label (Suara Menyatu)",
                            resolution = res,
                            extension = "mp4",
                            type = MediaType.VIDEO,
                            downloadUrl = vs.content,
                            audioUrl = null, // Sudah tergabung dari server YouTube
                            note = "Video & Audio Siap Tonton"
                        )
                    )
                }
            }

            // B. Ambil Seluruh Variasi Resolusi DASH Streams (4K, 2K, 1080p, 720p, 480p, 360p, 240p, 144p)
            val mp4VideoOnlyStreams = streamInfo.videoOnlyStreams
                ?.filter { it.format?.suffix?.contains("mp4", ignoreCase = true) == true || it.codec?.contains("avc", ignoreCase = true) == true }
                ?: emptyList()

            val allVideoStreams = if (mp4VideoOnlyStreams.isNotEmpty()) mp4VideoOnlyStreams else (streamInfo.videoOnlyStreams ?: emptyList())

            allVideoStreams.forEachIndexed { index, vs ->
                if (!vs.content.isNullOrBlank()) {
                    val res = vs.resolution ?: "HD"
                    val cleanRes = res.replace("p", "").trim()

                    // Tambahkan seluruh variasi resolusi (jika belum ada format progressive untuk resolusi ini)
                    if (!seenResolutions.contains(cleanRes)) {
                        seenResolutions.add(cleanRes)
                        val label = formatResolutionLabel(res)

                        formats.add(
                            MediaFormat(
                                id = "yt_dash_${res}_$index",
                                label = "$label (Auto-Audio)",
                                resolution = res,
                                extension = "mp4",
                                type = MediaType.VIDEO,
                                downloadUrl = vs.content,
                                audioUrl = bestAudioUrl, // Otomatis disatukan dengan audio AAC
                                note = "Otomatis Digabung dengan Audio HD"
                            )
                        )
                    }
                }
            }

            // C. Seluruh Variasi Audio (M4A / WebM / AAC)
            streamInfo.audioStreams?.forEachIndexed { index, asStream ->
                if (!asStream.content.isNullOrBlank()) {
                    val bitrate = asStream.averageBitrate.takeIf { it > 0 }?.let { "${it}kbps" } ?: "HQ"
                    val ext = asStream.format?.suffix ?: "m4a"
                    formats.add(
                        MediaFormat(
                            id = "yt_audio_${bitrate}_$index",
                            label = "Audio $bitrate ($ext)",
                            resolution = bitrate,
                            extension = if (ext.contains("m4a") || ext.contains("mp4")) "m4a" else "mp3",
                            type = MediaType.AUDIO,
                            downloadUrl = asStream.content,
                            note = "Audio Musik Suara Jernih"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Format Audio MP3 320 kbps Kualitas Studio
        val audioStreamUrl = searchAudioStream("$title $author")
        if (audioStreamUrl.isNotBlank()) {
            formats.add(
                MediaFormat(
                    id = "yt_audio_mp3_320",
                    label = "Audio Musik MP3 (320 kbps)",
                    resolution = "320kbps",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = audioStreamUrl,
                    approxSizeMb = 8.5,
                    note = "Audio Musik Suara Jernih"
                )
            )
        }

        // Urutkan format: Video dengan resolusi tertinggi (4K -> 2K -> 1080p -> 720p -> 480p -> 360p -> 240p -> 144p) di atas, lalu Audio
        val sortedFormats = formats.sortedWith(
            compareByDescending<MediaFormat> { it.type == MediaType.VIDEO }
                .thenByDescending { parseResolutionNumber(it.resolution) }
        )

        if (sortedFormats.isEmpty()) {
            return@withContext Result.failure(Exception("Tidak dapat mengekstrak media dari video ini. Coba video YouTube lainnya."))
        }

        val mediaInfo = MediaInfo(
            url = cleanUrl,
            title = title,
            author = author,
            thumbnailUrl = thumbnailUrl,
            durationText = "YouTube HD",
            platform = PlatformType.YOUTUBE,
            formats = sortedFormats
        )

        Result.success(mediaInfo)
    }

    private fun formatResolutionLabel(res: String): String {
        val num = parseResolutionNumber(res)
        return when {
            num >= 2160 -> "Video 4K Ultra HD (2160p)"
            num >= 1440 -> "Video 2K Quad HD (1440p)"
            num >= 1080 -> "Video 1080p Full HD"
            num >= 720 -> "Video 720p HD"
            num >= 480 -> "Video 480p Standar"
            num >= 360 -> "Video 360p Medium"
            num >= 240 -> "Video 240p Hemat"
            num >= 144 -> "Video 144p Super Hemat"
            else -> "Video $res MP4"
        }
    }

    private fun parseResolutionNumber(res: String): Int {
        val digits = res.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private suspend fun searchAudioStream(query: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchApiUrl = "https://saavn-api.vercel.app/search/songs?query=$encodedQuery"
            val responseStr = NetworkClient.fetchString(searchApiUrl)
            val jsonArray = org.json.JSONArray(responseStr)

            if (jsonArray.length() > 0) {
                val firstItem = jsonArray.getJSONObject(0)
                val directUrl = firstItem.optString("url", "")
                if (directUrl.isNotBlank()) {
                    return directUrl
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanYouTubeUrl(url: String): String {
        val trimmed = url.trim()
        val videoId = extractVideoId(trimmed)
        return if (videoId.isNotBlank()) {
            "https://www.youtube.com/watch?v=$videoId"
        } else if (!trimmed.startsWith("http")) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    private fun extractVideoId(url: String): String {
        val pattern = "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?|shorts)/|.*[?&]v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})".toRegex()
        val match = pattern.find(url)
        return match?.groupValues?.get(1) ?: ""
    }
}
