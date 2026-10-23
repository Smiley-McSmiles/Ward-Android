package org.ward.android.xmpp

import org.webrtc.VideoTrack

data class CallUiState(
    val sessionId: String,
    val peerJid: String,
    val peerName: String,
    val video: Boolean,
    val incoming: Boolean,
    val phase: String, // INCOMING | OUTGOING | RINGING | CONNECTING | ACTIVE | ENDED
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val error: String = "",
    val remoteVideo: VideoTrack? = null,
    val startedAt: Long = 0,
    val accountId: Long = 0
) {
    val active: Boolean get() = phase == "ACTIVE"
}
