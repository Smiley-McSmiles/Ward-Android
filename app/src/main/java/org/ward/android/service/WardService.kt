package org.ward.android.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.ward.android.MainActivity
import org.ward.android.R
import org.ward.android.WardCore
import org.ward.android.data.AccountEntity
import org.ward.android.data.ChatEntity
import org.ward.android.data.MessageEntity

class WardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var core: WardCore

    override fun onCreate() {
        super.onCreate()
        core = WardCore.get()
        createChannels()
        try {
            // remoteMessaging is the correct type for a persistent XMPP connection. dataSync
            // would be subject to Android 15's six-hour-per-day timeout and cannot be started
            // from BOOT_COMPLETED, both of which would break background delivery.
            val type = if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            ServiceCompat.startForeground(this, FOREGROUND_ID, connectionNotification(0, 0), type)
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground service start from a background restart.
            // Do not take the whole app down; the next foreground launch will reconnect.
            stopSelf()
            return
        }
        core.notificationSink = { msg, chat, acc -> notifyMessage(msg, chat, acc) }
        core.notificationDismissSink = { chatId ->
            getSystemService(NotificationManager::class.java)
                .cancel(MESSAGE_NOTIFICATION_BASE + chatId.toInt())
        }
        scope.launch {
            core.dao.accounts().collect { accounts ->
                for (a in accounts) if (a.enabled && core.session(a.id) == null && core.passwordOf(a).isNotBlank()) {
                    core.connectAccount(a)
                }
            }
        }
        scope.launch {
            combine(core.dao.accounts(), core.states) { accounts, states -> accounts to states }
                .collect { (accounts, states) ->
                    val connected = accounts.count { states[it.id]?.state == "online" }
                    getSystemService(NotificationManager::class.java)
                        .notify(FOREGROUND_ID, connectionNotification(connected, accounts.size))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") {
            core.allSessions().forEach { it.stop() }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        core.notificationSink = null
        core.notificationDismissSink = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15 calls this when a time-limited foreground service type reaches its limit.
     * remoteMessaging is not currently time-limited, but if a future platform version applies a
     * limit the service must stop within a few seconds or the process is killed with a fatal
     * exception. Stopping cleanly lets the next app launch reconnect.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(NotificationChannel("connection", "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
            fun messageChannel(id: String, name: String, quiet: Boolean, badge: Boolean) {
                mgr.createNotificationChannel(NotificationChannel(id, name,
                    if (quiet) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(!quiet)
                    if (quiet) setSound(null, null)
                    setShowBadge(badge)
                })
            }
            messageChannel("messages", "Messages", quiet = false, badge = true)
            messageChannel("messages-quiet", "Messages during quiet hours", quiet = true, badge = true)
            messageChannel("messages-nobadge", "Messages without app-icon badge", quiet = false, badge = false)
            messageChannel("messages-quiet-nobadge", "Quiet messages without app-icon badge", quiet = true, badge = false)
        }
    }

    private fun connectionNotification(connected: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            total == 0 -> "Connecting…"
            total == 1 -> "$connected of 1 account connected"
            else -> "$connected of $total accounts connected"
        }
        return NotificationCompat.Builder(this, "connection")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Ward")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** Parse a 24-hour "HH:MM-HH:MM" range, including ranges that cross midnight. */
    private fun inQuietHours(): Boolean {
        val raw = core.settings.string("notify.quiet_hours").trim()
        val match = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").find(raw) ?: return false
        val (h1, m1, h2, m2) = match.destructured
        val start = (h1.toIntOrNull() ?: return false) * 60 + (m1.toIntOrNull() ?: return false)
        val end = (h2.toIntOrNull() ?: return false) * 60 + (m2.toIntOrNull() ?: return false)
        if (start !in 0..1439 || end !in 0..1439 || start == end) return false
        val now = java.util.Calendar.getInstance()
        val minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return if (start < end) minute in start until end else minute >= start || minute < end
    }

    private fun notifyMessage(msg: MessageEntity, chat: ChatEntity, acc: AccountEntity) {
        if (chat.mutedUntil == -1L || chat.mutedUntil > System.currentTimeMillis()) return
        if (chat.isGroup) {
            when (core.settings.string("notify.groups", "mentions")) {
                "none" -> return
                "mentions" -> if (!msg.mentionsMe) return
            }
        }
        val quiet = inQuietHours()
        val sounds = core.settings.bool("notify.sounds") && !quiet
        val badge = core.settings.bool("notify.badge")
        val content = core.settings.string("notify.content", "full")
        val title = if (chat.isGroup) "${chat.displayName} · ${msg.senderName}" else chat.displayName
        val body = when {
            content == "nothing" -> "New message"
            content == "sender" || (core.settings.bool("notify.hide_encrypted") && msg.encryption.isNotBlank()) -> "New message"
            msg.kind == "me" -> msg.body.removePrefix("/me ").trimStart()
            else -> msg.body
        }
        val intent = Intent(this, MainActivity::class.java).apply { putExtra("openChatId", chat.id) }
        val notificationId = MESSAGE_NOTIFICATION_BASE + chat.id.toInt()
        val pi = PendingIntent.getActivity(this, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val channel = when {
            quiet && badge -> "messages-quiet"
            quiet -> "messages-quiet-nobadge"
            badge -> "messages"
            else -> "messages-nobadge"
        }
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup("ward-messages")
            .setSilent(!sounds)
        if (!badge) {
            builder.setNumber(0).setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
        } else if (chat.unread > 0) {
            builder.setNumber(chat.unread)
        }
        if (quiet) builder.setPriority(NotificationCompat.PRIORITY_LOW)
        getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
    }

    companion object {
        private const val FOREGROUND_ID = 1
        // Keep message notifications away from the foreground service notification id.
        private const val MESSAGE_NOTIFICATION_BASE = 100_000

        fun start(context: Context) {
            val i = Intent(context, WardService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        /** Stop background delivery and close the live connections. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WardService::class.java)) }
            runCatching { WardCore.get().allSessions().forEach { it.stop() } }
        }
    }
}
