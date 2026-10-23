package org.ward.android.xmpp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bouncycastle.crypto.generators.SCrypt
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore
import org.jxmpp.jid.impl.JidCreate
import org.ward.android.WardCore
import org.ward.android.data.AccountEntity
import org.ward.android.data.DeviceEntity
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.util.KeyHelper
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device and trust backups in the same passphrase-protected format as Ward for GTK.
 *
 * The GTK application stores the complete python-omemo key/value database in the file. We write
 * the same GTK keys (identity, X3DH state, pre-key id maps) plus an Android-only extension that
 * keeps the native Android state. That gives GTK a working identity/pre-key bundle while allowing
 * Android-to-Android restores to keep the full ratchet sessions.
 */
object OmemoBackup {
    const val MAGIC = "ward-backup-1"
    private const val SCRYPT_N = 32768
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val GTK_OMEMO2 = "urn:xmpp:omemo:2"
    private const val GTK_LEGACY = "eu.siacs.conversations.axolotl"
    private const val ANDROID_EXTENSION = "/org.ward.android/backup"
    private const val DEVICE_FILE = "ward-device.warddevice"
    private const val TRUST_FILE = "ward-trust.wardtrust"

    class BackupError(message: String) : Exception(message)

    data class X3dhPreKey(val id: Int, val privateKey: ByteArray, val publicKey: ByteArray)
    data class X3dhSignedPreKey(
        val id: Int, val privateKey: ByteArray, val publicKey: ByteArray,
        val signature: ByteArray, val timestamp: Long,
    )
    data class X3dhSnapshot(
        val identityPrivate: ByteArray, val signedPreKey: X3dhSignedPreKey,
        val preKeys: List<X3dhPreKey>,
    )

    data class LegacySnapshot(
        val deviceId: Int, val identityPrivate: ByteArray, val identityPublic: ByteArray,
        val signedPreKey: X3dhSignedPreKey, val preKeys: List<X3dhPreKey>,
    )

    private val gson = Gson()

    // ------------------------------------------------------------------ sealed files
    fun exportDevice(core: WardCore, account: AccountEntity, passphrase: String): ByteArray {
        require(passphrase.isNotBlank()) { "Choose a passphrase" }
        val store = buildGtkStore(core, account)
        val payload = linkedMapOf<String, Any?>(
            "jid" to account.jid,
            "store" to store,
        )
        return seal(payload, passphrase, "device")
    }

    suspend fun exportTrust(core: WardCore, passphrase: String): ByteArray {
        require(passphrase.isNotBlank()) { "Choose a passphrase" }
        val entries = JsonArray()
        for (account in core.accounts()) {
            val devices = core.dao.devicesForAccountNow(account.id)
            for (device in devices) {
                val level = when (device.trust) {
                    "verified" -> "VERIFIED"
                    "distrusted" -> "DISTRUSTED"
                    else -> continue
                }
                val fp = curveFingerprint(device.fingerprint, device.protocol) ?: continue
                entries.add(JsonObject().apply {
                    addProperty("jid", device.jid.lowercase())
                    addProperty("fp", fp.lowercase())
                    addProperty("level", level)
                })
            }
        }
        return seal(mapOf("entries" to entries), passphrase, "trust")
    }

