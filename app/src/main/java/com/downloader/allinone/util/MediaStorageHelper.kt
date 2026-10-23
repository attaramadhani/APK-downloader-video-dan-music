package com.downloader.allinone.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.downloader.allinone.model.MediaType
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object MediaStorageHelper {

    /**
     * Membersihkan nama file agar aman dari batasan sistem berkas Android/Linux:
     * - Batas byte UTF-8 maksimal 70 byte (mencegah error canonical file path / NAME_MAX 255 bytes saat ada prefix .pending)
     * - Menghilangkan karakter terlarang, kontrol karakter, newline, dan broken surrogate
     * - Menghilangkan titik dan spasi di ujung nama file
     */
    fun sanitizeFilename(name: String): String {
        var cleaned = name
            .replace("[\r\n\t]".toRegex(), " ")
            .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            .replace("[\\p{Cntrl}]".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        cleaned = cleaned.trimStart('.', ' ').trimEnd('.', ' ')

        // Batasi panjang byte UTF-8 dengan aman (tidak memutus surrogate pair atau karakter multi-byte CJK/Arab/Emoji)
        val safeSb = StringBuilder()
        var currentBytes = 0
        var i = 0
        while (i < cleaned.length) {
            val codePoint = cleaned.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size

            if (currentBytes + charBytes > 70) break

            safeSb.appendCodePoint(codePoint)
            currentBytes += charBytes
            i += charCount
        }

        var result = safeSb.toString().trim().trimEnd('.', ' ')
        if (result.isBlank()) {
            result = "Media_${System.currentTimeMillis() % 100000}"
        }
        return result
    }

    fun createMediaOutputStream(
        context: Context,
        rawFilename: String,
        extension: String,
        mediaType: MediaType
    ): Pair<OutputStream?, Uri?> {
        val cleanExt = extension.trimStart('.').trim().ifBlank { if (mediaType == MediaType.AUDIO) "mp3" else "mp4" }
        val sanitized = sanitizeFilename(rawFilename)
        val nameWithPrefix = if (sanitized.startsWith("AnyDrop_", ignoreCase = true)) sanitized else "AnyDrop_$sanitized"
        val fullFilename = if (nameWithPrefix.endsWith(".$cleanExt", ignoreCase = true)) nameWithPrefix else "$nameWithPrefix.$cleanExt"
        val mimeType = if (mediaType == MediaType.AUDIO) "audio/*" else "video/mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collectionUri = if (mediaType == MediaType.AUDIO) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val relativePath = if (mediaType == MediaType.AUDIO) {
                "${Environment.DIRECTORY_MUSIC}/AnyDrop"
            } else {
                "${Environment.DIRECTORY_MOVIES}/AnyDrop"
            }

            // Coba insert dengan nama asli yang sudah di-sanitize
            var uri: Uri? = null
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fullFilename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = context.contentResolver.insert(collectionUri, contentValues)
            } catch (_: Exception) {
                uri = null
            }

            // Fallback: Jika sistem Android tetap menolak nama (misal ada karakter khusus OS), gunakan nama unik aman
            if (uri == null) {
                try {
                    val safeFallbackName = "AnyDrop_${System.currentTimeMillis()}.$cleanExt"
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeFallbackName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = context.contentResolver.insert(collectionUri, contentValues)
                } catch (_: Exception) {
                    uri = null
                }
            }

            val stream = uri?.let { context.contentResolver.openOutputStream(it) }
            return Pair(stream, uri)
        } else {
            // Android 9 kebawah
            val targetDir = Environment.getExternalStoragePublicDirectory(
                if (mediaType == MediaType.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
            )
            val anyDropDir = File(targetDir, "AnyDrop").apply { mkdirs() }
            var file = File(anyDropDir, fullFilename)
            if (file.exists()) {
                file = File(anyDropDir, "AnyDrop_${System.currentTimeMillis()}_$fullFilename")
            }
            return Pair(FileOutputStream(file), Uri.fromFile(file))
        }
    }

    fun finalizeMedia(context: Context, uri: Uri?) {
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (_: Exception) {}
        }
    }

    fun openMediaFile(context: Context, uriString: String?, mediaType: MediaType) {
        if (uriString.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "Lokasi file tidak ditemukan di penyimpanan", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = android.net.Uri.parse(uriString)
            val mimeType = if (mediaType == MediaType.AUDIO) "audio/*" else "video/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "File tersimpan di folder Movies/AnyDrop", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
