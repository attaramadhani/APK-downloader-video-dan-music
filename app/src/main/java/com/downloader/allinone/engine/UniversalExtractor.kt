package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.regex.Pattern

object UniversalExtractor {

    // Instance publik Cobalt sering mati/butuh API key → pakai timeout pendek agar tidak membuat analisis lambat
    private val cobaltClient by lazy {
        NetworkClient.client.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val COBALT_INSTANCES = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.api.scannor.org",
        "https://co.wuk.sh/api/json",
        "https://cobalt-api.kwiatekm.tokyo/api/json"
    )

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val platform = PlatformType.detectFromUrl(url)

        // 1. Cek jika URL adalah tautan langsung file media (.mp4, .webm, .m3u8, .mp3, dsb)
        val directMedia = sniffDirectMediaUrl(url, platform)
        if (directMedia != null) {
            return@withContext Result.success(directMedia)
        }

        // 2. Jika Reddit: Gunakan API resmi publik Reddit
        if (platform == PlatformType.REDDIT) {
            val redditResult = extractReddit(url)
            if (redditResult.isSuccess) return@withContext redditResult
        }

        // 3. Jika Facebook: Ekstrak langsung dari HTML Facebook (playable_url / hd_src)
        if (platform == PlatformType.FACEBOOK) {
            val fbResult = extractFacebook(url)
            if (fbResult.isSuccess) return@withContext fbResult
        }

        // 4. Jika Instagram: Ekstrak via Embed Meta API / ddinstagram
        if (platform == PlatformType.INSTAGRAM) {
            val igResult = extractInstagram(url)
            if (igResult.isSuccess) return@withContext igResult
        }

        // 5. Deep Web Video Sniffer (Menganalisis website mana pun: kode HTML, frame iframe embed, JS player, dan Headless Network Sniffer)
        try {
            val sniffedMedia = DeepWebVideoSniffer.sniff(com.downloader.allinone.AllInOneApp.instance, url)
            if (sniffedMedia != null && sniffedMedia.formats.isNotEmpty()) {
                return@withContext Result.success(sniffedMedia)
            }
        } catch (_: Exception) {}

        // 6. Universal HTML5 Web Scraper (Mendukung 1000+ situs web umum: OpenGraph, Video Tag, JSON-LD)
        val webScraped = scrapeHtmlWebMedia(url, platform)
        if (webScraped != null) {
            return@withContext Result.success(webScraped)
        }

        // 6. Instance Ekstraktor Universal (Cobalt multi-instance)
        val cobaltResult = extractWithCobalt(url, platform)
        if (cobaltResult.isSuccess) {
            return@withContext cobaltResult
        }

