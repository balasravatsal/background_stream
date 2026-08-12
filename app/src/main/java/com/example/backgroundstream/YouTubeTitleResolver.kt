package com.example.backgroundstream

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import java.util.Locale

data class YouTubeNowPlaying(
    val displayQuery: String,
    val videoUrl: String?,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    /** BCP-47 / ISO language hint from YouTube session, e.g. "hi", "en-US", "Hindi". */
    val audioLanguage: String? = null
)

/**
 * Resolves the currently playing YouTube item (title, URL, position, speed, audio language).
 */
object YouTubeTitleResolver {

    private const val PREFS = "yt_title_cache"
    private const val KEY_TITLE = "active_title"
    private const val KEY_URL = "active_url"
    private const val KEY_LANG = "active_lang"

    private val videoIdRegex = Regex(
        """(?:v=|/shorts/|youtu\.be/|embed/|live/)([A-Za-z0-9_-]{11})|^([A-Za-z0-9_-]{11})$"""
    )

    val youtubePackages = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.mango",
    )

    fun resolveNowPlaying(context: Context): YouTubeNowPlaying? {
        fromMediaSessions(context)?.let {
            persist(context, it.displayQuery, it.videoUrl, it.audioLanguage)
            return it
        }

        val title = YouTubeNotificationListener.refreshActiveTitle()
            ?: YouTubeNotificationListener.activeYouTubeTitle
            ?: readPersisted(context, KEY_TITLE)
            ?: return null

        return YouTubeNowPlaying(
            displayQuery = title,
            videoUrl = readPersisted(context, KEY_URL),
            audioLanguage = YouTubeNotificationListener.activeAudioLanguage
                ?: readPersisted(context, KEY_LANG)
        )
    }

    fun resolve(context: Context): String? = resolveNowPlaying(context)?.displayQuery

    fun persist(
        context: Context,
        title: String,
        videoUrl: String? = null,
        audioLanguage: String? = null
    ) {
        if (title.isBlank()) return
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TITLE, title.trim())
        if (!videoUrl.isNullOrBlank()) {
            editor.putString(KEY_URL, videoUrl)
        }
        if (!audioLanguage.isNullOrBlank()) {
            editor.putString(KEY_LANG, audioLanguage)
        }
        editor.apply()
    }

    fun getYouTubeController(context: Context): MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val listenerComponent = ComponentName(context, YouTubeNotificationListener::class.java)
        val controllers = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            return null
        }
        return controllers
            .filter { it.packageName in youtubePackages }
            .maxByOrNull { score(it.playbackState?.state) }
    }

    fun estimatePositionMs(state: PlaybackState?): Long {
        if (state == null) return 0L
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        val speed = playbackSpeedOf(state)
        return (base + (elapsed * speed).toLong()).coerceAtLeast(0L)
    }

    fun playbackSpeedOf(state: PlaybackState?): Float {
        if (state == null) return 1f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            state.playbackSpeed.takeIf { it > 0f } ?: 1f
        } else {
            1f
        }
    }

    fun seekAndPlayYouTube(context: Context, positionMs: Long, speed: Float) {
        val controller = getYouTubeController(context) ?: return
        val controls = controller.transportControls
        controls.seekTo(positionMs.coerceAtLeast(0L))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            controls.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))
        }
        controls.play()
    }

    fun pauseYouTube(context: Context) {
        getYouTubeController(context)?.transportControls?.pause()
    }

    private fun readBundleLanguageHints(extras: Bundle): List<String?> {
        val values = mutableListOf<String?>()
        for (key in extras.keySet()) {
            val keyLower = key.lowercase(Locale.US)
            if (keyLower.contains("lang") ||
                keyLower.contains("audio") ||
                keyLower.contains("track") ||
                keyLower.contains("locale")
            ) {
                values += extras.getString(key) ?: extras.getCharSequence(key)?.toString()
            }
        }
        return values
    }

    fun sanitizeLanguageHint(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > 40) return null
        if (trimmed.contains("http", ignoreCase = true)) return null
        if (trimmed.contains(' ') && trimmed.length > 24) return null
        return trimmed
    }

    private fun readPersisted(context: Context, key: String): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun fromMediaSessions(context: Context): YouTubeNowPlaying? {
        val controller = getYouTubeController(context) ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)

        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val videoUrl = extractVideoUrl(mediaUri, mediaId, title, artist)
        val positionMs = estimatePositionMs(state)
        val speed = playbackSpeedOf(state)
        val audioLanguage = extractLanguageHint(controller)
            ?: YouTubeNotificationListener.activeAudioLanguage
            ?: readPersisted(context, KEY_LANG)

        if (!title.isNullOrBlank()) {
            val query = if (!artist.isNullOrBlank()) "$title $artist" else title
            return YouTubeNowPlaying(query, videoUrl, positionMs, speed, audioLanguage)
        }
        if (videoUrl != null) {
            return YouTubeNowPlaying(videoUrl, videoUrl, positionMs, speed, audioLanguage)
        }
        return null
    }

    fun extractLanguageHint(controller: MediaController): String? {
        val candidates = mutableListOf<String?>()
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        if (metadata != null) {
            for (key in metadata.keySet()) {
                val keyLower = key.lowercase(Locale.US)
                if (keyLower.contains("lang") ||
                    keyLower.contains("audio") ||
                    keyLower.contains("track") ||
                    keyLower.contains("locale")
                ) {
                    candidates += metadata.getString(key)
                }
            }
        }

        playbackState?.extras?.let { candidates += readBundleLanguageHints(it) }
        controller.extras?.let { candidates += readBundleLanguageHints(it) }

        return candidates
            .asSequence()
            .mapNotNull { sanitizeLanguageHint(it) }
            .firstOrNull()
    }

    private fun score(state: Int?): Int = when (state) {
        PlaybackState.STATE_PLAYING -> 3
        PlaybackState.STATE_BUFFERING -> 2
        PlaybackState.STATE_PAUSED -> 1
        else -> 0
    }

    private fun extractVideoUrl(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            val match = videoIdRegex.find(candidate) ?: continue
            val id = match.groupValues[1].ifBlank { match.groupValues[2] }
            if (id.length == 11) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }
        return null
    }
}
