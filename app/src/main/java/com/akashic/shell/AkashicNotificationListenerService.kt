package com.akashic.shell

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 零轮询系统通知与媒体会话监听适配器：
 * - 通知仅交给 DeviceEventBroker 规则引擎过滤；命中 auto_cancel 时读完即消掉状态栏卡片。
 * - 利用系统已授信的 NotificationListenerService ComponentName 直接订阅 MediaSessionManager。
 */
class AkashicNotificationListenerService : NotificationListenerService() {
    private var mediaThread: HandlerThread? = null
    private var mediaHandler: Handler? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val mediaCallbacks = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val lastMediaState = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected")
        DeviceEventBroker.init(applicationContext)
        startMediaObserver()
    }

    override fun onListenerDisconnected() {
        stopMediaObserver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopMediaObserver()
        super.onDestroy()
    }

    private fun startMediaObserver() {
        if (sessionsListener != null) return
        val ht = HandlerThread("AkashicMediaObserver").apply { isDaemon = true; start() }
        val handler = Handler(ht.looper)
        mediaThread = ht
        mediaHandler = handler
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return
            val comp = ComponentName(this, AkashicNotificationListenerService::class.java)
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                syncMediaControllers(controllers ?: emptyList())
            }
            sessionsListener = listener
            msm.addOnActiveSessionsChangedListener(listener, comp, handler)
            syncMediaControllers(msm.getActiveSessions(comp))
            Log.i(TAG, "Registered MediaSessionManager listener via NotificationListenerService")
        }.onFailure {
            Log.w(TAG, "MediaSessionManager listener unavailable", it)
            stopMediaObserver()
        }
    }

    private fun stopMediaObserver() {
        val listener = sessionsListener
        sessionsListener = null
        if (listener != null) {
            runCatching {
                getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(listener)
            }
        }
        for ((_, pair) in mediaCallbacks) {
            runCatching { pair.first.unregisterCallback(pair.second) }
        }
        mediaCallbacks.clear()
        mediaHandler = null
        mediaThread?.quitSafely()
        mediaThread = null
    }

    private fun syncMediaControllers(controllers: List<MediaController>) {
        val handler = mediaHandler ?: return
        val liveKeys = HashSet<String>()
        for (controller in controllers) {
            val pkg = controller.packageName ?: continue
            val key = "$pkg:${controller.sessionToken}"
            liveKeys.add(key)
            if (!mediaCallbacks.containsKey(key)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        reportMediaController(controller)
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        reportMediaController(controller)
                    }
                }
                runCatching {
                    controller.registerCallback(cb, handler)
                    mediaCallbacks[key] = controller to cb
                    reportMediaController(controller)
                }
            }
        }
        val iter = mediaCallbacks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in liveKeys) {
                runCatching { entry.value.first.unregisterCallback(entry.value.second) }
                iter.remove()
            }
        }
    }

    private fun reportMediaController(controller: MediaController) {
        val pkg = controller.packageName ?: return
        val pbState = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        val stateLabel = when (pbState) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> return
        }
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val sig = "$stateLabel|$title|$artist|$album"
        if (lastMediaState[pkg] == sig) return
        lastMediaState[pkg] = sig
        DeviceEventBroker.onMediaStateChanged(pkg, stateLabel, title, artist, album)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        DeviceEventBroker.init(applicationContext)
        val ongoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val bodyText = if (bigText.length > text.length) bigText else text
        val appName = runCatching {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)

        val result = DeviceEventBroker.onNotificationPosted(
            packageName = pkg,
            appName = appName,
            title = title,
            text = bodyText,
            subText = subText,
            category = notif.category ?: "",
            channelId = notif.channelId ?: "",
            postTimeMs = sbn.postTime,
            ongoing = ongoing,
            notificationKey = sbn.key ?: "",
        )
        if (result.autoCancel && !ongoing) {
            runCatching { cancelNotification(sbn.key) }
        }
    }

    companion object {
        private const val TAG = "AkashicNotifListener"
    }
}
