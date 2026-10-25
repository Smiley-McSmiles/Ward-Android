package org.ward.android.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
            startForeground(FOREGROUND_ID, connectionNotification(0, 0))
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

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(NotificationChannel("connection", "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
            mgr.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            })
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

    private fun notifyMessage(msg: MessageEntity, chat: ChatEntity, acc: AccountEntity) {
        if (chat.mutedUntil == -1L || chat.mutedUntil > System.currentTimeMillis()) return
        if (chat.isGroup) {
            when (core.settings.string("notify.groups", "mentions")) {
                "none" -> return
                "mentions" -> if (!msg.mentionsMe) return
            }
        }
        val content = core.settings.string("notify.content", "full")
        val title = if (chat.isGroup) "${chat.displayName} · ${msg.senderName}" else chat.displayName
        val body = when {
            content == "nothing" -> "New message"
            content == "sender" || (core.settings.bool("notify.hide_encrypted") && msg.encryption.isNotBlank()) -> "New message"
            else -> msg.body
        }
        val intent = Intent(this, MainActivity::class.java).apply { putExtra("openChatId", chat.id) }
        val notificationId = MESSAGE_NOTIFICATION_BASE + chat.id.toInt()
        val pi = PendingIntent.getActivity(this, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "messages")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup("ward-messages")
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, n)
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
    }
}
