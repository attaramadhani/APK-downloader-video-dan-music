package com.downloader.allinone.downloader

import android.content.Context
import android.net.Uri
import com.downloader.allinone.engine.CronetStreamFetcher
import com.downloader.allinone.engine.NetworkClient
import com.downloader.allinone.engine.YtDlpEngine
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.util.MediaMuxerHelper
import com.downloader.allinone.util.MediaStorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val progress: Float,
    val speedText: String,
    val etaText: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val isComplete: Boolean = false,
    val outputUri: Uri? = null
)

sealed class DownloadResult {
    data class Success(val uri: Uri) : DownloadResult()
    object Paused : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

object DownloadStateTracker {
    // Flag penanda jika user meminta jeda (Pause) pada taskId tertentu
    val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun isPaused(taskId: String): Boolean {
        return pauseFlags[taskId]?.get() == true
    }

    fun requestPause(taskId: String) {
        pauseFlags.computeIfAbsent(taskId) { AtomicBoolean(true) }.set(true)
        // Proses yt-dlp tidak berhenti hanya dengan membatalkan coroutine → hentikan prosesnya
        YtDlpEngine.cancel(taskId)
    }

    fun clearPause(taskId: String) {
        pauseFlags[taskId]?.set(false)
    }

    fun cleanup(context: Context, taskId: String, keepFlags: Boolean = false) {
        if (!keepFlags) pauseFlags.remove(taskId)
        try {
            val cacheDir = context.cacheDir
            File(cacheDir, "${taskId}_direct.part.idx").delete()
            File(cacheDir, "${taskId}_direct_hlsaudio.part").delete()
            File(cacheDir, "${taskId}_direct_hlsaudio.part.idx").delete()
            File(cacheDir, "${taskId}_hls.mp4").delete()
            File(cacheDir, "${taskId}_audio.m4a").delete()
            if (!keepFlags) YtDlpEngine.taskDir(context, taskId).deleteRecursively()
            File(cacheDir, "${taskId}_video.part").delete()
            File(cacheDir, "${taskId}_audio.part").delete()
            File(cacheDir, "${taskId}_direct.part").delete()
            File(cacheDir, "${taskId}_muxed.mp4").delete()
        } catch (_: Exception) {}
    }
}

object FileDownloader {

    private const val MAX_RETRIES = 5
    private const val BUFFER_SIZE = 256 * 1024 // 256 KB Buffer hemat CPU & super kencang

    /** Pesan HTTP terakhir per task (agar error yang tampil ke pengguna lebih informatif) */
    private val lastHttpError = ConcurrentHashMap<String, String>()

    /**
     * Titik masuk unduhan:
     * - Format dari yt-dlp, atau stream DASH (.mpd) → diunduh dengan yt-dlp (paling tangguh)
     * - Format lain → pengunduh bawaan (resume, Cronet, HLS); bila GAGAL dan URL halaman diketahui,
     *   otomatis dicoba ulang dengan yt-dlp (link CDN kedaluwarsa, 403, dsb.)
     */
    suspend fun download(
        context: Context,
        taskId: String,
        title: String,
        format: MediaFormat,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        DownloadStateTracker.clearPause(taskId)
        lastHttpError.remove(taskId)
        val isDash = format.downloadUrl.contains(".mpd", ignoreCase = true)
        val pageUrl = format.sourcePageUrl
        val ytdlpAllowed = pageUrl == null || !pageUrl.contains("spotify.com", ignoreCase = true)

        if (ytdlpAllowed && (format.ytdlpFormatId != null || isDash)) {
            val target = if (format.ytdlpFormatId != null) pageUrl ?: format.downloadUrl else format.downloadUrl
            val viaYtDlp = downloadViaYtDlp(context, taskId, title, format, target, format.ytdlpFormatId ?: defaultSelector(format), onProgress)
            if (viaYtDlp !is DownloadResult.Error || isDash || format.downloadUrl.isBlank()) return@withContext viaYtDlp
            // yt-dlp gagal → coba pengunduh bawaan dengan URL langsung
            val native = downloadNative(context, taskId, title, format, onProgress)
            return@withContext if (native is DownloadResult.Error) {
                DownloadResult.Error("${viaYtDlp.message}\n(Pengunduh bawaan juga gagal: ${native.message})")
            } else native
        }

        val native = downloadNative(context, taskId, title, format, onProgress)
        if (native is DownloadResult.Error && ytdlpAllowed && !pageUrl.isNullOrBlank() && !DownloadStateTracker.isPaused(taskId)) {
            onProgress(DownloadProgress(0.05f, "Mencoba ulang...", "Memakai mesin cadangan yt-dlp", 0L, 0L))
            DownloadStateTracker.cleanup(context, taskId, keepFlags = true)
            val viaYtDlp = downloadViaYtDlp(context, taskId, title, format, pageUrl, defaultSelector(format), onProgress)
            return@withContext if (viaYtDlp is DownloadResult.Error) {
                DownloadResult.Error("${native.message}\n(Cadangan yt-dlp: ${viaYtDlp.message})")
            } else viaYtDlp
        }
        native
    }