    suspend fun importDevice(core: WardCore, account: AccountEntity, blob: ByteArray, passphrase: String): String {
        val payload = unseal(blob, passphrase, "device")
        val jid = payload.get("jid") as? String ?: throw BackupError("This file belongs to another account")
        if (jid.substringBefore('/').lowercase() != account.jid.lowercase()) {
            throw BackupError("This file belongs to another account")
        }
        @Suppress("UNCHECKED_CAST")
        val store = payload.get("store") as? Map<String, String>
            ?: throw BackupError("This file is damaged")
        if (store.isEmpty()) throw BackupError("This file is damaged")

        val wasOnline = core.isOnline(account.id)
        val session = core.session(account.id)
        val ownId = session?.omemo2?.deviceId
            ?: session?.omemo?.ownDevice?.deviceId
            ?: legacyDeviceId(store, account.jid)
            ?: 0

        // Native Android state, if this file came from Ward for Android. This is the only path
        // that preserves ratchet sessions across devices.
        val native = store[ANDROID_EXTENSION]?.let { runCatching { gson.fromJson(it, JsonObject::class.java) }.getOrNull() }
        if (native != null) {
            if (wasOnline) core.disconnect(account.id)
            restoreNative(core, account, native, ownId)
            if (wasOnline) core.connectAccount(account)
            return "Device imported from Ward for Android"
        }

        if (wasOnline) core.disconnect(account.id)
        val gtkDeviceId = gtkDeviceId(store, account.jid) ?: ownId.takeIf { it > 0 }
            ?: throw BackupError("The backup does not contain this device's identity")
        val omemo2 = gtkX3dh(store, GTK_OMEMO2)
        val legacy = gtkX3dh(store, GTK_LEGACY)
        val identity = gtkIdentityPrivate(store) ?: omemo2?.identityPrivate ?: legacy?.identityPrivate
            ?: throw BackupError("The backup does not contain an OMEMO identity")

        if (omemo2 != null) {
            Omemo2.writeImportedState(core, account, Omemo2KeySnapshot(
                deviceId = gtkDeviceId,
                identityPrivate = identity,
                identityPublicEd = Omemo2.identityPublicEd(identity),
                signedPreKeyId = omemo2.signedPreKey.id,
                signedPreKeyPrivate = omemo2.signedPreKey.privateKey,
                signedPreKeyPublic = omemo2.signedPreKey.publicKey,
                signedPreKeySignature = omemo2.signedPreKey.signature,
                signedPreKeyTimestamp = omemo2.signedPreKey.timestamp * 1000,
                preKeys = omemo2.preKeys.map { Omemo2PreKeySnapshot(it.id, it.privateKey, it.publicKey) }
            ))
        } else {
            File(core.context.filesDir, "omemo2/${account.id}/state.json").delete()
        }

        if (legacy != null) {
            writeLegacyKeys(core, account, LegacySnapshot(
                deviceId = gtkDeviceId,
                identityPrivate = identity,
                identityPublic = curvePublic(identity),
                signedPreKey = legacy.signedPreKey,
                preKeys = legacy.preKeys,
            ))
        } else {
            // Keep the legacy and OMEMO 2 identities in sync even when GTK did not include the
            // oldmemo backend in its backup.
            generateLegacyKeys(core, account, gtkDeviceId, identity)
        }
        if (wasOnline) core.connectAccount(account)
        return "Device imported from Ward for GTK"
    }

    suspend fun importTrust(core: WardCore, blob: ByteArray, passphrase: String): Int {
        val payload = unseal(blob, passphrase, "trust")
        @Suppress("UNCHECKED_CAST")
        val entries = payload.get("entries") as? List<Map<String, Any?>> ?: emptyList()
        var changed = 0
        for (entry in entries) {
            val jid = (entry["jid"] as? String)?.lowercase()?.substringBefore('/') ?: continue
            val fp = (entry["fp"] as? String)?.lowercase() ?: continue
            if (fp.length != 64) continue
            val trust = when (entry["level"]) {
                "VERIFIED" -> "verified"
                "DISTRUSTED" -> "distrusted"
                else -> continue
            }
            for (account in core.accounts()) {
                val session = core.session(account.id)
                for (device in core.dao.devicesOnce(account.id, jid)) {
                    val clean = device.fingerprint.replace(":", "").replace(" ", "").lowercase()
                    val converted = curveFingerprint(device.fingerprint, device.protocol)
                    if (clean != fp && converted != fp) continue
                    val trusted = trust == "verified"
                    runCatching { core.dao.setTrust(account.id, jid, device.deviceId, device.protocol, trust) }
                    if (session != null) {
                        session.trustDevice(jid, device.deviceId, device.fingerprint, trusted, device.protocol)
                    } else if (device.protocol == "omemo2") {
                        runCatching { updateOmemo2TrustFile(core, account.id, jid, device.deviceId.toInt(), trust) }
                    }
                    changed++
                }
            }
        }
        return changed
    }

