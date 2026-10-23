package com.downloader.allinone.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.downloader.allinone.model.MediaType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

object MediaMuxerHelper {

    private const val TAG = "MediaMuxerHelper"

    fun muxUris(
        context: Context,
        videoUri: Uri,
        audioUri: Uri,
        outputTitle: String
    ): Result<Uri> {
        val tempDir = context.cacheDir
        val tempVideoFile = File(tempDir, "user_temp_video_${System.currentTimeMillis()}.mp4")
        val tempAudioFile = File(tempDir, "user_temp_audio_${System.currentTimeMillis()}.m4a")
        val tempMuxedFile = File(tempDir, "user_temp_muxed_${System.currentTimeMillis()}.mp4")

        try {
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(tempVideoFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(Exception("Tidak dapat membaca file video yang dipilih."))

            context.contentResolver.openInputStream(audioUri)?.use { input ->
                FileOutputStream(tempAudioFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(Exception("Tidak dapat membaca file audio yang dipilih."))

            val success = muxVideoAndAudio(tempVideoFile, tempAudioFile, tempMuxedFile)
            val finalSource = if (success && tempMuxedFile.exists() && tempMuxedFile.length() > 0L) tempMuxedFile else tempVideoFile

            val streamPair = MediaStorageHelper.createMediaOutputStream(
                context = context,
                rawFilename = if (outputTitle.isNotBlank()) outputTitle else "Merged_Video_${System.currentTimeMillis()}",
                extension = "mp4",
                mediaType = MediaType.VIDEO
            )

            val outputStream = streamPair.first ?: return Result.failure(Exception("Gagal membuat file baru di galeri penyimpanan."))
            val finalUri = streamPair.second ?: return Result.failure(Exception("URI penyimpanan tidak valid."))

            FileInputStream(finalSource).use { fis ->
                outputStream.use { fos ->
                    fis.copyTo(fos, bufferSize = 64 * 1024)
                    fos.flush()
                }
            }

            MediaStorageHelper.finalizeMedia(context, finalUri)
            return Result.success(finalUri)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
            tempMuxedFile.delete()
        }
    }

    /**
     * Menggabungkan video dan audio secara sinkron dengan INTERLEAVED TIMESTAMPS.
     * Mencegah buffer overflow / crash MediaMuxer pada video panjang.
     */
    fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Video file does not exist or is empty")
            return false
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file does not exist or is empty")
            return false
        }

        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 1. Temukan dan daftarkan Track Video
            var videoTrackIndex = -1
            var videoSourceTrack = -1
            var maxVideoInputSize = 1024 * 1024

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoSourceTrack = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > maxVideoInputSize) maxVideoInputSize = size
                    }
                    videoTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            // 2. Temukan dan daftarkan Track Audio
            var audioTrackIndex = -1
            var audioSourceTrack = -1
            var maxAudioInputSize = 512 * 1024

            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioSourceTrack = i
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > maxAudioInputSize) maxAudioInputSize = size
                    }
                    audioTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found in ${videoFile.name}")
                return false
            }

            muxer.start()

            // 3. Muxing dengan Interleaved Timestamp (Video dan Audio ditulis bersamaan urut waktu)
            videoExtractor.selectTrack(videoSourceTrack)
            val videoBuffer = ByteBuffer.allocate(maxVideoInputSize)
            val videoBufferInfo = MediaCodec.BufferInfo()
            var firstVideoTimeUs = -1L
            var hasMoreVideo = true

            val hasAudio = audioTrackIndex != -1 && audioSourceTrack != -1
            var audioBuffer: ByteBuffer? = null
            var audioBufferInfo: MediaCodec.BufferInfo? = null
            var firstAudioTimeUs = -1L
            var hasMoreAudio = false

            if (hasAudio) {
                audioExtractor.selectTrack(audioSourceTrack)
                audioBuffer = ByteBuffer.allocate(maxAudioInputSize)
                audioBufferInfo = MediaCodec.BufferInfo()
                hasMoreAudio = true
            }

            while (hasMoreVideo || hasMoreAudio) {
                val videoTime = if (hasMoreVideo) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (hasMoreAudio) audioExtractor.sampleTime else Long.MAX_VALUE

                if (hasMoreVideo && (videoTime <= audioTime || !hasMoreAudio)) {
                    val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                    if (sampleSize < 0) {
                        hasMoreVideo = false
                    } else {
                        val sampleTime = videoExtractor.sampleTime
                        if (firstVideoTimeUs == -1L) firstVideoTimeUs = sampleTime

                        videoBufferInfo.offset = 0
                        videoBufferInfo.size = sampleSize
                        videoBufferInfo.presentationTimeUs = (sampleTime - firstVideoTimeUs).coerceAtLeast(0L)
                        videoBufferInfo.flags = videoExtractor.sampleFlags

                        muxer.writeSampleData(videoTrackIndex, videoBuffer, videoBufferInfo)
                        videoExtractor.advance()
                    }
                } else if (hasMoreAudio && audioBuffer != null && audioBufferInfo != null) {
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        hasMoreAudio = false
                    } else {
                        val sampleTime = audioExtractor.sampleTime
                        if (firstAudioTimeUs == -1L) firstAudioTimeUs = sampleTime

                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = sampleSize
                        audioBufferInfo.presentationTimeUs = (sampleTime - firstAudioTimeUs).coerceAtLeast(0L)
                        audioBufferInfo.flags = audioExtractor.sampleFlags

                        muxer.writeSampleData(audioTrackIndex, audioBuffer, audioBufferInfo)
                        audioExtractor.advance()
                    }
                } else {
                    break
                }
            }

            Log.i(TAG, "Muxing completed successfully into ${outputFile.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Muxing failed: ${e.message}", e)
            return false
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
            try {
                videoExtractor.release()
            } catch (_: Exception) {}
            try {
                audioExtractor.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Menyiapkan file AUDIO saja dari hasil unduhan.
     * Sebelumnya, opsi "Audio MP3" hanya menyimpan file VIDEO dengan nama .mp3 (tidak bisa diputar di
     * pemutar musik). Sekarang trek audionya diambil ke kontainer .m4a (AAC, tanpa re-encode).
     * @return "mp3" bila file sudah berupa MP3 murni (pakai file asli), "m4a" bila berhasil ditulis ke
     *         [output], atau null bila gagal (pakai file asli apa adanya).
     */
    fun prepareAudioOnly(input: File, output: File): String? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var audioMime = ""
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/") && audioTrack == -1) {
                    audioTrack = i
                    audioMime = mime
                }
            }
            if (audioTrack == -1) return null
            if (!hasVideo && audioMime == "audio/mpeg") return "mp3"

            val format = extractor.getTrackFormat(audioTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            started = true
            extractor.selectTrack(audioTrack)

            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else 512 * 1024
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            var firstTime = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (firstTime < 0) firstTime = t
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (t - firstTime).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            return if (output.length() > 0 || started) "m4a" else null
        } catch (e: Exception) {
            Log.e(TAG, "Ekstrak audio gagal: ${e.message}")
            output.delete()
            return null
        } finally {
            try {
                if (started) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {
                output.delete()
            }
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