    private fun defaultSelector(format: MediaFormat): String {
        if (format.type == MediaType.AUDIO) return "bestaudio/best"
        val h = Regex("""(\d{3,4})p""").find(format.resolution + " " + format.label)?.groupValues?.get(1)?.toIntOrNull()
        return if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
    }

    private suspend fun downloadViaYtDlp(
        context: Context,
        taskId: String,
        title: String,
        format: MediaFormat,
        pageUrl: String,
        selector: String,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        var lastUi = 0L
        return try {
            onProgress(DownloadProgress(0.03f, "yt-dlp", "Menyiapkan mesin unduh...", 0L, 0L))
            val file = YtDlpEngine.download(
                context = context,
                taskId = taskId,
                pageUrl = pageUrl,
                formatSelector = selector,
                audioOnly = format.type == MediaType.AUDIO,
                directHeaders = if (pageUrl == format.downloadUrl) format.customHeaders else emptyMap()
            ) { p, eta ->
                val now = System.currentTimeMillis()
                if (now - lastUi >= 500) {
                    lastUi = now
                    onProgress(
                        DownloadProgress(
                            progress = (0.03f + p * 0.9f).coerceIn(0.03f, 0.93f),
                            speedText = "${(p * 100).toInt()}%",
                            etaText = if (eta > 0) formatEta(eta.toInt()) else "Memproses...",
                            downloadedBytes = 0L,
                            totalBytes = 0L
                        )
                    )
                }
            }
            if (DownloadStateTracker.isPaused(taskId)) return DownloadResult.Paused

            onProgress(DownloadProgress(0.96f, "Menyimpan...", "Menyalin ke galeri...", file.length(), file.length()))
            val ext = file.extension.lowercase().ifBlank { format.extension }
            val size = file.length()
            val uri = copyToMediaStore(context, file, title, format.copy(extension = ext))
                ?: return DownloadResult.Error("Gagal menyimpan file ke galeri.")
            YtDlpEngine.taskDir(context, taskId).deleteRecursively()
            onProgress(DownloadProgress(1f, "Selesai", "Tersimpan di Galeri", size, size, isComplete = true, outputUri = uri))
            DownloadResult.Success(uri)
        } catch (e: CancellationException) {
            YtDlpEngine.cancel(taskId)
            DownloadResult.Paused
        } catch (e: Throwable) {
            if (DownloadStateTracker.isPaused(taskId)) DownloadResult.Paused
            else DownloadResult.Error(e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(200) ?: "yt-dlp gagal mengunduh.")
        }
    }

    /** Simpan ke galeri; untuk format AUDIO, ambil trek audionya saja (bukan file video berlabel .mp3). */
    private fun saveFinal(context: Context, file: File, title: String, format: MediaFormat, taskId: String): Uri? {
        var src = file
        var fmt = format
        if (format.type == MediaType.AUDIO) {
            val out = File(context.cacheDir, "${taskId}_audio.m4a")
            when (MediaMuxerHelper.prepareAudioOnly(file, out)) {
                "m4a" -> if (out.exists() && out.length() > 0) { src = out; fmt = format.copy(extension = "m4a") }
                "mp3" -> fmt = format.copy(extension = "mp3")
                else -> {}
            }
        }
        val uri = copyToMediaStore(context, src, title, fmt)
        if (src != file) src.delete()
        return uri
    }