    // ------------------------------------------------------------------ store construction
    private fun buildGtkStore(core: WardCore, account: AccountEntity): Map<String, String> {
        val session = core.session(account.id)
        val omemo2 = session?.omemo2?.snapshotKeys()
        val legacy = legacySnapshot(core, account, session?.omemo?.ownDevice?.deviceId)
        val deviceId = omemo2?.deviceId ?: legacy?.deviceId ?: 0
        val identity = omemo2?.identityPrivate ?: legacy?.identityPrivate
            ?: throw BackupError("OMEMO has not been initialized for this account yet")
        val identityEd = omemo2?.identityPublicEd ?: Omemo2.identityPublicEd(identity)

        val out = linkedMapOf<String, String>()
        out["/ikp/is_seed"] = "false"
        out["/ikp/key"] = jsonString(b64url(identity))
        out["/own_device_id"] = gson.toJson(deviceId)
        out["/devices/$account.jid/list"] = gson.toJson(listOf(deviceId))
        out["/devices/${account.jid}/$deviceId/identity_key"] = jsonString(b64url(identityEd))
        out["/devices/${account.jid}/$deviceId/namespaces"] = gson.toJson(listOf(GTK_OMEMO2, GTK_LEGACY))
        out["/devices/${account.jid}/$deviceId/active"] = gson.toJson(mapOf(GTK_OMEMO2 to true, GTK_LEGACY to true))
        out["/devices/${account.jid}/$deviceId/label"] = gson.toJson("Ward")

        omemo2?.let { snapshot ->
            val x = X3dhSnapshot(
                identityPrivate = identity,
                signedPreKey = X3dhSignedPreKey(
                    snapshot.signedPreKeyId, snapshot.signedPreKeyPrivate, snapshot.signedPreKeyPublic,
                    snapshot.signedPreKeySignature, snapshot.signedPreKeyTimestamp / 1000
                ),
                preKeys = snapshot.preKeys.map { X3dhPreKey(it.id, it.privateKey, it.publicKey) }
            )
            addX3dh(out, GTK_OMEMO2, x)
        }
        legacy?.let { snapshot ->
            val x = X3dhSnapshot(
                identityPrivate = identity,
                signedPreKey = snapshot.signedPreKey,
                preKeys = snapshot.preKeys
            )
            addX3dh(out, GTK_LEGACY, x)
        }

        // Keep the exact Android state so Android-to-Android restores keep existing sessions.
        val native = JsonObject().apply {
            addProperty("version", 1)
            addProperty("jid", account.jid)
            val state = File(core.context.filesDir, "omemo2/${account.id}/state.json")
            if (state.isFile) addProperty("omemo2", state.readText())
            val files = JsonObject()
            for ((rel, bytes) in legacyFiles(core, account)) {
                files.addProperty(rel, Base64.getEncoder().encodeToString(bytes))
            }
            add("legacy", files)
        }
        out[ANDROID_EXTENSION] = native.toString()
        return out
    }

    private fun addX3dh(out: MutableMap<String, String>, ns: String, state: X3dhSnapshot) {
        out["/$ns/x3dh"] = gson.toJson(x3dhJson(state))
        val preIds = linkedMapOf<String, Int>()
        for (p in state.preKeys) preIds[Base64.getEncoder().encodeToString(p.publicKey)] = p.id
        out["/$ns/pre_key_ids"] = gson.toJson(preIds)
        out["/$ns/pre_key_id_counter"] = gson.toJson(state.preKeys.maxOfOrNull { it.id } ?: 0)
        out["/$ns/signed_pre_key_ids"] = gson.toJson(linkedMapOf(
            Base64.getEncoder().encodeToString(state.signedPreKey.publicKey) to state.signedPreKey.id
        ))
    }

    private fun x3dhJson(state: X3dhSnapshot): JsonObject {
        fun bytes(value: ByteArray): String = String(value, Charsets.ISO_8859_1)
        val identity = JsonObject().apply {
            addProperty("version", "1.0.0")
            addProperty("secret", bytes(state.identityPrivate))
            addProperty("secret_type", "PRIV")
        }
        val spk = JsonObject().apply {
            addProperty("version", "1.0.0")
            addProperty("priv", bytes(state.signedPreKey.privateKey))
            addProperty("sig", bytes(state.signedPreKey.signature))
            addProperty("timestamp", state.signedPreKey.timestamp)
        }
        val pre = JsonArray()
        for (p in state.preKeys) pre.add(bytes(p.privateKey))
        return JsonObject().apply {
            addProperty("version", "1.0.0")
            add("identity_key", identity)
            add("signed_pre_key", spk)
            add("old_signed_pre_key", null)
            add("pre_keys", pre)
        }
    }

