package com.downloader.allinone.downloader

import android.content.Context
import com.downloader.allinone.engine.CronetStreamFetcher
import com.downloader.allinone.engine.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Pengunduh HLS (.m3u8) yang lebih tangguh:
 * - Memilih varian KUALITAS TERTINGGI (berdasarkan resolusi/BANDWIDTH), bukan sekadar varian terakhir
 * - Mendukung trek audio terpisah (#EXT-X-MEDIA TYPE=AUDIO) → video tidak lagi bisu
 * - IV AES-128 dihitung dari #EXT-X-MEDIA-SEQUENCE (sebelumnya selalu mulai dari 0 → hasil rusak)
 * - Mendukung #EXT-X-BYTERANGE dan init segment fMP4 (#EXT-X-MAP)
 * - Unduh 4 segmen paralel + retry dengan jeda, dan bisa DILANJUTKAN setelah dijeda (resume)
 * - Menolak SAMPLE-AES / DRM dengan pesan jelas (konten DRM memang tidak boleh dibobol)
 */
internal object HlsDownloader {

    private const val PARALLEL = 4
    private const val SEGMENT_RETRIES = 4

    data class HlsResult(val error: String?, val audioPart: File? = null)

    private data class Segment(
        val url: String,
        val keyUri: String?,
        val iv: ByteArray?,
        val seq: Long,
        val rangeStart: Long?,
        val rangeLength: Long?
    )

    private data class Variant(val uri: String, val bandwidth: Long, val height: Int, val audioGroup: String?)

    suspend fun download(
        context: Context,
        url: String,
        headers: Map<String, String>,
        partFile: File,
        taskId: String,
        onProgress: (progress: Float, label: String) -> Unit
    ): HlsResult = withContext(Dispatchers.IO) {
        try {
            val firstText = fetchText(context, url, headers)
                ?: return@withContext HlsResult("Server menolak akses playlist video. Link mungkin kedaluwarsa — coba analisis ulang link-nya.")
            if (!firstText.contains("#EXTM3U")) {
                return@withContext HlsResult("Format playlist tidak valid (bukan M3U8). Link kemungkinan sudah kedaluwarsa.")
            }

            var mediaUrl = url
            var mediaText = firstText
            var audioPlaylistUrl: String? = null

            if (firstText.contains("#EXT-X-STREAM-INF")) {
                val lines = firstText.lines().map { it.trim() }
                val variants = mutableListOf<Variant>()
                val audioByGroup = mutableMapOf<String, MutableList<Pair<String, Boolean>>>()
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                        val bw = attr(line, "BANDWIDTH")?.toLongOrNull() ?: attr(line, "AVERAGE-BANDWIDTH")?.toLongOrNull() ?: 0L
                        val h = attr(line, "RESOLUTION")?.substringAfter('x')?.toIntOrNull() ?: 0
                        variants.add(Variant(uri, bw, h, attr(line, "AUDIO")))
                    } else if (line.startsWith("#EXT-X-MEDIA") && attr(line, "TYPE") == "AUDIO") {
                        val group = attr(line, "GROUP-ID") ?: continue
                        val uri = attr(line, "URI") ?: continue
                        val isDefault = attr(line, "DEFAULT") == "YES"
                        audioByGroup.getOrPut(group) { mutableListOf() }.add(uri to isDefault)
                    }
                }
                val best = variants.maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.bandwidth })
                    ?: return@withContext HlsResult("Playlist tidak berisi varian video.")
                mediaUrl = FileDownloader.resolveUrl(url, best.uri)
                mediaText = fetchText(context, mediaUrl, headers)
                    ?: return@withContext HlsResult("Gagal mengambil playlist kualitas terpilih.")
                best.audioGroup?.let { g ->
                    val list = audioByGroup[g]
                    val chosen = list?.firstOrNull { it.second } ?: list?.firstOrNull()
                    if (chosen != null) audioPlaylistUrl = FileDownloader.resolveUrl(url, chosen.first)
                }
            }

            val videoEnd = if (audioPlaylistUrl != null) 0.75f else 0.92f
            val videoErr = downloadMediaPlaylist(context, mediaUrl, mediaText, headers, partFile, taskId) { done, total ->
                onProgress(0.03f + (done.toFloat() / total.coerceAtLeast(1)) * (videoEnd - 0.03f), "Segmen $done/$total")
            }
            if (videoErr != null) return@withContext HlsResult(videoErr)

            var audioPart: File? = null
            val audioUrl = audioPlaylistUrl
            if (audioUrl != null) {
                val audioText = fetchText(context, audioUrl, headers)
                if (audioText != null && audioText.contains("#EXTM3U")) {
                    val f = File(partFile.parentFile, partFile.nameWithoutExtension + "_hlsaudio.part")
                    val audioErr = downloadMediaPlaylist(context, audioUrl, audioText, headers, f, taskId) { done, total ->
                        onProgress(videoEnd + (done.toFloat() / total.coerceAtLeast(1)) * (0.9f - videoEnd), "Audio $done/$total")
                    }
                    if (audioErr == null) audioPart = f
                }
            }
            HlsResult(null, audioPart)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HlsResult("Kesalahan HLS: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    private suspend fun downloadMediaPlaylist(
        context: Context,
        playlistUrl: String,
        text: String,
        headers: Map<String, String>,
        partFile: File,
        taskId: String,
        progress: (Int, Int) -> Unit
    ): String? {
        val lines = text.lines().map { it.trim() }
        var mediaSeq = 0L
        var currentKeyUri: String? = null
        var currentIv: ByteArray? = null
        var mapUri: String? = null
        var mapRange: Pair<Long, Long>? = null
        var pendingRange: Pair<Long, Long?>? = null
        var lastRangeEnd = 0L
        var expectSegment = false
        val segments = mutableListOf<Segment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    when (method) {
                        "AES-128" -> {
                            currentKeyUri = attr(line, "URI")?.let { FileDownloader.resolveUrl(playlistUrl, it) }
                            currentIv = attr(line, "IV")?.let { hexToBytes(it) }
                        }
                        "NONE" -> { currentKeyUri = null; currentIv = null }
                        else -> return "Video ini dilindungi DRM ($method) sehingga tidak dapat diunduh."
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    mapUri = attr(line, "URI")?.let { FileDownloader.resolveUrl(playlistUrl, it) }
                    mapRange = attr(line, "BYTERANGE")?.let { br ->
                        val len = br.substringBefore('@').toLongOrNull() ?: return@let null
                        val off = br.substringAfter('@', "0").toLongOrNull() ?: 0L
                        off to len
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':')
                    val len = v.substringBefore('@').toLongOrNull()
                    val off = if (v.contains('@')) v.substringAfter('@').toLongOrNull() else null
                    if (len != null) pendingRange = len to off
                }
                line.startsWith("#EXTINF") -> expectSegment = true
                line.isNotBlank() && !line.startsWith("#") -> {
                    if (expectSegment) {
                        val seq = mediaSeq + segments.size
                        var start: Long? = null
                        var length: Long? = null
                        pendingRange?.let { (len, off) ->
                            start = off ?: lastRangeEnd
                            length = len
                            lastRangeEnd = (start ?: 0L) + len
                        }
                        segments.add(Segment(FileDownloader.resolveUrl(playlistUrl, line), currentKeyUri, currentIv, seq, start, length))
                        pendingRange = null
                        expectSegment = false
                    }
                }
            }
        }

        val total = segments.size
        if (total == 0) return "Tidak ditemukan segmen video pada streaming ini."

        // ---- RESUME: lanjutkan dari segmen terakhir yang tersimpan ----
        val idxFile = File(partFile.path + ".idx")
        var startIndex = 0
        if (partFile.exists() && idxFile.exists()) {
            val parts = idxFile.readText().trim().split(" ")
            val savedCount = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val savedBytes = parts.getOrNull(1)?.toLongOrNull() ?: -1L
            val savedTotal = parts.getOrNull(2)?.toIntOrNull() ?: -1
            if (savedBytes == partFile.length() && savedTotal == total && savedCount in 1..total) {
                startIndex = savedCount
            }
        }

        val keyCache = mutableMapOf<String, ByteArray>()
        var skipped = 0
        val maxSkipped = maxOf(2, (total * 0.02).toInt())

        FileOutputStream(partFile, startIndex > 0).use { fos ->
            if (startIndex == 0) {
                mapUri?.let { mu ->
                    val initBytes = fetchBytes(context, mu, headers, mapRange?.first, mapRange?.second)
                        ?: return "Gagal mengambil init segment video."
                    fos.write(initBytes)
                }
            }
            progress(startIndex, total)

            var i = startIndex
            while (i < total) {
                if (DownloadStateTracker.isPaused(taskId)) return "Unduhan dijeda oleh pengguna."
                currentCoroutineContext().ensureActive()

                val batch = segments.subList(i, minOf(i + PARALLEL, total))
                val results = coroutineScope {
                    batch.map { seg ->
                        async(Dispatchers.IO) { fetchBytes(context, seg.url, headers, seg.rangeStart, seg.rangeLength) }
                    }.awaitAll()
                }

                for ((k, seg) in batch.withIndex()) {
                    val data = results[k]
                    if (data == null) {
                        skipped++
                        if (skipped > maxSkipped) {
                            return "Terputus di segmen ${i + k}/$total (server menolak / koneksi putus). Tekan coba lagi untuk melanjutkan."
                        }
                        continue
                    }
                    val out = if (seg.keyUri != null) {
                        val key = keyCache[seg.keyUri] ?: fetchBytes(context, seg.keyUri, headers, null, null)
                            ?.also { keyCache[seg.keyUri] = it }
                            ?: return "Gagal mengambil kunci dekripsi AES-128."
                        decryptAes128(data, key, seg.iv ?: sequenceIv(seg.seq))
                    } else data
                    fos.write(out)
                }
                fos.flush()
                i += batch.size
                runCatching { idxFile.writeText("$i ${partFile.length()} $total") }
                progress(i, total)
            }
        }
        idxFile.delete()
        if (partFile.length() <= 50 * 1024) return "Data video terlalu kecil — kemungkinan stream diblokir server."
        return null
    }

    // ---------------- Jaringan (Cronet dulu, lalu OkHttp) ----------------

    private suspend fun fetchText(context: Context, url: String, headers: Map<String, String>): String? {
        for (attempt in 1..3) {
            val safe = FileDownloader.buildSafeHeadersMap(url, headers)
            val viaCronet = runCatching { CronetStreamFetcher.fetchText(context, url, safe) }.getOrNull()
            if (viaCronet != null && viaCronet.first in 200..299 && viaCronet.second.contains("#EXTM3U")) return viaCronet.second
            val viaOk = runCatching {
                NetworkClient.client.newCall(FileDownloader.buildSafeMediaRequest(url, headers)).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
            if (viaOk != null && viaOk.contains("#EXTM3U")) return viaOk
            delay(700L * attempt)
        }
        return null
    }

    private suspend fun fetchBytes(
        context: Context,
        url: String,
        headers: Map<String, String>,
        rangeStart: Long?,
        rangeLength: Long?
    ): ByteArray? {
        val rangeHeader = if (rangeStart != null && rangeLength != null) "bytes=$rangeStart-${rangeStart + rangeLength - 1}" else null
        for (attempt in 1..SEGMENT_RETRIES) {
            currentCoroutineContext().ensureActive()
            val safe = FileDownloader.buildSafeHeadersMap(url, headers).toMutableMap()
            rangeHeader?.let { safe["Range"] = it }

            val viaCronet = runCatching {
                val conn = CronetStreamFetcher.openConnection(context, url, safe)
                try {
                    conn.connect()
                    if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (viaCronet != null && !looksLikeHtml(viaCronet)) return viaCronet

            val viaOk = runCatching {
                val req = Request.Builder().url(url).apply { safe.forEach { (k, v) -> header(k, v) } }.build()
                NetworkClient.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            }.getOrNull()
            if (viaOk != null && !looksLikeHtml(viaOk)) return viaOk

            delay(800L * attempt)
        }
        return null
    }

    private fun looksLikeHtml(b: ByteArray): Boolean {
        if (b.size < 15) return b.isEmpty()
        val head = String(b, 0, minOf(64, b.size), Charsets.ISO_8859_1).trimStart().lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html")
    }

    // ---------------- Util ----------------

    private fun attr(line: String, name: String): String? {
        val m = Regex("""(?:^|[,:])$name=("([^"]*)"|[^,]*)""").find(line) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[1] }.trim().trim('"')
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (_: Exception) {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                cipher.doFinal(data.copyOf(data.size - data.size % 16))
            } catch (_: Exception) {
                data
            }
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.trim().removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte() }
    }

    private fun sequenceIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        java.nio.ByteBuffer.wrap(iv).putLong(8, seq)
        return iv
    }
}
