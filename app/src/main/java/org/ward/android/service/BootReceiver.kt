package org.ward.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.ward.android.WardCore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WardCore.init(context)
            // Alarms do not survive a reboot; put every pending scheduled message back.
            ScheduledSendStore.rescheduleAll(context)
            val settings = WardCore.get().settings
            if (settings.bool("general.autostart") && settings.bool("general.background")) {
                WardService.start(context)
            }
        }
    }
}
