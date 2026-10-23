package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

object TelegramExtractor {

    private val TELEGRAM_URL_REGEX = Pattern.compile(
        "t(?:elegram)?\\.me/(?:[a-zA-Z0-9_]+)/(\\d+)",
        Pattern.CASE_INSENSITIVE
    )

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("t.me/") || lower.contains("telegram.me/")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            val appCtx = com.downloader.allinone.AllInOneApp.instance

            // 1. Cek jika ini adalah tautan saluran privat (format t.me/c/...)
            if (TelegramSessionBridge.isPrivateTelegramUrl(url)) {
                return@withContext TelegramSessionBridge.extractPrivateMedia(appCtx, url)
            }

            var videoUrl = ""
            var title = "Telegram Video Media"
            var author = "Telegram Channel"
            var thumbnail = ""

            // TAHAP 1: Ambil dari Halaman Embed Widget (?embed=1)
            val embedUrl = if (url.contains("?")) "$url&embed=1" else "$url?embed=1"
            val embedHtml = try { NetworkClient.fetchString(embedUrl) } catch (_: Exception) { "" }

            videoUrl = parseVideoUrlFromHtml(embedHtml)
            parseMetadataFromHtml(embedHtml)?.let { (t, a, th) ->
                if (t.isNotBlank()) title = t
                if (a.isNotBlank()) author = a
                if (th.isNotBlank()) thumbnail = th
            }

            // TAHAP 2: Jika video lama/tidak ada di embed, coba halaman Web Timeline (t.me/s/...)
            // Halaman timeline publik selalu merender video lama yang tidak muncul di embed biasa
            if (videoUrl.isBlank()) {
                val sUrl = convertToTimelineUrl(url)
                if (sUrl.isNotBlank()) {
                    val sHtml = try { NetworkClient.fetchString(sUrl) } catch (_: Exception) { "" }
                    videoUrl = parseVideoUrlFromHtml(sHtml)
                    parseMetadataFromHtml(sHtml)?.let { (t, a, th) ->
                        if (t.isNotBlank() && title == "Telegram Video Media") title = t
                        if (a.isNotBlank() && author == "Telegram Channel") author = a
                        if (th.isNotBlank() && thumbnail.isBlank()) thumbnail = th
                    }
                }
            }

            // TAHAP 3: Coba halaman utama Telegram langsung (t.me/channel/id)
            if (videoUrl.isBlank()) {
                val baseHtml = try { NetworkClient.fetchString(url) } catch (_: Exception) { "" }
                videoUrl = parseVideoUrlFromHtml(baseHtml)
            }

            // TAHAP 4: Dynamic Headless Sniffer pada Embed Widget Player
            // Untuk video lama atau file berukuran besar di mana Telegram hanya memuat video via JavaScript!
            if (videoUrl.isBlank()) {
                try {
                    val sniffed = DeepWebVideoSniffer.sniff(appCtx, embedUrl)
                    if (sniffed != null && sniffed.formats.isNotEmpty()) {
                        val sniffedFmt = sniffed.formats.firstOrNull { it.type == MediaType.VIDEO } ?: sniffed.formats.first()
                        videoUrl = sniffedFmt.downloadUrl
                        if (sniffed.title.isNotBlank() && title == "Telegram Video Media") title = sniffed.title
                        if (sniffed.thumbnailUrl.isNotBlank() && thumbnail.isBlank()) thumbnail = sniffed.thumbnailUrl
                    }
                } catch (_: Exception) {}
            }

            // TAHAP 5: Dynamic Headless Sniffer pada Web Timeline (t.me/s/...)
            if (videoUrl.isBlank()) {
                val sUrl = convertToTimelineUrl(url)
                if (sUrl.isNotBlank()) {
                    try {
                        val sniffed = DeepWebVideoSniffer.sniff(appCtx, sUrl)
                        if (sniffed != null && sniffed.formats.isNotEmpty()) {
                            val sniffedFmt = sniffed.formats.firstOrNull { it.type == MediaType.VIDEO } ?: sniffed.formats.first()
                            videoUrl = sniffedFmt.downloadUrl
                            if (sniffed.title.isNotBlank() && title == "Telegram Video Media") title = sniffed.title
                            if (sniffed.thumbnailUrl.isNotBlank() && thumbnail.isBlank()) thumbnail = sniffed.thumbnailUrl
                        }
                    } catch (_: Exception) {}
                }
            }

