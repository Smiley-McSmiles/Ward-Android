package org.ward.android.xmpp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.util.XmlStringBuilder
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.webrtc.*
import kotlinx.coroutines.launch
import org.ward.android.WardCore
import org.ward.android.data.ChatEntity
import org.jxmpp.jid.impl.JidCreate
import org.ward.android.util.JidUtil
import java.util.UUID
import java.util.concurrent.Executors

class CallManager(private val session: XmppSession) {
    companion object {
        @Volatile private var webrtcInitialized = false
    }
    private val context: Context get() = session.core.context
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peer: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var sid = ""
    private var peerJid = ""
    private var peerFullJid = ""
    private var peerName = ""
    private var wantsVideo = false
    private var isCaller = false
    private var initiatorJid = ""
    private var answering = false
    private var gatherScheduled = false
    private var pendingOfferXml: String? = null

    fun current(): CallUiState? = session.core.call.value

    fun start(chat: ChatEntity, video: Boolean) {
        if (session.core.call.value != null) return
        wantsVideo = video
        if (!ensureFactory()) return
        sid = "ward-" + UUID.randomUUID().toString()
        peerJid = chat.jid
        peerFullJid = ""
        peerName = chat.displayName
        initiatorJid = session.connection?.user?.toString() ?: session.account.jid
        setAudioMode(true)
        isCaller = true
        remoteSet = false
        answering = false
        publish(CallUiState(sid, peerJid, peerName, video, false, "OUTGOING"))
        sendJmi("propose", mapOf("video" to video))
    }

    fun onIncomingPropose(msg: Message, xml: String) {
        if (session.core.call.value != null) {
            sendJmi("reject", emptyMap(), from = msg.from?.asEntityBareJidIfPossible()?.toString() ?: return, id = extractAttr(xml, "id") ?: return)
            return
        }
        val id = extractAttr(xml, "id") ?: return
        sid = id
        peerJid = msg.from?.asEntityBareJidIfPossible()?.toString() ?: return
        peerFullJid = msg.from?.toString() ?: peerJid
        peerName = JidUtil.nick(peerJid)
        initiatorJid = msg.from?.toString() ?: peerJid
        session.core.scope.launch { val c = session.core.dao.contact(session.account.id, peerJid); if (c != null) { peerName = c.name; session.core.call.value?.let { publish(it.copy(peerName = c.name)) } } }
        wantsVideo = xml.contains("media='video'") || xml.contains("media=\"video\"")
        isCaller = false
        remoteSet = false
        answering = false
        if (!ensureFactory()) {
            sendJmi("reject", emptyMap(), id = sid)
            return
        }
        publish(CallUiState(sid, peerJid, peerName, wantsVideo, true, "INCOMING"))
        sendJmi("ringing", emptyMap(), id = sid)
    }

    fun answer() {
        val state = session.core.call.value ?: return
        if (!state.incoming) return
        setAudioMode(true)
        if (ensurePeer() == null) {
            publish(state.copy(phase = "ENDED", error = "Could not start audio"))
            sendJmi("reject", emptyMap(), id = sid)
            return
        }
        answering = true
        publish(state.copy(phase = "CONNECTING", incoming = false))
        // XEP-0353: the caller needs "proceed"; "accept" tells our other devices to stop ringing.
        sendJmi("proceed", emptyMap(), id = sid)
        session.connection?.user?.asBareJid()?.toString()?.let { own ->
            if (own != peerJid) sendJmi("accept", emptyMap(), from = own, id = sid)
        }
        pendingOfferXml?.let { xml -> pendingOfferXml = null; acceptSession(xml) }
    }

    fun reject() {
        val state = session.core.call.value ?: return
        sendJmi("reject", emptyMap(), id = sid)
        finish()
    }

    fun hangup() {
        if (sid.isNotBlank()) {
            val xml = "<jingle xmlns='${JingleSdp.NS_JINGLE}' action='session-terminate' sid='${esc(sid)}' initiator='${esc(if (initiatorJid.isBlank()) (session.connection?.user?.toString() ?: session.account.jid) else initiatorJid)}'><reason><success/></reason></jingle>"
            runCatching { sendJingleRaw("session-terminate", xml) }
            sendJmi("retract", emptyMap(), id = sid)
        }
        finish()
    }

