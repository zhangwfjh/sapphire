package com.sapphire.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Per-platform accent colors for origin tags (PRD §3.3 platform badges).
 *
 * Native brand hexes when UNREAD (so a reader can scan the timeline by platform);
 * collapsed to a mid-gray when READ per the visual state matrix — see [PlatformBadge].
 *
 * Kept conservative: recognizable but desaturated enough to sit inside the sapphire
 * palette without clashing. Unknown platforms fall back to the sapphire accent.
 */
object PlatformColors {
    val Twitter = 0xFF1D9BF0
    val X = 0xFFE7E9EA
    val Reddit = 0xFFFF4500
    val YouTube = 0xFFFF0033
    val Bluesky = 0xFF0085FF
    val Xiaohongshu = 0xFFFF2442
    val GitHub = 0xFFE6EDF3
    val Rss = 0xFFEE802F
    val HackerNews = 0xFFE3772C

    /** Resolve a platform tag to a brand color. Match is case-insensitive on substring. */
    fun forTag(tag: String?): Long {
        if (tag.isNullOrBlank()) return 0xFF3B82F6
        val t = tag.lowercase()
        return when {
            "x" == t || "twitter" in t -> Twitter
            "reddit" in t -> Reddit
            "youtube" in t || "yt" in t -> YouTube
            "bluesky" in t || "bsky" in t -> Bluesky
            "xhs" in t || "xiaohongshu" in t || "red" in t -> Xiaohongshu
            "github" in t -> GitHub
            "rss" in t || "atom" in t -> Rss
            "hacker" in t || "hn" in t -> HackerNews
            else -> 0xFF3B82F6
        }
    }
}

/** Short human label for a raw platform tag, normalized for display. */
object PlatformLabels {
    fun forTag(tag: String?): String {
        if (tag.isNullOrBlank()) return "WEB"
        val t = tag.trim().lowercase()
        return when {
            t == "x" || "twitter" in t -> "X"
            "reddit" in t -> "REDDIT"
            "youtube" in t || t == "yt" -> "YOUTUBE"
            "bluesky" in t || t == "bsky" -> "BLUESKY"
            "xhs" in t || "xiaohongshu" in t -> "XHS"
            "github" in t -> "GITHUB"
            "rss" in t -> "RSS"
            "atom" in t -> "ATOM"
            "hacker" in t || t == "hn" -> "HN"
            else -> tag.uppercase().take(12)
        }
    }
}
