package org.ward.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.ward.android.service.WardService
import org.ward.android.ui.AppViewModel
import org.ward.android.ui.WardApp
import org.ward.android.ui.WardTheme
import org.ward.android.data.SettingsStore
import org.ward.android.ui.CrashScreen
import org.ward.android.util.JidUtil

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WardCore.init(applicationContext)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val lastCrash = CrashHandler.read(this)
        setContent {
            if (lastCrash != null) {
                WardTheme(SettingsStore(this)) {
                    CrashScreen(lastCrash) { CrashHandler.clear(this); recreate() }
                }
                return@setContent
            }
            val vm: AppViewModel = viewModel()
            WardTheme(vm.settings) { WardApp(vm) }
        }
    }

    /**
     * A notification tap or an xmpp: link is delivered here. Recreating the activity would throw
     * away the unlocked session and the open chat, so navigation is handed to the running UI
     * instead.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getLongExtra(EXTRA_OPEN_CHAT, 0L)?.takeIf { it > 0L }?.let { pendingChatId.value = it }
        intent?.data?.takeIf { it.scheme == "xmpp" }?.let { pendingJid.value = JidUtil.bare(it.toString()) }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "openChatId"

        /** Chat the running UI should open next; consumed by WardApp. */
        val pendingChatId = MutableStateFlow<Long?>(null)
        val pendingJid = MutableStateFlow<String?>(null)
    }
}
