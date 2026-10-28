package org.ward.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ward.android.WardCore

data class ScheduledMessage(
    val id: String,
    val chatId: Long,
    val accountId: Long,
    val text: String,
    val at: Long,
    val attempts: Int = 0
)

/**
 * Scheduled text messages are small enough to live in SharedPreferences instead of a new Room
 * table. An AlarmManager broadcast wakes the process at the requested time; the receiver owns a
 * unique id so duplicate alarms replace rather than duplicate the pending send.
 */
object ScheduledSendStore {
    private const val PREFS = "ward_scheduled"
    private const val KEY = "messages"
    private const val MAX_ATTEMPTS = 3
    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<ScheduledMessage> {
        val raw = prefs(context).getString(KEY, "[]").orEmpty()
        return runCatching {
            gson.fromJson<List<ScheduledMessage>>(raw,
                object : TypeToken<List<ScheduledMessage>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, message: ScheduledMessage) {
        val next = all(context).filterNot { it.id == message.id } + message
        save(context, next)
        schedule(context, message)
    }

    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    private fun save(context: Context, messages: List<ScheduledMessage>) {
        prefs(context).edit().putString(KEY, gson.toJson(messages)).apply()
    }

    fun rescheduleAll(context: Context) {
        all(context).forEach { schedule(context, it) }
    }

    private fun schedule(context: Context, message: ScheduledMessage) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ScheduledSendReceiver::class.java)
            .putExtra(ScheduledSendReceiver.EXTRA_ID, message.id)
        val pending = PendingIntent.getBroadcast(context, message.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact delivery avoids the special exact-alarm permission. A scheduled message may
        // arrive a little later while the device is dozing, which is the right trade-off here.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            message.at.coerceAtLeast(System.currentTimeMillis() + 1_000L), pending)
    }

    suspend fun deliver(context: Context, id: String): Boolean {
        val message = all(context).firstOrNull { it.id == id } ?: return false
        WardCore.init(context)
        val core = WardCore.get()
        val chat = core.dao.chat(message.chatId)
        val account = core.dao.account(message.accountId)
        if (chat == null || account == null) {
            remove(context, id)
            return false
        }
        if (!core.isOnline(message.accountId)) {
            runCatching { core.connectAccount(account) }
            // BroadcastReceiver has a limited background window; if the server is slow, leave the
            // message pending and let the next alarm try again.
            for (i in 0 until 8) {
                if (core.isOnline(message.accountId)) break
                delay(1_000L)
            }
        }
        if (!core.isOnline(message.accountId)) {
            if (message.attempts + 1 >= MAX_ATTEMPTS) {
                remove(context, id)
            } else {
                add(context, message.copy(at = System.currentTimeMillis() + 120_000L,
                    attempts = message.attempts + 1))
            }
            return false
        }
        core.sendText(chat, message.text, null)
        remove(context, id)
        return true
    }
}

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledSendStore.deliver(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "scheduled_id"
    }
}
