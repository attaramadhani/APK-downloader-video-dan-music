package com.downloader.allinone.model

enum class PlatformType(val displayName: String, val badgeColor: Long) {
    YOUTUBE("YouTube", 0xFFFF0000),
    TWITTER("Twitter / X", 0xFF1DA1F2),
    TIKTOK("TikTok", 0xFF00F2FE),
    INSTAGRAM("Instagram", 0xFFE1306C),
    TELEGRAM("Telegram", 0xFF2AABEE),
    SPOTIFY("Spotify", 0xFF1DB954),
    SOUNDCLOUD("SoundCloud", 0xFFFF5500),
    FACEBOOK("Facebook", 0xFF1877F2),
    REDDIT("Reddit", 0xFFFF4500),
    GENERIC("1000+ Supported Sites", 0xFF6366F1);

    companion object {
        fun detectFromUrl(url: String): PlatformType {
            val lower = url.lowercase().trim()
            return when {
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("twitter.com") || lower.contains("x.com") -> TWITTER
                lower.contains("tiktok.com") -> TIKTOK
                lower.contains("instagram.com") -> INSTAGRAM
                lower.contains("t.me/") || lower.contains("telegram.me/") -> TELEGRAM
                lower.contains("spotify.com") -> SPOTIFY
                lower.contains("soundcloud.com") -> SOUNDCLOUD
                lower.contains("facebook.com") || lower.contains("fb.watch") -> FACEBOOK
                lower.contains("reddit.com") || lower.contains("redd.it") -> REDDIT
                else -> GENERIC
            }
        }
    }
}
