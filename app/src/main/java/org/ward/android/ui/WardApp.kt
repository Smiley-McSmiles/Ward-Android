package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        val enabled = AppLockStore.isEnabled(context)
        val backgroundAt = AppLockStore.backgroundAt(context)
        val now = android.os.SystemClock.elapsedRealtime()
        val delayMs = AppLockStore.lockDelaySeconds(context) * 1_000L
        val stillWithinDelay = delayMs > 0 && backgroundAt > 0 && now >= backgroundAt && now - backgroundAt < delayMs
        mutableStateOf(!enabled || AppLockStore.externalActivityActive() || stillWithinDelay)
    }
    val currentlyUnlocked by rememberUpdatedState(unlocked)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val backgroundAt = AppLockStore.backgroundAt(context)
                    AppLockStore.clearBackgroundAt(context)
                    if (currentlyUnlocked && backgroundAt > 0L && AppLockStore.isEnabled(context) &&
                        !AppLockStore.externalActivityActive()) {
                        val delayMs = AppLockStore.lockDelaySeconds(context) * 1_000L
                        val elapsed = android.os.SystemClock.elapsedRealtime() - backgroundAt
                        if (delayMs <= 0L || elapsed >= delayMs) unlocked = false
                    }
                    vm.core.setAppForeground(unlocked)
                }
                Lifecycle.Event.ON_STOP -> {
                    vm.core.setAppForeground(false)
                    if (AppLockStore.isEnabled(context) && !AppLockStore.externalActivityActive() && currentlyUnlocked) {
                        AppLockStore.setBackgroundAt(context, android.os.SystemClock.elapsedRealtime())
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    vm.core.setAppForeground(false)
                }
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
    val currentCall by vm.core.call.collectAsState()
    val callSounds = remember { CallSounds(context, vm.settings) }
    val ringingIncoming = currentCall?.phase == "INCOMING"
    val ringingOutgoing = currentCall?.let { it.phase in listOf("OUTGOING", "RINGING") && !it.incoming } == true
    DisposableEffect(currentCall?.sessionId, ringingIncoming, ringingOutgoing) {
        if (ringingIncoming || ringingOutgoing) callSounds.start(ringingIncoming)
        else callSounds.stop()
        onDispose { callSounds.stop() }
    }
    var addingAccount by remember { mutableStateOf(false) }
    LaunchedEffect(accounts.size) {
        if (accounts.isNotEmpty()) {
            WardService.start(vm.getApplication())
            if (initialChatId != null) vm.selectChatById(initialChatId)
            if (initialJid != null) vm.openChat(accounts.first().id, initialJid)
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The app sits behind the lock screen while it zooms in, then the lock slides away.
        AnimatedVisibility(visible = unlocked,
            enter = scaleIn(initialScale = 0.8f,
                animationSpec = tween(320, easing = FastOutSlowInEasing)),
            exit = ExitTransition.None, label = "app") {
            Crossfade(targetState = accounts.isEmpty() || addingAccount, animationSpec = tween(180), label = "root") { welcome ->
                if (welcome) {
                    WelcomeScreen(vm, onBack = if (addingAccount) ({ addingAccount = false }) else null)
                    if (addingAccount) BackHandler { addingAccount = false }
                } else {
                    Crossfade(targetState = currentCall != null, animationSpec = tween(160), label = "call") { inCall ->
                        if (inCall) CallOverlay(vm) else MainScreen(vm, onAddAccount = { addingAccount = true })
                    }
                }
            }
        }
        AnimatedVisibility(visible = !unlocked, enter = fadeIn(tween(120)),
            exit = slideOutVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it },
            label = "unlock") {
            AppUnlockScreen(context) { unlocked = true }
        }
    }
}
