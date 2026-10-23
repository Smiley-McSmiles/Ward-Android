package org.ward.android.xmpp

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smackx.pubsub.PubSubManager
import org.jivesoftware.smackx.pubsub.packet.PubSub
import org.jivesoftware.smackx.pubsub.packet.PubSubNamespace
import org.ward.android.WardCore
import org.ward.android.data.AccountEntity
import org.ward.android.data.DeviceEntity
import org.ward.omemo2.signal.*
import org.ward.omemo2.signal.ecc.Curve
import org.ward.omemo2.signal.ecc.ECPublicKey
import org.ward.omemo2.signal.state.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.ward.omemo2.signal.protocol.CiphertextMessage
import org.ward.omemo2.signal.protocol.PreKeySignalMessage
import org.ward.omemo2.signal.protocol.SignalMessage
import org.ward.omemo2.signal.ratchet.AliceSignalProtocolParameters
import org.ward.omemo2.signal.ratchet.RatchetingSession
import org.ward.omemo2.signal.util.KeyHelper
import org.ward.omemo2.signal.util.guava.Optional as SignalOptional
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * OMEMO 2 (urn:xmpp:omemo:2, XEP-0384 0.8+) using Dino's libomemo libsignal fork (relocated).
 *
 * OMEMO 2 keeps the same Double Ratchet as legacy OMEMO, but transfers the identity key in its
 * Ed25519 form, uses the <keys jid="..."> envelope and encrypts a whole SCE (XEP-0420) payload
 * instead of only the body.
 */
