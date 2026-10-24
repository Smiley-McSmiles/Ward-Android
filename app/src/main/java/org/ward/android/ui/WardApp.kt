package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ward.android.WardCore
import org.ward.android.service.WardService
import org.ward.android.util.AppLockStore

@Composable
fun WardApp(vm: AppViewModel, initialChatId: Long? = null, initialJid: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var unlocked by remember {
        mutableStateOf(!AppLockStore.isEnabled(context) || AppLockStore.externalActivityActive())
    }
    var lockJob by remember { mutableStateOf<Job?>(null) }
    val currentlyUnlocked by rememberUpdatedState(unlocked)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lockJob?.cancel()
                    lockJob = null
                    vm.core.setAppForeground(currentlyUnlocked)
                }
                Lifecycle.Event.ON_STOP -> {
                    vm.core.setAppForeground(false)
                    if (AppLockStore.isEnabled(context) && !AppLockStore.externalActivityActive() && currentlyUnlocked) {
                        val delaySeconds = AppLockStore.lockDelaySeconds(context)
                        if (delaySeconds <= 0) {
                            unlocked = false
                        } else {
                            lockJob?.cancel()
                            lockJob = scope.launch {
                                delay(delaySeconds * 1_000L)
                                unlocked = false
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    lockJob?.cancel()
                    lockJob = null
                    vm.core.setAppForeground(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lockJob?.cancel()
            lockJob = null
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.core.setAppForeground(false)
        }
    }
    // The service keeps receiving while the lock screen is visible. The foreground check is
    // disabled while locked so messages can still raise notifications.
    LaunchedEffect(unlocked) { vm.core.setAppForeground(unlocked) }
    // Pattern unlock is unreliable in landscape, so keep the lock screen in portrait. The rest
    // of the app stays free to follow the device rotation.
    val activity = context as? android.app.Activity
    DisposableEffect(unlocked, activity) {
        activity?.requestedOrientation = if (unlocked)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val accounts by vm.accounts.collectAsState()
    var addingAccount by remember { mutableStateOf(false) }
    LaunchedEffect(accounts.size) {
        if (accounts.isNotEmpty()) {
            WardService.start(vm.getApplication())
            if (initialChatId != null) vm.selectChatById(initialChatId)
            if (initialJid != null) vm.openChat(accounts.first().id, initialJid)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = !unlocked, enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.975f, animationSpec = tween(220)),
            label = "unlock") {
            AppUnlockScreen(context) { unlocked = true }
        }
        AnimatedVisibility(visible = unlocked, enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.985f, animationSpec = tween(220)),
            exit = fadeOut(tween(150)), label = "app") {
            Crossfade(targetState = accounts.isEmpty() || addingAccount, animationSpec = tween(180), label = "root") { welcome ->
                if (welcome) {
                    WelcomeScreen(vm, onBack = if (addingAccount) ({ addingAccount = false }) else null)
                    if (addingAccount) BackHandler { addingAccount = false }
                } else {
                    val call by vm.core.call.collectAsState()
                    Crossfade(targetState = call != null, animationSpec = tween(160), label = "call") { inCall ->
                        if (inCall) CallOverlay(vm) else MainScreen(vm, onAddAccount = { addingAccount = true })
                    }
                }
            }
        }
    }
}
