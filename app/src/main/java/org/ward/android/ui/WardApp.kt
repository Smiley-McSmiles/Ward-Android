package org.ward.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import org.ward.android.WardCore
import org.ward.android.service.WardService

@Composable
fun WardApp(vm: AppViewModel, initialChatId: Long? = null, initialJid: String? = null) {
    val accounts by vm.accounts.collectAsState()
    var addingAccount by remember { mutableStateOf(false) }
    LaunchedEffect(accounts.size) {
        if (accounts.isNotEmpty()) {
            WardService.start(vm.getApplication())
            if (initialChatId != null) vm.selectChatById(initialChatId)
            if (initialJid != null) vm.openChat(accounts.first().id, initialJid)
        }
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