        Result.failure(
            Exception(
                "Tidak dapat mengambil video dari tautan ini. Pastikan link bersifat publik dan dapat diakses tanpa login."
            )
        )
    }

    /**
     * 1. Deteksi URL langsung file media (CDN / File hosting / Cloud storage)
     */
    private fun sniffDirectMediaUrl(url: String, platform: PlatformType): MediaInfo? {
        val cleanUrl = url.split("?").first().lowercase()
        val isVideoExt = cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".mov") || cleanUrl.endsWith(".mkv")
        val isAudioExt = cleanUrl.endsWith(".mp3") || cleanUrl.endsWith(".m4a") || cleanUrl.endsWith(".wav") || cleanUrl.endsWith(".aac")

        if (isVideoExt || isAudioExt) {
            val ext = if (isVideoExt) "mp4" else "mp3"
            val type = if (isVideoExt) MediaType.VIDEO else MediaType.AUDIO
            val title = url.split("/").lastOrNull()?.split("?")?.firstOrNull() ?: "Media File"

            val formats = listOf(
                MediaFormat(
                    id = "direct_fmt_1",
                    label = if (isVideoExt) "Video Langsung ($ext)" else "Audio Langsung ($ext)",
                    extension = ext,
                    type = type,
                    downloadUrl = url,
                    approxSizeMb = 15.0,
                    note = "Tautan file langsung"
                )
            )

            return MediaInfo(
                url = url,
                title = URLDecoder.decode(title, "UTF-8").replace(".$ext", ""),
                author = "Tautan Langsung",
                thumbnailUrl = "",
                durationText = "Berkas Media",
                platform = platform,
                formats = formats
            )
        }
        return null
    }

    /**
     * 2. Ekstraksi Video Reddit
     */
    private fun extractReddit(url: String): Result<MediaInfo> {
        try {
            val jsonUrl = if (url.contains(".json")) url else "${url.split("?").first().trimEnd('/')}.json"
            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val responseStr = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }
            val jsonArray = JSONArray(responseStr)
            val postData = jsonArray.getJSONObject(0).getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data")

            val title = postData.optString("title", "Reddit Video")
            val author = postData.optString("author", "Reddit")
            val thumb = postData.optString("thumbnail", "")

            val media = postData.optJSONObject("secure_media") ?: postData.optJSONObject("media")
            val redditVideo = media?.optJSONObject("reddit_video")

            if (redditVideo != null) {
                val videoUrl = redditVideo.getString("fallback_url")
                val height = redditVideo.optInt("height", 720)
                // Audio URL terpisah di Reddit biasanya DASH_audio.mp4
                val baseUrl = videoUrl.substringBeforeLast("/")
                val audioUrl = "$baseUrl/DASH_AUDIO_128.mp4"

                val formats = listOf(
                    MediaFormat(
                        id = "reddit_hd",
                        label = "Video MP4 (${height}p)",
                        resolution = "${height}p",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = videoUrl,
                        audioUrl = audioUrl,
                        approxSizeMb = 18.0,
                        note = "Disertai Audio Suara"
                    )
                )

                return Result.success(
                    MediaInfo(
                        url = url,
                        title = title,
                        author = "u/$author",
                        thumbnailUrl = if (thumb.startsWith("http")) thumb else "",
                        durationText = "Reddit Video",
                        platform = PlatformType.REDDIT,
                        formats = formats
                    )
                )
            }
        } catch (_: Exception) {}
        return Result.failure(Exception("Gagal mengekstrak video Reddit."))
    }

    /**
     * 3. Ekstraksi Video Facebook (playable_url / hd_src / sd_src)
     */
    private fun extractFacebook(url: String): Result<MediaInfo> {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val html = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }

            // Cari HD dan SD streaming URL
            val hdMatch = Regex("""playable_url_quality_hd["']:\s*["']([^"']+)["']""").find(html)
                ?: Regex("""hd_src["']:\s*["']([^"']+)["']""").find(html)
            val sdMatch = Regex("""playable_url["']:\s*["']([^"']+)["']""").find(html)
                ?: Regex("""sd_src["']:\s*["']([^"']+)["']""").find(html)

            val hdUrl = hdMatch?.groupValues?.get(1)?.replace("\\/", "/")
            val sdUrl = sdMatch?.groupValues?.get(1)?.replace("\\/", "/")

            val titleMatch = Regex("""<meta property="og:title" content="([^"]+)"""").find(html)
                ?: Regex("""<title>([^<]+)</title>""").find(html)
            val title = titleMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: "Facebook Video"

            val thumbMatch = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)
            val thumb = thumbMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

            val formats = mutableListOf<MediaFormat>()
            if (!hdUrl.isNullOrBlank()) {
                formats.add(
                    MediaFormat(
                        id = "fb_hd",
                        label = "Video HD 720p (MP4)",
                        resolution = "HD",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = hdUrl,
                        approxSizeMb = 25.0,
                        note = "Kualitas Tinggi HD"
                    )
                )
            }
            if (!sdUrl.isNullOrBlank()) {
                formats.add(
                    MediaFormat(
                        id = "fb_sd",
                        label = "Video SD (MP4)",
                        resolution = "SD",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = sdUrl,
                        approxSizeMb = 12.0,
                        note = "Kualitas Standar"
                    )
                )
            }

            if (formats.isNotEmpty()) {
                return Result.success(
                    MediaInfo(
                        url = url,
                        title = title,
                        author = "Facebook",
                        thumbnailUrl = thumb,
                        durationText = "Facebook Video",
                        platform = PlatformType.FACEBOOK,
                        formats = formats
                    )
                )
            }
        } catch (_: Exception) {}
        return Result.failure(Exception("Gagal mengekstrak video Facebook."))
    }

    /**
     * 4. Ekstraksi Instagram (Embed & ddinstagram scraper)
     */
    private fun extractInstagram(url: String): Result<MediaInfo> {
        try {
            // Coba via ddinstagram atau embed endpoint
            val cleanPath = url.split("?").first().trimEnd('/')
            val embedUrl = "$cleanPath/embed/captioned/"

            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val html = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }

            val videoMatch = Regex("""class="EmbeddedVideo"[^>]*src="([^"]+)"""").find(html)
                ?: Regex("""video_url["']:\s*["']([^"']+)["']""").find(html)
                ?: Regex("""<video[^>]*src="([^"]+)"""").find(html)

            if (videoMatch != null) {
                val videoUrl = videoMatch.groupValues[1].replace("&amp;", "&").replace("\\/", "/")
                val thumbMatch = Regex("""<img[^>]*class="EmbeddedMediaImage"[^>]*src="([^"]+)"""").find(html)
                val thumb = thumbMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

                val formats = listOf(
                    MediaFormat(
                        id = "ig_video_hd",
                        label = "Instagram Video (HD MP4)",
                        resolution = "HD",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = videoUrl,
                        approxSizeMb = 15.0,
                        note = "Video Instagram Asli"
                    ),
                    MediaFormat(
                        id = "ig_audio_mp3",
                        label = "Audio Musik (MP3)",
                        extension = "mp3",
                        type = MediaType.AUDIO,
                        downloadUrl = videoUrl,
                        approxSizeMb = 4.0,
                        note = "Suara Reels/Post"
                    )
                )

                return Result.success(
                    MediaInfo(
                        url = url,
                        title = "Instagram Reel / Video",
                        author = "Instagram",
                        thumbnailUrl = thumb,
                        durationText = "Reel",
                        platform = PlatformType.INSTAGRAM,
                        formats = formats
                    )
                )
            }
        } catch (_: Exception) {}
        return Result.failure(Exception("Gagal mengekstrak video Instagram."))
    }

    /**
     * 5. Universal HTML5 Web Scraper (Mendukung 1000+ Situs Web: OpenGraph, HTML5 Video, Twitter Card, JSON-LD)
     */
    private fun scrapeHtmlWebMedia(url: String, platform: PlatformType): MediaInfo? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val html = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }
            if (html.isBlank()) return null

            // 1. Cari video URL dari tag Meta OpenGraph atau Twitter
            val videoUrl = findRegexGroup(html, """<meta\s+property=["']og:video["']\s+content=["']([^"']+)["']""")
                ?: findRegexGroup(html, """<meta\s+property=["']og:video:url["']\s+content=["']([^"']+)["']""")
                ?: findRegexGroup(html, """<meta\s+property=["']og:video:secure_url["']\s+content=["']([^"']+)["']""")
                ?: findRegexGroup(html, """<meta\s+name=["']twitter:player:stream["']\s+content=["']([^"']+)["']""")
                ?: findRegexGroup(html, """<source\s+[^>]*src=["']([^"']+\.mp4[^"']*)["']""")
                ?: findRegexGroup(html, """<video\s+[^>]*src=["']([^"']+)["']""")
                ?: findRegexGroup(html, """"contentUrl["']:\s*["']([^"']+\.mp4[^"']*)["']""")

            if (videoUrl != null && videoUrl.startsWith("http")) {
                val title = findRegexGroup(html, """<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""")
                    ?: findRegexGroup(html, """<title>([^<]+)</title>""")
                    ?: "${platform.displayName} Video"

                val thumb = findRegexGroup(html, """<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""")
                    ?: findRegexGroup(html, """<meta\s+name=["']twitter:image["']\s+content=["']([^"']+)["']""")
                    ?: ""

                val cleanTitle = title.replace("&amp;", "&").replace("&#39;", "'").trim()

                val formats = listOf(
                    MediaFormat(
                        id = "web_video_mp4",
                        label = "Video Web MP4 (Kualitas Asli)",
                        resolution = "HD",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = videoUrl.replace("&amp;", "&"),
                        approxSizeMb = 20.0,
                        note = "Format langsung web"
                    ),
                    MediaFormat(
                        id = "web_audio_mp3",
                        label = "Audio Musik (MP3)",
                        extension = "mp3",
                        type = MediaType.AUDIO,
                        downloadUrl = videoUrl.replace("&amp;", "&"),
                        approxSizeMb = 5.0,
                        note = "Ekstrak audio suara"
                    )
                )

                return MediaInfo(
                    url = url,
                    title = cleanTitle,
                    author = platform.displayName,
                    thumbnailUrl = thumb.replace("&amp;", "&"),
                    durationText = "Web Video",
                    platform = platform,
                    formats = formats
                )
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 6. Fallback Cobalt Multi-Instance
     */
    private fun extractWithCobalt(url: String, platform: PlatformType): Result<MediaInfo> {
        for (apiUrl in COBALT_INSTANCES) {
            try {
                val payload = JSONObject().apply {
                    put("url", url)
                    put("videoQuality", "720")
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = payload.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(apiUrl)
                    .post(requestBody)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", NetworkClient.DEFAULT_USER_AGENT)
                    .build()

                val responseStr = cobaltClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    response.body?.string() ?: ""
                }

                val json = JSONObject(responseStr)
                val status = json.optString("status")

                if (status == "stream" || status == "redirect" || status == "tunnel" || status == "success") {
                    val streamUrl = json.optString("url")
                    val filename = json.optString("filename", "${platform.displayName} Media")

                    val formats = listOf(
                        MediaFormat(
                            id = "cobalt_video_hd",
                            label = "Video MP4 HD",
                            resolution = "HD",
                            extension = "mp4",
                            type = MediaType.VIDEO,
                            downloadUrl = streamUrl,
                            approxSizeMb = 22.0,
                            note = "Kualitas Tinggi"
                        ),
                        MediaFormat(
                            id = "cobalt_audio_mp3",
                            label = "Audio MP3",
                            extension = "mp3",
                            type = MediaType.AUDIO,
                            downloadUrl = streamUrl,
                            approxSizeMb = 5.0,
                            note = "Ekstrak musik"
                        )
                    )

                    val mediaInfo = MediaInfo(
                        url = url,
                        title = filename.ifBlank { "Media ${platform.displayName}" },
                        author = platform.displayName,
                        thumbnailUrl = "",
                        durationText = "HD Media",
                        platform = platform,
                        formats = formats
                    )

                    return Result.success(mediaInfo)
                }
            } catch (_: Exception) {}
        }
        return Result.failure(Exception("Cobalt tidak dapat memproses tautan ini."))
    }

    private fun findRegexGroup(text: String, regexPattern: String): String? {
        val matcher = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE).matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }
}
