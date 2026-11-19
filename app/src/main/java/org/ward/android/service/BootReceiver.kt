package org.ward.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ward.android.WardCore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    WardCore.init(appContext)
                    // Alarms do not survive a reboot; put every pending scheduled message back.
                    ScheduledSendStore.rescheduleAll(appContext)
                    val settings = WardCore.get().settings
                    if (settings.bool("general.autostart") && settings.bool("general.background")) {
                        WardService.start(appContext)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
