package com.downloader.allinone.engine

import android.content.Context
import android.util.Log
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * Mesin yt-dlp (lewat library youtubedl-android).
 *
 * yt-dlp mendukung 1000+ situs (YouTube, Instagram, Facebook, TikTok, X, Reddit, Vimeo,
 * Dailymotion, Bilibili, SoundCloud, Twitch, dan banyak situs berita/video lain), menangani
 * DASH, HLS, header, cookie, dan penggabungan video+audio dengan ffmpeg.
 * Yang terpenting: ekstraktornya bisa DIPERBARUI saat aplikasi berjalan, jadi saat sebuah
 * situs mengubah kodenya, cukup update yt-dlp tanpa merilis ulang APK.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"
    private const val PREFS = "ytdlp_prefs"
    private const val KEY_LAST_UPDATE = "last_update_ms"
    private const val UPDATE_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3 hari
    private const val EXTRACT_TIMEOUT_MS = 60_000L

    @Volatile
    var isReady = false
        private set

    private val initMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningProcesses = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Dipanggil sekali di Application.onCreate (di background). */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (ensureInit(app)) updateIfStale(app)
        }
    }

    suspend fun ensureInit(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isReady) return@withContext true
        initMutex.withLock {
            if (isReady) return@withLock true
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                isReady = true
            } catch (e: Throwable) {
                Log.e(TAG, "Gagal inisialisasi yt-dlp", e)
                isReady = false
            }
            isReady
        }
    }

    /** Perbarui yt-dlp ke versi stabil terbaru bila sudah lebih dari 3 hari. */
    suspend fun updateIfStale(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (!force && System.currentTimeMillis() - last < UPDATE_INTERVAL_MS) return@withContext false
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Update yt-dlp gagal: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------------
    // EKSTRAKSI INFO
    // ---------------------------------------------------------------------

    suspend fun extract(context: Context, url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        if (!ensureInit(context)) {
            return@withContext Result.failure(Exception("Mesin yt-dlp belum siap."))
        }
        try {
            val json = dumpJson(context, url, extraOptions = listOf("--no-playlist" to null))
            val info = pickFirstEntry(json) ?: return@withContext Result.failure(Exception("yt-dlp tidak menemukan media."))
            val media = toMediaInfo(info, url)
            if (media.formats.isEmpty()) Result.failure(Exception("yt-dlp tidak menemukan format yang bisa diunduh."))
            else Result.success(media)
        } catch (e: Throwable) {
            Result.failure(Exception(friendlyError(e.message)))
        }
    }

    /**
     * Cari lagu di YouTube berdasarkan judul + artis (dipakai fitur Spotify: metadata dari Spotify,
     * audio dari hasil pencarian YouTube). Tidak menyentuh stream Spotify yang terenkripsi.
     */
    suspend fun searchAudio(context: Context, query: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        if (!ensureInit(context)) return@withContext Result.failure(Exception("Mesin yt-dlp belum siap."))
        try {
            val json = dumpJson(context, "ytsearch1:$query", extraOptions = emptyList())
            val info = pickFirstEntry(json) ?: return@withContext Result.failure(Exception("Lagu tidak ditemukan."))
            val pageUrl = info.optString("webpage_url").ifBlank { info.optString("original_url") }
            val media = toMediaInfo(info, pageUrl)
            val audioOnly = media.formats.filter { it.type == MediaType.AUDIO }
            if (audioOnly.isEmpty()) Result.failure(Exception("Audio tidak ditemukan."))
            else Result.success(media.copy(formats = audioOnly))
        } catch (e: Throwable) {
            Result.failure(Exception(friendlyError(e.message)))
        }
    }

    private suspend fun dumpJson(context: Context, url: String, extraOptions: List<Pair<String, String?>>): JSONObject {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--skip-download")
        request.addOption("--no-warnings")
        request.addOption("--socket-timeout", "20")
        extraOptions.forEach { (opt, arg) -> if (arg == null) request.addOption(opt) else request.addOption(opt, arg) }
        writeCookieFile(context, url)?.let { request.addOption("--cookies", it.absolutePath) }

        val processId = "info_" + UUID.randomUUID().toString()
        // Pengaman: matikan proses yt-dlp bila macet terlalu lama
        val watchdog = scope.launch {
            delay(EXTRACT_TIMEOUT_MS)
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        }
        try {
            val response = YoutubeDL.getInstance().execute(request, processId = processId, callback = null)
            return JSONObject(response.out.trim().lines().last { it.trim().startsWith("{") })
        } finally {
            watchdog.cancel()
        }
    }

    private fun pickFirstEntry(json: JSONObject): JSONObject? {
        if (json.optString("_type") == "playlist") {
            val entries = json.optJSONArray("entries") ?: return null
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i)
                if (e != null) return e
            }
            return null
        }
        return json
    }

    private data class RawFormat(
        val id: String,
        val url: String,
        val ext: String,
        val height: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val sizeBytes: Long,
        val tbr: Double,
        val protocol: String,
        val headers: Map<String, String>,
        val note: String
    )

    private fun toMediaInfo(info: JSONObject, originalUrl: String): MediaInfo {
        val topHeaders = jsonToMap(info.optJSONObject("http_headers"))
        val raw = mutableListOf<RawFormat>()
        val arr = info.optJSONArray("formats")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val u = f.optString("url")
                if (!u.startsWith("http")) continue
                val protocol = f.optString("protocol", "https")
                val vcodec = f.optString("vcodec", "")
                val acodec = f.optString("acodec", "")
                // Bila codec tidak diketahui (situs generik), anggap berisi video+audio
                val hasVideo = vcodec != "none" && (vcodec.isNotBlank() || f.optInt("height", 0) > 0 || acodec.isBlank())
                val hasAudio = acodec != "none" && (acodec.isNotBlank() || vcodec.isBlank())
                if (f.optString("format_note").contains("storyboard", true) || f.optString("ext") == "mhtml") continue
                raw.add(
                    RawFormat(
                        id = f.optString("format_id"),
                        url = u,
                        ext = f.optString("ext", "mp4"),
                        height = f.optInt("height", 0),
                        hasVideo = hasVideo,
                        hasAudio = hasAudio,
                        sizeBytes = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L),
                        tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
                        protocol = protocol,
                        headers = topHeaders + jsonToMap(f.optJSONObject("http_headers")),
                        note = f.optString("format_note", "")
                    )
                )
            }
        }
        // Situs dengan satu URL saja (tanpa daftar formats)
        if (raw.isEmpty() && info.optString("url").startsWith("http")) {
            raw.add(
                RawFormat(
                    id = info.optString("format_id", "best"),
                    url = info.optString("url"),
                    ext = info.optString("ext", "mp4"),
                    height = info.optInt("height", 0),
                    hasVideo = info.optString("vcodec") != "none",
                    hasAudio = info.optString("acodec") != "none",
                    sizeBytes = info.optLong("filesize", 0L),
                    tbr = 0.0,
                    protocol = info.optString("protocol", "https"),
                    headers = topHeaders,
                    note = ""
                )
            )
        }

        val pageUrl = info.optString("webpage_url").ifBlank { originalUrl }
        val audioOnly = raw.filter { it.hasAudio && !it.hasVideo }
        val bestAudio = audioOnly.sortedWith(
            compareByDescending<RawFormat> { it.ext == "m4a" } // m4a/AAC paling kompatibel dgn MediaMuxer
                .thenByDescending { isNativeProtocol(it.protocol) }
                .thenByDescending { it.tbr }
        ).firstOrNull()

        val formats = mutableListOf<MediaFormat>()

        // VIDEO: satu pilihan terbaik per resolusi
        val videoCandidates = raw.filter { it.hasVideo }
        val byHeight = videoCandidates.groupBy { it.height }
        byHeight.keys.sortedDescending().forEach { h ->
            val group = byHeight[h] ?: return@forEach
            val best = group.sortedWith(
                compareByDescending<RawFormat> { it.hasAudio }
                    .thenByDescending { it.ext == "mp4" }
                    .thenByDescending { isNativeProtocol(it.protocol) }
                    .thenByDescending { it.tbr }
            ).first()
            val needsAudio = !best.hasAudio && bestAudio != null
            val selector = if (needsAudio) "${best.id}+${bestAudio!!.id}/${best.id}+bestaudio/best" else "${best.id}/best"
            val resLabel = if (h > 0) "${h}p" else "Asli"
            val totalBytes = best.sizeBytes + (if (needsAudio) bestAudio!!.sizeBytes else 0L)
            formats.add(
                MediaFormat(
                    id = "ytdlp_v_${best.id}",
                    label = "Video ${qualityName(h)} (MP4)",
                    resolution = resLabel,
                    extension = "mp4",
                    type = MediaType.VIDEO,
                    downloadUrl = best.url,
                    audioUrl = if (needsAudio) bestAudio!!.url else null,
                    customHeaders = best.headers,
                    approxSizeMb = if (totalBytes > 0) Math.round(totalBytes / 104857.6) / 10.0 else null,
                    note = if (best.note.isNotBlank()) best.note else "via yt-dlp",
                    sourcePageUrl = pageUrl,
                    ytdlpFormatId = selector
                )
            )
        }

        // AUDIO
        if (bestAudio != null || videoCandidates.isNotEmpty()) {
            val src = bestAudio ?: videoCandidates.first()
            formats.add(
                MediaFormat(
                    id = "ytdlp_a_${src.id}",
                    label = "Audio MP3 (Kualitas Terbaik)",
                    resolution = if (src.tbr > 0) "${src.tbr.toInt()}kbps" else "",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = src.url,
                    customHeaders = src.headers,
                    approxSizeMb = if (bestAudio != null && bestAudio.sizeBytes > 0) Math.round(bestAudio.sizeBytes / 104857.6) / 10.0 else null,
                    note = "Untuk didengar offline",
                    sourcePageUrl = pageUrl,
                    ytdlpFormatId = "bestaudio/best"
                )
            )
        }

        val duration = info.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
        return MediaInfo(
            url = pageUrl,
            title = info.optString("title").ifBlank { info.optString("id", "Video") },
            author = info.optString("uploader").ifBlank { info.optString("channel").ifBlank { info.optString("extractor_key", "Web") } },
            thumbnailUrl = info.optString("thumbnail", ""),
            durationText = if (duration > 0) formatDuration(duration) else "Media",
            platform = PlatformType.detectFromUrl(pageUrl),
            formats = formats
        )
    }

    private fun isNativeProtocol(p: String): Boolean = p == "https" || p == "http" || p.startsWith("m3u8")

    private fun qualityName(h: Int): String = when {
        h >= 2160 -> "4K 2160p"
        h >= 1440 -> "2K 1440p"
        h >= 1080 -> "Full HD 1080p"
        h >= 720 -> "HD 720p"
        h > 0 -> "${h}p"
        else -> "Kualitas Asli"
    }

    private fun formatDuration(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun jsonToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k -> map[k] = obj.optString(k) }
        return map
    }

    // ---------------------------------------------------------------------
    // UNDUH LANGSUNG MENGGUNAKAN yt-dlp
    // ---------------------------------------------------------------------

    /**
     * Mengunduh [pageUrl] dengan yt-dlp ke folder cache task.
     * yt-dlp otomatis melanjutkan file .part bila sebelumnya dijeda (resume).
     * @return file hasil unduhan.
     */
    suspend fun download(
        context: Context,
        taskId: String,
        pageUrl: String,
        formatSelector: String,
        audioOnly: Boolean,
        directHeaders: Map<String, String> = emptyMap(),
        onProgress: (progress: Float, etaSeconds: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!ensureInit(context)) throw Exception("Mesin yt-dlp belum siap.")
        val outDir = taskDir(context, taskId).apply { mkdirs() }

        val request = YoutubeDLRequest(pageUrl)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--no-warnings")
        request.addOption("--continue")
        request.addOption("--retries", "10")
        request.addOption("--fragment-retries", "10")
        request.addOption("--concurrent-fragments", "4")
        request.addOption("--socket-timeout", "30")
        request.addOption("-o", File(outDir, "media.%(ext)s").absolutePath)
        writeCookieFile(context, pageUrl)?.let { request.addOption("--cookies", it.absolutePath) }
        // Untuk URL media langsung (.mpd/.m3u8 hasil sniffer): kirim Referer/Origin/User-Agent yang sama
        directHeaders.forEach { (k, v) ->
            if (v.isNotBlank() && k.lowercase() in setOf("referer", "origin", "user-agent")) {
                request.addOption("--add-header", "$k:$v")
            }
        }

        if (audioOnly) {
            request.addOption("-f", formatSelector.ifBlank { "bestaudio/best" })
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("-f", formatSelector.ifBlank { "bv*+ba/b" })
            request.addOption("--merge-output-format", "mp4")
            // Pastikan hasil bisa diputar di galeri Android
            request.addOption("--remux-video", "mp4")
        }

        val processId = "dl_$taskId"
        runningProcesses[taskId] = processId
        try {
            YoutubeDL.getInstance().execute(request, processId = processId) { progress, eta, _ ->
                onProgress((progress / 100f).coerceIn(0f, 1f), eta)
            }
        } finally {
            runningProcesses.remove(taskId)
        }

        val result = outDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".txt") && it.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: throw Exception("yt-dlp selesai tetapi file hasil tidak ditemukan.")
        result
    }

    fun cancel(taskId: String) {
        val pid = runningProcesses.remove(taskId) ?: "dl_$taskId"
        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
    }

    fun taskDir(context: Context, taskId: String): File = File(context.cacheDir, "ytdlp_$taskId")

    /** Mengubah cookie WebView (mis. setelah sniffer membuka halaman) menjadi file cookies.txt untuk yt-dlp. */
    private fun writeCookieFile(context: Context, url: String): File? {
        return try {
            val host = URI(url).host ?: return null
            val raw = android.webkit.CookieManager.getInstance().getCookie(url) ?: return null
            if (raw.isBlank()) return null
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            val domain = "." + host.removePrefix("www.")
            raw.split(";").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    sb.append("$domain\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
                }
            }
            File(context.cacheDir, "ytdlp_cookies_${host.hashCode()}.txt").apply { writeText(sb.toString()) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun friendlyError(msg: String?): String {
        val m = msg ?: return "yt-dlp gagal memproses link ini."
        return when {
            m.contains("Unsupported URL", true) -> "Situs ini belum didukung yt-dlp."
            m.contains("Private", true) || m.contains("login", true) || m.contains("sign in", true) ->
                "Video ini privat atau butuh login."
            m.contains("geo", true) || m.contains("not available in your country", true) ->
                "Video dibatasi wilayah (geo-block)."
            m.contains("DRM", true) -> "Video ini dilindungi DRM dan tidak bisa diunduh."
            m.contains("timed out", true) || m.contains("Unable to download webpage", true) ->
                "Koneksi ke situs gagal / timeout."
            else -> m.lines().lastOrNull { it.contains("ERROR", true) }?.substringAfter("ERROR:")?.trim()?.take(200)
                ?: "yt-dlp gagal memproses link ini."
        }
    }
}
