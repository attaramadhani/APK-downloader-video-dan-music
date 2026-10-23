package com.downloader.allinone.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaInfo
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Mesin Pelacak Video Mendalam (Deep Web Video Sniffer)
 * Mengakses website apa pun, menelusuri kode HTML, script JavaScript,
 * frame pemutar video (iframe), dan memantau request jaringan aktif
 * untuk menemukan file video asli (.mp4, .m3u8, .webm).
 */
object DeepWebVideoSniffer {

    private const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36"

    suspend fun sniff(context: Context, targetUrl: String): MediaInfo? = withContext(Dispatchers.IO) {
        val cleanUrl = targetUrl.trim()

        // TAHAP 1: Pengecekan Cepat Tautan Langsung Berkas Media
        val directMedia = checkDirectMedia(cleanUrl)
        if (directMedia != null) return@withContext directMedia

        // TAHAP 2: Analisis Statis & Script Web Halaman Utama
        var (pageTitle, thumbUrl, foundVideos) = analyzePageHtml(cleanUrl, cleanUrl)
        var headersByUrl: Map<String, Map<String, String>> = emptyMap()
        var refererForFound = cleanUrl

        // TAHAP 3: Jika belum ditemukan, Telusuri Frame Pemutar Video (iframe)
        if (foundVideos.isEmpty()) {
            val iframes = extractIframes(cleanUrl)
            for (iframeUrl in iframes.take(3)) {
                val (_, _, iframeVideos) = analyzePageHtml(iframeUrl, cleanUrl)
                if (iframeVideos.isNotEmpty()) {
                    foundVideos = iframeVideos
                    // CDN player embed biasanya mewajibkan Referer = halaman iframe, bukan halaman utama
                    refererForFound = iframeUrl
                    break
                }
            }
        }

        // TAHAP 4: Jika masih belum ditemukan (Web Player JS Dinamis/Anti-Scrape),
        // Gunakan Headless WebView Network Sniffer yang mengeksekusi JavaScript secara nyata!
        if (foundVideos.isEmpty()) {
            val dynamicResult = sniffDynamicWithHeadlessWebView(context, cleanUrl)
            if (dynamicResult != null && dynamicResult.videoUrls.isNotEmpty()) {
                foundVideos = dynamicResult.videoUrls
                headersByUrl = dynamicResult.headersByUrl
                if (pageTitle.isBlank() && !dynamicResult.title.isNullOrBlank()) {
                    pageTitle = dynamicResult.title
                }
                if (thumbUrl.isBlank() && !dynamicResult.thumbnail.isNullOrBlank()) {
                    thumbUrl = dynamicResult.thumbnail
                }
            }
        }

        if (foundVideos.isEmpty()) return@withContext null

        val unescapedTitle = runCatching {
            android.text.Html.fromHtml(pageTitle, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrDefault(pageTitle)
        val finalTitle = unescapedTitle.ifBlank { "Video dari " + (runCatching { URL(cleanUrl).host }.getOrNull() ?: "Web") }
        val platform = PlatformType.detectFromUrl(cleanUrl)

        val siteCookies = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(cleanUrl) ?: ""
        }.getOrDefault("")

        val siteOrigin = runCatching {
            val u = URL(cleanUrl)
            "${u.protocol}://${u.host}"
        }.getOrDefault(cleanUrl)

        val refOrigin = runCatching {
            val u = URL(refererForFound)
            "${u.protocol}://${u.host}"
        }.getOrDefault(siteOrigin)

        val baseHeaders = mutableMapOf(
            "User-Agent" to CHROME_USER_AGENT,
            "Referer" to refererForFound,
            "Origin" to refOrigin,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )
        if (siteCookies.isNotBlank()) {
            baseHeaders["Cookie"] = siteCookies
        }

        val sortedVideos = foundVideos.distinct().sortedWith(
            compareByDescending<String> { it.contains("playlist.m3u8", ignoreCase = true) }
                .thenByDescending { it.contains(".m3u8", ignoreCase = true) && !it.contains("preview", ignoreCase = true) && !it.contains("trailer", ignoreCase = true) }
                .thenBy { it.contains("preview", ignoreCase = true) || it.contains("trailer", ignoreCase = true) || it.contains("teaser", ignoreCase = true) }
        )

        val formats = sortedVideos.mapIndexed { index, videoUrl ->
            val ext = when {
                videoUrl.contains(".m3u8", ignoreCase = true) -> "mp4"
                videoUrl.contains(".webm", ignoreCase = true) -> "webm"
                else -> "mp4"
            }
            val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
            val isPreview = videoUrl.contains("preview", ignoreCase = true) || videoUrl.contains("trailer", ignoreCase = true) || videoUrl.contains("teaser", ignoreCase = true)
            
            val label = when {
                videoUrl.contains("playlist.m3u8", ignoreCase = true) -> "Kualitas Utama (Durasi Penuh - $ext)"
                isPreview -> "Cuplikan Singkat (Preview - $ext)"
                index == 0 -> "Kualitas Asli Web ($ext)"
                else -> "Kualitas Alternatif ($ext)"
            }
            
            MediaFormat(
                id = "sniffed_fmt_$index",
                label = label,
                resolution = if (isPreview) "SD" else "HD",
                extension = ext,
                type = MediaType.VIDEO,
                downloadUrl = videoUrl,
                customHeaders = mergeCapturedHeaders(baseHeaders, headersByUrl[videoUrl]),
                approxSizeMb = null, // ukuran asli dicek lewat HEAD oleh MediaExtractorManager
                note = when {
                    isPreview -> "Klip Cuplikan Singkat"
                    isHls -> "Streaming Langsung (Durasi Penuh)"
                    else -> "File Video Ditemukan"
                }
            )
        }.toMutableList()

        // Tambahkan opsi Audio jika diperlukan
        if (formats.isNotEmpty()) {
            formats.add(
                MediaFormat(
                    id = "sniffed_audio",
                    label = "Audio Suara Saja (MP3)",
                    extension = "mp3",
                    type = MediaType.AUDIO,
                    downloadUrl = formats.first().downloadUrl,
                    customHeaders = formats.first().customHeaders,
                    approxSizeMb = 5.0,
                    note = "Ekstrak Lagu/Suara"
                )
            )
        }

        return@withContext MediaInfo(
            url = cleanUrl,
            title = finalTitle,
            author = runCatching { URL(cleanUrl).host }.getOrNull() ?: "Situs Web",
            thumbnailUrl = thumbUrl,
            durationText = "Web Video",
            platform = platform,
            formats = formats
        )
    }

    private fun checkDirectMedia(url: String): MediaInfo? {
        val clean = url.split("?").first().lowercase()
        val isVideo = clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov") || clean.endsWith(".mkv") || clean.endsWith(".m3u8")
        if (isVideo) {
            val title = url.split("/").lastOrNull()?.split("?")?.firstOrNull() ?: "Video File"
            val decodedTitle = runCatching { URLDecoder.decode(title, "UTF-8") }.getOrDefault(title)
            return MediaInfo(
                url = url,
                title = decodedTitle.substringBeforeLast("."),
                author = "Tautan Langsung",
                thumbnailUrl = "",
                durationText = "File Media",
                platform = PlatformType.GENERIC,
                formats = listOf(
                    MediaFormat(
                        id = "direct_file_fmt",
                        label = "Video Langsung (MP4)",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = url,
                        approxSizeMb = 20.0,
                        note = "Tautan langsung server"
                    )
                )
            )
        }
        return null
    }

    /**
     * Membaca HTML halaman, mencari tag video, OpenGraph, JSON-LD, serta script player JavaScript.
     */
    private fun analyzePageHtml(pageUrl: String, referer: String): Triple<String, String, List<String>> {
        val foundUrls = mutableListOf<String>()
        var pageTitle = ""
        var thumbUrl = ""

        try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", CHROME_USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val html = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }
            if (html.isBlank()) return Triple("", "", emptyList())

            // 1. Ambil Judul
            pageTitle = findRegex(html, """<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""")
                ?: findRegex(html, """<title>([^<]+)</title>""")
                ?: ""
            pageTitle = pageTitle.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").trim()

            // 2. Ambil Thumbnail
            thumbUrl = findRegex(html, """<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""")
                ?: findRegex(html, """<meta\s+name=["']twitter:image["']\s+content=["']([^"']+)["']""")
                ?: ""

            // 3. Cari di Tag HTML5 Video & Source
            findRegexAll(html, """<source\s+[^>]*src=["']([^"']+)["']""").forEach { foundUrls.add(it) }
            findRegexAll(html, """<video\s+[^>]*src=["']([^"']+)["']""").forEach { foundUrls.add(it) }
            findRegex(html, """<meta\s+property=["']og:video["']\s+content=["']([^"']+)["']""")?.let { foundUrls.add(it) }
            findRegex(html, """<meta\s+property=["']og:video:url["']\s+content=["']([^"']+)["']""")?.let { foundUrls.add(it) }
            findRegex(html, """<meta\s+property=["']og:video:secure_url["']\s+content=["']([^"']+)["']""")?.let { foundUrls.add(it) }
            findRegex(html, """<meta\s+name=["']twitter:player:stream["']\s+content=["']([^"']+)["']""")?.let { foundUrls.add(it) }

            // 4. Cari konfigurasi player JavaScript (JWPlayer, VideoJS, DPlayer, Plyr, dll)
            // Pola: file: "https://...", src: "https://...", "url": "https://..."
            val jsPatterns = listOf(
                """(?:file|src|source|video_url|stream_url|url)\s*:\s*["'](https?:\\?/\\?/[^"'\s<>]+\.(?:mp4|m3u8|webm)[^"'\s<>]*)["']""",
                """"contentUrl"\s*:\s*["'](https?:\\?/\\?/[^"'\s<>]+\.(?:mp4|m3u8|webm)[^"'\s<>]*)["']""",
                """["'](https?:\\?/\\?/[^"'\s<>]+\.(?:mp4|m3u8)[^"'\s<>]*)["']"""
            )

            for (p in jsPatterns) {
                findRegexAll(html, p).forEach { raw ->
                    val clean = raw.replace("\\/", "/").replace("&amp;", "&")
                    if (clean.startsWith("http") && !clean.contains("googleads") && !clean.contains("analytics")) {
                        foundUrls.add(clean)
                    }
                }
            }

            // Normalisasi URL relatif menjadi URL absolut
            val normalized = foundUrls.mapNotNull { raw ->
                resolveAbsoluteUrl(pageUrl, raw)
            }.filter { u ->
                val l = u.lowercase()
                (l.contains(".mp4") || l.contains(".m3u8") || l.contains(".webm") || l.contains("/video")) &&
                        !l.contains(".jpg") && !l.contains(".png") && !l.contains(".css") && !l.contains(".js")
            }

            return Triple(pageTitle, thumbUrl, normalized)
        } catch (_: Exception) {
            return Triple(pageTitle, thumbUrl, emptyList())
        }
    }

