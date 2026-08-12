package com.example.backgroundstream

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media3 playback service with YouTube handoff:
 * when YouTube starts playing again, stop BG audio and seek YouTube
 * to this player's position / speed.
 */
class AudioService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var sessionManager: MediaSessionManager? = null
    private var handoffArmed = false
    private var syncActive = false
    private var handingOff = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val armHandoffRunnable = Runnable { handoffArmed = true }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { checkYouTubeHandoff() }

    private val youTubeCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            checkYouTubeHandoff(state)
        }
    }

    private var watchedYouTubeController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    beginSyncWatch()
                }
            }
        })

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        endSyncWatch()
        if (instance === this) {
            instance = null
        }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    fun beginSyncWatch() {
        if (syncActive) {
            // Re-arm after each fresh BG start.
            armHandoff()
            return
        }
        syncActive = true
        armHandoff()

        val listenerComponent = ComponentName(this, YouTubeNotificationListener::class.java)
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler
            )
        }.onFailure { Log.w(TAG, "Unable to watch media sessions", it) }

        attachYouTubeCallback()
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun endSyncWatch() {
        syncActive = false
        handoffArmed = false
        mainHandler.removeCallbacks(armHandoffRunnable)
        mainHandler.removeCallbacks(pollRunnable)
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        detachYouTubeCallback()
    }

    private fun armHandoff() {
        handoffArmed = false
        mainHandler.removeCallbacks(armHandoffRunnable)
        // Ignore the brief PLAYING→PAUSED transition while we steal audio focus.
        mainHandler.postDelayed(armHandoffRunnable, HANDOFF_ARM_DELAY_MS)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!syncActive) return
            attachYouTubeCallback()
            checkYouTubeHandoff()
            // Keep YouTube's paused scrubber roughly in sync so resume is close.
            if (handoffArmed && !handingOff) {
                quietlySyncYouTubePosition()
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun attachYouTubeCallback() {
        val latest = YouTubeTitleResolver.getYouTubeController(this) ?: return
        if (watchedYouTubeController === latest) return
        detachYouTubeCallback()
        watchedYouTubeController = latest
        runCatching { latest.registerCallback(youTubeCallback, mainHandler) }
    }

    private fun detachYouTubeCallback() {
        watchedYouTubeController?.let { controller ->
            runCatching { controller.unregisterCallback(youTubeCallback) }
        }
        watchedYouTubeController = null
    }

    private fun checkYouTubeHandoff(state: PlaybackState? = null) {
        if (!syncActive || !handoffArmed || handingOff) return
        val player = player ?: return
        if (!player.isPlaying && player.playbackState != Player.STATE_READY) return

        val ytState = state
            ?: YouTubeTitleResolver.getYouTubeController(this)?.playbackState
            ?: return

        if (ytState.state == PlaybackState.STATE_PLAYING ||
            ytState.state == PlaybackState.STATE_BUFFERING
        ) {
            handoffToYouTube()
        }
    }

    private fun quietlySyncYouTubePosition() {
        val player = player ?: return
        if (!player.isPlaying) return
        val controller = YouTubeTitleResolver.getYouTubeController(this) ?: return
        val ytState = controller.playbackState?.state
        // Only nudge while YouTube is paused (we own playback).
        if (ytState != PlaybackState.STATE_PAUSED && ytState != PlaybackState.STATE_STOPPED) {
            return
        }
        runCatching {
            controller.transportControls.seekTo(player.currentPosition.coerceAtLeast(0L))
        }
    }

    private fun handoffToYouTube() {
        val player = player ?: return
        handingOff = true
        handoffArmed = false

        val position = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed.coerceIn(0.25f, 3f)

        Log.i(TAG, "Handing off to YouTube at ${position}ms speed=$speed")

        runCatching { player.pause() }

        YouTubeTitleResolver.seekAndPlayYouTube(this, position, speed)

        runCatching {
            player.stop()
            player.clearMediaItems()
        }

        endSyncWatch()
        handingOff = false
    }

    companion object {
        private const val TAG = "AudioService"
        private const val HANDOFF_ARM_DELAY_MS = 2_000L
        private const val POLL_INTERVAL_MS = 2_500L

        @Volatile
        private var instance: AudioService? = null

        fun notifyBackgroundStarted() {
            instance?.beginSyncWatch()
        }
    }
}