    private fun setAudioMode(inCall: Boolean) {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        if (inCall) {
            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = session.core.call.value?.speaker ?: true
        } else {
            am.isSpeakerphoneOn = false
            am.mode = android.media.AudioManager.MODE_NORMAL
        }
    }

    fun toggleMute() {
        val state = session.core.call.value ?: return
        val muted = !state.muted
        audioTrack?.setEnabled(!muted)
        publish(state.copy(muted = muted))
    }

    fun toggleSpeaker() {
        val state = session.core.call.value ?: return
        val speaker = !state.speaker
        val am = context.getSystemService(android.media.AudioManager::class.java)
        am.isSpeakerphoneOn = speaker
        publish(state.copy(speaker = speaker))
    }

    fun toggleVideo() {
        val state = session.core.call.value ?: return
        if (videoTrack == null) return
        val enabled = !state.video || state.phase != "ACTIVE"
        videoTrack?.setEnabled(enabled)
        publish(state.copy(video = enabled))
    }

    fun attachLocal(renderer: SurfaceViewRenderer?) {
        if (renderer === localRenderer) { videoTrack?.addSink(renderer); return }
        val ctx = eglBase?.eglBaseContext ?: return
        localRenderer?.release()
        localRenderer = renderer
        renderer?.init(ctx, null)
        videoTrack?.addSink(renderer)
    }

    fun attachRemote(renderer: SurfaceViewRenderer?) {
        if (renderer === remoteRenderer) { session.core.call.value?.remoteVideo?.addSink(renderer); return }
        val ctx = eglBase?.eglBaseContext ?: return
        remoteRenderer?.release()
        remoteRenderer = renderer
        renderer?.init(ctx, null)
        session.core.call.value?.remoteVideo?.addSink(renderer)
    }

    // ================================================================ JMI
    fun onJmi(msg: Message, xml: String) {
        session.core.addXmlLog(session.account.id, true, xml)
        if (peerJid.isNotBlank() && msg.from?.asEntityBareJidIfPossible()?.toString() == peerJid)
            peerFullJid = msg.from?.toString() ?: peerFullJid
        val action = Regex("<([a-z-]+)[^>]*xmlns=['\\\"]urn:xmpp:jingle-message:0['\\\"]").find(xml)?.groupValues?.get(1)
            ?: Regex("xmlns=['\\\"]urn:xmpp:jingle-message:0['\\\"]").find(xml)?.let { "unknown" } ?: return
        val id = extractAttr(xml, "id") ?: ""
        if (id.isNotBlank() && sid.isNotBlank() && id != sid) return
        when (action) {
            "propose" -> onIncomingPropose(msg, xml)
            "ringing" -> session.core.call.value?.let { publish(it.copy(phase = "RINGING")) }
            "accept", "proceed" -> {
                if (isCaller && session.core.call.value?.phase in listOf("OUTGOING", "RINGING")) {
                    session.core.call.value?.let { publish(it.copy(phase = "CONNECTING", incoming = false)) }
                    createOfferAndInitiate()
                }
            }
            "reject" -> { finish(); session.core.setCallState(session.core.call.value?.copy(phase = "ENDED")) }
            "retract" -> finish()
        }
    }

    private fun sendJmi(action: String, extra: Map<String, Any>, from: String? = null, id: String = sid) {
        val to = from ?: peerJid
        if (to.isBlank() || id.isBlank()) return
        val body = StringBuilder("<$action xmlns='urn:xmpp:jingle-message:0' id='${esc(id)}'/>")
        if (action == "propose") {
            body.setLength(0)
            body.append("<propose xmlns='urn:xmpp:jingle-message:0' id='${esc(id)}'>")
            body.append("<description xmlns='${JingleSdp.NS_RTP}' media='audio'/>")
            if (extra["video"] == true) body.append("<description xmlns='${JingleSdp.NS_RTP}' media='video'/>")
            body.append("</propose>")
        }
        val msg = StanzaBuilder.buildMessage()
        try { msg.to(to) } catch (_: Exception) { return }
        msg.ofType(Message.Type.chat)
        msg.addExtension(RawExtension(action, JingleSdp.NS_JMI, body.toString()))
        val built = msg.build()
        session.core.addXmlLog(session.account.id, false, built.toXML(org.jivesoftware.smack.packet.XmlEnvironment.EMPTY).toString())
        session.connection?.sendStanza(built)
    }

