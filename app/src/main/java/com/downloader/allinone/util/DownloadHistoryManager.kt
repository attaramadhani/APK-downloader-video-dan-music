package com.downloader.allinone.util

import android.content.Context
import com.downloader.allinone.model.DownloadStatus
import com.downloader.allinone.model.DownloadTask
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

object DownloadHistoryManager {

    private const val HISTORY_FILE_NAME = "anydrop_download_history.json"

    fun loadHistory(context: Context): List<DownloadTask> {
        return try {
            val file = File(context.filesDir, HISTORY_FILE_NAME)
            if (!file.exists()) return emptyList()

            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return emptyList()

            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<DownloadTask>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val formatObj = obj.optJSONObject("format")

                val mediaFormat = if (formatObj != null) {
                    MediaFormat.fromJson(formatObj) ?: MediaFormat(id = "fmt_default", label = "Format Asli", downloadUrl = "")
                } else {
                    MediaFormat(
                        id = "fmt_default",
                        label = "Format Asli",
                        extension = "mp4",
                        type = MediaType.VIDEO,
                        downloadUrl = ""
                    )
                }

                val statusStr = obj.optString("status", "COMPLETED")
                val status = try { DownloadStatus.valueOf(statusStr) } catch (_: Exception) { DownloadStatus.COMPLETED }
                val platformStr = obj.optString("platform", "GENERIC")
                val platform = try { PlatformType.valueOf(platformStr) } catch (_: Exception) { PlatformType.GENERIC }

                list.add(
                    DownloadTask(
                        id = obj.optString("id"),
                        title = obj.optString("title", "Unduhan"),
                        author = obj.optString("author", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        platform = platform,
                        selectedFormat = mediaFormat,
                        progress = obj.optDouble("progress", 1.0).toFloat(),
                        speedText = obj.optString("speedText", "Selesai"),
                        etaText = obj.optString("etaText", "Tersimpan"),
                        downloadedBytes = obj.optLong("downloadedBytes", 0L),
                        totalBytes = obj.optLong("totalBytes", 0L),
                        status = status,
                        errorMessage = if (obj.has("errorMessage")) obj.optString("errorMessage") else null,
                        outputFilePath = if (obj.has("outputFilePath")) obj.optString("outputFilePath") else null,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun saveHistory(context: Context, tasks: List<DownloadTask>) {
        val snapshot = tasks.take(100)
        ioScope.launch {
            try {
                val jsonArray = JSONArray()
                snapshot.forEach { task ->
                    val obj = JSONObject().apply {
                        put("id", task.id)
                        put("title", task.title)
                        put("author", task.author)
                        put("thumbnailUrl", task.thumbnailUrl)
                        put("platform", task.platform.name)
                        put("progress", task.progress.toDouble())
                        put("speedText", task.speedText)
                        put("etaText", task.etaText)
                        put("downloadedBytes", task.downloadedBytes)
                        put("totalBytes", task.totalBytes)
                        put("status", task.status.name)
                        put("errorMessage", task.errorMessage)
                        put("outputFilePath", task.outputFilePath)
                        put("timestamp", task.timestamp)

                        val fmtObj = task.selectedFormat.toJson()
                        put("format", fmtObj)
                    }
                    jsonArray.put(obj)
                }

                val file = File(context.filesDir, HISTORY_FILE_NAME)
                file.writeText(jsonArray.toString())
            } catch (_: Exception) {}
        }
    }
}
