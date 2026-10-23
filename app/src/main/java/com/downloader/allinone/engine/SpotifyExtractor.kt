package com.downloader.allinone.engine

import com.downloader.allinone.AllInOneApp
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Link Spotify → lagu yang SAMA dari YouTube (cara kerja yang sama dengan spotDL).
 *
 * Audio Spotify sendiri dienkripsi DRM, jadi tidak diambil dari Spotify. Spotify hanya dipakai
 * untuk metadata (judul, artis, durasi, cover), lalu lagunya dicari di YouTube dan diunduh
 * lewat yt-dlp (hasil MP3).
 */
object SpotifyExtractor {

    private data class TrackMeta(
        val title: String,
        val artists: List<String>,
        val durationSec: Int,
        val coverUrl: String
    )

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("spotify.com") || lower.contains("spotify.link")
    }

    suspend fun extract(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. Link pendek spotify.link / spotify.app.link → link open.spotify.com
            val fullUrl = resolveShortLink(url)
            val type = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(track|album|playlist|episode|show|artist)/([A-Za-z0-9]+)""")
                .find(fullUrl)
            val kind = type?.groupValues?.get(1)
            val id = type?.groupValues?.get(2)

            if (kind == null || id == null) {
                return@withContext Result.failure(Exception("Link Spotify tidak dikenali. Gunakan link lagu, contoh: open.spotify.com/track/..."))
            }
            if (kind != "track") {
                return@withContext Result.failure(
                    Exception("Saat ini baru link LAGU (track) yang didukung. Buka lagunya di Spotify → Bagikan → Salin link lagu.")
                )
            }

            // 2. Metadata: judul, artis, durasi, cover
            val meta = fetchTrackMeta(id) ?: fetchOEmbedMeta(fullUrl)
                ?: return@withContext Result.failure(Exception("Gagal membaca info lagu dari Spotify. Periksa koneksi lalu coba lagi."))

            val artistText = meta.artists.joinToString(", ")
            val query = buildString {
                if (meta.artists.isNotEmpty()) append(meta.artists.first()).append(" - ")
                append(meta.title)
                append(" audio")
            }

            // 3. Cari di YouTube via yt-dlp (paling akurat & menghasilkan MP3 yang benar)
            val viaYtDlp = YtDlpEngine.searchAudio(AllInOneApp.instance, query).getOrNull()
            val media = viaYtDlp ?: searchWithNewPipe(query)
                ?: return@withContext Result.failure(
                    Exception("Lagu '${meta.title}' tidak ditemukan di YouTube. Coba lagi nanti atau cari judul lain.")
                )

            Result.success(
                media.copy(
                    title = meta.title,
                    author = artistText.ifBlank { media.author },
                    thumbnailUrl = meta.coverUrl.ifBlank { media.thumbnailUrl },
                    durationText = if (meta.durationSec > 0) formatDuration(meta.durationSec) else media.durationText,
                    platform = PlatformType.SPOTIFY,
                    formats = media.formats.filter { it.type == MediaType.AUDIO }.ifEmpty { media.formats }
                )
            )
        } catch (e: Exception) {
            Result.failure(Exception("Gagal memproses lagu Spotify: ${e.localizedMessage ?: "kesalahan tidak diketahui"}"))
        }
    }

    private fun resolveShortLink(url: String): String {
        if (url.contains("open.spotify.com")) return url
        return try {
            val req = Request.Builder().url(url).header("User-Agent", NetworkClient.DEFAULT_USER_AGENT).build()
            NetworkClient.client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                if (finalUrl.contains("open.spotify.com")) finalUrl
                else {
                    // Beberapa link pendek memakai halaman perantara berisi link asli
                    val body = resp.body?.string().orEmpty()
                    Regex("""https://open\.spotify\.com/[^"'\s<>]+""").find(body)?.value ?: url
                }
            }
        } catch (_: Exception) {
            url
        }
    }

    /** Halaman embed Spotify berisi JSON __NEXT_DATA__ lengkap dengan nama artis & durasi. */
    private fun fetchTrackMeta(id: String): TrackMeta? {
        return try {
            val html = NetworkClient.client.newCall(
                Request.Builder()
                    .url("https://open.spotify.com/embed/track/$id")
                    .header("User-Agent", NetworkClient.DEFAULT_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }

            val jsonText = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return null
            val root = JSONObject(jsonText)
            // Utamakan objek bertipe "track"; bila tidak ada, objek apa pun yang punya daftar artis
            val entity = findEntity(root) { it.optString("type") == "track" && (it.has("name") || it.has("title")) }
                ?: findEntity(root) { it.has("artists") && (it.has("name") || it.has("title")) }
                ?: return null

            val title = entity.optString("name").ifBlank { entity.optString("title") }
            if (title.isBlank()) return null

            val artists = mutableListOf<String>()
            entity.optJSONArray("artists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { artists.add(it) }
                }
            }
            if (artists.isEmpty()) {
                entity.optString("subtitle").takeIf { it.isNotBlank() }?.let { sub ->
                    artists.addAll(sub.split(",").map { it.trim() }.filter { it.isNotBlank() })
                }
            }

            val durationMs = entity.optLong("duration", 0L).takeIf { it > 0 }
                ?: entity.optJSONObject("duration")?.optLong("totalMilliseconds", 0L) ?: 0L

            var cover = ""
            val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
                ?: entity.optJSONObject("visualIdentity")?.optJSONArray("image")
            if (sources != null && sources.length() > 0) {
                cover = (0 until sources.length())
                    .mapNotNull { sources.optJSONObject(it) }
                    .maxByOrNull { it.optInt("width", it.optInt("maxWidth", 0)) }
                    ?.optString("url").orEmpty()
            }

            TrackMeta(title, artists, (durationMs / 1000).toInt(), cover)
        } catch (_: Exception) {
            null
        }
    }

    /** Cari objek JSON yang berisi data lagu (punya "artists" atau type "track") di mana pun letaknya. */
    private fun findEntity(node: Any?, depth: Int = 0, match: (JSONObject) -> Boolean): JSONObject? {
        if (depth > 12 || node == null) return null
        when (node) {
            is JSONObject -> {
                if (match(node)) return node
                val keys = node.keys()
                while (keys.hasNext()) {
                    findEntity(node.opt(keys.next()), depth + 1, match)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                findEntity(node.opt(i), depth + 1, match)?.let { return it }
            }
        }
        return null
    }

    /** Cadangan metadata: oEmbed (hanya judul + cover, kadang tanpa artis). */
    private fun fetchOEmbedMeta(url: String): TrackMeta? {
        return try {
            val json = JSONObject(
                NetworkClient.client.newCall(
                    Request.Builder()
                        .url("https://open.spotify.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))
                        .header("User-Agent", NetworkClient.DEFAULT_USER_AGENT)
                        .build()
                ).execute().use { it.body?.string().orEmpty() }
            )
            var title = json.optString("title")
            if (title.isBlank()) return null
            var artists = emptyList<String>()
            if (title.contains(" by ")) {
                artists = listOf(title.substringAfterLast(" by ").trim())
                title = title.substringBeforeLast(" by ").trim()
            }
            TrackMeta(title, artists, 0, json.optString("thumbnail_url"))
        } catch (_: Exception) {
            null
        }
    }

    /** Cadangan bila yt-dlp belum siap: cari video di YouTube lalu ekstrak dengan NewPipe. */
    private suspend fun searchWithNewPipe(query: String): MediaInfo? {
        return try {
            val html = NetworkClient.fetchString(
                "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            )
            val videoId = Regex("""/watch\?v=([\w-]{11})""").find(html)?.groupValues?.get(1) ?: return null
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            YouTubeExtractor.extract(watchUrl).getOrNull()
                ?.takeIf { info -> info.formats.any { it.type == MediaType.AUDIO } }
                // URL halaman YouTube dipakai sebagai cadangan yt-dlp saat unduhan langsung gagal
                ?.let { info -> info.copy(formats = info.formats.map { it.copy(sourcePageUrl = watchUrl) }) }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDuration(sec: Int): String = String.format("%d:%02d", sec / 60, sec % 60)
}
