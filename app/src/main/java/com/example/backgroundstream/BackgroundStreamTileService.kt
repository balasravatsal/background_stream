package com.example.backgroundstream

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackgroundStreamTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onStartListening() {
        super.onStartListening()
        val sessionToken = SessionToken(this, ComponentName(this, AudioService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching { mediaController = future.get() }
        }, MoreExecutors.directExecutor())
    }

    override fun onClick() {
        super.onClick()

        YouTubeNotificationListener.requestRebindIfPossible(this)
        val nowPlaying = YouTubeTitleResolver.resolveNowPlaying(this)

        if (nowPlaying == null || nowPlaying.displayQuery.isBlank()) {
            Toast.makeText(this, R.string.no_youtube_title, Toast.LENGTH_LONG).show()
            return
        }

        // Capture position/speed BEFORE pausing YouTube / stealing audio focus.
        val startPositionMs = nowPlaying.positionMs
        val startSpeed = nowPlaying.playbackSpeed.coerceIn(0.25f, 3f)

        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()

        Toast.makeText(
            this,
            getString(
                R.string.capturing_audio_sync,
                nowPlaying.displayQuery,
                formatPosition(startPositionMs),
                startSpeed
            ),
            Toast.LENGTH_SHORT
        ).show()

        // Pause YouTube so our player can take over cleanly.
        YouTubeTitleResolver.pauseYouTube(this)

        // Leave YouTube immediately (collapse shade + home).
        goHomeAndCollapse()

        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                StreamUtils.getAudioStream(
                    query = nowPlaying.displayQuery,
                    videoUrl = nowPlaying.videoUrl,
                    preferredAudioLanguage = nowPlaying.audioLanguage
                )
            }

            val controller = awaitController()
            when (result) {
                is StreamUtils.ExtractResult.Success -> {
                    if (controller == null) {
                        Toast.makeText(
                            applicationContext,
                            R.string.player_connect_failed,
                            Toast.LENGTH_LONG
                        ).show()
                        qsTile?.state = Tile.STATE_INACTIVE
                        qsTile?.updateTile()
                        return@launch
                    }

                    playFromSyncedPosition(controller, result.url, startPositionMs, startSpeed)
                    AudioService.notifyBackgroundStarted()

                    qsTile?.state = Tile.STATE_ACTIVE
                    qsTile?.updateTile()
                }

                is StreamUtils.ExtractResult.Failure -> {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.extract_failed_detail, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                    qsTile?.state = Tile.STATE_INACTIVE
                    qsTile?.updateTile()
                }
            }
        }
    }

    private fun playFromSyncedPosition(
        controller: MediaController,
        url: String,
        positionMs: Long,
        speed: Float
    ) {
        val applySync = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    controller.setPlaybackSpeed(speed)
                    if (positionMs > 0L) {
                        controller.seekTo(positionMs)
                    }
                    controller.play()
                    controller.removeListener(this)
                }
            }
        }

        controller.addListener(applySync)
        controller.setMediaItem(MediaItem.fromUri(url))
        controller.setPlaybackSpeed(speed)
        controller.prepare()
        controller.play()
    }

    private fun goHomeAndCollapse() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ prefers PendingIntent; Intent overload still works via collapse helper.
                @Suppress("DEPRECATION")
                startActivityAndCollapse(home)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(home)
            }
        } catch (_: Exception) {
            startActivity(home)
        }
    }

    private fun formatPosition(ms: Long): String {
        val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    override fun onStopListening() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun awaitController(): MediaController? {
        mediaController?.let { return it }
        val future = controllerFuture ?: run {
            val sessionToken = SessionToken(this, ComponentName(this, AudioService::class.java))
            MediaController.Builder(this, sessionToken).buildAsync().also {
                controllerFuture = it
            }
        }
        return withContext(Dispatchers.IO) {
            runCatching { future.get() }.getOrNull()
        }.also { mediaController = it }
    }
}
