package com.downloader.allinone.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.downloader.allinone.MainActivity

object DownloadNotificationHelper {

    private const val CHANNEL_ID = "anydrop_download_channel"
    private const val COMPLETE_CHANNEL_ID = "anydrop_complete_channel"
    private const val NOTIFICATION_ID = 1001

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            
            val downloadChannel = NotificationChannel(
                CHANNEL_ID,
                "Status Unduhan Berjalan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan progres download video dan musik yang sedang berlangsung"
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "Unduhan Selesai",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi saat file video/musik telah berhasil disimpan di HP"
                setShowBadge(true)
            }

            manager.createNotificationChannel(downloadChannel)
            manager.createNotificationChannel(completeChannel)
        }
    }

    fun updateProgress(context: Context, title: String, progress: Float, speed: String, eta: String) {
        try {
            initChannels(context)
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val percent = (progress * 100).toInt().coerceIn(0, 100)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("$percent% • $speed • $eta")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, progress <= 0f)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun showComplete(context: Context, title: String) {
        try {
            initChannels(context)
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(NOTIFICATION_ID)

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
                .setContentTitle("Unduhan Berhasil!")
                .setContentText("$title telah tersimpan di Galeri/Musik.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: Exception) {}
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