    // ================================================================ Jingle IQ
    fun onJingleIq(iq: IQ, xml: String) {
        session.core.addXmlLog(session.account.id, true, xml)
        val action = extractAttr(xml, "action") ?: return
        val sessionId = extractAttr(xml, "sid") ?: return
        if (sid.isBlank()) {
            if (action == "session-initiate") {
                sid = sessionId
                peerJid = iq.from?.asEntityBareJidIfPossible()?.toString() ?: return
                peerFullJid = iq.from?.toString() ?: peerJid
                peerName = JidUtil.nick(peerJid)
                initiatorJid = iq.from?.toString() ?: extractAttr(xml, "initiator") ?: peerJid
                session.core.scope.launch { val c = session.core.dao.contact(session.account.id, peerJid); if (c != null) { peerName = c.name; session.core.call.value?.let { publish(it.copy(peerName = c.name)) } } }
                wantsVideo = xml.contains("media='video'") || xml.contains("media=\"video\"")
                isCaller = false
                if (!ensureFactory()) return
                publish(CallUiState(sid, peerJid, peerName, wantsVideo, true, "INCOMING"))
            } else return
        }
        if (sessionId != sid) return
        when (action) {
            "session-initiate" -> {
                if (!isCaller && !answering && session.core.call.value?.phase == "INCOMING") {
                    pendingOfferXml = xml
                } else acceptSession(xml)
            }
            "session-accept" -> {
                val sdp = JingleSdp.jingleToSdp(xml, answering = true) ?: run {
                    session.core.addXmlLog(session.account.id, true, "Jingle: could not parse session-accept SDP")
                    return
                }
                peer?.setRemoteDescription(SimpleSdpObserver("remote"), SessionDescription(SessionDescription.Type.ANSWER, sdp))
                remoteSet = true
                flushCandidates()
                session.core.call.value?.let { publish(it.copy(phase = "ACTIVE", startedAt = System.currentTimeMillis())) }
            }
            "transport-info" -> addCandidates(xml)
            "session-terminate" -> finish()
        }
    }

