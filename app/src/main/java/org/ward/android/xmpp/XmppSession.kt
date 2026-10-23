package org.ward.android.xmpp

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jivesoftware.smack.*
import org.jivesoftware.smack.tcp.*
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.packet.*
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntry
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.roster.SubscribeListener
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.bookmarks.BookmarkManager
import org.jivesoftware.smackx.carbons.CarbonCopyReceivedListener
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.chat_markers.ChatMarkersManager
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.ChatStateListener
import org.jivesoftware.smackx.chatstates.ChatStateManager
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jivesoftware.smackx.disco.packet.DiscoverItems
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.httpfileupload.UploadService
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.pubsub.*
import org.jivesoftware.smackx.muc.*
import org.jivesoftware.smackx.omemo.OmemoConfiguration
import org.jivesoftware.smackx.omemo.OmemoManager
import org.jivesoftware.smackx.omemo.OmemoMessage
import org.jivesoftware.smackx.omemo.element.OmemoElement
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener
import org.jivesoftware.smackx.omemo.listener.OmemoMucMessageListener
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback
import org.jivesoftware.smackx.omemo.trust.TrustState
import org.jivesoftware.smackx.omemo.util.MessageOrOmemoMessage
import org.jivesoftware.smackx.receipts.DeliveryReceipt
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.jxmpp.stringprep.XmppStringprepException
import org.ward.android.ConnectionState
import org.ward.android.WardCore
import org.ward.android.ReactionSet
import org.ward.android.parseReactions
import org.ward.android.xmpp.OpenPgp
import org.ward.android.data.*
import org.ward.android.util.JidUtil
import org.ward.android.util.TimeUtil
import org.ward.omemo2.signal.IdentityKeyPair
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class OccupantInfo(val nick: String, val jid: String, val role: String, val affiliation: String,
                        val status: String = "", val isSelf: Boolean = false)