            if (videoUrl.isBlank()) {
                val universalResult = UniversalExtractor.extract(url)
                if (universalResult.isSuccess) {
                    return@withContext universalResult
                }
                return@withContext Result.failure(
                    Exception("Video lama pada postingan Telegram ini tidak dapat diakses atau telah dihapus oleh pengunggah.")
                )
            }

            val formats = listOf(
                MediaFormat(
                    id = "tg_video_original",
                    label = "Video Kualitas Asli (MP4)",
                    resolution = "HD",
                    extension = "mp4",
                    type = MediaType.VIDEO,
                    downloadUrl = videoUrl,
                    note = "Cloud CDN Telegram"
                ),
                MediaFormat(
                    id = "tg_audio",
                    label = "Ekstrak Audio (MP3)",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = videoUrl,
                    note = "Suara Saja"
                )
            )

            val mediaInfo = MediaInfo(
                url = url,
                title = title,
                author = author,
                thumbnailUrl = thumbnail,
                durationText = "Telegram",
                platform = PlatformType.TELEGRAM,
                formats = formats
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengekstrak video Telegram: ${e.localizedMessage ?: "Format tidak didukung"}"))
        }
    }

    private fun parseVideoUrlFromHtml(html: String): String {
        if (html.isBlank()) return ""

        // 1. Tag <video src="...">
        val videoTag = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (videoTag.find()) return videoTag.group(1) ?: ""

        // 2. Tag <source src="...">
        val sourceTag = Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (sourceTag.find()) return sourceTag.group(1) ?: ""

        // 3. Atribut data-video="..." atau data-src="..."
        val dataVideo = Pattern.compile("(?:data-video|data-src)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (dataVideo.find()) {
            val u = dataVideo.group(1) ?: ""
            if (u.startsWith("http")) return u
        }

        // 4. Direct Telesco.pe CDN link
        val telescoRegex = Pattern.compile("(https?://(?:cdn\\d*\\.telesco\\.pe|telegram\\.org)/file/[^\"'\\s<>]+)", Pattern.CASE_INSENSITIVE).matcher(html)
        if (telescoRegex.find()) return telescoRegex.group(1) ?: ""

        // 5. OpenGraph og:video
        val ogVideo = Pattern.compile("<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogVideo.find()) return ogVideo.group(1) ?: ""

        // 6. Twitter Player stream
        val twitterStream = Pattern.compile("<meta[^>]+name=[\"']twitter:player:stream[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (twitterStream.find()) return twitterStream.group(1) ?: ""

        return ""
    }

    private fun parseMetadataFromHtml(html: String): Triple<String, String, String>? {
        if (html.isBlank()) return null
        var title = ""
        var author = ""
        var thumbnail = ""

        val textMatcher = Pattern.compile("<div[^>]+class=[\"'][^\"']*tgme_widget_message_text[^\"']*[\"'][^>]*>(.*?)</div>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
        if (textMatcher.find()) {
            val rawText = textMatcher.group(1) ?: ""
            title = rawText.replace("<[^>]+>".toRegex(), " ").trim().take(90)
        }

        val authorMatcher = Pattern.compile("<div[^>]+class=[\"'][^\"']*tgme_widget_message_owner_name[^\"']*[\"'][^>]*>(.*?)</div>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(html)
        if (authorMatcher.find()) {
            val rawAuthor = authorMatcher.group(1) ?: ""
            author = rawAuthor.replace("<[^>]+>".toRegex(), "").trim()
        }

        val ogImageMatcher = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImageMatcher.find()) {
            thumbnail = ogImageMatcher.group(1) ?: ""
        }

        return Triple(title, author, thumbnail)
    }

    private fun convertToTimelineUrl(url: String): String {
        return try {
            val clean = url.replace("https://", "").replace("http://", "")
            val parts = clean.split("/").filter { it.isNotBlank() }
            if (parts.size >= 3 && (parts[0].contains("t.me") || parts[0].contains("telegram.me"))) {
                val channel = parts[1]
                val id = parts[2].split("?").first()
                if (channel != "s" && channel != "c") {
                    "https://t.me/s/$channel/$id"
                } else ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }
}