    private fun isProbablyHtml(file: File): Boolean = try {
        java.io.FileInputStream(file).use { fis ->
            val buf = ByteArray(64)
            val n = fis.read(buf)
            if (n <= 0) false else String(buf, 0, n, Charsets.ISO_8859_1).trimStart().lowercase().let {
                it.startsWith("<!doctype") || it.startsWith("<html")
            }
        }
    } catch (_: Exception) { false }

    private suspend fun downloadNative(
        context: Context,
        taskId: String,
        title: String,
        format: MediaFormat,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val tempDir = context.cacheDir

        try {
            // KASUS 1: DASH STREAM (Video terpisah + Audio terpisah)
            if (!format.audioUrl.isNullOrBlank()) {
                val videoPart = File(tempDir, "${taskId}_video.part")
                val audioPart = File(tempDir, "${taskId}_audio.part")
                val muxedFile = File(tempDir, "${taskId}_muxed.mp4")

                val videoBytes = AtomicLong(videoPart.length())
                val audioBytes = AtomicLong(audioPart.length())
                val videoTotal = AtomicLong(0L)
                val audioTotal = AtomicLong(0L)
                var lastUiUpdate = 0L
                var lastBytes = videoBytes.get() + audioBytes.get()

                // Unduh video dan audio secara paralel dengan dukungan RESUME penuh
                val (videoOk, audioOk) = coroutineScope {
                    val vJob = async(Dispatchers.IO) {
                        downloadWithResume(
                            context = context,
                            url = format.downloadUrl,
                            headers = format.customHeaders,
                            partFile = videoPart,
                            taskId = taskId
                        ) { cur, tot ->
                            videoBytes.set(cur)
                            if (tot > 0) videoTotal.set(tot)

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 500) {
                                val totCur = videoBytes.get() + audioBytes.get()
                                val totMax = (videoTotal.get() + audioTotal.get()).coerceAtLeast(1L)
                                val elapsed = (now - lastUiUpdate).coerceAtLeast(1)
                                val bytesDiff = (totCur - lastBytes).coerceAtLeast(0)
                                val speed = (bytesDiff * 1000.0) / elapsed
                                val progress = ((totCur.toFloat() / totMax.toFloat()) * 0.88f).coerceIn(0.05f, 0.88f)
                                val remaining = (totMax - totCur).coerceAtLeast(0L)
                                val eta = formatEta((remaining / speed.coerceAtLeast(1.0)).toInt())

                                onProgress(
                                    DownloadProgress(
                                        progress = progress,
                                        speedText = formatSpeed(speed),
                                        etaText = eta,
                                        downloadedBytes = totCur,
                                        totalBytes = totMax
                                    )
                                )
                                lastUiUpdate = now
                                lastBytes = totCur
                            }
                        }
                    }

                    val aJob = async(Dispatchers.IO) {
                        downloadWithResume(
                            context = context,
                            url = format.audioUrl,
                            headers = format.customHeaders,
                            partFile = audioPart,
                            taskId = taskId
                        ) { cur, tot ->
                            audioBytes.set(cur)
                            if (tot > 0) audioTotal.set(tot)
                        }
                    }

                    Pair(vJob.await(), aJob.await())
                }

                // Jika user menjeda di tengah jalan
                if (DownloadStateTracker.isPaused(taskId)) {
                    return@withContext DownloadResult.Paused
                }

                val videoValid = videoOk || (videoPart.exists() && videoPart.length() > 50 * 1024)
                val audioValid = audioOk || (audioPart.exists() && audioPart.length() > 10 * 1024)

                if (!videoValid || !audioValid) {
                    return@withContext DownloadResult.Error("Unduhan terputus.")
                }

                // Penggabungan Muxing Video + Audio
                onProgress(
                    DownloadProgress(
                        progress = 0.92f,
                        speedText = "Menyatukan...",
                        etaText = "Menyatukan Video & Audio HD...",
                        downloadedBytes = videoPart.length() + audioPart.length(),
                        totalBytes = videoPart.length() + audioPart.length()
                    )
                )

                val muxSuccess = MediaMuxerHelper.muxVideoAndAudio(videoPart, audioPart, muxedFile)
                val finalFile = if (muxSuccess && muxedFile.exists() && muxedFile.length() > 0) muxedFile else videoPart

                // Simpan ke MediaStore Galeri
                val savedUri = copyToMediaStore(context, finalFile, title, format)
                    ?: return@withContext DownloadResult.Error("Gagal menyimpan ke galeri.")

                // Hapus part file sementara karena sudah 100% selesai
                videoPart.delete()
                audioPart.delete()
                muxedFile.delete()

                onProgress(
                    DownloadProgress(
                        progress = 1.0f,
                        speedText = "Selesai",
                        etaText = "Tersimpan di Galeri",
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length(),
                        isComplete = true,
                        outputUri = savedUri
                    )
                )
                return@withContext DownloadResult.Success(savedUri)

            } else {
                // KASUS 2: FORMAT LANGSUNG (Twitter, TikTok, YouTube Progressive 720p/360p, Instagram, Spotify)
                val partFile = File(tempDir, "${taskId}_direct.part")
                var lastUiUpdate = 0L
                var lastBytes = partFile.length()

                val isHls = format.downloadUrl.contains(".m3u8", ignoreCase = true)
                if (isHls) {
                    val hls = HlsDownloader.download(
                        context = context,
                        url = format.downloadUrl,
                        headers = format.customHeaders,
                        partFile = partFile,
                        taskId = taskId
                    ) { p, label ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 500) {
                            onProgress(
                                DownloadProgress(
                                    progress = p.coerceIn(0.03f, 0.92f),
                                    speedText = formatFileSize(partFile.length()),
                                    etaText = label,
                                    downloadedBytes = partFile.length(),
                                    totalBytes = 0L
                                )
                            )
                            lastUiUpdate = now
                        }
                    }
                    if (DownloadStateTracker.isPaused(taskId)) return@withContext DownloadResult.Paused
                    hls.error?.let { return@withContext DownloadResult.Error(it) }

                    onProgress(
                        DownloadProgress(
                            progress = 0.94f,
                            speedText = "Memproses...",
                            etaText = "Mengonversi stream ke MP4...",
                            downloadedBytes = partFile.length(),
                            totalBytes = partFile.length()
                        )
                    )
                    // Gabungkan segmen TS/fMP4 menjadi MP4 asli (+ trek audio terpisah bila ada)
                    val mp4 = File(tempDir, "${taskId}_hls.mp4")
                    val audioPart = hls.audioPart
                    val remuxOk = if (audioPart != null) {
                        MediaMuxerHelper.muxVideoAndAudio(partFile, audioPart, mp4)
                    } else {
                        MediaMuxerHelper.muxVideoAndAudio(partFile, partFile, mp4)
                    }
                    val finalFile = if (remuxOk && mp4.exists() && mp4.length() > 50 * 1024) mp4 else partFile
                    val finalSize = finalFile.length()
                    val savedUri = saveFinal(context, finalFile, title, format, taskId)
                        ?: return@withContext DownloadResult.Error("Gagal menyimpan file ke galeri.")
                    partFile.delete()
                    audioPart?.delete()
                    mp4.delete()

                    onProgress(
                        DownloadProgress(
                            progress = 1.0f,
                            speedText = "Selesai",
                            etaText = "Tersimpan di Galeri",
                            downloadedBytes = finalSize,
                            totalBytes = finalSize,
                            isComplete = true,
                            outputUri = savedUri
                        )
                    )
                    return@withContext DownloadResult.Success(savedUri)
                }

                val success = downloadWithResume(
                    context = context,
                    url = format.downloadUrl,
                    headers = format.customHeaders,
                    partFile = partFile,
                    taskId = taskId
                ) { cur, tot ->
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 500) {
                        val elapsed = (now - lastUiUpdate).coerceAtLeast(1)
                        val bytesDiff = (cur - lastBytes).coerceAtLeast(0)
                        val speed = (bytesDiff * 1000.0) / elapsed
                        val progress = if (tot > 0) (cur.toFloat() / tot.toFloat()).coerceIn(0.05f, 0.95f) else 0.5f
                        val remaining = (tot - cur).coerceAtLeast(0L)
                        val eta = if (tot > 0) formatEta((remaining / speed.coerceAtLeast(1.0)).toInt()) else "${formatFileSize(cur)} terunduh"

                        onProgress(
                            DownloadProgress(
                                progress = progress,
                                speedText = formatSpeed(speed),
                                etaText = eta,
                                downloadedBytes = cur,
                                totalBytes = tot
                            )
                        )
                        lastUiUpdate = now
                        lastBytes = cur
                    }
                }