class XmppSession(internal val core: WardCore, var account: AccountEntity, private val password: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    @Volatile var connection: XMPPTCPConnection? = null
        private set
    @Volatile var online = false
        private set
    @Volatile var omemo: OmemoManager? = null
        private set
    @Volatile var omemoReady = false
        private set
    @Volatile var omemo2: Omemo2? = null
        private set
    var admin: AdminService? = null
        private set
    private val gson = Gson()
    private val rooms = ConcurrentHashMap<String, MultiUserChat>()
    private val _occupants = MutableStateFlow<Map<String, List<OccupantInfo>>>(emptyMap())
    val occupants: StateFlow<Map<String, List<OccupantInfo>>> = _occupants
    private var reconnectDelay = 2000L
    private var mamSynced = false
    private var roster: Roster? = null

    sealed class EncryptedPayload {
        data class Omemo(val message: OmemoMessage.Sent) : EncryptedPayload()
        data class Omemo2(val xml: String) : EncryptedPayload()
        data class Pgp(val base64: String) : EncryptedPayload()
    }

    val call = CallManager(this)

    private val options: Map<String, String> by lazy {
        runCatching { gson.fromJson<Map<String, String>>(account.optionsJson, object : TypeToken<Map<String, String>>() {}.type) }
            .getOrNull() ?: emptyMap()
    }

    private fun opt(key: String, def: String = "") = options[key] ?: def

    fun start() {
        if (running) return
        running = true
        scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        online = false
        runCatching { call.finish() }
        runCatching { connection?.disconnect() }
        connection = null
        rooms.clear()
        core.onState(account.id, ConnectionState("offline"))
    }

    private suspend fun connectLoop() {
        while (running) {
            try {
                core.onState(account.id, ConnectionState("connecting", "Looking up the server…"))
                doConnect()
                reconnectDelay = 2000L
                while (running && connection?.isConnected == true) delay(1000)
                online = false
                rooms.clear()
                if (!running) break
                core.onState(account.id, ConnectionState("error", "", "Connection lost"))
            } catch (e: Exception) {
                online = false
                if (!running) break
                core.onState(account.id, ConnectionState("error", "", friendlyError(e)))
            }
            if (!running) break
            var wait = reconnectDelay
            while (wait > 0 && running) { delay(1000); wait -= 1000 }
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(core.settings.int("advanced.reconnect_cap") * 60_000L)
        }
    }

    private fun friendlyError(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("authentication", true) -> "Authentication failed. Check your password."
            m.contains("certificate", true) || m.contains("SSL", true) -> "The server's certificate could not be verified."
            m.contains("UnknownHost", true) || m.contains("resolve", true) -> "The server could not be found."
            m.contains("timeout", true) -> "The connection timed out."
            else -> m
        }
    }


    private fun tofuTrustManager(): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("No certificate")
                val fp = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString(":") { "%02X".format(it) }
                val key = "certificate.pin.${account.id}"
                val saved = core.settings.string(key, "")
                if (saved.isBlank()) core.settings.setString(key, fp)
                else if (!saved.equals(fp, ignoreCase = true)) {
                    throw java.security.cert.CertificateException("The server's certificate changed. Clear the app data for this account or verify the new fingerprint in the XML console.")
                }
                core.addXmlLog(account.id, false, "server certificate SHA-256 $fp")
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
    }

    private fun doConnect() {
        val domain = account.domain
        val builder = XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(domain)
            .setUsernameAndPassword(account.localpart, password)
            .setResource(opt("resource", "Ward Android").ifBlank { "Ward Android" })
            .setCompressionEnabled(true)
            .setSendPresence(false)
            .setConnectTimeout(30_000)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
        builder.setCustomX509TrustManager(tofuTrustManager())
        builder.setHostnameVerifier { _, _ -> true }
        val host = opt("host")
        if (host.isNotBlank()) builder.setHost(host).setPort(opt("port", "5222").toIntOrNull() ?: 5222)
        else if (opt("port").isNotBlank()) builder.setPort(opt("port").toInt())
        builder.setResource(opt("resource", "Ward Android").ifBlank { "Ward Android" })
        val conn = XMPPTCPConnection(builder.build())
        connection = conn
        conn.addConnectionListener(object : AbstractConnectionListener() {
            override fun connected(c: XMPPConnection) {
                core.addXmlLog(account.id, false, "connected to ${c.host}:${c.port}")
            }
            override fun connectionClosed() {
                online = false
                if (running) core.onState(account.id, ConnectionState("offline", "", "Disconnected"))
            }
            override fun connectionClosedOnError(e: Exception) {
                online = false
                core.addXmlLog(account.id, false, "connection error: ${e.message}")
            }
        })
        core.onState(account.id, ConnectionState("connecting", "Securing the connection…"))
        mamSynced = false
        conn.connect()
        if (!conn.isAuthenticated) conn.login(account.localpart, password, Resourcepart.from(opt("resource", "Ward Android").ifBlank { "Ward Android" }))
        online = true
        core.onState(account.id, ConnectionState("online"))
        core.addXmlLog(account.id, false, "signed in as ${account.jid}")
        afterLogin(conn)
    }

    private fun afterLogin(conn: XMPPTCPConnection) {
        try {
            val presence = Presence(Presence.Type.available)
            conn.sendStanza(presence)
        } catch (_: Exception) {}
        val disco = ServiceDiscoveryManager.getInstanceFor(conn)
        for (feature in listOf(
            "urn:xmpp:jingle:1", "urn:xmpp:jingle:apps:rtp:1",
            "urn:xmpp:jingle:apps:rtp:audio", "urn:xmpp:jingle:apps:rtp:video",
            "urn:xmpp:jingle:transports:ice-udp:1", "urn:xmpp:jingle:apps:dtls:0",
            "urn:xmpp:jingle-message:0", "urn:ietf:rfc:3264"
        )) disco.addFeature(feature)
        setupRoster(conn)
        setupMessageListeners(conn)
        setupManagers(conn)
        scope.launch {
            runCatching { setupOmemo(conn) }
            runCatching { setupOmemo2(conn) }
            runCatching { setupAdmin(conn) }
            runCatching { setupOpenPgp(conn) }
            runCatching { syncMam(conn) }
            runCatching { joinBookmarks() }
            runCatching { flushOutbox() }
        }
    }

    private fun setupRoster(conn: XMPPConnection) {
        // The Roster instance is usually created when the connection is created, i.e. before this
        // method runs. Set both the static default and the existing instance to manual, otherwise
        // Smack silently rejects every incoming subscription request before the UI can see it.
        Roster.setDefaultSubscriptionMode(Roster.SubscriptionMode.manual)
        val roster = Roster.getInstanceFor(conn)
        roster.setSubscriptionMode(Roster.SubscriptionMode.manual)
        this.roster = roster
        setupSubscriptionListener(conn)
        roster.addRosterListener(object : RosterListener {
            override fun entriesAdded(addresses: Collection<Jid>) = syncRoster(roster)
            override fun entriesUpdated(addresses: Collection<Jid>) = syncRoster(roster)
            override fun entriesDeleted(addresses: Collection<Jid>) = syncRoster(roster)
            override fun presenceChanged(presence: Presence) {
                val from = presence.from ?: return
                if (from.asEntityBareJidIfPossible() == null) return
                scope.launch {
                    val jid = from.asBareJid().toString()
                    val c = core.dao.contact(account.id, jid)
                    // A subscription request must surface even when the contact row already exists: after
                    // removing each other the old row survives with subscription "none".
                    if (presence.type == Presence.Type.subscribe) requestSubscription(jid, "")
                    else if (c != null) updateContactPresence(c, presence)
                }
            }
        })
        syncRoster(roster)
        roster.reloadAndWait()
        syncRoster(roster)
    }

    private fun syncRoster(roster: Roster) {
        scope.launch {
            val existing = mutableSetOf<String>()
            for (entry in roster.entries) {
                val jid = entry.jid.toString()
                existing.add(jid)
                val pres = roster.getPresence(entry.jid)
                val subscription = when {
                    entry.canSeeHisPresence() && entry.canSeeMyPresence() -> "both"
                    entry.canSeeMyPresence() -> "from"
                    entry.canSeeHisPresence() -> "to"
                    else -> "none"
                }
                val old = core.dao.contact(account.id, jid)
                core.dao.insertContact(ContactEntity(account.id, jid,
                    rosterName = entry.name ?: old?.rosterName ?: "",
                    groups = entry.groups.joinToString(", ") { it.name },
                    subscription = subscription,
                    ask = if (entry.isSubscriptionPending) "subscribe" else "",
                    inRoster = true, blocked = old?.blocked ?: false,
                    presence = if (pres != null && pres.isAvailable) showOf(pres) else old?.presence ?: "offline",
                    status = pres?.status ?: old?.status ?: "",
                    nickname = old?.nickname ?: "", avatar = old?.avatar ?: ""))
            }
            // remove contacts no longer in the roster, keeping chat-only contacts
            for (c in core.dao.contactsNow(account.id)) {
                if (c.inRoster && c.jid !in existing) core.dao.insertContact(c.copy(inRoster = false))
            }
        }
    }

    private fun updateContactPresence(c: ContactEntity, p: Presence) {
        scope.launch {
            if (p.type == Presence.Type.unavailable) {
                core.dao.insertContact(c.copy(presence = "offline", status = "", lastSeen = System.currentTimeMillis()))
            } else {
                core.dao.insertContact(c.copy(presence = showOf(p), status = p.status ?: ""))
            }
        }
    }

    private fun showOf(p: Presence): String = when (p.mode ?: Presence.Mode.available) {
        Presence.Mode.chat, Presence.Mode.available -> "available"
        Presence.Mode.away -> "away"; Presence.Mode.xa -> "xa"; Presence.Mode.dnd -> "dnd"
    }

    private fun setupManagers(conn: XMPPConnection) {
        runCatching {
            val carbon = CarbonManager.getInstanceFor(conn)
            carbon.enableCarbons()
        }
        runCatching {
            val receipts = DeliveryReceiptManager.getInstanceFor(conn)
            receipts.setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.always)
            receipts.addReceiptReceivedListener(ReceiptReceivedListener { _, _, id, _ ->
                scope.launch { markReceipt(id, delivered = true) }
            })
        }
        runCatching {
            ChatMarkersManager.getInstanceFor(conn).addIncomingChatMarkerMessageListener { state, message, _ ->
                val id = message.ext("displayed", Ns.MARKERS)?.getAttributeValue("id")
                    ?: message.ext("received", Ns.MARKERS)?.getAttributeValue("id") ?: return@addIncomingChatMarkerMessageListener
                scope.launch { markReceipt(id, delivered = true, displayed = state == org.jivesoftware.smackx.chat_markers.ChatMarkersState.displayed) }
            }
        }
        runCatching {
            ChatStateManager.getInstance(conn).addChatStateListener(object : ChatStateListener {
                override fun stateChanged(chat: Chat, state: ChatState, message: Message) {
                    val jid = message.from?.asBareJid()?.toString() ?: return
                    scope.launch {
                        val c = core.dao.chat(account.id, jid) ?: return@launch
                        if (state == ChatState.composing) core.setTyping(c.id, c.displayName)
                        else core.setTyping(c.id, "")
                    }
                }
            })
        }
        runCatching {
            val pm = org.jivesoftware.smackx.ping.PingManager.getInstanceFor(conn)
            pm.setPingInterval(core.settings.int("advanced.keepalive").coerceAtLeast(30))
        }
    }

    private fun setupSubscriptionListener(conn: XMPPConnection) {
        val r = roster ?: Roster.getInstanceFor(conn).also { roster = it }
        r.setSubscriptionMode(Roster.SubscriptionMode.manual)
        r.addSubscribeListener(object : SubscribeListener {
            override fun processSubscribe(from: Jid, subscribeRequest: Presence): SubscribeListener.SubscribeAnswer? {
                val bare = from.asBareJid().toString()
                if (bare != account.jid) {
                    scope.launch {
                        if (core.dao.contact(account.id, bare)?.blocked == true) return@launch
                        val chat = core.ensureChat(account.id, bare, "chat")
                        core.dao.updateChat(chat.copy(isRequest = true))
                        core.dao.insertRequest(RequestEntity(account.id, bare, "subscription",
                            "wants to add you to their contact list", chat.id))
                        core.addXmlLog(account.id, true, "Contact request from $bare")
                    }
                }
                // null = leave it pending for the user instead of auto-approving or rejecting.
                return null
            }
        })
    }

    private fun setupMessageListeners(conn: XMPPConnection) {
        conn.addAsyncStanzaListener({ stanza ->
            if (stanza is Message) {
                core.addXmlLog(account.id, true, stanza.toXML(XmlEnvironment.EMPTY).toString())
                if (stanza.type != Message.Type.groupchat) {
                    val raw = stanza.toXML(XmlEnvironment.EMPTY).toString()
                    if (raw.contains("urn:xmpp:jingle-message:0")) {
                        call.onJmi(stanza, raw)
                        return@addAsyncStanzaListener
                    }
                    val envelope2 = raw.contains("urn:xmpp:omemo:2")
                    val pgp = stanza.ext("openpgp", Ns.OPENPGP) != null
                    val enc = stanza.getExtensionElement("encrypted", Ns.OMEMO) != null || envelope2 || pgp
                    if (pgp) {
                        scope.launch { processPgp(stanza) }
                    } else if (envelope2) {
                        scope.launch { processOmemo2(stanza, null, raw) }
                    } else if (!enc) processMessage(stanza, directionOverride = null)
                }
            }
        }, null)
        // Jingle IQs are requests: Smack delivers those only to an IQ request handler, never to
        // stanza listeners. The raw provider also avoids Smack's built-in Jingle parser closing
        // the connection on GTK's <group semantics='BUNDLE'> element.
        JingleIqs.register(conn, call)
        conn.addStanzaInterceptor({ stanza ->
            if (stanza is Message) core.addXmlLog(account.id, false, stanza.toXML(XmlEnvironment.EMPTY).toString())
        }, null)
        runCatching {
            CarbonManager.getInstanceFor(conn).addCarbonCopyReceivedListener(object : CarbonCopyReceivedListener {
                override fun onCarbonCopyReceived(direction: CarbonExtension.Direction, carbonMessage: Message, wrapped: Message) {
                    if (carbonMessage.type == Message.Type.groupchat) return
                    val dir = if (direction == CarbonExtension.Direction.sent) 1 else 0
                    val raw = wrapped.toXML(XmlEnvironment.EMPTY).toString()
                    if (raw.contains("urn:xmpp:omemo:2")) scope.launch { processOmemo2(wrapped, dir, raw) }
                    else processMessage(wrapped, directionOverride = dir)
                }
            })
        }
    }

    private fun setupOmemo(conn: XMPPConnection) {
        if (!core.settings.bool("encryption.legacy_omemo")) return
        val resetFlag = File(core.context.filesDir, "reset-legacy-omemo")
        if (resetFlag.exists()) {
            runCatching {
                PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
                    .deleteNode("eu.siacs.conversations.axolotl.devicelist")
            }
            resetFlag.delete()
        }
        try {
            SignalOmemoService.acknowledgeLicense()
            SignalOmemoService.setup()
            val service = SignalOmemoService.getInstance()
            val root = File(core.context.filesDir, "omemo")
            if (service.omemoStoreBackend == null) {
                val setter = service.javaClass.methods.firstOrNull { it.name == "setOmemoStoreBackend" }
                setter?.invoke(service, SignalFileBasedOmemoStore(root))
            }
            OmemoConfiguration.setAddOmemoHintBody(false)
            val mgr = OmemoManager.getInstanceFor(conn)
            mgr.setTrustCallback(object : OmemoTrustCallback {
                override fun getTrust(device: OmemoDevice, fingerprint: OmemoFingerprint): TrustState {
                    val saved = runBlocking(Dispatchers.IO) {
                        core.dao.devicesOnce(account.id, device.jid.toString()).firstOrNull { it.deviceId == device.deviceId.toLong() && it.protocol == "omemo" }
                    }
                    if (saved != null) return when (saved.trust) {
                        "verified", "trusted" -> TrustState.trusted
                        "distrusted" -> TrustState.untrusted
                        else -> TrustState.undecided
                    }
                    return if (core.settings.string("encryption.trust") == "btbv") {
                        runBlocking(Dispatchers.IO) {
                            core.dao.insertDevice(DeviceEntity(account.id, device.jid.toString(), device.deviceId.toLong(),
                                fingerprint.toString(), trust = "trusted"))
                        }
                        TrustState.trusted
                    } else TrustState.undecided
                }
                override fun setTrust(device: OmemoDevice, fingerprint: OmemoFingerprint, state: TrustState) {
                    val trust = when (state) {
                        TrustState.trusted -> "trusted"; TrustState.untrusted -> "distrusted"; else -> "undecided"
                    }
                    runBlocking(Dispatchers.IO) {
                        core.dao.insertDevice(DeviceEntity(account.id, device.jid.toString(), device.deviceId.toLong(),
                            fingerprint.toString(), trust = trust))
                    }
                }
            })
            mgr.initialize()
            openLegacyBundleNodes(conn, mgr)
            omemo = mgr
            omemoReady = true
            mgr.addOmemoMessageListener(object : OmemoMessageListener {
                override fun onOmemoMessageReceived(stanza: Stanza, decrypted: OmemoMessage.Received) {
                    if (stanza is Message) {
                        val trust = if (omemo?.let { runCatching { it.isTrustedOmemoIdentity(decrypted.senderDevice, decrypted.sendersFingerprint) }.getOrDefault(false) } == true) "trusted" else "undecided"
                        processMessage(stanza, directionOverride = null, decryptedBody = decrypted.body, senderDevice = decrypted.senderDevice.deviceId.toLong(), trust = trust, encrypted = "omemo")
                    }
                }
                override fun onOmemoCarbonCopyReceived(direction: CarbonExtension.Direction, carbonMessage: Message, wrapped: Message, decrypted: OmemoMessage.Received) {
                    val dir = if (direction == CarbonExtension.Direction.sent) 1 else 0
                    processMessage(wrapped, directionOverride = dir, decryptedBody = decrypted.body,
                        senderDevice = decrypted.senderDevice.deviceId.toLong(), encrypted = "omemo")
                }
            })
            mgr.addOmemoMucMessageListener(object : OmemoMucMessageListener {
                override fun onOmemoMucMessageReceived(muc: MultiUserChat, stanza: Stanza, decrypted: OmemoMessage.Received) {
                    if (stanza is Message) processGroupMessage(muc, stanza, decryptedBody = decrypted.body,
                        senderDevice = decrypted.senderDevice.deviceId.toLong(), encrypted = "omemo")
                }
            })
            refreshOwnDevices()
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "OMEMO unavailable: ${e.message}")
        }
    }

    /**
     * Smack publishes legacy OMEMO bundles without publish-options. Prosody then creates the
     * per-device bundle node with access_model=presence, so a contact who is not currently
     * subscribed (for example directly after deleting and re-adding each other) cannot fetch the
     * bundle. Other clients then report "SenderNotFound" for every message we send.
     */
    private fun openLegacyBundleNodes(conn: XMPPConnection, mgr: OmemoManager) {
        val own = runCatching { mgr.ownDevice }.getOrNull() ?: return
        val localIds = runCatching {
            SignalOmemoService.getInstance().omemoStoreBackend.localDeviceIdsOf(own.jid).toList()
        }.getOrDefault(emptyList())
        val ids = (localIds + own.deviceId).distinct()
        val pm = PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
        for (id in ids) {
            val nodeName = "eu.siacs.conversations.axolotl.bundles:$id"
            runCatching {
                val node = pm.getLeafNode(nodeName)
                val form = node.getNodeConfiguration().fillableForm
                form.setAccessModel(AccessModel.open)
                form.setPersistentItems(true)
                form.setMaxItems(1)
                node.sendConfigurationForm(form)
                core.addXmlLog(account.id, false, "Legacy OMEMO bundle $nodeName is open")
            }.onFailure {
                core.addXmlLog(account.id, false, "Could not open legacy OMEMO bundle $nodeName: ${it.message}")
            }
        }
    }

    /**
     * Smack's file store has the identity key pair in
     * omemo/OMEMO_Store/<jid>/<deviceId>/identityKeyPair.  Loading it through the store can fail
     * when the manager is still settling, so read the file directly as a fallback. The bytes use
     * the same serialized IdentityKeyPair format as the OMEMO 2 implementation.
     */
    private fun findLegacyIdentityBytes(deviceId: Int?): ByteArray? {
        val expected = runCatching {
            omemo?.ownFingerprint?.toString()?.replace(":", "")?.lowercase()
        }.getOrNull()
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val roots = listOf(File(core.context.filesDir, "omemo"), core.context.filesDir)
        for (root in roots) {
            if (!root.isDirectory) continue
            val files = root.walkTopDown()
                .filter { it.isFile && it.name == "identityKeyPair" }
                .filter { f ->
                    val path = f.path
                    path.contains(account.localpart) || path.contains(account.jid)
                }
                .toList()
            if (files.isEmpty()) continue
            val ordered = if (deviceId != null) {
                files.sortedByDescending { it.parentFile?.name == deviceId.toString() }
            } else {
                files.sortedByDescending { it.lastModified() }
            }
            for (f in ordered) {
                if (deviceId != null && f.parentFile?.name != deviceId.toString()) continue
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val pair = runCatching { IdentityKeyPair(bytes) }.getOrNull() ?: continue
                if (expected == null || hex(pair.publicKey.publicKey.serializeEd()) == expected) return bytes
            }
            // The device directory may not match the manager's idea of its own id. Prefer the
            // identity whose fingerprint the legacy manager itself reports.
            for (f in files.sortedByDescending { it.lastModified() }) {
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val pair = runCatching { IdentityKeyPair(bytes) }.getOrNull() ?: continue
                if (expected == null || hex(pair.publicKey.publicKey.serializeEd()) == expected) return bytes
            }
        }
        return null
    }

    private fun setupOmemo2(conn: XMPPConnection) {        try {
            // OMEMO 2 must use the same identity as legacy OMEMO: other clients store one identity
            // per device id, derived from the legacy bundle. Even when legacy OMEMO is disabled, its
            // store may still hold the identity that contacts already cached.
            val legacy = runCatching {
                val bare = JidCreate.entityBareFrom(account.jid)
                val store = (SignalOmemoService.getInstance().omemoStoreBackend as? SignalFileBasedOmemoStore)
                    ?: SignalFileBasedOmemoStore(File(core.context.filesDir, "omemo"))
                val ownId = omemo?.ownDevice?.deviceId ?: store.localDeviceIdsOf(bare).lastOrNull()
                val identity = ownId?.let {
                    store.loadOmemoIdentityKeyPair(OmemoDevice(bare, it)).serialize()
                }
                ownId to identity
            }.getOrNull()
            val legacyId = legacy?.first ?: omemo?.ownDevice?.deviceId
            var legacyIdentity = legacy?.second
            if (legacyIdentity == null) {
                legacyIdentity = findLegacyIdentityBytes(legacyId)
                if (legacyIdentity != null) {
                    core.addXmlLog(account.id, false,
                        "OMEMO 2 recovered the legacy identity from the OMEMO store file for device $legacyId")
                }
            }
            if (legacyIdentity == null) {
                // Do not disable OMEMO 2 just because the legacy identity could not be read. The
                // per-account OMEMO 2 state may already contain a usable identity; otherwise a
                // standalone identity is generated. Publishing under the legacy device id keeps the
                // envelope address stable, and a fresh bundle lets the contact build a session.
                core.addXmlLog(account.id, false,
                    "OMEMO 2 legacy identity unavailable; using stored or standalone OMEMO 2 identity")
            }
            val mgr = Omemo2(core, account, conn, legacyId, legacyIdentity)
            omemo2 = mgr
            var published = mgr.publish()
            if (!published) {
                core.addXmlLog(account.id, false, "OMEMO 2 first publish did not complete; retrying")
                published = mgr.publish()
            }
            runBlocking(Dispatchers.IO) {
                runCatching {
                    core.dao.insertDevice(DeviceEntity(account.id, account.jid, mgr.deviceId.toLong(),
                        mgr.fingerprint(), label = "This device (OMEMO 2)", trust = "verified",
                        own = true, thisDevice = false, protocol = "omemo2"))
                }
            }
            core.addXmlLog(account.id, false,
                "OMEMO 2 ready: device ${mgr.deviceId}, legacy device ${legacyId ?: "none"}, published=$published")
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "OMEMO 2 unavailable: ${e.stackTraceToString()}")
        }
    }

    private fun refreshOwnDevices() {
        val mgr = omemo ?: return
        val fp = runCatching { mgr.ownFingerprint }.getOrNull() ?: return
        val device = mgr.ownDevice
        runBlocking(Dispatchers.IO) {
            core.dao.insertDevice(DeviceEntity(account.id, account.jid, device.deviceId.toLong(), fp.toString(),
                label = "This device", trust = "verified", own = true, thisDevice = true))
        }
    }

    private fun removeOldOmemo2Devices(conn: XMPPConnection) {
        // 1.0.2 published OMEMO 2 keys with an identity key Ward for GTK rejects. Remove those
        // nodes so contacts fall back to our ordinary legacy OMEMO device.
        val mgr = PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
        for (node in listOf("urn:xmpp:omemo:2:devices", "urn:xmpp:omemo:2:bundles")) {
            runCatching { mgr.deleteNode(node) }
        }
    }

    private fun setupAdmin(conn: XMPPConnection) {
        try {
            val service = AdminService(conn, account.domain)
            admin = service
            val commands = service.listCommands()
            core.onAdmin(account.id, commands)
            core.addXmlLog(account.id, false, "server commands: ${commands.size}")
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "ad-hoc commands unavailable: ${e.message}")
        }
    }

    // ============================================================ incoming messages
    private fun processMessage(msg: Message, directionOverride: Int?, decryptedBody: String? = null,
                               senderDevice: Long = 0, trust: String = "", encrypted: String = "") {
        if (!online) return
        val from = msg.from ?: return
        var jid = from.asEntityBareJidIfPossible()?.toString() ?: return
        var outgoing = directionOverride == 1 || jid == account.jid
        if (directionOverride == 1 || (jid == account.jid && msg.to?.asEntityBareJidIfPossible() != null)) {
            val to = msg.to?.asEntityBareJidIfPossible()?.toString()
            if (to != null) jid = to
            outgoing = true
        }
        if (jid == account.jid && msg.to?.asBareJid()?.toString() == account.jid) outgoing = false
        val body = decryptedBody ?: msg.body ?: ""
        val stanzaId = msg.stanzaId ?: msg.ext("origin-id", Ns.SID)?.getAttributeValue("id") ?: JidUtil.randomId()
        val timestamp = (msg.getExtension("delay", Ns.DELAY) as? DelayInformation)?.stamp?.time
            ?: (msg.getExtension("delay", "urn:xmpp:delay") as? DelayInformation)?.stamp?.time
            ?: System.currentTimeMillis()
        val reply = msg.ext("reply", Ns.REPLY)
        val target = msg.ext("replace", Ns.CORRECT)?.getAttributeValue("id")
        val retract = msg.ext("retract", Ns.RETRACT)?.getAttributeValue("id")
            ?: msg.ext("retract", Ns.RETRACT_0)?.getAttributeValue("id")
        val reactions = msg.ext("reactions", Ns.REACTIONS)
        val receipt = msg.ext("received", Ns.RECEIPTS)?.getAttributeValue("id")
            ?: msg.ext("displayed", Ns.MARKERS)?.getAttributeValue("id")
        val marker = msg.ext("displayed", Ns.MARKERS)
        val attachments = msg.attachments().ifEmpty { attachmentsFromBody(body) }
        val hasContent = body.isNotBlank() || attachments.isNotEmpty() || reactions != null || retract != null || target != null
        if (!hasContent) return
        scope.launch {
            try {
            val chat = core.ensureChat(account.id, jid, "chat")
            if (target != null) {
                val old = core.findRef(chat.id, target)
                if (old != null) core.dao.updateMessage(old.copy(body = body.stripReplyQuote(reply), edited = true))
                return@launch
            }
            if (retract != null) {
                val old = core.findRef(chat.id, retract)
                if (old != null) core.dao.updateMessage(old.copy(retracted = 1))
                return@launch
            }
            if (reactions != null) {
                val rid = reactions.getAttributeValue("id") ?: return@launch
                val old = core.findRef(chat.id, rid) ?: return@launch
                val emojis = runCatching { reactions.getElements("reaction", Ns.REACTIONS) }
                    .getOrNull()?.mapNotNull { it.text } ?: emptyList()
                val list = parseReactions(old.reactionsJson).toMutableList()
                val who = contactName(chat, from)
                list.removeAll { it.who == who }
                if (emojis.isNotEmpty()) list.add(ReactionSet(emojis, who, false))
                core.dao.updateMessage(old.copy(reactionsJson = gson.toJson(list)))
                return@launch
            }
            if (body.isBlank() && attachments.isEmpty()) return@launch
            val contact = core.dao.contact(account.id, jid)
            if (contact == null && !outgoing) {
                when (core.settings.string("privacy.strangers", "requests")) {
                    "block" -> return@launch
                    "requests" -> {
                        val c = core.ensureChat(account.id, jid, "chat")
                        if (!c.isRequest) core.dao.updateChat(c.copy(isRequest = true))
                        core.dao.insertRequest(RequestEntity(account.id, jid, "message", body.take(100), c.id))
                    }
                }
            }
            val chatNow = core.dao.chat(chat.id) ?: chat
            if (timestamp <= chatNow.clearedAt) return@launch
            if (!chatNow.open) core.dao.updateChat(chatNow.copy(open = true))
            val name = contactName(chatNow, from)
            val cleanBody = if (attachments.any { it.url.startsWith("aesgcm://") })
                body.substringBefore("aesgcm://").trim().ifBlank { attachments.first().name } else body
            val msgEntity = MessageEntity(chatId = chatNow.id, stanzaId = stanzaId,
                originId = msg.ext("origin-id", Ns.SID)?.getAttributeValue("id") ?: "",
                msgId = msg.stanzaId ?: "", sender = jid, senderJid = from.toString(), senderName = name,
                occupantId = msg.ext("occupant-id", Ns.OCCUPANT_ID)?.getAttributeValue("id") ?: "",
                direction = if (outgoing) 1 else 0, timestamp = timestamp,
                body = cleanBody.stripReplyQuote(reply), kind = if (cleanBody.startsWith("/me ")) "me" else if (attachments.isNotEmpty()) "file" else "text",
                encryption = encrypted, senderDevice = senderDevice, trust = trust,
                replyTo = reply?.getAttributeValue("id") ?: "",
                replyPreview = replyPreview(msg, body), replyAuthor = "",
                status = if (outgoing) 1 else 0,
                mentionsMe = mentionsMe(chatNow, body),
                attachmentsJson = gson.toJson(attachments.map { mapOf("url" to it.url, "name" to it.name,
                    "mime" to it.mime, "size" to it.size, "width" to it.width, "height" to it.height) }))
            val id = core.dao.insertMessage(msgEntity)
            if (id < 0) return@launch
            val stored = msgEntity.copy(id = id)
            core.touchChat(chatNow, stored, outgoing = outgoing)
            val active = core.isChatActive(chatNow.id)
            if (!outgoing && active) {
                if (core.settings.bool("chats.send_receipts")) sendReceipt(chatNow, stanzaId)
                if (core.settings.bool("chats.send_markers")) sendDisplayed(chatNow, stored)
                core.dao.updateChat(chatNow.copy(unread = 0, mentions = 0, lastReadId = id))
            }
            } catch (e: Exception) {
                core.addXmlLog(account.id, false, "message handling failed: ${e.stackTraceToString()}")
            }
        }
    }


    private suspend fun processOmemo2(msg: Message, direction: Int?, raw: String) {
        val mgr = omemo2
        if (mgr == null) { saveUnreadableOmemo2(msg); return }
        val content = mgr.decrypt(msg, raw)
        if (content == null) {
            // Only complain when the stanza actually contained a key for this device.
            val forUs = Regex("rid=['\"]${mgr.deviceId}['\"]").containsMatchIn(raw)
            if (forUs) saveUnreadableOmemo2(msg)
            return
        }
        if (content.empty) return
        for (child in content.children) msg.addExtension(child)
        processMessage(msg, directionOverride = direction, decryptedBody = content.body,
            senderDevice = content.deviceId.toLong(), trust = content.trust, encrypted = "omemo2")
    }

    private suspend fun saveUnreadableOmemo2(msg: Message) {
        val from = msg.from?.asEntityBareJidIfPossible()?.toString() ?: return
        if (from == account.jid) return
        val chat = core.ensureChat(account.id, from, "chat")
        val id = msg.stanzaId ?: JidUtil.randomId()
        val e = MessageEntity(chatId = chat.id, stanzaId = id, originId = msg.ext("origin-id", Ns.SID)?.getAttributeValue("id") ?: "",
            sender = from, senderJid = from, senderName = (core.dao.contact(account.id, from)?.name ?: JidUtil.nick(from)),
            direction = 0, timestamp = System.currentTimeMillis(), kind = "system", encryption = "omemo2",
            body = "Could not decrypt this OMEMO 2 message. The encrypted session or identity did not match. Check the XML console for the specific error.")
        val lid = core.dao.insertMessage(e)
        if (lid >= 0) core.touchChat(chat, e.copy(id = lid), outgoing = false)
    }



    private suspend fun processPgp(msg: Message) {
        val ext = msg.ext("openpgp", Ns.OPENPGP) ?: return
        val from = msg.from?.asEntityBareJidIfPossible()?.toString() ?: return
        val secret = OpenPgp.loadSecret(core, account.id) ?: return
        val certs = core.dao.pgpKeysNow(account.id, from).mapNotNull { OpenPgp.parsePublic(it.armored) }
        val decrypted = runCatching {
            val raw = android.util.Base64.decode(ext.text?.trim(), android.util.Base64.DEFAULT)
            OpenPgp.decrypt(secret, certs, raw)
        }.getOrNull()
        if (decrypted == null) {
            val chat = core.ensureChat(account.id, from, "chat")
            val e = MessageEntity(chatId = chat.id, stanzaId = msg.stanzaId ?: JidUtil.randomId(),
                sender = from, senderJid = from, senderName = (core.dao.contact(account.id, from)?.name ?: JidUtil.nick(from)),
                direction = 0, timestamp = System.currentTimeMillis(), kind = "system", encryption = "pgp",
                body = "Could not decrypt this OpenPGP message. The sender's key may not be available.")
            val id = core.dao.insertMessage(e); if (id >= 0) core.touchChat(chat, e.copy(id = id), outgoing = false)
            return
        }
        val body = OpenPgp.parseSigncrypt(String(decrypted), account.jid)
        if (body == null) {
            val chat = core.ensureChat(account.id, from, "chat")
            val e = MessageEntity(chatId = chat.id, stanzaId = msg.stanzaId ?: JidUtil.randomId(),
                sender = from, senderJid = from, senderName = (core.dao.contact(account.id, from)?.name ?: JidUtil.nick(from)),
                direction = 0, timestamp = System.currentTimeMillis(), kind = "system", encryption = "pgp",
                body = "Rejected an OpenPGP message meant for someone else, or dated in the future.")
            val id = core.dao.insertMessage(e); if (id >= 0) core.touchChat(chat, e.copy(id = id), outgoing = false)
            return
        }
        processMessage(msg, directionOverride = null, decryptedBody = body, encrypted = "pgp")
    }

    fun ensurePgp() {
        val conn = connection ?: return
        if (!core.settings.bool("pgp.enabled")) return
        scope.launch { runCatching { setupOpenPgp(conn) } }
    }

    suspend fun refreshPgp(jid: String) {
        if (!core.settings.bool("pgp.enabled")) return
        val conn = connection ?: return
        try {
            val mgr = PubSubManager.getInstanceFor(conn, JidCreate.bareFrom(jid))
            val node = mgr.getLeafNode(OpenPgp.NODE_META)
            val items = node.getItems<PayloadItem<*>>()
            for (item in items) {
                val xml = item.toXML(XmlEnvironment.EMPTY).toString()
                val fps = Regex("v4-fingerprint=['\"]([0-9A-Fa-f]+)['\"]").findAll(xml).map { it.groupValues[1].uppercase() }.toList()
                for (fp in fps) {
                    val keyNode = mgr.getLeafNode(OpenPgp.NODE_META + ":" + fp)
                    for (ki in keyNode.getItems<PayloadItem<*>>()) {
                        val kxml = ki.toXML(XmlEnvironment.EMPTY).toString()
                        val data = Regex("<data>([^<]+)</data>").find(kxml)?.groupValues?.get(1) ?: continue
                        val armored = String(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                        val parsed = OpenPgp.parsePublic(armored) ?: continue
                        val realFp = org.pgpainless.PGPainless.inspectKeyRing(parsed).fingerprint.toString().replace(" ", "").uppercase()
                        if (realFp != fp) continue
                        core.dao.insertPgpKey(PgpKeyEntity(account.id, jid.substringBefore('/'), fp, armored, announced = true))
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private suspend fun setupOpenPgp(conn: XMPPTCPConnection) {
        if (!core.settings.bool("pgp.enabled")) return
        val secret = OpenPgp.ensureKey(core, account)
        val fp = OpenPgp.fingerprint(secret)
        core.setAccountOption(account, "pgp_key", fp)
        val armored = OpenPgp.publicArmor(secret)
        core.dao.insertPgpKey(PgpKeyEntity(account.id, account.jid, fp, armored, name = account.displayName, trust = "verified", announced = true))
        try {
            val mgr = PubSubManager.getInstanceFor(conn, conn.user.asBareJid())
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val keyXml = "<pubkey xmlns='${OpenPgp.NS}' date='$stamp'><data>${android.util.Base64.encodeToString(armored.toByteArray(), android.util.Base64.NO_WRAP)}</data></pubkey>"
            mgr.getOrCreateLeafNode(OpenPgp.NODE_META + ":" + fp).publish(PayloadItem(stamp,
                RawExtension("pubkey", OpenPgp.NS, keyXml)))
            val listXml = "<public-keys-list xmlns='${OpenPgp.NS}'><pubkey-metadata v4-fingerprint='$fp' date='$stamp'/></public-keys-list>"
            mgr.getOrCreateLeafNode(OpenPgp.NODE_META).publish(PayloadItem("current",
                RawExtension("public-keys-list", OpenPgp.NS, listXml)))
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "OpenPGP publish failed: ${e.message}")
        }
    }

    private suspend fun markReceipt(stanzaId: String, delivered: Boolean = false, displayed: Boolean = false) {
        val chats = core.dao.chatsNow()
        for (chat in chats) {
            val m = core.findRef(chat.id, stanzaId) ?: continue
            if (m.direction != 1) continue
            val status = if (displayed) 3 else if (delivered) maxOf(m.status, 2) else m.status
            core.dao.updateMessage(m.copy(status = status))
            if (delivered) core.dao.receipt(ReceiptEntity(m.id, chat.jid, deliveredAt = System.currentTimeMillis()))
            if (displayed) core.dao.receipt(ReceiptEntity(m.id, chat.jid, displayedAt = System.currentTimeMillis()))
            return
        }
    }

    private fun replyPreview(msg: Message, body: String): String {
        val r = msg.ext("reply", Ns.REPLY) ?: return ""
        val fb = msg.ext("fallback", Ns.FALLBACK)
        val b = fb?.getFirstElement("body", Ns.FALLBACK)
        val start = b?.getAttributeValue("start")?.toIntOrNull() ?: 0
        val end = b?.getAttributeValue("end")?.toIntOrNull() ?: 0
        val quote = if (end > start && end <= body.length) body.substring(start, end) else body.lineSequence().takeWhile { it.startsWith(">") }.joinToString("\n")
        return quote.lines().joinToString("\n") { it.removePrefix("> ").removePrefix(">") }.trim()
    }

    private fun String.stripReplyQuote(reply: org.jivesoftware.smack.packet.StandardExtensionElement?): String {
        if (reply == null) return this
        val lines = lineSequence().toMutableList()
        while (lines.isNotEmpty() && lines.first().startsWith(">")) lines.removeAt(0)
        return lines.joinToString("\n").trimStart('\n')
    }

    private fun mentionsMe(chat: ChatEntity, body: String): Boolean {
        if (chat.type != "groupchat") return false
        val lower = body.lowercase()
        val names = mutableListOf(account.nickname, account.localpart, chat.ourNick)
        names += core.settings.list("notify.highlight_words")
        return names.filter { it.isNotBlank() }.any { lower.contains("@${it.lowercase()}") || lower.contains(it.lowercase()) }
    }

    private suspend fun contactName(chat: ChatEntity, from: Jid): String {
        if (chat.isGroup) return from.resourceOrNull?.toString() ?: from.asBareJid().toString()
        val c = core.dao.contact(account.id, from.asEntityBareJidIfPossible()?.toString() ?: chat.jid)
        return c?.name ?: JidUtil.nick(chat.jid)
    }

    private fun requestSubscription(jid: String, text: String) {
        scope.launch {
            val chat = core.ensureChat(account.id, jid, "chat")
            core.dao.insertRequest(RequestEntity(account.id, jid, "subscription", text, chat.id))
            val c = core.dao.contact(account.id, jid) ?: ContactEntity(account.id, jid)
            core.dao.insertContact(c.copy(subscription = "from"))
        }
    }

    // ============================================================ group chat
    fun joinRoom(chat: ChatEntity, nick: String = "", password: String = "") {
        scope.launch {
            try {
                val conn = connection ?: throw IllegalStateException("Not connected")
                val mgr = MultiUserChatManager.getInstanceFor(conn)
                val roomJid = JidCreate.entityBareFrom(chat.jid)
                val muc = rooms[chat.jid] ?: mgr.getMultiUserChat(roomJid).also { rooms[chat.jid] = it; setupMuc(it, chat) }
                val resource = Resourcepart.from(nick.ifBlank { chat.ourNick.ifBlank { account.nickname.ifBlank { account.localpart } } })
                if (!muc.isJoined) {
                    if (password.isNotBlank()) muc.join(resource, password) else muc.join(resource)
                }
                val updated = chat.copy(joined = true, joinError = "", ourNick = resource.toString(),
                    subject = muc.subject ?: chat.subject, memberCount = muc.occupantsCount)
                core.dao.updateChat(updated)
                updateOccupants(muc, chat)
                runCatching { saveAutojoin(updated) }
            } catch (e: Exception) {
                core.dao.updateChat(chat.copy(joined = false, joinError = friendlyError(e)))
            }
        }
    }

    private fun setupMuc(muc: MultiUserChat, chat: ChatEntity) {
        muc.addMessageListener { msg ->
            core.addXmlLog(account.id, true, msg.toXML(XmlEnvironment.EMPTY).toString())
            val enc = msg.getExtensionElement("encrypted", Ns.OMEMO) != null || msg.getExtensionElement("envelope", Ns.OMEMO2) != null
            if (!enc) processGroupMessage(muc, msg)
        }
        muc.addSubjectUpdatedListener { subject, from ->
            scope.launch {
                val c = core.dao.chat(account.id, chat.jid) ?: return@launch
                core.dao.updateChat(c.copy(subject = subject ?: ""))
            }
        }
        muc.addParticipantListener { p ->
            scope.launch {
                updateOccupants(muc, chat)
                val c = core.dao.chat(account.id, chat.jid)
                if (c != null) core.dao.updateChat(c.copy(memberCount = muc.occupantsCount, joined = muc.isJoined))
            }
        }
        muc.addUserStatusListener(object : DefaultUserStatusListener() {
            override fun kicked(actor: Jid?, reason: String?) {
                scope.launch {
                    val c = core.dao.chat(account.id, chat.jid) ?: return@launch
                    core.dao.updateChat(c.copy(joined = false, joinError = "Removed from the group" + (reason?.let { ": $it" } ?: "")))
                }
            }
            override fun roomDestroyed(room: MultiUserChat?, reason: String?) {
                scope.launch {
                    val c = core.dao.chat(account.id, chat.jid) ?: return@launch
                    core.dao.updateChat(c.copy(joined = false, joinError = "The group was destroyed"))
                }
            }
        })
    }

    private fun updateOccupants(muc: MultiUserChat, chat: ChatEntity) {
        val list = try {
            muc.occupants.map { jid ->
                val occ = muc.getOccupant(jid)
                OccupantInfo(jid.resourceOrNull?.toString() ?: jid.toString(),
                    occ?.jid?.toString() ?: "", occ?.role?.toString() ?: "participant",
                    occ?.affiliation?.toString() ?: "none",
                    muc.getOccupantPresence(jid)?.status ?: "",
                    jid.resourceOrNull?.toString() == muc.nickname?.toString())
            }
        } catch (_: Exception) { return }
        _occupants.value = _occupants.value + (chat.jid to list.sortedBy { it.nick.lowercase() })
    }

    private fun processGroupMessage(muc: MultiUserChat, msg: Message, decryptedBody: String? = null,
                                    senderDevice: Long = 0, encrypted: String = "") {
        val roomJid = muc.room.toString()
        val nick = msg.from?.resourceOrNull?.toString() ?: return
        val body = decryptedBody ?: msg.body ?: ""
        val stanzaId = msg.stanzaId ?: JidUtil.randomId()
        val timestamp = (msg.getExtension("delay", Ns.DELAY) as? DelayInformation)?.stamp?.time ?: System.currentTimeMillis()
        val reply = msg.ext("reply", Ns.REPLY)
        val target = msg.ext("replace", Ns.CORRECT)?.getAttributeValue("id")
        val retract = msg.ext("retract", Ns.RETRACT)?.getAttributeValue("id")
        val reactions = msg.ext("reactions", Ns.REACTIONS)
        val attachments = msg.attachments().ifEmpty { attachmentsFromBody(body) }
        if (body.isBlank() && attachments.isEmpty() && reactions == null && target == null && retract == null) return
        scope.launch {
            try {
            val chat = core.ensureChat(account.id, roomJid, "groupchat", muc.room.toString().substringBefore('@'))
            if (timestamp <= chat.clearedAt) return@launch
            if (!chat.open) core.dao.updateChat(chat.copy(open = true))
            if (target != null) {
                core.findRef(chat.id, target)?.let { core.dao.updateMessage(it.copy(body = body.stripReplyQuote(reply), edited = true)) }
                return@launch
            }
            if (retract != null) {
                core.findRef(chat.id, retract)?.let { core.dao.updateMessage(it.copy(retracted = 1)) }
                return@launch
            }
            if (reactions != null) {
                val rid = reactions.getAttributeValue("id") ?: return@launch
                val old = core.findRef(chat.id, rid) ?: return@launch
                val emojis = runCatching { reactions.getElements("reaction", Ns.REACTIONS) }
                    .getOrNull()?.mapNotNull { it.text } ?: emptyList()
                val list = parseReactions(old.reactionsJson).toMutableList()
                list.removeAll { it.who == nick }
                if (emojis.isNotEmpty()) list.add(ReactionSet(emojis, nick, nick == muc.nickname?.toString()))
                core.dao.updateMessage(old.copy(reactionsJson = gson.toJson(list)))
                return@launch
            }
            if (body.isBlank()) return@launch
            val occ = msg.from?.asEntityFullJidIfPossible()?.let { muc.getOccupant(it)?.jid?.toString() } ?: ""
            val cleanBody = if (attachments.any { it.url.startsWith("aesgcm://") })
                body.substringBefore("aesgcm://").trim().ifBlank { attachments.first().name } else body
            val msgEntity = MessageEntity(chatId = chat.id, stanzaId = stanzaId,
                originId = msg.ext("origin-id", Ns.SID)?.getAttributeValue("id") ?: "", msgId = msg.stanzaId ?: "",
                sender = nick, senderJid = occ, senderName = nick, direction = if (nick == muc.nickname?.toString()) 1 else 0,
                timestamp = timestamp, body = cleanBody.stripReplyQuote(reply),
                kind = if (cleanBody.startsWith("/me ")) "me" else if (attachments.isNotEmpty()) "file" else "text",
                encryption = encrypted, senderDevice = senderDevice, replyTo = reply?.getAttributeValue("id") ?: "",
                replyPreview = replyPreview(msg, body), mentionsMe = mentionsMe(chat, body),
                attachmentsJson = gson.toJson(attachments.map { mapOf("url" to it.url, "name" to it.name,
                    "mime" to it.mime, "size" to it.size, "width" to it.width, "height" to it.height) }))
            val id = core.dao.insertMessage(msgEntity)
            if (id >= 0) {
                val stored = msgEntity.copy(id = id)
                core.touchChat(chat, stored, outgoing = msgEntity.direction == 1)
                if (core.isChatActive(chat.id) && msgEntity.direction == 0) {
                    if (core.settings.bool("chats.send_markers")) sendDisplayed(chat, stored)
                    core.dao.updateChat(chat.copy(unread = 0, mentions = 0, lastReadId = id))
                }
            }
            } catch (e: Exception) {
                core.addXmlLog(account.id, false, "group message handling failed: ${e.stackTraceToString()}")
            }
        }
    }

    fun leaveRoom(chat: ChatEntity) {
        scope.launch {
            rooms.remove(chat.jid)?.let { runCatching { it.leave() } }
            core.dao.updateChat(chat.copy(joined = false))
        }
    }

    fun setSubject(chat: ChatEntity, subject: String) {
        scope.launch { rooms[chat.jid]?.changeSubject(subject); core.dao.updateChat(chat.copy(subject = subject)) }
    }
    fun changeNick(chat: ChatEntity, nick: String) {
        scope.launch {
            runCatching { rooms[chat.jid]?.changeNickname(Resourcepart.from(nick)) }
            core.dao.updateChat(chat.copy(ourNick = nick))
        }
    }
    fun invite(chat: ChatEntity, jid: String, reason: String = "") {
        scope.launch { runCatching { rooms[chat.jid]?.invite(JidCreate.entityBareFrom(jid), reason) } }
    }
    fun kick(chat: ChatEntity, nick: String, reason: String = "") {
        scope.launch { runCatching { rooms[chat.jid]?.kickParticipant(Resourcepart.from(nick), reason) } }
    }
    fun ban(chat: ChatEntity, jid: String, reason: String = "") {
        scope.launch { runCatching { rooms[chat.jid]?.banUser(JidCreate.entityBareFrom(jid), reason) } }
    }
    fun setRole(chat: ChatEntity, nick: String, role: String) {
        scope.launch {
            val muc = rooms[chat.jid] ?: return@launch
            val r = Resourcepart.from(nick)
            when (role) {
                "moderator" -> muc.grantModerator(r); "participant" -> muc.grantVoice(r)
                "visitor" -> muc.revokeVoice(r)
            }
        }
    }
    fun setAffiliation(chat: ChatEntity, jid: String, affiliation: String) {
        scope.launch {
            val muc = rooms[chat.jid] ?: return@launch
            val j = JidCreate.entityBareFrom(jid)
            when (affiliation) {
                "owner" -> muc.grantOwnership(j); "admin" -> muc.grantAdmin(j)
                "member" -> muc.grantMembership(j); "outcast" -> muc.banUser(j, "")
                else -> muc.revokeMembership(j)
            }
        }
    }

    fun createGroup(chat: ChatEntity, name: String, preset: String, invitees: List<String>, description: String) {
        scope.launch {
            try {
                val conn = connection ?: throw IllegalStateException("Not connected")
                val mgr = MultiUserChatManager.getInstanceFor(conn)
                val muc = mgr.getMultiUserChat(JidCreate.entityBareFrom(chat.jid))
                if (!muc.isJoined) muc.createOrJoin(Resourcepart.from(chat.ourNick.ifBlank { account.nickname.ifBlank { account.localpart } }))
                runCatching {
                    val form = muc.configurationForm
                    val fill = org.jivesoftware.smackx.xdata.form.FillableForm(form.dataForm)
                    fill.setAnswer("muc#roomconfig_roomname", name)
                    fill.setAnswer("muc#roomconfig_roomdesc", description)
                    fill.setAnswer("muc#roomconfig_membersonly", preset == "private")
                    fill.setAnswer("muc#roomconfig_publicroom", preset == "public")
                    fill.setAnswer("muc#roomconfig_whois", if (preset == "private") "members" else "anyone")
                    muc.sendConfigurationForm(fill)
                }
                for (jid in invitees) runCatching { muc.invite(JidCreate.entityBareFrom(jid), "") }
                rooms[chat.jid] = muc
                setupMuc(muc, chat)
                core.dao.updateChat(chat.copy(joined = true, name = name, subject = description,
                    ourNick = muc.nickname?.toString() ?: "", memberCount = muc.occupantsCount,
                    encryption = if (preset == "private" && core.settings.bool("encryption.legacy_omemo")) "omemo" else "off"))
                saveAutojoin(chat)
            } catch (e: Exception) {
                core.dao.updateChat(chat.copy(joined = false, joinError = friendlyError(e)))
            }
        }
    }

    private fun saveAutojoin(chat: ChatEntity) {
        val conn = connection ?: return
        val bm = BookmarkManager.getBookmarkManager(conn)
        runCatching {
            val existing = bm.bookmarkedConferences.firstOrNull { it.jid.toString() == chat.jid }
            if (existing == null) bm.addBookmarkedConference(chat.displayName, JidCreate.entityBareFrom(chat.jid),
                true, Resourcepart.from(chat.ourNick.ifBlank { account.localpart }), "")
            else bm.addBookmarkedConference(existing.name, existing.jid, true, existing.nickname, existing.password)
        }
    }

    private fun joinBookmarks() {
        val conn = connection ?: return
        runCatching {
            val bm = BookmarkManager.getBookmarkManager(conn)
            for (conf in bm.bookmarkedConferences) {
                scope.launch {
                    val chat = core.ensureChat(account.id, conf.jid.toString(), "groupchat",
                        conf.name ?: conf.jid.toString().substringBefore('@'))
                    joinRoom(chat, conf.nickname?.toString() ?: "", conf.password ?: "")
                }
            }
        }
    }

    // ============================================================ outgoing
    fun sendChat(chat: ChatEntity, entity: MessageEntity) {
        scope.launch { sendChatNow(chat, entity) }
    }

    private suspend fun sendChatNow(chat: ChatEntity, entity: MessageEntity) {
        try {
            val conn = connection ?: throw IllegalStateException("Not connected")
            val encrypted = tryEncrypt(chat, entity)
            val message = buildBaseMessage(chat, entity, encrypted)
            if (chat.isGroup) {
                val muc = rooms[chat.jid] ?: throw IllegalStateException("Not in the group")
                muc.sendMessage(message)
            } else {
                conn.sendStanza(message)
            }
            core.dao.updateMessage(entity.copy(status = 1, encryption = when (encrypted) {
                is EncryptedPayload.Omemo -> "omemo"; is EncryptedPayload.Omemo2 -> "omemo2"
                is EncryptedPayload.Pgp -> "pgp"; null -> ""
            }))
        } catch (e: Exception) {
            core.dao.updateMessage(entity.copy(status = -1, error = friendlyError(e)))
        }
    }

    private fun buildBaseMessage(chat: ChatEntity, entity: MessageEntity, payload: EncryptedPayload?): Message {
        val id = entity.stanzaId.ifBlank { JidUtil.randomId() }
        val builder = StanzaBuilder.buildMessage(id)
        try { builder.to(chat.jid) } catch (_: XmppStringprepException) {}
        builder.ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat)
        var body = entity.body
        if (entity.replyTo.isNotBlank()) {
            val quote = entity.replyPreview.lines().filter { it.isNotBlank() }.take(3).joinToString("\n") { "> $it" }
            if (quote.isNotBlank()) body = quote + "\n" + body
        }
        if (payload == null || payload is EncryptedPayload.Pgp) builder.setBody(body)
        builder.addOriginId(entity.originId.ifBlank { id })
        builder.addMarkdown()
        if (!chat.isGroup) builder.addExtension(RawExtension("request", Ns.RECEIPTS, "<request xmlns='${Ns.RECEIPTS}'/>"))
        builder.addMarkable()
        if (core.settings.bool("chats.send_typing")) builder.addChatState("active")
        if (entity.replyTo.isNotBlank() && payload !is EncryptedPayload.Omemo2) {
            builder.addReply(if (chat.isGroup) "" else chat.jid, entity.replyTo)
            if (body.length > entity.body.length) builder.addReplyFallback(body.length - entity.body.length)
        }
        when (payload) {
            is EncryptedPayload.Omemo -> {
                builder.addExtension(payload.message.element)
                builder.addEmojiEncryption(if (payload.message.element is OmemoElement) Ns.OMEMO else Ns.OMEMO2, "OMEMO")
            }
            is EncryptedPayload.Omemo2 -> {
                builder.addExtension(RawExtension("encrypted", Ns.OMEMO2, payload.xml))
                builder.addEmojiEncryption(Ns.OMEMO2, "OMEMO")
            }
            is EncryptedPayload.Pgp -> {
                builder.addExtension(RawExtension("openpgp", Ns.OPENPGP,
                    "<openpgp xmlns='${Ns.OPENPGP}'>${payload.base64}</openpgp>"))
                builder.addEmojiEncryption(Ns.OPENPGP, "OpenPGP for XMPP")
                builder.setBody("This message is encrypted with OpenPGP (XEP-0374).")
            }
            null -> {}
        }
        if (entity.attachmentsJson != "[]") {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val atts: List<Map<String, Any>> = gson.fromJson(entity.attachmentsJson, type) ?: emptyList()
            atts.firstOrNull()?.let { a ->
                builder.addFileSharing(a["url"]?.toString() ?: "", a["name"]?.toString() ?: entity.body,
                    a["mime"]?.toString() ?: "", (a["size"] as? Number)?.toLong() ?: 0,
                    encrypted = payload != null)
            }
        }
        builder.addHint("store")
        return builder.build()
    }

    private fun xmlEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    private fun buildSceContent(chat: ChatEntity, entity: MessageEntity): String {
        val sb = StringBuilder()
        sb.append("<body xmlns='jabber:client'>").append(xmlEscape(entity.body)).append("</body>")
        if (entity.replyTo.isNotBlank()) {
            sb.append("<reply xmlns='").append(Ns.REPLY).append("' id='").append(xmlEscape(entity.replyTo))
                .append("' to='").append(xmlEscape(chat.jid)).append("'>")
            sb.append("<fallback xmlns='").append(Ns.FALLBACK).append("'><body xmlns='jabber:client'>")
                .append(xmlEscape(entity.replyPreview)).append("</body></fallback></reply>")
        }
        return sb.toString()
    }

    private fun ensureLegacyManager(): OmemoManager? {
        omemo?.let { return it }
        if (!core.settings.bool("encryption.legacy_omemo")) return null
        val conn = connection ?: return null
        if (!omemoReady) setupOmemo(conn)
        return omemo
    }

    private suspend fun tryEncrypt(chat: ChatEntity, entity: MessageEntity): EncryptedPayload? {
        if (chat.encryption == "off") return null
        if (chat.encryption == "pgp") {
            if (!core.settings.bool("pgp.enabled")) throw IllegalStateException("OpenPGP is turned off in Preferences > Encryption")
            val secret = OpenPgp.loadSecret(core, account.id) ?: throw IllegalStateException("Generate an OpenPGP key in the account settings first")
            refreshPgp(chat.jid)
            val certs = (core.dao.pgpKeysNow(account.id, chat.jid)
                .filter { it.trust != "distrusted" }
                .mapNotNull { OpenPgp.parsePublic(it.armored) } +
                listOfNotNull(OpenPgp.parsePublic(OpenPgp.publicArmor(secret)))).distinctBy {
                    org.pgpainless.PGPainless.inspectKeyRing(it).fingerprint.toString()
                }
            if (certs.size <= 1) throw IllegalStateException("${chat.displayName} has no OpenPGP key. Ask them to publish one from Ward.")
            val signcrypt = OpenPgp.signcryptXml(entity.body, chat.jid)
            val data = OpenPgp.encrypt(secret, certs, signcrypt.toByteArray())
            return EncryptedPayload.Pgp(android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        }
        val effective = core.effectiveEncryption(chat, account)
        if (effective != "omemo") return null
        val force2 = chat.encryption == "omemo2"
        val forceLegacy = chat.encryption == "omemo"
        if (force2 && chat.isGroup)
            throw IllegalStateException("OMEMO 2 group chats are not supported yet. Choose Legacy OMEMO for this group.")
        if (!chat.isGroup && !forceLegacy && (force2 || core.settings.string("encryption.omemo2", "true").toBoolean())) {
            val o2 = omemo2
            if (o2 != null) {
                val content = buildSceContent(chat, entity)
                val xml = try { o2.encryptContent(chat.jid, content) } catch (e: Exception) {
                    core.addXmlLog(account.id, false, "OMEMO 2 encrypt failed: ${e.message}"); null
                }
                if (xml != null) return EncryptedPayload.Omemo2(xml)
            }
            if (force2) throw IllegalStateException(
                "This contact has no OMEMO 2 devices. Ask them to enable OMEMO 2, or choose Legacy OMEMO in Details.")
        }
        if (!core.settings.bool("encryption.legacy_omemo")) {
            throw IllegalStateException("Legacy OMEMO is turned off in Settings → Encryption")
        }
        val mgr = ensureLegacyManager()
            ?: throw IllegalStateException("Legacy OMEMO is not ready on this device yet. Try again in a moment.")
        if (!omemoReady) throw IllegalStateException("Legacy OMEMO is still starting. Try again in a moment.")
        return try {
            if (chat.isGroup) {
                val muc = rooms[chat.jid] ?: throw IllegalStateException("Not in the group yet")
                if (!mgr.multiUserChatSupportsOmemo(muc)) throw IllegalStateException("This group cannot be encrypted; change encryption in Details to Off")
                EncryptedPayload.Omemo(mgr.encrypt(muc, entity.body))
            } else {
                EncryptedPayload.Omemo(mgr.encrypt(JidCreate.entityBareFrom(chat.jid), entity.body))
            }
        } catch (e: Exception) {
            throw IllegalStateException("Could not encrypt: ${e.message ?: e.javaClass.simpleName}. Turn encryption off in Details to send unencrypted.")
        }
    }

    fun sendFile(chat: ChatEntity, entity: MessageEntity, url: String, name: String, mime: String, size: Long,
                 encryptedFile: Boolean = false) {
        scope.launch {
            try {
                val conn = connection ?: throw IllegalStateException("Not connected")
                val enc = core.effectiveEncryption(chat, account) != "off"
                // In an encrypted chat the aesgcm link travels inside the OMEMO payload, never in
                // SFS/OOB metadata. Unencrypted chats keep the normal file-sharing extension.
                val sendEntity = if (enc) entity.copy(body = "$name\n$url", attachmentsJson = "[]") else
                    entity.copy(body = name, attachmentsJson = gson.toJson(listOf(mapOf("url" to url,
                        "name" to name, "mime" to mime, "size" to size, "encrypted" to encryptedFile))))
                val sent = if (enc) tryEncrypt(chat, sendEntity) else null
                val msg = buildBaseMessage(chat, sendEntity, sent)
                if (!enc && encryptedFile) {
                    // aesgcm in a plaintext chat: keep the URL as an OOB fallback
                    msg.addFileSharing(url, name, mime, size, encrypted = true)
                }
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
                core.dao.updateMessage(entity.copy(status = 1, encryption = when (sent) {
                    is EncryptedPayload.Omemo -> "omemo"; is EncryptedPayload.Omemo2 -> "omemo2"
                    is EncryptedPayload.Pgp -> "pgp"; null -> ""
                }))
            } catch (e: Exception) {
                core.dao.updateMessage(entity.copy(status = -1, error = friendlyError(e)))
            }
        }
    }

    fun sendReceipt(chat: ChatEntity, stanzaId: String) {
        if (stanzaId.isBlank()) return
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val msg = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat)
                    .to(chat.jid).addExtension(DeliveryReceipt(stanzaId)).build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    fun sendDisplayed(chat: ChatEntity, entity: MessageEntity) {
        val ref = if (chat.isGroup) entity.stanzaId else entity.msgId.ifBlank { entity.stanzaId }
        if (ref.isBlank()) return
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val b = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat)
                    .to(chat.jid)
                b.addMarker("displayed", ref)
                b.addHint("store")
                val msg = b.build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    /** Sends an OMEMO 2 encrypted extension-only message (reactions, edits, retractions). */
    private fun sendEncrypted2Content(chat: ChatEntity, contentXml: String): Boolean {
        if (chat.isGroup) return false
        if (chat.encryption == "off" || chat.encryption == "pgp") return false
        if (chat.encryption == "omemo") return false
        if (chat.encryption != "omemo2" && !core.settings.string("encryption.omemo2", "true").toBoolean()) return false
        val o2 = omemo2 ?: return false
        val conn = connection ?: return false
        val xml = runCatching { o2.encryptContent(chat.jid, contentXml) }.getOrNull() ?: return false
        val b = StanzaBuilder.buildMessage().ofType(Message.Type.chat).to(chat.jid)
        b.addOriginId(JidUtil.randomId())
        b.addExtension(RawExtension("encrypted", Ns.OMEMO2, xml))
        b.addEmojiEncryption(Ns.OMEMO2, "OMEMO")
        b.addHint("store")
        conn.sendStanza(b.build())
        return true
    }

    fun sendReaction(chat: ChatEntity, entity: MessageEntity, emojis: List<String>) {
        val target = if (chat.isGroup) entity.stanzaId else entity.originId.ifBlank { entity.msgId }
        if (target.isBlank()) return
        scope.launch {
            runCatching {
                val reactions = buildString {
                    append("<reactions xmlns='").append(Ns.REACTIONS).append("' id='").append(xmlEscape(target)).append("'>")
                    for (e in emojis) append("<reaction>").append(xmlEscape(e)).append("</reaction>")
                    append("</reactions>")
                }
                if (sendEncrypted2Content(chat, reactions)) return@runCatching
                if (chat.encryption == "omemo2") return@runCatching
                val conn = connection ?: return@runCatching
                val b = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat).to(chat.jid)
                b.setBody("")
                b.addReactions(target, emojis)
                b.addHint("store")
                val msg = b.build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    fun sendEdit(chat: ChatEntity, entity: MessageEntity, text: String) {
        val target = entity.originId.ifBlank { entity.msgId }
        scope.launch {
            runCatching {
                val content = "<body xmlns='jabber:client'>" + xmlEscape(text) + "</body>" +
                    "<replace xmlns='" + Ns.CORRECT + "' id='" + xmlEscape(target) + "'/>"
                if (sendEncrypted2Content(chat, content)) return@runCatching
                if (chat.encryption == "omemo2") return@runCatching
                val conn = connection ?: return@runCatching
                val b = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat).to(chat.jid)
                b.setBody(text); b.addCorrection(target); b.addOriginId(JidUtil.randomId()); b.addMarkdown(); b.addHint("store")
                val msg = b.build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    fun sendRetraction(chat: ChatEntity, entity: MessageEntity) {
        val target = if (chat.isGroup) (entity.stanzaId.ifBlank { entity.originId }) else (entity.originId.ifBlank { entity.msgId })
        scope.launch {
            runCatching {
                val content = "<retract xmlns='" + Ns.RETRACT + "' id='" + xmlEscape(target) + "'/>"
                if (sendEncrypted2Content(chat, content)) return@runCatching
                if (chat.encryption == "omemo2") return@runCatching
                val conn = connection ?: return@runCatching
                val b = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat).to(chat.jid)
                b.setBody("This person attempted to retract a previous message, but it's unsupported by your client.")
                b.addRetraction(target); b.addOriginId(JidUtil.randomId()); b.addMarkdown(); b.addHint("store")
                val msg = b.build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    fun sendTyping(chat: ChatEntity, state: String) {
        if (!core.settings.bool("chats.send_typing")) return
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val b = StanzaBuilder.buildMessage().ofType(if (chat.isGroup) Message.Type.groupchat else Message.Type.chat).to(chat.jid)
                b.addChatState(state); b.addHint("no-store")
                val msg = b.build()
                if (chat.isGroup) rooms[chat.jid]?.sendMessage(msg) else conn.sendStanza(msg)
            }
        }
    }

    private suspend fun flushOutbox() {
        val chats = core.dao.chatsNow()
        for (chat in chats) {
            val msgs = core.dao.messagesNow(chat.id, 500).filter { it.direction == 1 && it.status == 0 }
            for (m in msgs) if (chat.isGroup && rooms[chat.jid]?.isJoined == true || !chat.isGroup) sendChatNow(chat, m)
        }
    }

    // ============================================================ MAM
    private suspend fun syncMam(conn: XMPPConnection) {
        if (mamSynced) return
        mamSynced = true
        try {
            val mam = MamManager.getInstanceFor(conn)
            if (!mam.isSupported) return
            val days = 7
            val since = Date(System.currentTimeMillis() - days * 86_400_000L)
            val query = mam.queryArchive(MamManager.MamQueryArgs.builder().setResultPageSize(100).limitResultsSince(since).build())
            var msgs = query.page.forwarded
            var decrypted: List<MessageOrOmemoMessage>? = null
            if (omemoReady) runCatching { decrypted = omemo?.decryptMamQueryResult(query) }
            processMam(msgs, decrypted)
            var guard = 0
            while (!query.isComplete && guard++ < 20) {
                val next = query.pageNext(100)
                if (next.isEmpty()) break
                // pageNext returns plain messages; forwarded info is no longer available, parse minimal
                for (m in next) processMessage(m, directionOverride = null)
            }
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "MAM sync failed: ${e.message}")
        }
    }

    private fun processMam(forwarded: List<Forwarded<Message>>, decrypted: List<MessageOrOmemoMessage>?) {
        forwarded.forEachIndexed { i, f ->
            val msg = f.forwardedStanza
            val delay = f.delayInformation?.stamp?.time ?: System.currentTimeMillis()
            val raw = msg.toXML(XmlEnvironment.EMPTY).toString()
            if (raw.contains("urn:xmpp:omemo:2")) {
                val c = omemo2?.decrypt(msg, raw)
                if (c != null && !c.empty) {
                    scope.launch {
                        for (child in c.children) msg.addExtension(child)
                        processMessage(msg, directionOverride = null, decryptedBody = c.body,
                            senderDevice = c.deviceId.toLong(), trust = c.trust, encrypted = "omemo2")
                    }
                }
            } else if (decrypted != null && i < decrypted.size && decrypted[i].isOmemoMessage) {
                val d = decrypted[i].omemoMessage
                processMessage(msg, directionOverride = null, decryptedBody = d.body,
                    senderDevice = d.senderDevice.deviceId.toLong(), encrypted = "omemo")
            } else {
                processMessage(msg, directionOverride = null)
            }
        }
    }

    fun syncChat(chat: ChatEntity) {
        scope.launch {
            try {
                val conn = connection ?: return@launch
                val mam = MamManager.getInstanceFor(conn, JidCreate.from(if (chat.isGroup) chat.jid else account.jid))
                val query = mam.queryMostRecentPage(JidCreate.from(chat.jid), 80)
                val msgs: List<Message> = query.messages
                for (m in msgs) {
                    val raw = m.toXML(XmlEnvironment.EMPTY).toString()
                    if (raw.contains("urn:xmpp:omemo:2")) {
                        val c = omemo2?.decrypt(m, raw)
                        if (c != null && !c.empty) {
                            for (child in c.children) m.addExtension(child)
                            processMessage(m, directionOverride = null, decryptedBody = c.body,
                                senderDevice = c.deviceId.toLong(), trust = c.trust, encrypted = "omemo2")
                        }
                    } else processMessage(m, directionOverride = null)
                }
            } catch (_: Exception) {}
        }
    }

    // ============================================================ rosters / requests / admin
    fun addContact(jid: String, name: String, message: String = "") {
        scope.launch {
            runCatching {
                val r = roster ?: return@runCatching
                val bare = JidCreate.entityBareFrom(jid)
                r.createItemAndRequestSubscription(bare, name, arrayOf())
            }
        }
    }
    fun renameContact(jid: String, name: String) {
        scope.launch {
            runCatching { roster?.getEntry(JidCreate.entityBareFrom(jid))?.name = name }
            val c = core.dao.contact(account.id, jid)
            if (c != null) core.dao.insertContact(c.copy(rosterName = name))
        }
    }
    fun removeContact(jid: String) {
        scope.launch {
            runCatching {
                val bare = JidCreate.entityBareFrom(jid)
                roster?.getEntry(bare)?.let { roster?.removeEntry(it) }
                omemo2?.forget(jid)
                forgetLegacy(jid)
                core.dao.deleteContact(account.id, jid)
                core.dao.deleteDevicesFor(account.id, jid)
                core.dao.deletePgpKeysFor(account.id, jid)
                core.dao.deleteRequest(account.id, jid)
                core.dao.deleteMessagesForChatJid(account.id, jid)
                core.dao.deleteChatForJid(account.id, jid)
                core.addXmlLog(account.id, false, "Removed contact and local keys for $jid")
            }
        }
    }

    fun forgetDevice(jid: String, deviceId: Long, protocol: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (protocol == "omemo2") omemo2?.forgetDevice(jid, deviceId.toInt())
                else forgetLegacyDevice(jid, deviceId.toInt())
                core.dao.deleteDevice(account.id, jid, deviceId, protocol)
            }
        }
    }

    /**
     * Drop the per-account OMEMO 2 state and rebuild it from the legacy OMEMO identity. This is
     * used when a previous state no longer matches the published bundle (which makes every incoming
     * message fail authentication) or when a failed publish left stale keys on the server.
     */
    fun repairOmemo2(): String? {
        val conn = connection ?: return null
        omemo2 = null
        val dir = File(core.context.filesDir, "omemo2/${account.id}")
        dir.deleteRecursively()
        setupOmemo2(conn)
        val mgr = omemo2 ?: return "${account.jid}: OMEMO 2 could not be initialized; see the XML console"
        val published = mgr.publish()
        return "${account.jid}: OMEMO 2 device ${mgr.deviceId}, published=$published"
    }

    private fun forgetLegacy(jid: String) {
        runCatching {
            val mgr = omemo ?: return
            val bare = JidCreate.entityBareFrom(jid)
            val own = mgr.ownDevice
            val store = SignalOmemoService.getInstance().omemoStoreBackend as? SignalFileBasedOmemoStore ?: return
            for (device in mgr.getDevicesOf(bare)) {
                runCatching { store.removeRawSession(own, device) }
                runCatching { store.removeOmemoIdentityKey(own, device) }
            }
        }
    }

    private fun forgetLegacyDevice(jid: String, deviceId: Int) {
        runCatching {
            val mgr = omemo ?: return
            val store = SignalOmemoService.getInstance().omemoStoreBackend as? SignalFileBasedOmemoStore ?: return
            val device = OmemoDevice(JidCreate.entityBareFrom(jid), deviceId)
            store.removeRawSession(mgr.ownDevice, device)
            store.removeOmemoIdentityKey(mgr.ownDevice, device)
        }
    }
    fun acceptSubscription(jid: String) {
        scope.launch { runCatching { roster?.let { Roster.getInstanceFor(connection!!) }
            val p = Presence(Presence.Type.subscribed); p.to = JidCreate.from(jid); connection?.sendStanza(p)
            val pp = Presence(Presence.Type.subscribe); pp.to = JidCreate.from(jid); connection?.sendStanza(pp)
        } }
    }
    fun declineSubscription(jid: String) {
        scope.launch { runCatching { val p = Presence(Presence.Type.unsubscribed); p.to = JidCreate.from(jid); connection?.sendStanza(p) } }
    }
    fun setBlocked(jid: String, block: Boolean) {
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val mgr = org.jivesoftware.smackx.blocking.BlockingCommandManager.getInstanceFor(conn)
                val j = JidCreate.entityBareFrom(jid)
                if (block) mgr.blockContacts(listOf(j)) else mgr.unblockContacts(listOf(j))
            }
        }
    }
    fun setPresence(show: String, status: String) {
        scope.launch {
            runCatching {
                val type = if (show == "offline") Presence.Type.unavailable else Presence.Type.available
                val p = Presence(type)
                if (type == Presence.Type.available) {
                    p.mode = when (show) { "away" -> Presence.Mode.away; "xa" -> Presence.Mode.xa; "dnd" -> Presence.Mode.dnd; else -> Presence.Mode.available }
                    if (status.isNotBlank()) p.status = status
                }
                connection?.sendStanza(p)
            }
        }
    }


    suspend fun loadDevices(jid: String) {
        val mgr = omemo
        if (mgr != null) {
            val bare = try { JidCreate.entityBareFrom(jid) } catch (_: Exception) { null }
            if (bare != null) {
                val devices = runCatching { mgr.getDevicesOf(bare) }.getOrDefault(emptySet())
                for (device in devices) {
                    val fp = runCatching { mgr.getFingerprint(device) }.getOrNull() ?: continue
                    val trust = when {
                        runCatching { mgr.isTrustedOmemoIdentity(device, fp) }.getOrDefault(false) -> "trusted"
                        runCatching { mgr.isDecidedOmemoIdentity(device, fp) }.getOrDefault(false) -> "distrusted"
                        else -> "undecided"
                    }
                    runBlocking(Dispatchers.IO) {
                        core.dao.insertDevice(DeviceEntity(account.id, jid, device.deviceId.toLong(), fp.toString(),
                            label = if (device.deviceId == mgr.ownDevice.deviceId) "This device" else "",
                            trust = trust, own = jid == account.jid, thisDevice = jid == account.jid && device.deviceId == mgr.ownDevice.deviceId))
                    }
                }
            }
        }
        val o2 = omemo2
        if (o2 != null) {
            runCatching {
                for ((dev, fp) in o2.loadDeviceInfo(jid)) {
                    runBlocking(Dispatchers.IO) {
                        val old = core.dao.devicesOnce(account.id, jid)
                            .firstOrNull { it.deviceId == dev.toLong() && it.protocol == "omemo2" }
                        core.dao.insertDevice(DeviceEntity(account.id, jid, dev.toLong(), fp,
                            label = old?.label?.takeIf { it.isNotBlank() } ?: if (jid == account.jid) "This device (OMEMO 2)" else "",
                            trust = old?.trust ?: if (core.settings.string("encryption.trust", "btbv") == "btbv") "trusted" else "undecided",
                            own = jid == account.jid, thisDevice = false, protocol = "omemo2"))
                    }
                }
            }
        }
    }

    fun trustDevice(jid: String, deviceId: Long, fingerprint: String, trusted: Boolean, protocol: String = "omemo") {
        if (protocol == "omemo2") {
            scope.launch(Dispatchers.IO) {
                omemo2?.rememberTrust(jid, deviceId.toInt(), if (trusted) "trusted" else "distrusted")
                runCatching { core.dao.setTrust(account.id, jid, deviceId, "omemo2", if (trusted) "verified" else "distrusted") }
            }
            return
        }
        val mgr = omemo ?: return
        runBlocking(Dispatchers.IO) {
            runCatching {
                val device = OmemoDevice(JidCreate.entityBareFrom(jid), deviceId.toInt())
                val fp = OmemoFingerprint(fingerprint)
                if (trusted) mgr.trustOmemoIdentity(device, fp) else mgr.distrustOmemoIdentity(device, fp)
                core.dao.setTrust(account.id, jid, deviceId, "omemo", if (trusted) "verified" else "distrusted")
            }
        }
    }

    fun setPassword(pw: String) {
        account = account.copy(encryptedPassword = org.ward.android.util.SecretStore.encrypt(pw))
        scope.launch { core.dao.updateAccount(account) }
    }

    suspend fun upload(file: File, name: String, mime: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = connection ?: return@withContext null
            val mgr = HttpFileUploadManager.getInstanceFor(conn)
            if (!mgr.isUploadServiceDiscovered) {
                runCatching { mgr.discoverUploadService() }
            }
            if (mgr.getDefaultUploadService() == null) {
                core.addXmlLog(account.id, false, "HTTP upload service not found by Smack; probing discovery")
                ensureUploadService(conn, mgr) ?: run {
                    core.addXmlLog(account.id, false, "No XEP-0363 upload service advertised by this server")
                    return@withContext null
                }
            }
            val service = mgr.getDefaultUploadService()
            core.addXmlLog(account.id, false, "HTTP upload via ${service?.address}")
            try {
                mgr.uploadFile(file.inputStream(), name, file.length()).toString()
            } catch (e: Exception) {
                // Some servers accept the slot request but reject Smack's HTTP upload setup.
                // Request a fresh slot and put the bytes directly, which is the same XEP-0363
                // transaction without Smack's internal URL connection quirks.
                core.addXmlLog(account.id, false,
                    "HTTP upload via Smack failed (${e.javaClass.simpleName}: ${e.message}); retrying direct PUT")
                val slot = mgr.requestSlot(name, file.length(), mime)
                directPut(slot.putUrl, slot.headers, file)
                slot.getUrl.toString()
            }
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "upload failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun directPut(url: URL, headers: Map<String, String>, file: File): String {
        val http = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(file.length())
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }
        try {
            http.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = http.responseCode
            if (code !in 200..299) {
                val text = runCatching { http.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw IllegalStateException("Upload server returned HTTP $code${if (text.isBlank()) "" else ": ${text.take(200)}"}")
            }
            return url.toString()
        } finally {
            http.disconnect()
        }
    }

    /**
     * Smack's discoverUploadService() can miss a Prosody upload component when the server's
     * disco#items answer arrives in a slightly different shape. Walk the server's items and
     * feature discovery ourselves, then install the discovered service into the manager.
     */
    private fun ensureUploadService(conn: XMPPConnection, mgr: HttpFileUploadManager): UploadService? {
        val disco = ServiceDiscoveryManager.getInstanceFor(conn)
        val domain = conn.xmppServiceDomain
        val candidates = linkedSetOf<org.jxmpp.jid.Jid>()
        candidates.add(domain)
        runCatching {
            disco.discoverItems(domain).items.forEach { candidates.add(it.entityID) }
        }
        for (prefix in listOf("upload", "httpupload", "files", "share")) {
            runCatching { candidates.add(org.jxmpp.jid.impl.JidCreate.domainBareFrom("$prefix.${domain}")) }
        }
        fun install(info: org.jivesoftware.smackx.disco.packet.DiscoverInfo): UploadService? {
            if (!info.containsFeature("urn:xmpp:http:upload:0") &&
                !info.containsFeature("urn:xmpp:http:upload")) return null
            val service = runCatching {
                val method = HttpFileUploadManager::class.java
                    .getDeclaredMethod("uploadServiceFrom", org.jivesoftware.smackx.disco.packet.DiscoverInfo::class.java)
                method.isAccessible = true
                method.invoke(null, info) as UploadService
            }.getOrNull() ?: return null
            val field = HttpFileUploadManager::class.java.getDeclaredField("defaultUploadService")
            field.isAccessible = true
            field.set(mgr, service)
            core.addXmlLog(account.id, false, "XEP-0363 service: ${service.address} (${info.from})")
            return service
        }
        // Let Smack do the standard server/items walk first.
        runCatching {
            disco.findServicesDiscoverInfo("urn:xmpp:http:upload:0", false, false)
                .firstNotNullOfOrNull { info -> install(info) }
        }.getOrNull()?.let { return it }
        runCatching {
            disco.findServicesDiscoverInfo("urn:xmpp:http:upload", false, false)
                .firstNotNullOfOrNull { info -> install(info) }
        }.getOrNull()?.let { return it }
        for (jid in candidates) {
            val info = runCatching { disco.discoverInfo(jid) }.getOrNull() ?: continue
            install(info)?.let { return it }
        }
        return null
    }

    fun discoverInfo(jid: String): DiscoverInfo? = try {
        ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(JidCreate.from(jid))
    } catch (_: Exception) { null }
    fun discoverItems(jid: String): DiscoverItems? = try {
        ServiceDiscoveryManager.getInstanceFor(connection).discoverItems(JidCreate.from(jid))
    } catch (_: Exception) { null }

    fun sendRaw(xml: String): Boolean = try {
        val stanza = PacketParserUtils.parseStanza<Stanza>(xml)
        connection?.sendStanza(stanza)
        core.addXmlLog(account.id, false, xml)
        true
    } catch (e: Exception) {
        core.addXmlLog(account.id, false, "error: ${e.message}")
        false
    }

    fun publishAvatar(bytes: ByteArray, mime: String) {
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val vcard = org.jivesoftware.smackx.vcardtemp.VCardManager.getInstanceFor(conn)
                val card = vcard.loadVCard(conn.user.asEntityBareJidIfPossible())
                card.setField("PHOTO", Base64.getEncoder().encodeToString(bytes))
                vcard.saveVCard(card)
            }
        }
    }

    fun publishNick(nick: String) {
        scope.launch {
            runCatching {
                val conn = connection ?: return@runCatching
                val vcard = org.jivesoftware.smackx.vcardtemp.VCardManager.getInstanceFor(conn)
                val card = vcard.loadVCard(conn.user.asEntityBareJidIfPossible())
                card.nickName = nick
                vcard.saveVCard(card)
            }
        }
    }
}