    private fun createOfferAndInitiate() {
        val pc = ensurePeer() ?: run {
            session.core.call.value?.let { publish(it.copy(phase = "ENDED", error = "Could not start the call")) }
            sendJmi("reject", emptyMap(), id = sid)
            return
        }
        answering = false
        remoteSet = false
        pc.createOffer(object : SimpleSdpObserver("offer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver("local-offer") {
                    override fun onSetSuccess() { scheduleInitialJingle("session-initiate", sdp.type) }
                }, sdp)
            }
        }, MediaConstraints())
    }

    private fun acceptSession(xml: String) {
        val pc = ensurePeer() ?: run {
            session.core.call.value?.let { publish(it.copy(phase = "ENDED", error = "Could not start the call")) }
            sendJmi("reject", emptyMap(), id = sid)
            return
        }
        val sdp = JingleSdp.jingleToSdp(xml, answering = false) ?: run {
            session.core.addXmlLog(session.account.id, true, "Jingle: could not parse session-initiate SDP")
            return
        }
        answering = true
        pc.setRemoteDescription(object : SimpleSdpObserver("remote-offer") {
            override fun onSetSuccess() {
                remoteSet = true
                flushCandidates()
                pc.createAnswer(object : SimpleSdpObserver("answer") {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : SimpleSdpObserver("local-answer") {
                            override fun onSetSuccess() { scheduleInitialJingle("session-accept", answer.type) }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun scheduleInitialJingle(action: String, type: SessionDescription.Type) {
        if (gatherScheduled) return
        gatherScheduled = true
        handler.postDelayed({
            val pc = peer ?: return@postDelayed
            val local = pc.localDescription ?: return@postDelayed
            val xml = JingleSdp.sdpToJingle(local.description, sid, action,
                if (initiatorJid.isBlank()) (session.connection?.user?.toString() ?: session.account.jid) else initiatorJid,
                if (action == "session-accept") (session.connection?.user?.toString() ?: session.account.jid) else null)
            sendJingleRaw(action, xml)
            if (action == "session-accept") {
                session.core.call.value?.let { publish(it.copy(phase = "CONNECTING", incoming = false)) }
            }
        }, 1500)
    }

    private fun sendJingle(action: String, offer: String?, type: SessionDescription.Type?) {
        // reserved for explicit actions
    }

    private fun sendJingleRaw(action: String, jingleXml: String) {
        val iq = RawJingleIq(jingleXml)
        iq.to = JidCreate.from(peerFullJid.ifBlank { peerJid })
        iq.type = IQ.Type.set
        session.core.addXmlLog(session.account.id, false, iq.toXML(org.jivesoftware.smack.packet.XmlEnvironment.EMPTY).toString())
        session.connection?.sendStanza(iq)
    }

    private fun addCandidates(xml: String) {
        val doc = runCatching {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml.toByteArray()))
        }.getOrNull() ?: return
        val contents = doc.getElementsByTagNameNS(JingleSdp.NS_JINGLE, "content")
        for (ci in 0 until contents.length) {
            val content = contents.item(ci) as org.w3c.dom.Element
            val mid = content.getAttribute("name").ifBlank { "0" }
            val candidates = content.getElementsByTagNameNS(JingleSdp.NS_ICE, "candidate")
            for (i in 0 until candidates.length) {
                val e = candidates.item(i) as org.w3c.dom.Element
                val c = IceCandidate(mid, 0, "candidate:${e.getAttribute("foundation")} ${e.getAttribute("component").ifBlank { "1" }} ${e.getAttribute("protocol").ifBlank { "udp" }} ${e.getAttribute("priority")} ${e.getAttribute("ip")} ${e.getAttribute("port")} typ ${e.getAttribute("type").ifBlank { "host" }}")
                if (remoteSet) peer?.addIceCandidate(c) else pendingCandidates.add(c)
            }
        }
    }

    private fun flushCandidates() {
        if (!remoteSet) return
        for (c in pendingCandidates) peer?.addIceCandidate(c)
        pendingCandidates.clear()
    }

    // ================================================================ WebRTC
    private fun ensureFactory(): Boolean {
        if (factory != null) return true
        try {
            if (!webrtcInitialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false).createInitializationOptions())
                webrtcInitialized = true
            }
            val builder = PeerConnectionFactory.builder()
            // Audio calls must not depend on a working GPU/EGL context. Only bring up the video stack
            // when the call actually wants video.
            if (wantsVideo) {
                val egl = EglBase.create()
                eglBase = egl
                builder.setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            }
            factory = builder.createPeerConnectionFactory()
            return true
        } catch (e: Exception) {
            runCatching { factory?.dispose() }
            runCatching { eglBase?.release() }
            factory = null; eglBase = null
            publish(CallUiState(sid, peerJid, peerName, wantsVideo, false, "ENDED", error = e.message ?: "Could not start call"))
            return false
        }
    }

    private fun ensurePeer(): PeerConnection? {
        peer?.let { return it }
        val f = factory ?: return null
        val servers = mutableListOf<PeerConnection.IceServer>()
        val stun = session.core.settings.string("calls.stun").split(' ', ',').filter { it.isNotBlank() }
        stun.forEach { servers.add(PeerConnection.IceServer.builder(it.trim()).createIceServer()) }
        val turn = session.core.settings.string("calls.turn").split(' ', ',').filter { it.isNotBlank() }
        val user = session.core.settings.string("calls.turn_user")
        val pass = session.core.settings.string("calls.turn_password")
        turn.forEach { servers.add(PeerConnection.IceServer.builder(it.trim()).setUsername(user).setPassword(pass).createIceServer()) }
        if (servers.isEmpty()) servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                session.core.addXmlLog(session.account.id, true, "WebRTC ICE state: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    session.core.call.value?.let { s ->
                        if (s.phase != "ACTIVE") publish(s.copy(phase = "ACTIVE", startedAt = System.currentTimeMillis()))
                    }
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    session.core.call.value?.let { s -> publish(s.copy(phase = "ENDED", error = "The call could not connect (ICE failed)")) }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val c = candidate.sdp ?: return
                val parts = c.removePrefix("candidate:").split(' ')
                if (parts.size < 8) return
                val content = candidate.sdpMid?.toString() ?: "0"
                val xml = "<jingle xmlns='${JingleSdp.NS_JINGLE}' action='transport-info' sid='${esc(sid)}' initiator='${esc(initiatorJid)}'>" +
                    "<content name='${esc(content)}'><transport xmlns='${JingleSdp.NS_ICE}'><candidate component='${parts[1]}' foundation='${esc(parts[0])}' generation='0' id='${esc(parts[0])}${parts[1]}' ip='${esc(parts[4])}' network='0' port='${parts[5]}' priority='${parts[3]}' protocol='${esc(parts[2])}' type='${esc(parts[7])}'/></transport></content></jingle>"
                sendJingleRaw("transport-info", xml)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) { attachRemoteTracks(stream) }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    session.core.call.value?.let { publish(it.copy(remoteVideo = track)) }
                    remoteRenderer?.let { track.addSink(it) }
                }
            }
        })
        val audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("ward-audio", audioSource).also { pc!!.addTrack(it, listOf("ward")) }
        if (wantsVideo) attachVideo(pc!!, f)
        peer = pc
        return pc
    }

    private fun attachVideo(pc: PeerConnection, f: PeerConnectionFactory) {
        try {
            val enumerator = Camera2Enumerator(context)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
            val capturer = enumerator.createCapturer(name, null) ?: return
            val helper = SurfaceTextureHelper.create("WardCapture", eglBase!!.eglBaseContext)
            val source = f.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(640, 480, 30)
            videoCapturer = capturer
            videoTrack = f.createVideoTrack("ward-video", source).also {
                pc.addTrack(it, listOf("ward"))
                localRenderer?.let { r -> it.addSink(r) }
            }
        } catch (_: Exception) {}
    }

    private fun attachRemoteTracks(stream: MediaStream) {
        stream.videoTracks.firstOrNull()?.let {
            session.core.call.value?.let { s -> publish(s.copy(remoteVideo = it)) }
            remoteRenderer?.let { r -> it.addSink(r) }
        }
    }

    private fun publish(state: CallUiState) {
        session.core.setCallState(state.copy(accountId = session.account.id))
    }

    fun finish() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { localRenderer?.release() }
        runCatching { remoteRenderer?.release() }
        setAudioMode(false)
        peer = null; audioTrack = null; videoTrack = null; videoCapturer = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { eglBase?.release() }; eglBase = null
        localRenderer = null; remoteRenderer = null
        pendingCandidates.clear(); remoteSet = false; gatherScheduled = false; pendingOfferXml = null
        sid = ""; peerJid = ""; peerFullJid = ""; peerName = ""; wantsVideo = false; initiatorJid = ""
        session.core.setCallState(null)
    }

    private fun extractAttr(xml: String, name: String): String? =
        Regex("$name=['\\\"]([^'\\\"]+)['\\\"]").find(xml)?.groupValues?.get(1)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&apos;").replace("\"", "&quot;")
}

class RawJingleIq(private val jingleXml: String) : IQ("jingle", JingleSdp.NS_JINGLE) {
    override fun getIQChildElementBuilder(builder: IQ.IQChildElementXmlStringBuilder): IQ.IQChildElementXmlStringBuilder {
        val open = jingleXml.substringAfter('<')
        val attrs = open.substringBefore('>')
        val inner = jingleXml.substringAfter('>').substringBeforeLast("</jingle>")
        Regex("([a-z]+)=['\"]([^'\"]*)['\"]").findAll(attrs).forEach { m ->
            if (m.groupValues[1] != "xmlns") builder.attribute(m.groupValues[1], m.groupValues[2])
        }
        builder.rightAngleBracket()
        builder.append(inner)
        return builder
    }
}

open class SimpleSdpObserver(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