                // Jika user menjeda unduhan
                if (DownloadStateTracker.isPaused(taskId)) {
                    return@withContext DownloadResult.Paused
                }

                val directValid = success && (partFile.exists() && partFile.length() > 50 * 1024)
                if (!directValid) {
                    return@withContext DownloadResult.Error(lastHttpError[taskId] ?: "Unduhan terputus atau file terlalu kecil. Periksa koneksi internet Anda.")
                }

                // Server kadang membalas halaman HTML (error/captcha) dengan kode 200 — jangan simpan sebagai video
                if (isProbablyHtml(partFile)) {
                    partFile.delete()
                    return@withContext DownloadResult.Error("Server mengirim halaman web, bukan file video (link kedaluwarsa atau diblokir).")
                }

                val finalSize = partFile.length()
                val savedUri = saveFinal(context, partFile, title, format, taskId)
                    ?: return@withContext DownloadResult.Error("Gagal menyimpan file ke galeri.")

                partFile.delete() // Bersihkan part file

                onProgress(
                    DownloadProgress(
                        progress = 1.0f,
                        speedText = "Selesai",
                        etaText = "Tersimpan di Galeri",
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        isComplete = true,
                        outputUri = savedUri
                    )
                )
                return@withContext DownloadResult.Success(savedUri)
            }
        } catch (_: CancellationException) {
            // Ditangkap saat coroutine dibatalkan karena Pause
            return@withContext DownloadResult.Paused
        } catch (e: Exception) {
            return@withContext DownloadResult.Error(e.message ?: "Terjadi kesalahan unduh.")
        }
    }

    /**
     * Mesin Pengunduh dengan kemampuan RESUME (melanjutkan byte dari titik terakhir).
     * Jika file .part sudah ada 20 MB, ia akan meminta Range: bytes=20000000- sehingga tidak download ulang dari 0!
     */
    private suspend fun downloadWithResume(
        context: Context,
        url: String,
        headers: Map<String, String>,
        partFile: File,
        taskId: String,
        progressCallback: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var attempt = 0
        var completed = false

        while (!completed && attempt < MAX_RETRIES) {
            if (DownloadStateTracker.isPaused(taskId)) return@withContext false

            attempt++
            var inputStream: InputStream? = null
            var fos: FileOutputStream? = null

            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = buildSafeMediaRequest(url, headers).newBuilder()

                // Minta Range jika file part sudah memiliki data
                if (existingBytes > 0) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = NetworkClient.client.newCall(reqBuilder.build()).execute()

                // HTTP 416 (Range Not Satisfiable): Byte yang diminta melebihi ukuran file, artinya SELURUH FILE SUDAH LENGKAP 100%!
                if (response.code == 416 && existingBytes > 0) {
                    completed = true
                    break
                }

                val isRangeAccepted = response.code == 206
                val isOk = response.code == 200

                if (!isRangeAccepted && !isOk) {
                    val respCode = response.code
                    response.close()
                    // Jika OkHttp dicegat HTTP 403 oleh CDN Cloudflare, otomatis gunakan mesin Chromium Cronet
                    if (respCode == 403) {
                        val safeHeaders = buildSafeHeadersMap(url, headers).toMutableMap()
                        if (existingBytes > 0) safeHeaders["Range"] = "bytes=$existingBytes-"
                        val conn = CronetStreamFetcher.openConnection(context, url, safeHeaders)
                        conn.connect()
                        val cCode = conn.responseCode
                        if (cCode == 416 && existingBytes > 0) {
                            conn.disconnect()
                            completed = true
                            break
                        }
                        if (cCode != 200 && cCode != 206) {
                            conn.disconnect()
                            lastHttpError[taskId] = httpErrorMessage(cCode)
                            throw Exception(httpErrorMessage(cCode))
                        }
                        val cIsRange = cCode == 206
                        val cLen = conn.contentLengthLong
                        val cTotal = when {
                            cIsRange && cLen > 0 -> existingBytes + cLen
                            cCode == 200 && cLen > 0 -> cLen
                            else -> 0L
                        }
                        val cAppend = cIsRange && existingBytes > 0
                        if (!cAppend && existingBytes > 0) {
                            partFile.delete()
                            partFile.createNewFile()
                        }
                        fos = FileOutputStream(partFile, cAppend)
                        val cStream = conn.inputStream
                        val buffer = ByteArray(BUFFER_SIZE)
                        var currentDownloaded = if (cAppend) existingBytes else 0L
                        var read: Int

                        while (cStream.read(buffer).also { read = it } != -1) {
                            if (DownloadStateTracker.isPaused(taskId)) {
                                fos.flush()
                                conn.disconnect()
                                return@withContext false
                            }
                            currentCoroutineContext().ensureActive()
                            fos.write(buffer, 0, read)
                            currentDownloaded += read
                            progressCallback(currentDownloaded, cTotal)
                        }
                        fos.flush()
                        completed = true
                        conn.disconnect()
                        break
                    }
                    lastHttpError[taskId] = httpErrorMessage(respCode)
                    throw Exception(httpErrorMessage(respCode))
                }

                val body = response.body ?: throw Exception("Respon kosong dari server")
                val responseLen = body.contentLength()

                val totalLength = when {
                    isRangeAccepted && responseLen > 0 -> existingBytes + responseLen
                    isOk && responseLen > 0 -> responseLen
                    else -> 0L
                }

                // Jika server tidak mendukung resume (kode 200), reset file dari 0
                val appendMode = isRangeAccepted && existingBytes > 0
                if (!appendMode && existingBytes > 0) {
                    partFile.delete()
                }

                fos = FileOutputStream(partFile, appendMode)
                inputStream = body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var currentDownloaded = if (appendMode) existingBytes else 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    // Cek jika user menekan Pause di tengah proses
                    if (DownloadStateTracker.isPaused(taskId)) {
                        fos.flush()
                        return@withContext false
                    }
                    currentCoroutineContext().ensureActive()

                    fos.write(buffer, 0, read)
                    currentDownloaded += read
                    progressCallback(currentDownloaded, totalLength)
                }

                fos.flush()
                completed = true
            } catch (e: CancellationException) {
                return@withContext false
            } catch (e: Exception) {
                val curLen = partFile.length()
                if (curLen > 100 * 1024 && attempt >= MAX_RETRIES) {
                    completed = true
                    break
                }
                if (attempt >= MAX_RETRIES || DownloadStateTracker.isPaused(taskId)) {
                    return@withContext (partFile.exists() && partFile.length() > 50 * 1024)
                }
                delay(1000L * attempt)
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { fos?.close() } catch (_: Exception) {}
            }
        }

        return@withContext (completed || (partFile.exists() && partFile.length() > 50 * 1024))
    }

    internal fun buildSafeHeadersMap(targetUrl: String, headers: Map<String, String>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["User-Agent"] = headers["User-Agent"] ?: NetworkClient.DEFAULT_USER_AGENT
        map["Accept"] = "*/*"
        map["Accept-Language"] = "en-US,en;q=0.9,id;q=0.8"

        val rawReferer = headers["Referer"] ?: ""
        val targetHost = runCatching { java.net.URI(targetUrl).host }.getOrNull() ?: ""
        val refererUri = runCatching { java.net.URI(rawReferer) }.getOrNull()
        val refererHost = refererUri?.host ?: ""

        val cleanOrigin = if (refererUri != null) {
            "${refererUri.scheme}://${refererUri.host}"
        } else if (rawReferer.isNotBlank()) {
            rawReferer.substringBefore('/', "")
        } else ""

        if (cleanOrigin.isNotBlank()) {
            map["Origin"] = cleanOrigin
        }
        if (rawReferer.isNotBlank()) {
            map["Referer"] = rawReferer
        }

        map["Sec-Ch-Ua"] = "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\""
        map["Sec-Ch-Ua-Mobile"] = "?1"
        map["Sec-Ch-Ua-Platform"] = "\"Android\""
        map["Sec-Fetch-Dest"] = "empty"
        map["Sec-Fetch-Mode"] = "cors"
        map["Sec-Fetch-Site"] = if (targetHost.equals(refererHost, ignoreCase = true)) "same-origin" else "cross-site"

        val cdnCookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(targetUrl) ?: ""
        }.getOrDefault("")

        if (cdnCookie.isNotBlank()) {
            map["Cookie"] = cdnCookie
        } else if (targetHost.isNotBlank() && refererHost.isNotBlank() && targetHost.equals(refererHost, ignoreCase = true)) {
            headers["Cookie"]?.let { if (it.isNotBlank()) map["Cookie"] = it }
        }

        return map
    }

    internal fun buildSafeMediaRequest(targetUrl: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(targetUrl)
        buildSafeHeadersMap(targetUrl, headers).forEach { (k, v) ->
            builder.header(k, v)
        }
        return builder.build()
    }

    internal fun resolveUrl(base: String, relative: String): String {
        return try {
            val resolved = if (relative.startsWith("http://") || relative.startsWith("https://")) {
                relative
            } else if (relative.startsWith("//")) {
                "https:$relative"
            } else {
                java.net.URI(base).resolve(relative).toString()
            }

            val baseUri = java.net.URI(base)
            val resolvedUri = java.net.URI(resolved)
            val baseQuery = baseUri.rawQuery
            if (!baseQuery.isNullOrBlank()) {
                val resolvedQuery = resolvedUri.rawQuery
                if (resolvedQuery.isNullOrBlank()) {
                    val sep = if (resolved.contains("?")) "&" else "?"
                    "$resolved$sep$baseQuery"
                } else {
                    val existingKeys = resolvedQuery.split("&").map { it.substringBefore("=") }.toSet()
                    val missingParams = baseQuery.split("&").filter { param ->
                        val key = param.substringBefore("=")
                        !existingKeys.contains(key)
                    }
                    if (missingParams.isNotEmpty()) {
                        val toAppend = missingParams.joinToString("&")
                        "$resolved&$toAppend"
                    } else {
                        resolved
                    }
                }
            } else {
                resolved
            }
        } catch (_: Exception) {
            relative
        }
    }

    private fun copyToMediaStore(
        context: Context,
        sourceFile: File,
        title: String,
        format: MediaFormat
    ): Uri? {
        val streamInfo = MediaStorageHelper.createMediaOutputStream(
            context = context,
            rawFilename = title,
            extension = format.extension,
            mediaType = format.type
        )
        val outputStream = streamInfo.first ?: return null
        val outputUri = streamInfo.second

        try {
            FileInputStream(sourceFile).use { fis ->
                outputStream.use { os ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var r: Int
                    while (fis.read(buf).also { r = it } != -1) {
                        os.write(buf, 0, r)
                    }
                    os.flush()
                }
            }
            MediaStorageHelper.finalizeMedia(context, outputUri)
            return outputUri
        } catch (_: Exception) {
            return null
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        401, 403 -> "Server menolak akses (HTTP $code). Link mungkin kedaluwarsa atau butuh login."
        404, 410 -> "File sudah tidak ada di server (HTTP $code). Coba analisis ulang link-nya."
        429 -> "Terlalu banyak permintaan (HTTP 429). Tunggu sebentar lalu coba lagi."
        in 500..599 -> "Server sedang bermasalah (HTTP $code). Coba lagi nanti."
        else -> "Server merespon kode HTTP $code."
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }

    private fun formatEta(seconds: Int): String {
        return when {
            seconds <= 0 -> "Hampir selesai"
            seconds < 60 -> "Sisa $seconds dtk"
            seconds < 3600 -> "Sisa ${seconds / 60} mnt"
            else -> "Sisa ${seconds / 3600} jam"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