    private fun gtkIdentityPrivate(store: Map<String, String>): ByteArray? {
        val key = store["/ikp/key"]?.let { runCatching { b64urlDecode(gson.fromJson(it, String::class.java)) }.getOrNull() }
            ?: return null
        val seed = runCatching { gson.fromJson(store["/ikp/is_seed"], Boolean::class.java) }.getOrDefault(true)
        return if (seed) seedToPrivate(key) else key
    }

    private fun seedToPrivate(seed: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-512").digest(seed)
        val priv = digest.copyOfRange(0, 32)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = ((priv[31].toInt() and 127) or 64).toByte()
        return priv
    }

    private fun gtkDeviceId(store: Map<String, String>, jid: String): Int? {
        store["/own_device_id"]?.let { v -> runCatching { gson.fromJson(v, Int::class.java) }.getOrNull()?.let { return it } }
        val list = store["/devices/$jid/list"] ?: return null
        return runCatching {
            gson.fromJson(list, JsonArray::class.java).mapNotNull { it.asInt }.lastOrNull()
        }.getOrNull()
    }

    private fun gtkX3dh(store: Map<String, String>, ns: String): X3dhSnapshot? {
        val value = store["/$ns/x3dh"] ?: return null
        return runCatching {
            val root = gson.fromJson(value, JsonObject::class.java)
            val ik = root.getAsJsonObject("identity_key")
            val spk = root.getAsJsonObject("signed_pre_key")
            val preKeys = root.getAsJsonArray("pre_keys") ?: JsonArray()
            val secret = charBytes(ik.get("secret").asString)
            val identity = if (ik.get("secret_type")?.asString == "SEED") seedToPrivate(secret) else secret
            val signedPriv = charBytes(spk.get("priv").asString)
            val signedPub = curvePublic(signedPriv)
            val signedIds = gtkIdMap(store["/$ns/signed_pre_key_ids"])
            val signed = X3dhSignedPreKey(
                id = signedIds[Base64.getEncoder().encodeToString(signedPub)] ?: 1,
                privateKey = signedPriv,
                publicKey = signedPub,
                signature = charBytes(spk.get("sig").asString),
                timestamp = spk.get("timestamp").asLong
            )
            val preIds = gtkIdMap(store["/$ns/pre_key_ids"])
            var next = (preIds.values.maxOrNull() ?: 0) + 1
            val parsed = preKeys.map { e ->
                val priv = charBytes(e.asString)
                val pub = curvePublic(priv)
                val id = preIds[Base64.getEncoder().encodeToString(pub)] ?: next++
                X3dhPreKey(id, priv, pub)
            }
            X3dhSnapshot(identity, signed, parsed)
        }.getOrNull()
    }

    private fun gtkIdMap(value: String?): Map<String, Int> {
        if (value == null) return emptyMap()
        return runCatching {
            val obj = gson.fromJson(value, JsonObject::class.java)
            obj.entrySet().associate { it.key to it.value.asInt }
        }.getOrDefault(emptyMap())
    }

    private fun updateOmemo2TrustFile(core: WardCore, accountId: Long, jid: String, deviceId: Int, trust: String) {
        val file = File(core.context.filesDir, "omemo2/$accountId/state.json")
        if (!file.isFile) return
        val root = gson.fromJson(file.readText(), JsonObject::class.java)
        val trusted = root.getAsJsonObject("trusted") ?: JsonObject().also { root.add("trusted", it) }
        when (trust) {
            "verified", "trusted", "trusted" -> trusted.addProperty("$jid|$deviceId", true)
            "distrusted" -> trusted.addProperty("$jid|$deviceId", false)
            else -> trusted.remove("$jid|$deviceId")
        }
        file.writeText(gson.toJson(root))
    }

    // ------------------------------------------------------------------ native legacy store
    private fun legacySnapshot(core: WardCore, account: AccountEntity, deviceId: Int?): LegacySnapshot? {
        val id = deviceId ?: return null
        return runCatching {
            val store = legacyStore(core)
            val bare = JidCreate.entityBareFrom(account.jid)
            val device = OmemoDevice(bare, id)
            val pair = store.loadOmemoIdentityKeyPair(device)
            val spk = store.loadOmemoSignedPreKeys(device).values.maxByOrNull { it.id } ?: return null
            val pre = store.loadOmemoPreKeys(device).map { (keyId, record) ->
                X3dhPreKey(keyId, record.keyPair.privateKey.serialize(), rawPublic(record.keyPair.publicKey.serialize()))
            }
            LegacySnapshot(
                id,
                pair.privateKey.serialize(),
                pair.publicKey.publicKey.serialize(),
                X3dhSignedPreKey(spk.id, spk.keyPair.privateKey.serialize(),
                    rawPublic(spk.keyPair.publicKey.serialize()), spk.signature, spk.timestamp / 1000),
                pre,
            )
        }.getOrNull()
    }

