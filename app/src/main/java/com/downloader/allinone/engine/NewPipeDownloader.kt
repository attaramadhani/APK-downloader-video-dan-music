package com.downloader.allinone.engine

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NewPipeDownloader : Downloader() {

    private val cookieJar = ConcurrentHashMap<String, String>()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val reqBuilder = okhttp3.Request.Builder()
            .url(url)

        var hasUserAgent = false
        var hasCookie = false

        headers.forEach { (name, values) ->
            if (values.isNotEmpty()) {
                val headerValue = values.joinToString("; ")
                reqBuilder.header(name, headerValue)
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                if (name.equals("Cookie", ignoreCase = true)) hasCookie = true
            }
        }

        if (!hasUserAgent) {
            reqBuilder.header("User-Agent", NetworkClient.DEFAULT_USER_AGENT)
        }

        if (!hasCookie && cookieJar.isNotEmpty()) {
            val cookiesFormatted = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            reqBuilder.header("Cookie", cookiesFormatted)
        }

        if (httpMethod.equals("POST", ignoreCase = true)) {
            val bodyBytes = dataToSend ?: ByteArray(0)
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            reqBuilder.post(bodyBytes.toRequestBody(mediaType))
        } else if (httpMethod.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        val call = NetworkClient.client.newCall(reqBuilder.build())
        val okResponse = call.execute()

        // Capture Set-Cookie headers
        okResponse.headers("Set-Cookie").forEach { rawCookie ->
            val parts = rawCookie.split(";")
            if (parts.isNotEmpty()) {
                val cookiePair = parts[0].split("=", limit = 2)
                if (cookiePair.size == 2) {
                    cookieJar[cookiePair[0].trim()] = cookiePair[1].trim()
                }
            }
        }

        val responseCode = okResponse.code
        val responseMessage = okResponse.message
        val responseHeaders = okResponse.headers.toMultimap()
        val responseBody = okResponse.body?.string() ?: ""
        val latestUrl = okResponse.request.url.toString()

        return Response(responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
    }
}
