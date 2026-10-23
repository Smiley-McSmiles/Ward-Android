package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ward.android.WardCore
import org.ward.android.service.WardService
import org.ward.android.util.AppLockStore

@Composable
fun WardApp(vm: AppViewModel, initialChatId: Long? = null, initialJid: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unlocked by remember {
        mutableStateOf(!AppLockStore.isEnabled(context) || AppLockStore.externalActivityActive())
    }
    val currentlyUnlocked by rememberUpdatedState(unlocked)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.core.setAppForeground(currentlyUnlocked)
                Lifecycle.Event.ON_STOP -> {
                    vm.core.setAppForeground(false)
                    if (AppLockStore.isEnabled(context) && !AppLockStore.externalActivityActive()) unlocked = false
                }
                Lifecycle.Event.ON_DESTROY -> vm.core.setAppForeground(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.core.setAppForeground(false)
        }
    }
    // The service keeps receiving while the lock screen is visible. The foreground check is
    // disabled while locked so messages can still raise notifications.
    LaunchedEffect(unlocked) { vm.core.setAppForeground(unlocked) }
    val accounts by vm.accounts.collectAsState()
    var addingAccount by remember { mutableStateOf(false) }
    LaunchedEffect(accounts.size) {
        if (accounts.isNotEmpty()) {
            WardService.start(vm.getApplication())
            if (initialChatId != null) vm.selectChatById(initialChatId)
            if (initialJid != null) vm.openChat(accounts.first().id, initialJid)
        }
    }
    if (!unlocked) {
        AppUnlockScreen(context) { unlocked = true }
        return
    }
    Crossfade(targetState = accounts.isEmpty() || addingAccount, animationSpec = tween(240), label = "root") { welcome ->
        if (welcome) {
            WelcomeScreen(vm, onBack = if (addingAccount) ({ addingAccount = false }) else null)
            if (addingAccount) BackHandler { addingAccount = false }
        } else {
            val call by vm.core.call.collectAsState()
            Crossfade(targetState = call != null, animationSpec = tween(180), label = "call") { inCall ->
                if (inCall) CallOverlay(vm) else MainScreen(vm, onAddAccount = { addingAccount = true })
            }
        }
    }
}
