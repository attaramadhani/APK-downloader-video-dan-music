package com.downloader.allinone

import android.app.Application
import com.downloader.allinone.engine.YtDlpEngine

class AllInOneApp : Application() {
    companion object {
        lateinit var instance: AllInOneApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Siapkan yt-dlp di background (ekstrak Python pertama kali ~beberapa detik) & update berkala
        YtDlpEngine.warmUp(this)
    }
}
