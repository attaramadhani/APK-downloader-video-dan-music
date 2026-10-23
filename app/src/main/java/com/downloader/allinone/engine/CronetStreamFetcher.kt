package com.downloader.allinone.engine

import android.content.Context
import org.chromium.net.CronetEngine
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object CronetStreamFetcher {
    @Volatile
    private var engine: CronetEngine? = null

    fun getEngine(context: Context): CronetEngine {
        return engine ?: synchronized(this) {
            engine ?: run {
                val builder = CronetEngine.Builder(context.applicationContext)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .setUserAgent(NetworkClient.DEFAULT_USER_AGENT)
                builder.build().also { engine = it }
            }
        }
    }

    /**
     * Membuka koneksi HTTP yang dieksekusi oleh mesin native Chromium BoringSSL.
     * Tidak memiliki batasan CORS browser dan memiliki sidik jari TLS 100% Google Chrome asli.
     */
    fun openConnection(
        context: Context,
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 15000,
        readTimeoutMs: Int = 30000
    ): HttpURLConnection {
        val cronet = getEngine(context)
        val conn = cronet.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true

        headers.forEach { (k, v) ->
            if (v.isNotBlank()) {
                conn.setRequestProperty(k, v)
            }
        }
        return conn
    }

    /**
     * Mengambil teks (seperti playlist m3u8) menggunakan mesin Chromium
     */
    fun fetchText(context: Context, url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = openConnection(context, url, headers)
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val text = stream.bufferedReader().use { it.readText() }
            Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Mengambil data biner (seperti kunci enkripsi AES-128)
     */
    fun fetchBytes(context: Context, url: String, headers: Map<String, String>): Pair<Int, ByteArray?> {
        val conn = openConnection(context, url, headers)
        return try {
            conn.connect()
            val code = conn.responseCode
            val bytes = if (code in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else null
            Pair(code, bytes)
        } finally {
            conn.disconnect()
        }
    }
}
