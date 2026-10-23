package com.downloader.allinone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.downloader.allinone.ui.screens.MainScreen
import com.downloader.allinone.ui.theme.AllInOneDownloaderTheme
import com.downloader.allinone.ui.theme.DarkBackground
import com.downloader.allinone.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Handle shared link from other apps (Twitter, YouTube, TikTok, etc.)
        handleIncomingIntent(intent)

        setContent {
            AllInOneDownloaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                // Extract URL from shared text (in case there is accompanying text)
                val url = extractUrlFromText(sharedText)
                viewModel.onUrlChanged(url)
                viewModel.analyzeUrl()
            }
        }
    }

    private fun extractUrlFromText(text: String): String {
        val urlRegex = "(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\.&]+)".toRegex()
        val match = urlRegex.find(text)
        return match?.value ?: text.trim()
    }
}
