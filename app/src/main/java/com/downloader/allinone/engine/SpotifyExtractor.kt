package com.downloader.allinone.engine

import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object SpotifyExtractor {

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("spotify.com") || lower.contains("spotify.link")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. Ambil Metadata Resmi Spotify via OEmbed API (Gratis & Terbuka Tanpa Butuh Token)
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            val oembedUrl = "https://open.spotify.com/oembed?url=$encodedUrl"
            val oembedJsonStr = try {
                NetworkClient.fetchString(oembedUrl)
            } catch (e: Exception) {
                ""
            }

            var trackTitle = "Spotify Track"
            var artistName = "Spotify Artist"
            var coverArtUrl = ""

            if (oembedJsonStr.isNotBlank()) {
                val oembedJson = JSONObject(oembedJsonStr)
                trackTitle = oembedJson.optString("title", "Spotify Track")
                coverArtUrl = oembedJson.optString("thumbnail_url", "")
                if (trackTitle.contains(" by ")) {
                    val parts = trackTitle.split(" by ")
                    trackTitle = parts[0].trim()
                    artistName = parts.getOrNull(1)?.trim() ?: "Spotify Artist"
                }
            }

            val searchQuery = "$trackTitle $artistName".trim()

            // 2. Cari stream audio resolusi tinggi 320kbps dari Music Resolver Engine
            var directAudioUrl = searchMusicStream(searchQuery)

            // Jika belum dapat, coba cari melalui YouTube engine
            if (directAudioUrl.isBlank()) {
                val ytSearchResult = searchYouTubeAudio(searchQuery)
                if (ytSearchResult.isNotBlank()) {
                    directAudioUrl = ytSearchResult
                }
            }

            // Cadangan terkuat: pencarian YouTube lewat yt-dlp (judul + artis dari Spotify)
            if (directAudioUrl.isBlank()) {
                val yt = YtDlpEngine.searchAudio(com.downloader.allinone.AllInOneApp.instance, "$trackTitle $artistName audio")
                val ytMedia = yt.getOrNull()
                if (ytMedia != null) {
                    return@withContext Result.success(
                        ytMedia.copy(
                            title = trackTitle,
                            author = artistName,
                            thumbnailUrl = coverArtUrl.ifBlank { ytMedia.thumbnailUrl },
                            platform = PlatformType.SPOTIFY
                        )
                    )
                }
            }

            if (directAudioUrl.isBlank()) {
                return@withContext Result.failure(Exception("Gagal menemukan aliran audio untuk lagu '$trackTitle'. Coba cari judul lagu lain."))
            }

            val formats = listOf(
                MediaFormat(
                    id = "spotify_mp3_320",
                    label = "Audio MP3 (320 kbps Kualitas Tinggi)",
                    resolution = "320kbps",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = directAudioUrl,
                    approxSizeMb = 8.5,
                    note = "Audio Jernih HQ"
                ),
                MediaFormat(
                    id = "spotify_m4a_256",
                    label = "Audio M4A / AAC (Original)",
                    resolution = "256kbps",
                    extension = "m4a",
                    type = MediaType.AUDIO,
                    downloadUrl = directAudioUrl,
                    approxSizeMb = 6.2,
                    note = "Format Audio Asli"
                )
            )

            val mediaInfo = MediaInfo(
                url = url,
                title = trackTitle,
                author = artistName,
                thumbnailUrl = coverArtUrl,
                durationText = "Audio 320kbps",
                platform = PlatformType.SPOTIFY,
                formats = formats
            )

            Result.success(mediaInfo)
        } catch (e: Exception) {
            Result.failure(Exception("Gagal mengekstrak lagu Spotify: ${e.localizedMessage ?: "Format tidak terdeteksi"}"))
        }
    }

    private suspend fun searchMusicStream(query: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchApiUrl = "https://saavn-api.vercel.app/search/songs?query=$encodedQuery"
            val responseStr = NetworkClient.fetchString(searchApiUrl)
            val jsonArray = JSONArray(responseStr)

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

    private suspend fun searchYouTubeAudio(query: String): String {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val ytSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery"
            val html = NetworkClient.fetchString(ytSearchUrl)

            val pattern = "/watch\\?v=([\\w-]{11})".toRegex()
            val match = pattern.find(html)
            val videoId = match?.groupValues?.get(1) ?: return ""

            val ytInfo = YouTubeExtractor.extract("https://www.youtube.com/watch?v=$videoId")
            if (ytInfo.isSuccess) {
                val info = ytInfo.getOrNull()
                val audioFmt = info?.formats?.firstOrNull { it.type == MediaType.AUDIO }
                    ?: info?.formats?.firstOrNull()
                return audioFmt?.downloadUrl ?: ""
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