    private fun legacyStore(core: WardCore): SignalFileBasedOmemoStore {
        // Reuse the service's store when it exists so its in-memory state sees the imported files.
        return runCatching {
            org.jivesoftware.smackx.omemo.signal.SignalOmemoService.getInstance().omemoStoreBackend
                as? SignalFileBasedOmemoStore
        }.getOrNull() ?: SignalFileBasedOmemoStore(File(core.context.filesDir, "omemo"))
    }

    private fun writeLegacyKeys(core: WardCore, account: AccountEntity, snapshot: LegacySnapshot) {
        val store = legacyStore(core)
        val bare = JidCreate.entityBareFrom(account.jid)
        for (old in store.localDeviceIdsOf(bare)) runCatching {
            store.purgeOwnDeviceKeys(OmemoDevice(bare, old))
        }
        val own = OmemoDevice(bare, snapshot.deviceId)
        val priv = Curve.decodePrivatePoint(snapshot.identityPrivate)
        val pub = Curve.decodePoint(byteArrayOf(0x05) + snapshot.identityPublic, 0)
        store.storeOmemoIdentityKeyPair(own, IdentityKeyPair(IdentityKey(pub), priv))
        val spkPub = Curve.decodePoint(byteArrayOf(0x05) + snapshot.signedPreKey.publicKey, 0)
        val spkPair = ECKeyPair(spkPub, Curve.decodePrivatePoint(snapshot.signedPreKey.privateKey))
        store.storeOmemoSignedPreKey(own, snapshot.signedPreKey.id,
            SignedPreKeyRecord(snapshot.signedPreKey.id, snapshot.signedPreKey.timestamp * 1000,
                spkPair, snapshot.signedPreKey.signature))
        val pre = TreeMap<Int, PreKeyRecord>()
        for (p in snapshot.preKeys) {
            val pubKey = Curve.decodePoint(byteArrayOf(0x05) + p.publicKey, 0)
            pre[p.id] = PreKeyRecord(p.id, ECKeyPair(pubKey, Curve.decodePrivatePoint(p.privateKey)))
        }
        store.storeOmemoPreKeys(own, pre)
    }

    private fun generateLegacyKeys(core: WardCore, account: AccountEntity, deviceId: Int, identityPrivate: ByteArray) {
        val store = legacyStore(core)
        val bare = JidCreate.entityBareFrom(account.jid)
        for (old in store.localDeviceIdsOf(bare)) runCatching {
            store.purgeOwnDeviceKeys(OmemoDevice(bare, old))
        }
        val own = OmemoDevice(bare, deviceId)
        val priv = Curve.decodePrivatePoint(identityPrivate)
        val publicRaw = curvePublic(identityPrivate)
        val pub = Curve.decodePoint(byteArrayOf(0x05) + publicRaw, 0)
        val pair = IdentityKeyPair(IdentityKey(pub), priv)
        store.storeOmemoIdentityKeyPair(own, pair)
        val spk = KeyHelper.generateSignedPreKey(pair, 1)
        store.storeOmemoSignedPreKey(own, spk.id, spk)
        val generated = TreeMap<Int, PreKeyRecord>()
        for (record in KeyHelper.generatePreKeys(1, 25)) generated[record.id] = record
        store.storeOmemoPreKeys(own, generated)
    }

