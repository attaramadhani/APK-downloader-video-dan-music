package com.downloader.allinone.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Pengecualian khusus ketika link berasal dari channel privat dan butuh otentikasi
 */
class TelegramPrivateAuthRequiredException(message: String) : Exception(message)

object TelegramSessionBridge {

    private val PRIVATE_URL_PATTERN = Pattern.compile(
        "t(?:elegram)?\\.me/c/(\\d+)/(\\d+)",
        Pattern.CASE_INSENSITIVE
    )

    fun isPrivateTelegramUrl(url: String): Boolean {
        return url.contains("/c/") && PRIVATE_URL_PATTERN.matcher(url).find()
    }

    fun parsePrivateUrl(url: String): Pair<String, String>? {
        val matcher = PRIVATE_URL_PATTERN.matcher(url)
        return if (matcher.find()) {
            val channelId = matcher.group(1) ?: return null
            val messageId = matcher.group(2) ?: return null
            Pair(channelId, messageId)
        } else {
            null
        }
    }

    /**
     * Memeriksa apakah pengguna sudah pernah login ke Telegram Web di HP ini
     */
    fun hasTelegramSession(): Boolean {
        return try {
            val cookies = CookieManager.getInstance().getCookie("https://web.telegram.org")
            !cookies.isNullOrBlank() && (cookies.contains("stel_token") || cookies.contains("stel_ssid") || cookies.contains("user_auth"))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mengekstrak video dari channel privat secara tersembunyi menggunakan sesi Telegram Web
     */
    suspend fun extractPrivateMedia(context: Context, url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val pair = parsePrivateUrl(url) ?: return@withContext Result.failure(Exception("Format link privat Telegram tidak valid."))
        val (channelId, messageId) = pair

        // Jika belum ada sesi login, minta user login sekali secara resmi
        if (!hasTelegramSession()) {
            return@withContext Result.failure(
                TelegramPrivateAuthRequiredException(
                    "Channel ini bersifat privat. Silakan hubungkan akun Telegram Anda sekali saja di AnyDrop untuk mengunduh video dari channel yang Anda ikuti."
                )
            )
        }

        // URL pesan privat di Telegram Web A / K
        val webTelegramUrl = "https://web.telegram.org/a/#-100${channelId}_${messageId}"

        val interceptedVideoUrl = withTimeoutOrNull(10000) {
            suspendCancellableCoroutine<String?> { continuation ->
                Handler(Looper.getMainLooper()).post {
                    var webView: WebView? = null
                    var isResumed = false

                    try {
                        webView = WebView(context.applicationContext).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                                allowFileAccess = false
                                allowContentAccess = false
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: ""
                                    val lower = reqUrl.lowercase()

                                    val isVideo = (lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains("/stream/")) &&
                                            !lower.contains("favicon") && !lower.contains(".js")

                                    if (isVideo && !isResumed) {
                                        isResumed = true
                                        Handler(Looper.getMainLooper()).post {
                                            try {
                                                webView?.stopLoading()
                                                webView?.destroy()
                                            } catch (_: Exception) {}
                                        }
                                        continuation.resume(reqUrl)
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            loadUrl(webTelegramUrl)
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

        if (interceptedVideoUrl.isNullOrBlank()) {
            return@withContext Result.failure(
                Exception("Tidak dapat menemukan aliran video pada pesan privat ini. Pastikan Anda sudah bergabung di channel privat tersebut.")
            )
        }

        val formats = listOf(
            MediaFormat(
                id = "tg_private_video",
                label = "Video Privat (MP4 Asli)",
                resolution = "HD",
                extension = "mp4",
                type = MediaType.VIDEO,
                downloadUrl = interceptedVideoUrl,
                note = "Langsung dari Saluran Privat Telegram"
            ),
            MediaFormat(
                id = "tg_private_audio",
                label = "Ekstrak Suara Privat (MP3)",
                extension = "mp3",
                type = MediaType.AUDIO,
                downloadUrl = interceptedVideoUrl,
                note = "Audio Saja"
            )
        )

        val mediaInfo = MediaInfo(
            url = url,
            title = "Telegram Private Video #$messageId",
            author = "Channel Privat Telegram",
            thumbnailUrl = "",
            durationText = "Privat",
            platform = PlatformType.TELEGRAM,
            formats = formats
        )

        Result.success(mediaInfo)
    }
}
