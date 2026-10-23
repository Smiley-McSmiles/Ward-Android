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
import org.ward.android.service.WardService
import org.ward.android.ui.AppViewModel
import org.ward.android.ui.WardApp
import org.ward.android.ui.WardTheme
import org.ward.android.data.SettingsStore
import org.ward.android.ui.CrashScreen

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var openChatId: Long? = null
    private var openJid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WardCore.init(applicationContext)
        openChatId = intent?.getLongExtra("openChatId", 0L)?.takeIf { it > 0 }
        openJid = intent?.data?.takeIf { it.scheme == "xmpp" }?.let { org.ward.android.util.JidUtil.bare(it.toString()) }
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
            WardTheme(vm.settings) { WardApp(vm, openChatId, openJid) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra("openChatId", 0L)
        if (id > 0) recreate()
        if (intent.data?.scheme == "xmpp") recreate()
    }
}
