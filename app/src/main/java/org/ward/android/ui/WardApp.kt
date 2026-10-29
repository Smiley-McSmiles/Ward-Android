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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ward.android.BuildConfig
import org.ward.android.MainActivity
import org.ward.android.WardCore
import org.ward.android.service.WardService
import org.ward.android.updates.UpdateCheck
import org.ward.android.updates.UpdateInfo
import org.ward.android.util.AppLockPolicy
import org.ward.android.util.AppLockStore

@Composable
fun WardApp(vm: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unlocked by remember {
        val enabled = AppLockStore.isEnabled(context)
        mutableStateOf(AppLockPolicy.startUnlocked(
            enabled = enabled,
            sessionUnlocked = AppLockStore.sessionUnlocked(),
            externalActivity = AppLockStore.externalActivityActive(),
            backgroundAt = AppLockStore.backgroundAt(context),
            now = SystemClock.elapsedRealtime(),
            lockDelayMs = AppLockStore.lockDelaySeconds(context) * 1_000L,
        ))
    }
    val currentlyUnlocked by rememberUpdatedState(unlocked)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val backgroundAt = AppLockStore.backgroundAt(context)
                    AppLockStore.clearBackgroundAt(context)
                    val now = SystemClock.elapsedRealtime()
                    if (AppLockStore.isEnabled(context) && !AppLockStore.externalActivityActive() &&
                        AppLockPolicy.lockOnReturn(backgroundAt, now, AppLockStore.lockDelaySeconds(context) * 1_000L)) {
                        AppLockStore.setSessionUnlocked(false)
                        unlocked = false
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
    // Ask GitHub at most once a day for a newer release. The timestamp is written before the
    // request so a failed check still waits the full day instead of retrying on every start.
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(unlocked) {
        if (!unlocked || !vm.settings.bool("general.update_check")) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - vm.settings.lastUpdateCheck() < DAY_MS) return@LaunchedEffect
        vm.settings.setLastUpdateCheck(now)
        val info = withContext(Dispatchers.IO) { UpdateCheck.latest() } ?: return@LaunchedEffect
        if (!UpdateCheck.isNewer(info.version, BuildConfig.VERSION_NAME)) return@LaunchedEffect
        if (info.version == vm.settings.skippedUpdateVersion()) return@LaunchedEffect
        update = info
    }
    val settingsState by vm.settings.state.collectAsState()
    val runInBackground = settingsState["general.background"] != "false"
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
    LaunchedEffect(accounts.size, runInBackground) {
        if (accounts.isNotEmpty()) {
            if (runInBackground) WardService.start(vm.getApplication())
            else WardService.stop(vm.getApplication())
        }
    }
    // Notification taps and xmpp: links are delivered to the running activity instead of a new
    // one, so a chat request can arrive while the UI is already composed.
    val pendingChatId by MainActivity.pendingChatId.collectAsState()
    val pendingJid by MainActivity.pendingJid.collectAsState()
    LaunchedEffect(pendingChatId, pendingJid, accounts.size) {
        val account = accounts.firstOrNull() ?: return@LaunchedEffect
        val jid = pendingJid
        if (jid != null) {
            vm.openChat(account.id, jid)
            MainActivity.pendingJid.value = null
        } else {
            val id = pendingChatId ?: return@LaunchedEffect
            vm.selectChatById(id)
            MainActivity.pendingChatId.value = null
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
            AppUnlockScreen(context) {
                AppLockStore.setSessionUnlocked(true)
                unlocked = true
            }
        }
    }
    update?.let { info ->
        UpdateAvailableDialog(info) {
            vm.settings.skipUpdateVersion(info.version)
            update = null
        }
    }
}

/** A newer release was published on GitHub; the download stays on the releases page. */
@Composable
fun UpdateAvailableDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text("Ward ${info.version} is available") },
        text = { Text("You are running ${BuildConfig.VERSION_NAME}. The new release is on the GitHub releases page, with its notes and the signed APK.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url))) }
                onDismiss()
            }) { Text("View release") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
