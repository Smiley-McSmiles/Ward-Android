package org.ward.android.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import org.ward.android.R
import org.ward.android.data.SettingsStore

/**
 * Repeats the bundled Ward ringtone/calling tone while a call rings, matching the GTK app's
 * three-second cadence. Settings can switch each sound to the system default or silence.
 */
class CallSounds(private val context: Context, private val settings: SettingsStore) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var active = false
    private var incoming = false

    private val tick = object : Runnable {
        override fun run() {
            if (active) {
                playOnce()
                handler.postDelayed(this, 3_000L)
            }
        }
    }

    fun start(isIncoming: Boolean) {
        if (active && incoming == isIncoming) return
        stop()
        active = true
        incoming = isIncoming
        handler.post(tick)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(tick)
        release()
    }

    private fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun playOnce() {
        release()
        val key = if (incoming) "calls.ringtone" else "calls.ringback"
        val choice = settings.string(key, "ward")
        if (choice == "none") return
        runCatching {
            val created = if (choice == "system") {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                if (uri == null) bundled(incoming) else MediaPlayer().apply {
                    setDataSource(context, uri)
                    setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    prepare()
                    isLooping = false
                    start()
                }
            } else bundled(incoming)
            player = created
        }.onFailure { release() }
    }

    private fun bundled(isIncoming: Boolean): MediaPlayer? = MediaPlayer.create(context,
        if (isIncoming) R.raw.phone_incoming_call else R.raw.phone_outgoing_calling)?.apply {
        isLooping = false
        start()
    }
}