    /**
     * Mengekstrak URL iframe tempat pemutar video tertanam (embed player)
     */
    private fun extractIframes(pageUrl: String): List<String> {
        return try {
            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", CHROME_USER_AGENT)
                .build()

            val html = NetworkClient.client.newCall(request).execute().use { it.body?.string() ?: "" }
            val iframes = findRegexAll(html, """<iframe\s+[^>]*src=["']([^"']+)["']""")
            iframes.mapNotNull { resolveAbsoluteUrl(pageUrl, it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class DynamicSniffResult(
        val title: String?,
        val thumbnail: String?,
        val videoUrls: List<String>,
        // Header ASLI yang dikirim WebView untuk tiap URL media (Referer dari iframe player, dll.)
        val headersByUrl: Map<String, Map<String, String>> = emptyMap()
    )

    /**
     * TAHAP 4: Headless WebView Network Sniffer & JavaScript Bridge
     * Mengeksekusi script halaman di WebView tersembunyi, mengaitkan (hook) fungsi pemutar
     * video HTML5 dan XHR/Fetch, serta mencegat setiap request media streaming secara nyata!
     */
    private suspend fun sniffDynamicWithHeadlessWebView(context: Context, targetUrl: String): DynamicSniffResult? {
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { continuation ->
                Handler(Looper.getMainLooper()).post {
                    var webView: WebView? = null
                    var isResumed = false
                    val detectedUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                    val capturedHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
                    var finishScheduled = false
                    val mainHandler = Handler(Looper.getMainLooper())
                    var extractedTitle: String? = null
                    var extractedThumb: String? = null

                    fun finishWithResults() {
                        if (!isResumed) {
                            isResumed = true
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    webView?.stopLoading()
                                    webView?.destroy()
                                } catch (_: Exception) {}
                            }
                            val validList = detectedUrls.filter { u ->
                                val l = u.lowercase()
                                !l.contains("googleads") && !l.contains("favicon") && !l.contains("doubleclick")
                            }.sortedByDescending { u ->
                                val l = u.lowercase()
                                var score = 0
                                if (l.contains("playlist.m3u8") || l.contains("master.m3u8")) score += 100
                                if (l.contains(".m3u8")) score += 30
                                if (l.contains("preview") || l.contains("trailer") || l.contains("teaser") || l.contains("thumb")) score -= 80
                                score
                            }
                            continuation.resume(
                                if (validList.isNotEmpty()) {
                                    DynamicSniffResult(
                                        title = extractedTitle,
                                        thumbnail = extractedThumb,
                                        videoUrls = validList,
                                        headersByUrl = HashMap(capturedHeaders)
                                    )
                                } else null
                            )
                        }
                    }

                    // Tunggu sebentar setelah media pertama terdeteksi agar master playlist / kualitas
                    // tertinggi ikut tertangkap (sebelumnya langsung selesai 0,5 dtk → sering dapat preview)
                    fun scheduleFinish(urlLower: String) {
                        val isMaster = urlLower.contains("master.m3u8") || urlLower.contains("playlist.m3u8") || urlLower.contains(".mpd")
                        val isPreview = urlLower.contains("preview") || urlLower.contains("trailer") || urlLower.contains("teaser")
                        val delayMs = when {
                            isMaster -> 1200L
                            isPreview -> 5000L
                            else -> 3000L
                        }
                        mainHandler.post {
                            if (!finishScheduled || isMaster) {
                                finishScheduled = true
                                mainHandler.postDelayed({ finishWithResults() }, delayMs)
                            }
                        }
                    }

                    try {
                        webView = WebView(context.applicationContext).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                userAgentString = CHROME_USER_AGENT

                                // Sandbox
                                allowFileAccess = false
                                allowContentAccess = false
                                allowFileAccessFromFileURLs = false
                                allowUniversalAccessFromFileURLs = false
                                savePassword = false
                                saveFormData = false
                                setGeolocationEnabled(false)
                                databaseEnabled = false
                            }

                            // JS Bridge untuk menangkap video event & XHR/fetch
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface
                                fun onVideoDetected(mediaUrl: String?) {
                                    if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http")) {
                                        val l = mediaUrl.lowercase()
                                        if (l.contains(".m3u8") || l.contains(".mp4") || l.contains(".webm") || l.contains(".mpd") || l.contains("/video/")) {
                                            if (detectedUrls.add(mediaUrl)) scheduleFinish(l)
                                        }
                                    }
                                }

                                @android.webkit.JavascriptInterface
                                fun onMetadataExtracted(title: String?, thumb: String?) {
                                    if (!title.isNullOrBlank() && extractedTitle.isNullOrBlank()) {
                                        extractedTitle = title
                                    }
                                    if (!thumb.isNullOrBlank() && extractedThumb.isNullOrBlank()) {
                                        extractedThumb = thumb
                                    }
                                }
                            }, "AnyDropSniffer")

                            // Player di dalam iframe sering butuh cookie pihak ketiga
                            runCatching {
                                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            }
                            // Pasang hook XHR/fetch SEBELUM script halaman jalan (lebih banyak request tertangkap)
                            runCatching {
                                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, EARLY_HOOK_JS, setOf("*"))
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val urlScheme = request?.url?.scheme?.lowercase() ?: ""
                                    return urlScheme != "http" && urlScheme != "https"
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: ""
                                    val lower = reqUrl.lowercase()

                                    val isVideoRequest = lower.endsWith(".mp4") || lower.contains(".mp4?") ||
                                            lower.endsWith(".m3u8") || lower.contains(".m3u8?") ||
                                            lower.endsWith(".webm") || lower.contains(".webm?") ||
                                            lower.endsWith(".mpd") || lower.contains(".mpd?") ||
                                            lower.endsWith(".mov") || lower.contains(".mov?") ||
                                            lower.endsWith(".mkv") || lower.contains(".mkv?") ||
                                            lower.contains("/videoplayback") ||
                                            lower.contains("/video/") ||
                                            lower.contains("/hls/") ||
                                            lower.contains("playlist.m3u8") ||
                                            lower.contains("master.m3u8")

                                    val isAd = lower.contains("googleads") || lower.contains("doubleclick") ||
                                            lower.contains("analytics") || lower.contains("favicon") ||
                                            lower.contains("facebook.com/tr") || lower.contains("adsystem")

                                    if (isVideoRequest && !isAd) {
                                        // Simpan header asli request (Referer/Origin dari iframe player)
                                        request?.requestHeaders?.let { h ->
                                            if (h.isNotEmpty()) capturedHeaders[reqUrl] = HashMap(h)
                                        }
                                        if (detectedUrls.add(reqUrl)) scheduleFinish(lower)
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (view != null && !isResumed) {
                                        if (extractedTitle.isNullOrBlank()) {
                                            extractedTitle = view.title
                                        }

                                        val jsInjector = """
                                            (function() {
                                                try {
                                                    var t = document.title || "";
                                                    var imgMeta = document.querySelector('meta[property="og:image"]') || document.querySelector('meta[name="twitter:image"]');
                                                    var thumb = imgMeta ? imgMeta.content : "";
                                                    if (window.AnyDropSniffer) {
                                                        window.AnyDropSniffer.onMetadataExtracted(t, thumb);
                                                    }

                                                    if (window.HTMLMediaElement) {
                                                        var origPlay = HTMLMediaElement.prototype.play;
                                                        HTMLMediaElement.prototype.play = function() {
                                                            try {
                                                                var s = this.currentSrc || this.src;
                                                                if (s && window.AnyDropSniffer) window.AnyDropSniffer.onVideoDetected(s);
                                                            } catch(e) {}
                                                            return origPlay.apply(this, arguments);
                                                        };
                                                    }

                                                    var origOpen = XMLHttpRequest.prototype.open;
                                                    XMLHttpRequest.prototype.open = function(method, url) {
                                                        try {
                                                            if (url && window.AnyDropSniffer) {
                                                                window.AnyDropSniffer.onVideoDetected(new URL(String(url), location.href).href);
                                                            }
                                                        } catch(e) {}
                                                        return origOpen.apply(this, arguments);
                                                    };

                                                    if (window.fetch) {
                                                        var origFetch = window.fetch;
                                                        window.fetch = function() {
                                                            try {
                                                                var u = arguments[0];
                                                                if (u && typeof u === 'object' && u.url) u = u.url;
                                                                if (u && window.AnyDropSniffer) {
                                                                    window.AnyDropSniffer.onVideoDetected(new URL(String(u), location.href).href);
                                                                }
                                                            } catch(e) {}
                                                            return origFetch.apply(this, arguments);
                                                        };
                                                    }

                                                    function scanMedia() {
                                                        try {
                                                            var elements = document.querySelectorAll('video, audio, source');
                                                            for (var i = 0; i < elements.length; i++) {
                                                                var el = elements[i];
                                                                var src = el.src || el.currentSrc || el.getAttribute('data-src') || el.getAttribute('data-video');
                                                                if (src && window.AnyDropSniffer) {
                                                                    window.AnyDropSniffer.onVideoDetected(src);
                                                                }
                                                                if (el.tagName === 'VIDEO' && el.play) {
                                                                    el.muted = true;
                                                                    el.play().catch(function(){});
                                                                }
                                                            }
                                                            var btns = document.querySelectorAll('.play, .play-btn, .vjs-big-play-button, [aria-label*="Play" i]');
                                                            for (var k = 0; k < Math.min(btns.length, 2); k++) {
                                                                btns[k].click();
                                                            }
                                                        } catch(e) {}
                                                    }
                                                    scanMedia();
                                                    setTimeout(scanMedia, 800);
                                                } catch(err) {}
                                            })();
                                        """.trimIndent()
                                        view.evaluateJavascript(jsInjector, null)
                                    }
                                }
                            }

                            loadUrl(targetUrl)
                            // Batas aman: kembalikan apa pun yang sudah tertangkap sebelum timeout global
                            mainHandler.postDelayed({ finishWithResults() }, 17_000L)
                        }

