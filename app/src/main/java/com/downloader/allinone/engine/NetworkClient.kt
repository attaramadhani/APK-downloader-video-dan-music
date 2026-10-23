package com.downloader.allinone.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object NetworkClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(EncryptedDnsResolver)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    const val ANDROID_USER_AGENT = "com.google.android.youtube/19.44.38 (Linux; U; Android 14; US) gzip"

    fun newRequest(url: String, headers: Map<String, String> = emptyMap()): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")

        headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        return builder.build()
    }

    suspend fun fetchString(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = newRequest(url, headers)
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP error code: ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    private val quickClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchContentLengthMb(url: String, headers: Map<String, String> = emptyMap()): Double? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "*/*")
            headers.forEach { (k, v) -> builder.header(k, v) }

            quickClient.newCall(builder.build()).execute().use { response ->
                val length = response.header("Content-Length")?.toLongOrNull()
                    ?: response.body?.contentLength()
                if (length != null && length > 0) {
                    Math.round((length / (1024.0 * 1024.0)) * 10.0) / 10.0
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
