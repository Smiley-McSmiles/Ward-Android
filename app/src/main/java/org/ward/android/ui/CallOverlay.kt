package org.ward.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import org.ward.android.xmpp.CallUiState

@Composable
fun CallOverlay(vm: AppViewModel) {
    val state by vm.core.call.collectAsState()
    val callState = state ?: return
    val session = vm.core.session(callState.accountId) ?: return
    val call = session.call
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { call.answer() }
    fun answerCall() {
        val needed = if (callState.video) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
            else arrayOf(android.Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter { androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) call.answer() else permissions.launch(missing.toTypedArray())
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        if (callState.video && callState.remoteVideo != null && callState.phase == "ACTIVE") {
            AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx) }, update = { view -> call.attachRemote(view) },
                modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Avatar(callState.peerName, 110, show = "available")
                Spacer(Modifier.height(16.dp))
                Text(callState.peerName, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(when (callState.phase) {
                    "INCOMING" -> if (callState.video) "Incoming video call" else "Incoming audio call"
                    "OUTGOING", "RINGING" -> "Calling…"
                    "CONNECTING" -> "Connecting…"
                    "ACTIVE" -> "Connected"
                    else -> callState.phase.lowercase().replaceFirstChar { it.uppercase() }
                }, color = Color(0xFFCCCCCC))
                if (callState.error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp)); Text(callState.error, color = Color(0xFFFFAAAA))
                }
            }
        }
        if (callState.video && callState.phase == "ACTIVE") {
            AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx) }, update = { view -> call.attachLocal(view) },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(110.dp, 150.dp)
                    .clip(RoundedCornerShape(12.dp)))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (callState.phase == "INCOMING") {
                CallButton(Icons.Filled.CallEnd, "Decline", Color(0xFFE01B24)) { call.reject() }
                CallButton(Icons.Filled.Call, "Answer", Color(0xFF2EC27E)) { answerCall() }
            } else {
                CallButton(if (callState.muted) Icons.Filled.MicOff else Icons.Filled.Mic, "Mute", Color(0xFF444444)) { call.toggleMute() }
                if (callState.video) CallButton(Icons.Filled.Videocam, "Camera", Color(0xFF444444)) { call.toggleVideo() }
                CallButton(if (callState.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", Color(0xFF444444)) { call.toggleSpeaker() }
                CallButton(Icons.Filled.CallEnd, "Hang up", Color(0xFFE01B24)) { call.hangup() }
            }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(58.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(icon, label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
