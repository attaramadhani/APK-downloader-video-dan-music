package com.downloader.allinone.model

import org.json.JSONObject

enum class MediaType {
    VIDEO,
    AUDIO
}

data class MediaFormat(
    val id: String,
    val label: String, // e.g., "1080p Full HD", "720p HD", "Audio MP3"
    val resolution: String = "",
    val extension: String = "mp4",
    val type: MediaType = MediaType.VIDEO,
    val downloadUrl: String = "",
    val audioUrl: String? = null, // URL audio terpisah untuk di-muxing jika format adalah video-only DASH
    val customHeaders: Map<String, String> = emptyMap(),
    val approxSizeMb: Double? = null,
    val note: String = "",
    // URL halaman asli (dipakai sebagai cadangan: yt-dlp bisa mengekstrak ulang bila link CDN kedaluwarsa)
    val sourcePageUrl: String? = null,
    // Jika format berasal dari yt-dlp: format selector yang dipakai saat mengunduh lewat yt-dlp
    val ytdlpFormatId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("resolution", resolution)
        put("extension", extension)
        put("type", type.name)
        put("downloadUrl", downloadUrl)
        audioUrl?.let { put("audioUrl", it) }
        if (customHeaders.isNotEmpty()) {
            put("customHeaders", JSONObject().apply { customHeaders.forEach { (k, v) -> put(k, v) } })
        }
        approxSizeMb?.let { put("approxSizeMb", it) }
        put("note", note)
        sourcePageUrl?.let { put("sourcePageUrl", it) }
        ytdlpFormatId?.let { put("ytdlpFormatId", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject?): MediaFormat? {
            if (obj == null) return null
            val headers = mutableMapOf<String, String>()
            obj.optJSONObject("customHeaders")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.optString(k) }
            }
            return MediaFormat(
                id = obj.optString("id", "fmt_default"),
                label = obj.optString("label", "Format Asli"),
                resolution = obj.optString("resolution", ""),
                extension = obj.optString("extension", "mp4"),
                type = runCatching { MediaType.valueOf(obj.optString("type", "VIDEO")) }.getOrDefault(MediaType.VIDEO),
                downloadUrl = obj.optString("downloadUrl", ""),
                audioUrl = obj.optString("audioUrl", "").ifBlank { null },
                customHeaders = headers,
                approxSizeMb = if (obj.has("approxSizeMb")) obj.optDouble("approxSizeMb") else null,
                note = obj.optString("note", ""),
                sourcePageUrl = obj.optString("sourcePageUrl", "").ifBlank { null },
                ytdlpFormatId = obj.optString("ytdlpFormatId", "").ifBlank { null }
            )
        }

        fun fromJsonString(json: String?): MediaFormat? =
            if (json.isNullOrBlank()) null else runCatching { fromJson(JSONObject(json)) }.getOrNull()
    }
}