                        continuation.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    webView?.stopLoading()
                                    webView?.destroy()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                        if (!isResumed) {
                            isResumed = true
                            continuation.resume(null)
                        }
                    }
                }
            }
        }
    }

    /** Gabungkan header dasar dengan header asli yang ditangkap WebView (Referer/Origin diutamakan). */
    private fun mergeCapturedHeaders(base: Map<String, String>, captured: Map<String, String>?): Map<String, String> {
        if (captured.isNullOrEmpty()) return base
        val merged = base.toMutableMap()
        captured.forEach { (k, v) ->
            when (k.lowercase()) {
                "referer" -> merged["Referer"] = v
                "origin" -> merged["Origin"] = v
                "user-agent" -> merged["User-Agent"] = v
                "range", "accept-encoding", "host", "connection" -> {}
                else -> if (!merged.keys.any { it.equals(k, ignoreCase = true) }) merged[k] = v
            }
        }
        return merged
    }

    private val EARLY_HOOK_JS = """
        (function() {
          try {
            if (window.__anydropHooked) return; window.__anydropHooked = true;
            function report(u) {
              try { if (u && window.AnyDropSniffer) window.AnyDropSniffer.onVideoDetected(new URL(String(u), location.href).href); } catch (e) {}
            }
            var oOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u) { report(u); return oOpen.apply(this, arguments); };
            if (window.fetch) {
              var oFetch = window.fetch;
              window.fetch = function(i) { report(i && i.url ? i.url : i); return oFetch.apply(this, arguments); };
            }
            var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
            if (srcDesc && srcDesc.set) {
              Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                get: function() { return srcDesc.get.call(this); },
                set: function(v) { report(v); return srcDesc.set.call(this, v); },
                configurable: true
              });
            }
          } catch (err) {}
        })();
    """.trimIndent()

    private fun resolveAbsoluteUrl(baseUrl: String, relativeUrl: String): String? {
        return try {
            if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
                relativeUrl
            } else if (relativeUrl.startsWith("//")) {
                "https:$relativeUrl"
            } else {
                val base = URI_or_Null(baseUrl) ?: return null
                base.resolve(relativeUrl).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun URI_or_Null(url: String): java.net.URI? {
        return try { java.net.URI(url) } catch (_: Exception) { null }
    }

    private fun findRegex(text: String, patternStr: String): String? {
        val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun findRegexAll(text: String, patternStr: String): List<String> {
        val list = mutableListOf<String>()
        val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(text)
        while (matcher.find()) {
            matcher.group(1)?.let { list.add(it) }
        }
        return list
    }
}