    private fun restoreNative(core: WardCore, account: AccountEntity, native: JsonObject, fallbackId: Int) {
        val jid = native.get("jid")?.asString
        if (jid == null || jid.substringBefore('/').lowercase() != account.jid.lowercase()) {
            throw BackupError("This file belongs to another account")
        }
        val dir = File(core.context.filesDir, "omemo2/${account.id}")
        val state = native.get("omemo2")?.asString
        if (state != null) {
            dir.mkdirs()
            File(dir, "state.json").writeText(state)
        } else {
            File(dir, "state.json").delete()
        }
        val legacy = native.getAsJsonObject("legacy") ?: JsonObject()
        val root = File(core.context.filesDir, "omemo")
        val accountFiles = legacyFiles(core, account).keys
        for (rel in accountFiles) runCatching { File(root, rel).delete() }
        for ((rel, value) in legacy.entrySet()) {
            val target = File(root, rel)
            val safe = target.canonicalPath.startsWith(root.canonicalPath)
            if (!safe) continue
            target.parentFile?.mkdirs()
            target.writeBytes(Base64.getDecoder().decode(value.asString))
        }
    }

    private fun legacyFiles(core: WardCore, account: AccountEntity): Map<String, ByteArray> {
        val root = File(core.context.filesDir, "omemo")
        if (!root.isDirectory) return emptyMap()
        val needle = account.localpart
        return root.walkTopDown().filter { file ->
            file.isFile && (file.path.contains(needle) || file.path.contains(account.jid))
        }.associate { file ->
            root.toPath().relativize(file.toPath()).toString() to file.readBytes()
        }
    }

    private fun legacyDeviceId(store: Map<String, String>, jid: String): Int? =
        gtkDeviceId(store, jid)

    // ------------------------------------------------------------------ crypto
    internal fun seal(payload: Map<String, Any?>, passphrase: String, kind: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = SCrypt.generate(passphrase.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD((MAGIC + kind).toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(gson.toJson(payload).toByteArray(Charsets.UTF_8))
        val outer = JsonObject().apply {
            addProperty("format", MAGIC)
            addProperty("kind", kind)
            addProperty("salt", Base64.getEncoder().encodeToString(salt))
            addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
            addProperty("data", Base64.getEncoder().encodeToString(data))
        }
        return gson.toJson(outer).toByteArray(Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun unseal(blob: ByteArray, passphrase: String, kind: String): Map<String, Any?> {
        val outer = runCatching { gson.fromJson(String(blob, Charsets.UTF_8), JsonObject::class.java) }.getOrNull()
            ?: throw BackupError("The file is damaged")
        if (outer.get("format")?.asString != MAGIC || outer.get("kind")?.asString != kind) {
            throw BackupError("This is not a ${kind} file")
        }
        val salt = runCatching { Base64.getDecoder().decode(outer.get("salt").asString) }.getOrNull()
            ?: throw BackupError("The file is damaged")
        val nonce = runCatching { Base64.getDecoder().decode(outer.get("nonce").asString) }.getOrNull()
            ?: throw BackupError("The file is damaged")
        val data = runCatching { Base64.getDecoder().decode(outer.get("data").asString) }.getOrNull()
            ?: throw BackupError("The file is damaged")
        val key = SCrypt.generate(passphrase.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, 32)
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD((MAGIC + kind).toByteArray(Charsets.UTF_8))
            cipher.doFinal(data)
        }.getOrElse { throw BackupError("Wrong passphrase") }
        return runCatching {
            gson.fromJson(String(plain, Charsets.UTF_8), Map::class.java) as Map<String, Any?>
        }.getOrElse { throw BackupError("The file is damaged") }
    }

    // ------------------------------------------------------------------ small helpers
    private fun jsonString(value: String): String = gson.toJson(value)
    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)
    private fun b64urlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun charBytes(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
    private fun rawPublic(bytes: ByteArray): ByteArray =
        if (bytes.size == 33 && bytes[0] == 0x05.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

    private fun curvePublic(privateKey: ByteArray): ByteArray {
        val base = ByteArray(32).also { it[0] = 9 }
        return Curve25519.getInstance(Curve25519.BEST).calculateAgreement(privateKey, base)
    }

    /**
     * Convert an Ed25519 fingerprint (what Android stores for OMEMO 2) to the Curve25519
     * fingerprint used by Ward for GTK's trust backup. Legacy fingerprints are already Curve25519.
     */
    private fun curveFingerprint(fp: String, protocol: String): String? {
        val clean = fp.replace(":", "").replace(" ", "").lowercase()
        if (clean.length != 64) return null
        if (protocol != "omemo2") return clean
        return runCatching { Omemo2.curveFingerprintFromEdHex(clean) }.getOrNull()
    }

    fun defaultDeviceFileName(): String = DEVICE_FILE
    fun defaultTrustFileName(): String = TRUST_FILE
}
