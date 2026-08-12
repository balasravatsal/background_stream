package com.example.backgroundstream

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class YouTubeNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var activeYouTubeTitle: String? = null
            private set

        @Volatile
        var activeAudioLanguage: String? = null
            private set

        @Volatile
        private var instance: YouTubeNotificationListener? = null

        fun refreshActiveTitle(): String? {
            instance?.scanActiveNotifications()
            return activeYouTubeTitle
        }

        fun requestRebindIfPossible(context: android.content.Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                requestRebind(ComponentName(context, YouTubeNotificationListener::class.java))
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        scanActiveNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { captureYouTubeTitle(it) }
    }

    private fun scanActiveNotifications() {
        runCatching {
            activeNotifications.forEach { captureYouTubeTitle(it) }
        }
    }

    private fun captureYouTubeTitle(sbn: StatusBarNotification) {
        if (sbn.packageName !in YouTubeTitleResolver.youtubePackages) return

        val extras = sbn.notification.extras
        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            sbn.notification.tickerText?.toString()
        )
        val text = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        )

        val languageHint = firstNonBlank(
            extras.getString("android.media.metadata.LANGUAGE"),
            extras.getString("language"),
            extras.getString("audio_language"),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        )?.let { YouTubeTitleResolver.sanitizeLanguageHint(it) }

        if (!languageHint.isNullOrBlank()) {
            activeAudioLanguage = languageHint
        }

        if (!title.isNullOrBlank()) {
            val combined = if (!text.isNullOrBlank() && !title.contains(text)) {
                "$title $text"
            } else {
                title
            }
            activeYouTubeTitle = combined
            YouTubeTitleResolver.persist(this, combined, audioLanguage = languageHint)
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }
}