class Omemo2(
    private val core: WardCore,
    private val account: AccountEntity,
    private val conn: XMPPConnection,
    legacyDeviceId: Int?,
    legacyIdentity: ByteArray? = null,
) : SignalProtocolStore {

    companion object {
        const val NS = "urn:xmpp:omemo:2"
        const val DEVICES = "urn:xmpp:omemo:2:devices"
        const val BUNDLES = "urn:xmpp:omemo:2:bundles"
        const val PUBSUB = "http://jabber.org/protocol/pubsub"
        const val SCE = "urn:xmpp:sce:1"
        const val VERSION = 4
        private const val MIN_PRE_KEYS = 25
        private const val DEVICE_CACHE_MS = 5 * 60_000L
    }

    data class Content(
        val body: String,
        val deviceId: Int,
        val trust: String,
        val children: List<org.jivesoftware.smack.packet.ExtensionElement>,
        val empty: Boolean,
    )

    private data class Disk(
        var identity: String = "",
        var edIdentity: String = "",
        var legacyIdentity: String = "",
        var staleDeviceId: Int = 0,
        var cleanupVersion: Int = 0,
        var ignoredDevices: MutableList<Int> = mutableListOf(),
        var deviceId: Int = 0,
        var registrationId: Int = 0,
        var signedPreKeys: MutableMap<String, String> = mutableMapOf(),
        var preKeys: MutableMap<String, String> = mutableMapOf(),
        var nextPreKeyId: Int = 1,
        var sessions: MutableMap<String, String> = mutableMapOf(),
        var identities: MutableMap<String, String> = mutableMapOf(),
        var trusted: MutableMap<String, Boolean> = mutableMapOf(),
    )

    private data class CachedDevices(val at: Long, val devices: List<Int>)
    private data class CachedBundle(val at: Long, val bundle: PreKeyBundle?)

    // OMEMO state is per account: a single global store would give every account the same
    // identity, device id and ratchet sessions.
    private val dir = File(core.context.filesDir, "omemo2/${account.id}")
    private val stateFile = File(dir, "state.json")
    private val lock = Any()
    private val random = SecureRandom()
    private val gson = Gson()

    var deviceId: Int = 0
        private set
    private var registrationId: Int = 0
    private lateinit var identity: IdentityKeyPair
    private lateinit var edIdentity: ByteArray
    private var disk = Disk()

    private val signedPreKeys = LinkedHashMap<Int, SignedPreKeyRecord>()
    private val preKeys = LinkedHashMap<Int, PreKeyRecord>()
    private val sessions = HashMap<String, SessionRecord>()
    private val identities = HashMap<String, IdentityKey>()
    private val trusted = HashMap<String, Boolean>()
    private val deviceCache = HashMap<String, CachedDevices>()
    private val bundleCache = HashMap<String, CachedBundle>()

    @Volatile private var published = false
    private var configured = false
    @Volatile var ready = false
        private set

    init {
        synchronized(lock) {
            dir.mkdirs()
            disk = if (stateFile.isFile) runCatching { gson.fromJson(stateFile.readText(), Disk::class.java) }
                .getOrNull() ?: Disk() else Disk()
            if (disk.identity.isBlank()) {
                disk.identity = b64(KeyHelper.generateIdentityKeyPair().serialize())
            }
            if (disk.deviceId <= 0) {
                disk.deviceId = legacyDeviceId?.takeIf { it > 0 } ?: (random.nextInt(Int.MAX_VALUE - 1) + 1)
                disk.registrationId = disk.deviceId
            }
            deviceId = disk.deviceId
            registrationId = disk.registrationId.takeIf { it > 0 } ?: disk.deviceId
            val storedIdentity = runCatching { IdentityKeyPair(Base64.getDecoder().decode(disk.identity)) }.getOrNull()
            val storedEd = runCatching { disk.edIdentity.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) } }.getOrNull()
            val legacyB64 = legacyIdentity?.let { b64(it) }
            val mustMigrate = legacyIdentity != null && disk.legacyIdentity != legacyB64
            identity = when {
                // OMEMO 2 and legacy OMEMO must share one identity key: Ward/omemo stores a single
                // identity per device id, derived from the legacy bundle's Ed25519 sign bit.
                legacyIdentity != null -> IdentityKeyPair(legacyIdentity)
                storedIdentity != null && storedEd != null ->
                    runCatching { IdentityKeyPair(IdentityKey(Curve.decodePointEd(storedEd, 0)), storedIdentity.privateKey) }
                        .getOrElse { storedIdentity }
                else -> storedIdentity ?: KeyHelper.generateIdentityKeyPair()
            }
            edIdentity = identity.publicKey.publicKey.serializeEd()
            disk.identity = b64(identity.serialize())
            disk.edIdentity = b64(edIdentity)
            if (legacyB64 != null) disk.legacyIdentity = legacyB64
            if (mustMigrate) {
                // The old standalone OMEMO 2 identity is incompatible with the device identity other
                // clients cached from legacy OMEMO. Drop the sessions and keys created under it.
                sessions.clear()
                disk.sessions.clear()
                signedPreKeys.clear()
                disk.signedPreKeys.clear()
                preKeys.clear()
                disk.preKeys.clear()
                nextPreKeyId = 1
                // Move back to the legacy device id so contacts reuse the identity they already cached.
                val newId = legacyDeviceId?.takeIf { it > 0 }
                if (newId != null && newId != deviceId) {
                    disk.staleDeviceId = deviceId
                    disk.deviceId = newId
                    deviceId = newId
                    registrationId = newId
                }
            }
            // OMEMO 2 is addressed by device id in the envelope and in the published device list.
            // If the legacy device id changed, keep the same identity but publish under the current
            // legacy id; otherwise contacts look for a device that we never advertise.
            if (legacyDeviceId != null && legacyDeviceId > 0 && legacyDeviceId != deviceId) {
                disk.staleDeviceId = deviceId
                disk.deviceId = legacyDeviceId
                disk.registrationId = legacyDeviceId
                deviceId = legacyDeviceId
                registrationId = legacyDeviceId
            }
            for ((k, v) in disk.signedPreKeys) runCatching {
                signedPreKeys[k.toInt()] = SignedPreKeyRecord(Base64.getDecoder().decode(v))
            }
            for ((k, v) in disk.preKeys) runCatching {
                preKeys[k.toInt()] = PreKeyRecord(Base64.getDecoder().decode(v))
            }
            for ((k, v) in disk.sessions) runCatching {
                sessions[k] = SessionRecord(Base64.getDecoder().decode(v))
            }
            for ((k, v) in disk.identities) runCatching {
                val bytes = Base64.getDecoder().decode(v)
                identities[k] = if (bytes.size == 32) IdentityKey(Curve.decodePointEd(bytes, 0)) else IdentityKey(bytes, 0)
            }
            trusted.putAll(disk.trusted)
            ensureKeys()
            save()
            ready = true
        }
    }

    // ---------------------------------------------------------------- persistence
    private fun save() {
        disk.signedPreKeys = signedPreKeys.entries.associate { it.key.toString() to b64(it.value.serialize()) }.toMutableMap()
        disk.preKeys = preKeys.entries.associate { it.key.toString() to b64(it.value.serialize()) }.toMutableMap()
        disk.sessions = sessions.entries.associate { it.key to b64(it.value.serialize()) }.toMutableMap()
        disk.identities = identities.entries.associate { it.key to b64(it.value.serialize()) }.toMutableMap()
        disk.trusted = trusted
        disk.nextPreKeyId = nextPreKeyId
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(gson.toJson(disk))
        if (!tmp.renameTo(stateFile)) { stateFile.writeText(tmp.readText()); tmp.delete() }
    }

    private var nextPreKeyId: Int
        get() = disk.nextPreKeyId
        set(value) { disk.nextPreKeyId = value }

    private fun ensureKeys() {
        if (signedPreKeys.isEmpty()) {
            val spk = KeyHelper.generateSignedPreKey(identity, 1)
            signedPreKeys[spk.id] = spk
        }
        while (preKeys.size < MIN_PRE_KEYS) addPreKey()
    }

    private fun addPreKey(): PreKeyRecord {
        var id = nextPreKeyId
        if (id <= 0 || id == Int.MAX_VALUE) id = 1
        while (preKeys.containsKey(id)) {
            id++
            if (id <= 0) id = 1
        }
        val pk = KeyHelper.generatePreKeys(id, 1).first()
        preKeys[id] = pk
        nextPreKeyId = if (id >= Int.MAX_VALUE - 1) 1 else id + 1
        return pk
    }

    private fun key(address: SignalProtocolAddress) = "${address.name}|${address.deviceId}"

    // ---------------------------------------------------------------- SignalProtocolStore
    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        synchronized(lock) {
            val old = identities[key(address)]
            identities[key(address)] = identityKey
            disk.identities[key(address)] = b64(identityKey.publicKey.serializeEd())
            save()
            if (old != null && !old.equals(identityKey)) trusted[key(address)] = false
            return old != null && !old.equals(identityKey)
        }
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey,
                                   direction: IdentityKeyStore.Direction): Boolean {
        synchronized(lock) {
            val k = key(address)
            if (trusted[k] == false) return false
            val old = identities[k]
            if (old != null && !old.equals(identityKey)) return false
            if (core.settings.string("encryption.trust", "btbv") == "btbv") return true
            return trusted[k] == true
        }
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = synchronized(lock) { identities[key(address)] }

    override fun loadSession(address: SignalProtocolAddress, version: Int): SessionRecord =
        synchronized(lock) { sessions[key(address)] ?: SessionRecord(VERSION) }

    override fun getSubDeviceSessions(name: String): List<Int> = synchronized(lock) {
        sessions.keys.filter { it.startsWith("$name|") }.mapNotNull { it.substringAfter('|').toIntOrNull() }
    }

    override fun storeSession(address: SignalProtocolAddress, sessionRecord: SessionRecord) {
        synchronized(lock) {
            sessions[key(address)] = sessionRecord
            disk.sessions[key(address)] = b64(sessionRecord.serialize())
            save()
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = synchronized(lock) { sessions.containsKey(key(address)) }
    override fun deleteSession(address: SignalProtocolAddress) {
        synchronized(lock) { sessions.remove(key(address)); disk.sessions.remove(key(address)); save() }
    }
    override fun deleteAllSessions(name: String) {
        synchronized(lock) {
            sessions.keys.removeIf { it.startsWith("$name|") }
            disk.sessions.keys.removeIf { it.startsWith("$name|") }
            save()
        }
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = synchronized(lock) {
        preKeys[preKeyId] ?: throw InvalidKeyIdException("No pre key $preKeyId")
    }
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        synchronized(lock) { preKeys[preKeyId] = record; disk.preKeys[preKeyId.toString()] = b64(record.serialize()); save() }
    }
    override fun containsPreKey(preKeyId: Int): Boolean = synchronized(lock) { preKeys.containsKey(preKeyId) }
    override fun removePreKey(preKeyId: Int) {
        synchronized(lock) { preKeys.remove(preKeyId); disk.preKeys.remove(preKeyId.toString()); save() }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = synchronized(lock) {
        signedPreKeys[signedPreKeyId] ?: throw InvalidKeyIdException("No signed pre key $signedPreKeyId")
    }
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = synchronized(lock) { signedPreKeys.values.toList() }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        synchronized(lock) { signedPreKeys[signedPreKeyId] = record; disk.signedPreKeys[signedPreKeyId.toString()] = b64(record.serialize()); save() }
    }
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = synchronized(lock) { signedPreKeys.containsKey(signedPreKeyId) }
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        synchronized(lock) { signedPreKeys.remove(signedPreKeyId); disk.signedPreKeys.remove(signedPreKeyId.toString()); save() }
    }

    // ---------------------------------------------------------------- PEP
    private fun pubsub(type: IQ.Type, child: String): IQ {
        val ps = PubSub(conn.user.asBareJid(), type, PubSubNamespace.basic)
        ps.addExtension(RawExtension("pubsub-child", PUBSUB, child))
        return conn.sendIqRequestAndWaitForResponse(ps)
    }

    private fun publishOptions(maxItems: String, open: Boolean = true, includeMax: Boolean = true): String = buildString {
        append("<publish-options xmlns='$PUBSUB'><x xmlns='jabber:x:data' type='submit'>")
        append("<field var='FORM_TYPE' type='hidden'><value>http://jabber.org/protocol/pubsub#publish-options</value></field>")
        if (open) append("<field var='pubsub#access_model'><value>open</value></field>")
        if (includeMax) append("<field var='pubsub#max_items'><value>$maxItems</value></field>")
        append("</x></publish-options>")
    }

    private fun configureNode(node: String, maxItems: String) {
        val owner = "$PUBSUB#owner"
        val ps = PubSub(conn.user.asBareJid(), IQ.Type.set, PubSubNamespace.owner)
        val xml = "<configure node='$node'><x xmlns='jabber:x:data' type='submit'>" +
            "<field var='FORM_TYPE' type='hidden'><value>$owner#configure</value></field>" +
            "<field var='pubsub#access_model'><value>open</value></field>" +
            "<field var='pubsub#max_items'><value>$maxItems</value></field></x></configure>"
        ps.addExtension(RawExtension("configure", owner, xml))
        conn.sendIqRequestAndWaitForResponse<IQ>(ps)
    }

    fun publish(): Boolean {
        if (!conn.isConnected) return false
        synchronized(lock) {
            if (!configured) {
                runCatching { configureNode(DEVICES, "1") }
                runCatching { configureNode(BUNDLES, "max") }
            }
            var ids = emptyList<Int>()
            try {
                if (!Ed25519.validatePublicKeyFull(edIdentity, 0)) {
                    core.addXmlLog(account.id, false, "OMEMO 2 own Ed25519 identity is invalid; not publishing")
                    return false
                }
                if (disk.cleanupVersion < 1) cleanupOwnDevices()
                val fetched = runCatching { fetchDevices(account.jid, true) }.getOrDefault(emptyList())
                val known = if (fetched.isEmpty()) deviceCache[account.jid]?.devices ?: emptyList() else fetched
                val stale = disk.staleDeviceId
                ids = (known + deviceId).filter { it != stale && it !in disk.ignoredDevices }.distinct()
                val devicesXml = "<publish node='$DEVICES'><item id='current'><devices xmlns='$NS'>" +
                    ids.joinToString("") { "<device id='$it'/>" } + "</devices></item></publish>"

                // Publish the bundle first. Advertising a device without a downloadable bundle makes
                // other OMEMO clients report SenderNotFound for every message we send.
                val spk = signedPreKey()
                val bundleXml = "<publish node='$BUNDLES'><item id='$deviceId'><bundle xmlns='$NS'>" +
                    "<spk id='${spk.id}'>${b64(spk.keyPair.publicKey.serialize2())}</spk>" +
                    "<spks>${b64(spk.signature2)}</spks>" +
                    "<ik>${b64(edIdentity)}</ik><prekeys>" +
                    preKeys.entries.joinToString("") { (id, p) -> "<pk id='$id'>${b64(p.keyPair.publicKey.serialize2())}</pk>" } +
                    "</prekeys></bundle></item></publish>"
                val bundleOk = try {
                    pubsub(IQ.Type.set, bundleXml + publishOptions("max"))
                    true
                } catch (e: Exception) {
                    core.addXmlLog(account.id, false, "OMEMO 2 bundle publish with options failed: ${e.message}; retrying after node configuration")
                    runCatching { configureNode(BUNDLES, "max") }
                    val withMax = runCatching { pubsub(IQ.Type.set, bundleXml + publishOptions("max")) }.isSuccess
                    if (withMax) true else {
                        val openOnly = runCatching {
                            pubsub(IQ.Type.set, bundleXml + publishOptions("max", includeMax = false))
                        }.isSuccess
                        if (openOnly) true else runCatching { pubsub(IQ.Type.set, bundleXml) }.isSuccess
                    }
                }
                if (!bundleOk) throw IllegalStateException("The OMEMO 2 bundle could not be published")

                pubsub(IQ.Type.set, devicesXml + publishOptions("1"))
                deviceCache[account.jid] = CachedDevices(System.currentTimeMillis(), ids)
                published = true
                configured = true
                disk.cleanupVersion = 1
                disk.ignoredDevices.clear()
                if (disk.staleDeviceId != 0) disk.staleDeviceId = 0
                save()
                core.addXmlLog(account.id, false, "OMEMO 2 published device $deviceId")
                return true
            } catch (e: Exception) {
                // Do not leave a device advertised if its bundle could not be published.
                if (ids.isNotEmpty()) {
                    runCatching {
                        val without = ids.filter { it != deviceId }
                        val xml = "<publish node='$DEVICES'><item id='current'><devices xmlns='$NS'>" +
                            without.joinToString("") { "<device id='$it'/>" } + "</devices></item></publish>"
                        pubsub(IQ.Type.set, xml + publishOptions("1"))
                    }
                }
                core.addXmlLog(account.id, false, "OMEMO 2 publish failed: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}")
                return false
            }
        }
    }

    /** Remove OMEMO 2 devices whose published Ed25519 identity cannot be converted safely. */
    private fun cleanupOwnDevices() {
        val ids = runCatching { fetchDevices(account.jid, true) }.getOrDefault(emptyList())
        val invalid = mutableListOf<Int>()
        for (id in ids) {
            if (id == deviceId) continue
            val bundle = fetchBundle(account.jid, id) ?: continue
            val ed = runCatching { bundle.identityKey.publicKey.serializeEd() }.getOrNull() ?: continue
            if (!runCatching { Ed25519.validatePublicKeyFull(ed, 0) }.getOrDefault(false)) {
                invalid.add(id)
                runCatching {
                    PubSubManager.getInstanceFor(conn, conn.user.asBareJid()).getLeafNode(BUNDLES).deleteItem(id.toString())
                }
            }
        }
        disk.ignoredDevices = invalid.toMutableList()
        save()
    }

    private fun signedPreKey(): SignedPreKeyRecord {
        val current = signedPreKeys.values.maxByOrNull { it.id }!!
        return if (System.currentTimeMillis() - current.timestamp > 30L * 86_400_000L) rotateSignedPreKey() else current
    }

    private fun rotateSignedPreKey(): SignedPreKeyRecord {
        val id = (signedPreKeys.keys.maxOrNull() ?: 0) + 1
        val spk = KeyHelper.generateSignedPreKey(identity, id)
        signedPreKeys[id] = spk
        save()
        return spk
    }

    private fun fetchDevices(bareJid: String, force: Boolean = false): List<Int> {
        val now = System.currentTimeMillis()
        if (!force) deviceCache[bareJid]?.let { if (now - it.at < DEVICE_CACHE_MS) return it.devices }
        return try {
            val node = PubSubManager.getInstanceFor(conn, org.jxmpp.jid.impl.JidCreate.entityBareFrom(bareJid)).getLeafNode(DEVICES)
            var items: List<org.jivesoftware.smackx.pubsub.Item> = node.getItems("current")
            if (items.isEmpty()) items = node.getItems()
            val xml = items.firstOrNull()?.toXML(XmlEnvironment.EMPTY)?.toString() ?: ""
            val doc = parse(xml)
            val listed = doc.getElementsByTagNameNS(NS, "device").let { nl ->
                (0 until nl.length).mapNotNull { (nl.item(it) as? Element)?.getAttribute("id")?.toIntOrNull() }
            }
            // Ward (and some older clients) can have a stale OMEMO 2 device list that omits the
            // device whose bundle is actually published. Bundle items are authoritative enough for
            // us to start a session, so merge their item ids into the advertised list.
            val bundled = runCatching {
                val bundleNode = PubSubManager.getInstanceFor(conn,
                    org.jxmpp.jid.impl.JidCreate.entityBareFrom(bareJid)).getLeafNode(BUNDLES)
                val bundleItems: List<org.jivesoftware.smackx.pubsub.Item> = bundleNode.getItems()
                bundleItems.mapNotNull { it.id?.toIntOrNull() }
            }.getOrDefault(emptyList())
            val list = (listed + bundled).distinct()
            deviceCache[bareJid] = CachedDevices(now, list)
            list
        } catch (e: Exception) {
            deviceCache[bareJid]?.devices ?: emptyList()
        }
    }

    private fun fetchBundle(bareJid: String, devId: Int): PreKeyBundle? {
        val ck = "$bareJid|$devId"
        val now = System.currentTimeMillis()
        bundleCache[ck]?.let { if (now - it.at < DEVICE_CACHE_MS) return it.bundle }
        return try {
            val node = PubSubManager.getInstanceFor(conn, org.jxmpp.jid.impl.JidCreate.entityBareFrom(bareJid)).getLeafNode(BUNDLES)
            val items: List<org.jivesoftware.smackx.pubsub.Item> = node.getItems(devId.toString())
            val xml = items.firstOrNull()?.toXML(XmlEnvironment.EMPTY)?.toString() ?: return null
            val doc = parse(xml)
            val bundle = doc.getElementsByTagNameNS(NS, "bundle").item(0) as? Element ?: return null
            fun child(name: String): Element? =
                (0 until bundle.getElementsByTagNameNS(NS, name).length)
                    .map { bundle.getElementsByTagNameNS(NS, name).item(it) as? Element }
                    .firstOrNull { it?.parentNode == bundle }
            val spkEl = child("spk") ?: return null
            val spkId = spkEl.getAttribute("id").toIntOrNull() ?: return null
            val spk = Curve.decodePointMont(Base64.getDecoder().decode(spkEl.textContent.trim()), 0)
            val spks = Base64.getDecoder().decode(child("spks")?.textContent?.trim() ?: return null)
            val ik = IdentityKey(Curve.decodePointEd(Base64.getDecoder().decode(child("ik")?.textContent?.trim() ?: return null), 0))
            val prekeyEls = bundle.getElementsByTagNameNS(NS, "pk")
            val candidates = (0 until prekeyEls.length).mapNotNull { i ->
                val el = prekeyEls.item(i) as? Element ?: return@mapNotNull null
                val id = el.getAttribute("id").toIntOrNull() ?: return@mapNotNull null
                runCatching { id to Curve.decodePointMont(Base64.getDecoder().decode(el.textContent.trim()), 0) }.getOrNull()
            }
            val (pkId, pk) = candidates.randomOrNull() ?: return null
            val bundleObj = PreKeyBundle(devId, devId, pkId, pk, spkId, spk, spks, ik)
            bundleCache[ck] = CachedBundle(now, bundleObj)
            bundleObj
        } catch (e: Exception) {
            null
        }
    }

    private fun ownDevices(): List<Int> = fetchDevices(account.jid).ifEmpty { listOf(deviceId) }

    private fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    // ---------------------------------------------------------------- crypto
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info.toByteArray(Charsets.US_ASCII))
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun encryptPayload(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val km = hkdf(key, ByteArray(32), "OMEMO Payload", 80)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(km.copyOfRange(0, 32), "AES"), IvParameterSpec(km.copyOfRange(64, 80)))
        val ct = cipher.doFinal(plain)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(km.copyOfRange(32, 64), "HmacSHA256"))
        val tag = mac.doFinal(ct).copyOf(16)
        return ct to (key + tag)
    }

    private fun decryptPayload(ct: ByteArray, keyMaterial: ByteArray): ByteArray {
        require(keyMaterial.size == 48) { "bad OMEMO 2 key material" }
        val key = keyMaterial.copyOfRange(0, 32)
        val tag = keyMaterial.copyOfRange(32, 48)
        val km = hkdf(key, ByteArray(32), "OMEMO Payload", 80)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(km.copyOfRange(32, 64), "HmacSHA256"))
        val expected = mac.doFinal(ct)
        require(MessageDigest.isEqual(tag, expected.copyOf(16))) { "OMEMO 2 payload authentication failed" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(km.copyOfRange(0, 32), "AES"), IvParameterSpec(km.copyOfRange(64, 80)))
        return cipher.doFinal(ct)
    }


    private fun envelope(contentXml: String, to: String): ByteArray {
        val pad = buildString { repeat(random.nextInt(200)) { append(('A'.code + random.nextInt(26)).toChar()) } }
        return ("<envelope xmlns='$SCE'><content>$contentXml</content><rpad>$pad</rpad>" +
            "<from jid='${escape(account.jid)}'/><to jid='${escape(to)}'/></envelope>").toByteArray()
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(c)
        }
    }

    private fun bodyXml(body: String) = "<body xmlns='jabber:client'>${escape(body)}</body>"

    // ---------------------------------------------------------------- public operations
    fun encrypt(toBare: String, body: String): String? = encryptContent(toBare, bodyXml(body))

    fun encryptContent(toBare: String, contentXml: String): String? = synchronized(lock) {
        if (!published && !publish()) return null
        val recipients = LinkedHashMap<String, MutableList<Int>>()
        var contactDevices = 0
        for (dev in fetchDevices(toBare)) {
            if (toBare == account.jid && dev == deviceId) continue
            if (!ensureSession(toBare, dev)) continue
            recipients.getOrPut(toBare) { mutableListOf() }.add(dev)
            contactDevices++
        }
        if (contactDevices == 0) return null
        // Our other devices need a copy too, otherwise they cannot read the conversation.
        if (toBare != account.jid) {
            for (dev in ownDevices()) {
                if (dev == deviceId) continue
                if (!ensureSession(account.jid, dev)) continue
                recipients.getOrPut(account.jid) { mutableListOf() }.add(dev)
            }
        }
        if (recipients.isEmpty()) return null
        val (ct, keyMaterial) = encryptPayload(envelope(contentXml, toBare))
        val sb = StringBuilder("<encrypted xmlns='$NS'><header sid='$deviceId'>")
        for ((jid, list) in recipients) {
            sb.append("<keys jid='").append(escape(jid)).append("'>")
            for (dev in list) {
                val address = SignalProtocolAddress(jid, dev)
                val cipher = SessionCipher(this, address).also { it.setVersion(VERSION) }
                val msg = try { cipher.encrypt(keyMaterial) } catch (e: Exception) {
                    core.addXmlLog(account.id, false, "OMEMO 2 encrypt to $jid/$dev failed: ${e.message}")
                    continue
                }
                // We only know whether this was a key exchange after encrypting; the cipher stores it.
                val kexAttr = if (msg.type == CiphertextMessage.PREKEY_TYPE) " kex='true'" else ""
                sb.append("<key rid='").append(dev).append("'").append(kexAttr).append(">")
                    .append(b64(msg.serialize())).append("</key>")
            }
            sb.append("</keys>")
        }
        sb.append("</header><payload>").append(b64(ct)).append("</payload></encrypted>")
        sb.toString()
    }

    private fun ensureSession(bareJid: String, devId: Int): Boolean {
        val address = SignalProtocolAddress(bareJid, devId)
        val existing = sessions[key(address)]
        if (existing != null && !existing.isFresh) return true
        val bundle = fetchBundle(bareJid, devId) ?: return false
        return try {
            if (!verifyBundleSignature(bundle)) throw InvalidKeyException("Invalid OMEMO 2 signed pre key signature")
            createSession(address, bundle)
            true
        } catch (e: Exception) {
            core.addXmlLog(account.id, false, "OMEMO 2 session with $bareJid/$devId failed: ${e.message}")
            false
        }
    }

    /** OMEMO 2 transfers the identity key in Ed25519 form; verify the signed pre key with Ed25519. */
    private fun verifyBundleSignature(bundle: PreKeyBundle): Boolean {
        val ed = bundle.identityKey.publicKey.serializeEd()
        val msg = bundle.signedPreKey.serialize2()
        val sig = bundle.signedPreKeySignature
        return runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(ed, 0))
            verifier.update(msg, 0, msg.size)
            verifier.verifySignature(sig)
        }.getOrDefault(false)
    }

    private fun createSession(address: SignalProtocolAddress, bundle: PreKeyBundle) {
        if (!isTrustedIdentity(address, bundle.identityKey, IdentityKeyStore.Direction.SENDING)) {
            throw UntrustedIdentityException(address.name, bundle.identityKey)
        }
        val record = loadSession(address, VERSION)
        if (!record.isFresh) record.archiveCurrentState()
        // archiveCurrentState() starts a fresh state with version 0; without this, a replacement
        // session would silently fall back to the v3 Signal wire format.
        record.sessionState.setSessionVersion(VERSION)
        val ourBase = Curve.generateKeyPair()
        val theirSpk = bundle.signedPreKey
        val theirOtpk = bundle.preKey?.let { SignalOptional.of(it) } ?: SignalOptional.absent<ECPublicKey>()
        val params = AliceSignalProtocolParameters.newBuilder()
            .setOurBaseKey(ourBase)
            .setOurIdentityKey(identity)
            .setTheirIdentityKey(bundle.identityKey)
            .setTheirSignedPreKey(theirSpk)
            .setTheirRatchetKey(theirSpk)
            .setTheirOneTimePreKey(theirOtpk)
            .create()
        RatchetingSession.initializeSession(record.sessionState, params)
        record.sessionState.setUnacknowledgedPreKeyMessage(
            if (bundle.preKey != null) SignalOptional.of(bundle.preKeyId) else SignalOptional.absent(),
            bundle.signedPreKeyId, ourBase.publicKey)
        record.sessionState.setLocalRegistrationId(registrationId)
        record.sessionState.setRemoteRegistrationId(bundle.registrationId)
        record.sessionState.setAliceBaseKey(ourBase.publicKey.serialize())
        saveIdentity(address, bundle.identityKey)
        storeSession(address, record)
    }

    fun decrypt(msg: Message, raw: String): Content? {
        val encrypted = msg.getExtensionElement("encrypted", NS) as? org.jivesoftware.smack.packet.StandardExtensionElement
            ?: return decryptDom(msg, raw)
        val keys = encrypted.getElements("keys")
        val ours = keys.firstOrNull { it.getAttributeValue("jid")?.substringBefore('/') == account.jid } ?: return null
        val keyEl = ours.getElements("key").firstOrNull { it.getAttributeValue("rid")?.toIntOrNull() == deviceId } ?: return null
        val blob = runCatching { Base64.getDecoder().decode(keyEl.text?.trim() ?: return null) }.getOrNull() ?: return null
        var kex = keyEl.getAttributeValue("kex") == "true"
        val senderBare = msg.from?.asEntityBareJidIfPossible()?.toString() ?: account.jid
        // The sender's device id is in the <header sid>.
        val header = encrypted.getElements("header").firstOrNull()
        val senderDev = header?.getAttributeValue("sid")?.toIntOrNull() ?: 0
        val address = SignalProtocolAddress(senderBare, senderDev)
        val toJid = msg.to?.asEntityBareJidIfPossible()?.toString() ?: account.jid
        return try {
            val cipher = SessionCipher(this, address).also { it.setVersion(VERSION) }
            val keyMaterial = if (kex) {
                val pk = PreKeySignalMessage(blob, VERSION)
                val usedPreKey = pk.preKeyId
                val out = cipher.decrypt(pk)
                usedPreKey?.takeIf { it.isPresent }?.get()?.let { id ->
                    removePreKey(id)
                    addPreKey()
                    published = false
                    runCatching { publish() }
                }
                out
            } else {
                try { cipher.decrypt(SignalMessage(blob, true)) }
                catch (e: Exception) {
                    // Tolerate senders that omit kex='true' on a key exchange.
                    val pk = PreKeySignalMessage(blob, VERSION)
                    val out = cipher.decrypt(pk)
                    pk.preKeyId?.takeIf { it.isPresent }?.get()?.let { id ->
                        removePreKey(id); addPreKey(); published = false; runCatching { publish() }
                    }
                    out
                }
            }
            val payload = encrypted.getElements("payload").firstOrNull()?.text?.trim()
            val trust = trustOf(address)
            if (payload.isNullOrBlank()) {
                Content("", senderDev, trust, emptyList(), true)
            } else {
                val ct = Base64.getDecoder().decode(payload)
                val plain = decryptPayload(ct, keyMaterial)
                val (body, children) = parseEnvelope(String(plain, Charsets.UTF_8), senderBare, toJid)
                Content(body, senderDev, trust, children, false)
            }
        } catch (e: Exception) {
            core.addXmlLog(account.id, true, "OMEMO 2 decrypt failed from $senderBare/$senderDev: ${e.stackTraceToString()}")
            null
        }
    }

    /** Fallback for stanzas whose <encrypted> element was not parsed by Smack. */
    private fun decryptDom(msg: Message, raw: String): Content? {
        if (!raw.contains("urn:xmpp:omemo:2")) return null
        val doc = runCatching { parse(raw) }.getOrNull() ?: return null
        val enc = doc.getElementsByTagNameNS(NS, "encrypted").item(0) as? Element ?: return null
        val keys = enc.getElementsByTagNameNS(NS, "keys")
        for (i in 0 until keys.length) {
            val k = keys.item(i) as? Element ?: continue
            if (k.getAttribute("jid").substringBefore('/') != account.jid) continue
            val keyEls = k.getElementsByTagNameNS(NS, "key")
            for (j in 0 until keyEls.length) {
                val ke = keyEls.item(j) as? Element ?: continue
                if (ke.getAttribute("rid").toIntOrNull() != deviceId) continue
                val blob = runCatching { Base64.getDecoder().decode(ke.textContent.trim()) }.getOrNull() ?: continue
                val kex = ke.getAttribute("kex") == "true"
                val sid = (enc.getElementsByTagNameNS(NS, "header").item(0) as? Element)?.getAttribute("sid")?.toIntOrNull() ?: 0
                val senderBare = msg.from?.asEntityBareJidIfPossible()?.toString() ?: account.jid
                val toJid = msg.to?.asEntityBareJidIfPossible()?.toString() ?: account.jid
                val address = SignalProtocolAddress(senderBare, sid)
                return try {
                    val cipher = SessionCipher(this, address).also { it.setVersion(VERSION) }
                    val km = if (kex) cipher.decrypt(PreKeySignalMessage(blob, VERSION)) else cipher.decrypt(SignalMessage(blob, true))
                    val payloadEl = enc.getElementsByTagNameNS(NS, "payload").item(0) as? Element
                    val payload = payloadEl?.textContent?.trim()
                    val trust = trustOf(address)
                    if (payload.isNullOrBlank()) Content("", sid, trust, emptyList(), true)
                    else {
                        val (body, children) = parseEnvelope(String(decryptPayload(Base64.getDecoder().decode(payload), km), Charsets.UTF_8), senderBare, toJid)
                        Content(body, sid, trust, children, false)
                    }
                } catch (e: Exception) { null }
            }
        }
        return null
    }

    private fun trustOf(address: SignalProtocolAddress): String {
        val id = getIdentity(address) ?: return "undecided"
        val fp = hex(id.publicKey.serializeEd())
        val stored = runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { core.dao.devicesOnce(account.id, address.name) }
        }.getOrNull()?.firstOrNull { it.deviceId == address.deviceId.toLong() }
        if (stored == null) {
            if (core.settings.string("encryption.trust", "btbv") == "btbv") {
                core.scope.launch(Dispatchers.IO) {
                    runCatching { core.dao.insertDevice(DeviceEntity(account.id, address.name,
                        address.deviceId.toLong(), fp, trust = "trusted", label = "OMEMO 2", protocol = "omemo2")) }
                }
                return "trusted"
            }
            return "undecided"
        }
        return stored.trust
    }

    private fun parseEnvelope(xml: String, from: String, to: String): Pair<String, List<org.jivesoftware.smack.packet.ExtensionElement>> {
        val doc = parse(xml)
        val env = doc.documentElement
        if (env.namespaceURI != SCE || env.localName != "envelope") throw IllegalArgumentException("not an SCE envelope")
        val frm = doc.getElementsByTagNameNS(SCE, "from").item(0) as? Element
        val toEl = doc.getElementsByTagNameNS(SCE, "to").item(0) as? Element
        if (frm == null || frm.getAttribute("jid").substringBefore('/').lowercase() != from.lowercase())
            throw IllegalArgumentException("SCE sender mismatch")
        if (toEl != null && toEl.getAttribute("jid").substringBefore('/').lowercase() != to.lowercase())
            throw IllegalArgumentException("SCE recipient mismatch")
        val content = doc.getElementsByTagNameNS(SCE, "content").item(0) as? Element
        var body = ""
        val children = mutableListOf<org.jivesoftware.smack.packet.ExtensionElement>()
        if (content != null) {
            val nl = content.childNodes
            for (i in 0 until nl.length) {
                val el = nl.item(i) as? Element ?: continue
                if (el.namespaceURI == "jabber:client" && el.localName == "body") body = el.textContent
                else runCatching { children.add(toExtension(el)) }
            }
        }
        return body to children
    }

    private fun toExtension(el: Element): org.jivesoftware.smack.packet.StandardExtensionElement {
        val b = org.jivesoftware.smack.packet.StandardExtensionElement.builder(
            el.localName, el.namespaceURI ?: "")
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName != "xmlns") b.addAttribute(a.nodeName, a.nodeValue)
        }
        val childNodes = el.childNodes
        var hasElementChild = false
        for (i in 0 until childNodes.length) {
            val child = childNodes.item(i)
            if (child is Element) { hasElementChild = true; b.addElement(toExtension(child)) }
        }
        if (!hasElementChild) b.setText(el.textContent)
        return b.build()
    }

    fun forgetDevice(bareJid: String, devId: Int) {
        synchronized(lock) {
            val prefix = "$bareJid|$devId"
            sessions.remove(prefix)
            identities.remove(prefix)
            trusted.remove(prefix)
            disk.sessions.remove(prefix)
            disk.identities.remove(prefix)
            disk.trusted.remove(prefix)
            save()
        }
    }

    fun forget(bareJid: String) {
        synchronized(lock) {
            val prefix = "$bareJid|"
            sessions.keys.removeIf { it.startsWith(prefix) }
            identities.keys.removeIf { it.startsWith(prefix) }
            trusted.keys.removeIf { it.startsWith(prefix) }
            disk.sessions.keys.removeIf { it.startsWith(prefix) }
            disk.identities.keys.removeIf { it.startsWith(prefix) }
            disk.trusted.keys.removeIf { it.startsWith(prefix) }
            save()
        }
    }

    fun rememberTrust(bareJid: String, devId: Int, trust: String) {
        synchronized(lock) {
            val k = "$bareJid|$devId"
            when (trust) {
                "trusted", "verified" -> trusted[k] = true
                "distrusted" -> trusted[k] = false
                else -> trusted.remove(k)
            }
            save()
        }
    }

    /** Device ids and identity fingerprints for the device sheet. */
    fun loadDeviceInfo(bareJid: String): List<Pair<Int, String>> = synchronized(lock) {
        val out = mutableListOf<Pair<Int, String>>()
        for (dev in fetchDevices(bareJid, true)) {
            val b = fetchBundle(bareJid, dev) ?: continue
            out.add(dev to hex(b.identityKey.publicKey.serializeEd()))
        }
        out
    }

    fun fingerprint(): String = hex(edIdentity)
    fun deviceIds(): List<Int> = ownDevices()
    fun removePublished() {
        runCatching { PubSubManager.getInstanceFor(conn, conn.user.asBareJid()).deleteNode(DEVICES) }
        runCatching { PubSubManager.getInstanceFor(conn, conn.user.asBareJid()).deleteNode(BUNDLES) }
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}
