package org.ward.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ward.android.data.*
import org.ward.android.util.Colors
import org.ward.android.util.JidUtil
import org.ward.android.util.SecretStore
import org.ward.android.xmpp.*
import org.ward.android.xmpp.initExtensionProviders
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class ConnectionState(val state: String = "offline", val step: String = "", val error: String = "")

class WardCore private constructor(val context: Context) {
    val db = WardDatabase.get(context)
    val dao = db.dao()
    val settings = SettingsStore(context)
    val gson = Gson()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<Long, XmppSession>()
    private val downloadJobs = ConcurrentHashMap<String, Deferred<File?>>()
    private val _states = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<Long, ConnectionState>> = _states
    private val _typing = MutableStateFlow<Map<Long, String>>(emptyMap())
    val typing: StateFlow<Map<Long, String>> = _typing
    private val _activeChat = MutableStateFlow<Long?>(null)
    val activeChat: StateFlow<Long?> = _activeChat
    @Volatile private var appForeground = false
    private val _adminCommands = MutableStateFlow<Map<Long, List<AdminCommand>>>(emptyMap())
    val adminCommands: StateFlow<Map<Long, List<AdminCommand>>> = _adminCommands
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread
    var notificationSink: ((MessageEntity, ChatEntity, AccountEntity) -> Unit)? = null
    var notificationDismissSink: ((Long) -> Unit)? = null
    var accountList: List<AccountEntity> = emptyList()

    init {
        initExtensionProviders()
        scope.launch { dao.accounts().collect { accountList = it; recalcUnread() } }
        scope.launch { dao.chats().collect { c -> _unread.value = c.sumOf { it.unread } } }
    }

    private suspend fun recalcUnread() {
        runCatching {
            val chats = dao.chatsNow()
            _unread.value = chats.sumOf { it.unread }
        }
    }

    fun state(accountId: Long): ConnectionState = _states.value[accountId] ?: ConnectionState()
    private fun setState(accountId: Long, s: ConnectionState) {
        _states.value = _states.value + (accountId to s)
    }
    fun setActiveChat(id: Long?) { _activeChat.value = id }
    fun setAppForeground(value: Boolean) { appForeground = value }
    /** True only while the activity is visible and this chat is the one on screen. */
    fun isChatActive(chatId: Long): Boolean = appForeground && _activeChat.value == chatId
    fun setTyping(chatId: Long, who: String) {
        _typing.value = if (who.isBlank()) _typing.value - chatId else _typing.value + (chatId to who)
    }

    suspend fun addAccount(jid: String, password: String, remember: Boolean = true, options: Map<String, String> = emptyMap(),
                           enabled: Boolean = true): AccountEntity {
        val bare = JidUtil.bare(jid)
        val existing = dao.accountsNow()
        if (existing.any { it.jid.equals(bare, ignoreCase = true) }) {
            throw IllegalStateException("This account is already added.")
        }
        val color = listOf("#3584e4", "#9141ac", "#26a269", "#e66100", "#c88800", "#2190a4")[existing.size % 6]
        val encryptedPassword = if (remember) {
            SecretStore.encryptOrNull(password)
                ?: throw IllegalStateException("Android secure storage is unavailable, so the password could not be saved.")
        } else ""
        val acc = AccountEntity(jid = bare, encryptedPassword = encryptedPassword,
            color = color, optionsJson = gson.toJson(options), nickname = existing.firstOrNull()?.nickname ?: "",
            enabled = enabled)
        val id = dao.insertAccount(acc)
        return acc.copy(id = id)
    }

    suspend fun updateAccount(acc: AccountEntity) = dao.updateAccount(acc)

    suspend fun setAccountOption(acc: AccountEntity, key: String, value: String): AccountEntity {
        val map = runCatching { gson.fromJson<Map<String, String>>(acc.optionsJson, object : TypeToken<Map<String, String>>() {}.type) }
            .getOrNull()?.toMutableMap() ?: mutableMapOf()
        map[key] = value
        val updated = acc.copy(optionsJson = gson.toJson(map))
        dao.updateAccount(updated)
        accountList = dao.accountsNow()
        return updated
    }
    fun accountOption(acc: AccountEntity, key: String): String =
        runCatching { gson.fromJson<Map<String, String>>(acc.optionsJson, object : TypeToken<Map<String, String>>() {}.type) }
            .getOrNull()?.get(key) ?: ""
    suspend fun deleteAccount(acc: AccountEntity) {
        disconnect(acc.id)
        dao.deleteAccount(acc.id)
    }
    suspend fun account(id: Long): AccountEntity? = dao.account(id)
    suspend fun accounts(): List<AccountEntity> = dao.accountsNow()

    fun passwordOf(acc: AccountEntity): String {
        val plain = SecretStore.decrypt(acc.encryptedPassword)
        // Upgrade credentials written by older builds, which could contain Base64 or bare
        // plaintext when the keystore was unavailable. This only writes after a successful
        // encryption, so a failing keystore never rewrites or loses the password.
        if (plain.isNotBlank() && !acc.encryptedPassword.startsWith("enc:")) {
            SecretStore.encryptOrNull(plain)?.let { stored ->
                scope.launch { dao.setAccountPassword(acc.id, stored) }
            }
        }
        return plain
    }

    suspend fun connectAccount(acc: AccountEntity, password: String = passwordOf(acc)) {
        if (password.isBlank()) {
            setState(acc.id, ConnectionState("locked", "", "Enter your password to connect."))
            return
        }
        disconnect(acc.id)
        val session = XmppSession(this, acc, password)
        sessions[acc.id] = session
        session.start()
    }

    fun disconnect(accountId: Long) {
        sessions.remove(accountId)?.stop()
        setState(accountId, ConnectionState("offline"))
    }
    fun session(accountId: Long): XmppSession? = sessions[accountId]
    fun allSessions(): List<XmppSession> = sessions.values.toList()
    fun isOnline(accountId: Long): Boolean = sessions[accountId]?.online == true

    /**
     * Security wipe after the configured number of failed app-lock attempts. Removes local
     * encryption keys and message history, but keeps accounts, contacts and chat shells so the
     * user can sign in again and start a new conversation.
     */
    suspend fun wipeEncryptionAndHistory() = withContext(Dispatchers.IO) {
        allSessions().forEach { runCatching { it.stop() } }
        sessions.clear()
        dao.clearAllMessages()
        dao.clearAllReceipts()
        dao.clearAllDevices()
        dao.clearAllPgpKeys()
        dao.resetAllChats()
        runCatching { File(context.filesDir, "omemo").deleteRecursively() }
        runCatching { File(context.filesDir, "omemo2").deleteRecursively() }
        runCatching { File(context.filesDir, "pgp").deleteRecursively() }
        runCatching { context.filesDir.listFiles { f -> f.name.startsWith("omemo2-") }?.forEach { it.delete() } }
        runCatching { File(context.filesDir, "reset-legacy-omemo").delete() }
        recalcUnread()
    }

    fun repairOmemo2(): String {
        val all = allSessions()
        if (all.isEmpty()) return "No connected accounts. Connect an account and try again."
        return all.joinToString("\n") { s -> s.repairOmemo2() ?: "${s.account.jid}: not connected" }
    }

    /**
     * Reconcile local OMEMO state with the server's published device lists. This deliberately
     * fetches from PEP first, because the local database can contain devices that were removed
     * from the server months ago (or bundles left behind by a reset).
     */
    /** Re-sync every open chat from the server archive, optionally for one account. */
    suspend fun resyncHistory(accountId: Long? = null): String {
        val targets = allSessions().filter { it.online && (accountId == null || it.account.id == accountId) }
        if (targets.isEmpty()) return "Connect an account first."
        val lines = mutableListOf<String>()
        for (session in targets) lines += session.resyncAllChats()
        return lines.joinToString("\n")
    }

    suspend fun cleanUpKeys(): String {
        val all = allSessions()
        if (all.isEmpty()) return "No connected accounts. Connect an account and try again."
        val lines = mutableListOf<String>()
        for (session in all) {
            lines += session.cleanUpDevices()
            runCatching { session.loadDevices(session.account.jid) }
        }
        return lines.joinToString("\n")
    }

    internal fun onState(accountId: Long, state: ConnectionState) { setState(accountId, state) }
    internal fun onAdmin(accountId: Long, commands: List<AdminCommand>) {
        _adminCommands.value = _adminCommands.value + (accountId to commands)
        scope.launch { dao.setAdmin(accountId, commands.any { it.node.startsWith(NsAdmin.ADMIN) }) }
    }

    suspend fun ensureChat(accountId: Long, jid: String, type: String = "chat", name: String = ""): ChatEntity {
        val bare = JidUtil.bare(jid)
        val existing = dao.chat(accountId, bare)
        val existingContact = dao.contact(accountId, bare)
        if (existing != null) {
            val avatar = existingContact?.avatar.orEmpty()
            if (existing.avatar.isBlank() && avatar.isNotBlank()) {
                val updated = existing.copy(avatar = avatar)
                dao.updateChat(updated)
                return updated
            }
            return existing
        }
        val acc = dao.account(accountId)
        val contact = existingContact
        val display = name.ifBlank { contact?.name ?: if (type == "groupchat") bare.substringBefore('@') else JidUtil.nick(bare) }
        val defaultEnc = acc?.let { accountEncryptionDefault(it) } ?: "default"
        val encryption = when {
            defaultEnc != "default" -> defaultEnc
            type == "chat" -> "default"
            else -> "default"
        }
        val id = dao.insertChat(ChatEntity(accountId = accountId, jid = bare, type = type, name = display,
            encryption = encryption, avatar = contact?.avatar ?: ""))
        return dao.chat(id) ?: dao.chat(accountId, bare)!!
    }

    private fun accountEncryptionDefault(acc: AccountEntity): String {
        val opts = runCatching { gson.fromJson<Map<String, String>>(acc.optionsJson, object : TypeToken<Map<String, String>>() {}.type) }.getOrNull() ?: emptyMap()
        return opts["encryption"] ?: "default"
    }

    fun effectiveEncryption(chat: ChatEntity, acc: AccountEntity? = null): String {
        if (chat.encryption == "omemo2") return "omemo"
        if (chat.encryption != "default") return chat.encryption
        val perAccount = acc?.let { accountEncryptionDefault(it) } ?: "default"
        if (perAccount != "default") return perAccount
        return when (settings.string("encryption.default", "always")) {
            "off" -> "off"; "available" -> "omemo"; else -> "omemo"
        }
    }

    suspend fun sendText(chat: ChatEntity, text: String, replyTo: MessageEntity? = null): MessageEntity {
        val acc = dao.account(chat.accountId) ?: error("No account")
        val id = JidUtil.randomId()
        val msg = MessageEntity(chatId = chat.id, stanzaId = id, originId = id, msgId = id,
            sender = acc.jid, senderJid = acc.jid, senderName = acc.displayName, direction = 1,
            timestamp = System.currentTimeMillis(), body = text, kind = if (text.startsWith("/me ")) "me" else "text",
            replyTo = replyTo?.stanzaId ?: "",
            replyPreview = replyTo?.let { if (it.kind == "me") it.body.removePrefix("/me ").trimStart() else it.body } ?: "",
            replyAuthor = replyTo?.senderName ?: "",
            status = 0)
        val localId = dao.insertMessage(msg)
        val stored = msg.copy(id = localId)
        touchChat(chat, stored, outgoing = true)
        val session = sessions[chat.accountId]
        if (session != null && session.online) {
            session.sendChat(chat, stored)
        }
        return stored
    }

    /**
     * Forward a message to another chat. Text is copied as-is; attachments are forwarded as
     * their existing links (the encrypted aesgcm link keeps its key in the fragment), so files do
     * not need to be uploaded again.
     */
    suspend fun forward(target: ChatEntity, message: MessageEntity) {
        val type = object : TypeToken<List<Map<String, Any>>>() {}.type
        val atts: List<Map<String, Any>> = runCatching { gson.fromJson<List<Map<String, Any>>>(message.attachmentsJson, type) }
            .getOrNull() ?: emptyList()
        val names = atts.mapNotNull { it["name"]?.toString() }.toSet()
        val parts = mutableListOf<String>()
        if (message.body.isNotBlank() && message.body !in names) parts.add(message.body)
        for (a in atts) {
            val url = a["url"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val name = a["name"]?.toString().orEmpty()
            parts.add(if (url.startsWith("aesgcm://") && name.isNotBlank()) "$name\n$url" else url)
        }
        if (parts.isNotEmpty()) sendText(target, parts.joinToString("\n\n"))
    }

    suspend fun touchChat(chat: ChatEntity, msg: MessageEntity, outgoing: Boolean = false, notify: Boolean = true) {
        val active = isChatActive(chat.id)
        val mentions = msg.mentionsMe
        val newUnread = if (!outgoing && !active) chat.unread + 1 else chat.unread
        val newMentions = if (!outgoing && !active && mentions) chat.mentions + 1 else chat.mentions
        val preview = when {
            msg.retracted > 0 -> "Message deleted"
            msg.kind == "me" && msg.body.isNotBlank() ->
                msg.body.removePrefix("/me ").trimStart().lineSequence().firstOrNull() ?: ""
            msg.body.isNotBlank() -> msg.body.lineSequence().firstOrNull() ?: ""
            msg.attachmentsJson != "[]" -> "📎 Attachment"
            else -> chat.preview
        }
        val updated = chat.copy(lastActivity = msg.timestamp, preview = preview,
            previewSender = if (outgoing) "You" else msg.senderName.ifBlank { msg.sender },
            unread = newUnread, mentions = newMentions, draft = if (active) chat.draft else chat.draft)
        dao.updateChat(updated)
        if (!outgoing && notify && !active) {
            if (settings.bool("notify.enabled")) {
                dao.account(chat.accountId)?.let { notificationSink?.invoke(msg, updated, it) }
            }
        }
    }

    suspend fun markRead(chat: ChatEntity, force: Boolean = false) {
        if (!force && !isChatActive(chat.id)) return
        notificationDismissSink?.invoke(chat.id)
        val msgs = dao.messagesNow(chat.id, 500)
        val lastIn = msgs.lastOrNull { it.direction == 0 }
        val updated = chat.copy(unread = 0, mentions = 0, lastReadId = lastIn?.id ?: chat.lastReadId)
        dao.updateChat(updated)
        if (lastIn != null && settings.bool("chats.send_markers")) sessions[chat.accountId]?.sendDisplayed(chat, lastIn)
    }

    suspend fun setDraft(chat: ChatEntity, draft: String) {
        dao.setDraft(chat.id, draft)
    }

    suspend fun updateMessage(m: MessageEntity) = dao.updateMessage(m)
    suspend fun message(id: Long) = dao.message(id)
    suspend fun findRef(chatId: Long, ref: String) = dao.findByRef(chatId, ref)

    suspend fun setPinned(chat: ChatEntity, pinned: Boolean) {
        dao.updateChat(chat.copy(pinned = pinned))
    }
    suspend fun setMuted(chat: ChatEntity, until: Long) = dao.updateChat(chat.copy(mutedUntil = until))
    suspend fun setArchived(chat: ChatEntity, archived: Boolean) = dao.updateChat(chat.copy(archived = archived))
    suspend fun setEncryption(chat: ChatEntity, encryption: String) {
        dao.updateChat(chat.copy(encryption = encryption))
        // A mode switch is a good moment to discard a stale OMEMO device list so both the
        // contact's current devices and our own published device are picked up.
        sessions[chat.accountId]?.refreshOmemoDevices(chat.jid)
    }
    suspend fun setNotify(chat: ChatEntity, notify: String) = dao.updateChat(chat.copy(notify = notify))
    suspend fun clearHistory(chat: ChatEntity) {
        dao.clearMessages(chat.id)
        dao.updateChat(chat.copy(clearedAt = System.currentTimeMillis(), lastActivity = System.currentTimeMillis(),
            preview = "", previewSender = "", unread = 0, mentions = 0, lastReadId = 0))
    }
    suspend fun closeChat(chat: ChatEntity) {
        dao.clearMessages(chat.id)
        dao.updateChat(chat.copy(open = false, clearedAt = System.currentTimeMillis(), lastActivity = System.currentTimeMillis(),
            preview = "", previewSender = "", unread = 0, mentions = 0, lastReadId = 0))
    }

    /** Remove selected chats from the local list while keeping contacts, groups and server history. */
    suspend fun deleteChat(chat: ChatEntity) {
        notificationDismissSink?.invoke(chat.id)
        dao.clearMessages(chat.id)
        if (chat.isRequest) dao.deleteRequest(chat.accountId, chat.jid)
        dao.updateChat(chat.copy(open = false, archived = false, pinned = false, isRequest = false,
            draft = "", preview = "", previewSender = "", unread = 0, mentions = 0,
            lastActivity = System.currentTimeMillis(), lastReadId = 0, clearedAt = System.currentTimeMillis()))
    }

    suspend fun setContactPresence(accountId: Long, jid: String, presence: String, status: String) {
        val c = dao.contact(accountId, jid) ?: ContactEntity(accountId, JidUtil.bare(jid))
        dao.insertContact(c.copy(presence = presence, status = status))
    }

    suspend fun blockContact(accountId: Long, jid: String, block: Boolean) {
        session(accountId)?.setBlocked(jid, block)
        val c = dao.contact(accountId, jid) ?: ContactEntity(accountId, JidUtil.bare(jid))
        dao.insertContact(c.copy(blocked = block))
    }

    suspend fun acceptRequest(accountId: Long, jid: String) {
        session(accountId)?.acceptSubscription(jid)
        dao.deleteRequest(accountId, jid)
    }
    suspend fun declineRequest(accountId: Long, jid: String, block: Boolean = false) {
        session(accountId)?.declineSubscription(jid)
        if (block) blockContact(accountId, jid, true)
        dao.deleteRequest(accountId, jid)
    }

    suspend fun openChatWith(accountId: Long, jid: String): ChatEntity = ensureChat(accountId, jid)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun sendFile(chat: ChatEntity, uri: Uri): MessageEntity? {
        val session = sessions[chat.accountId] ?: return null
        var name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        var mime = context.contentResolver.getType(uri) ?: org.ward.android.xmpp.guessMime(name)
        var tmp = File(context.cacheDir, "upload-${System.currentTimeMillis()}-${name.replace('/', '_')}")
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        if (mime.startsWith("image/") && mime != "image/gif") {
            withContext(Dispatchers.IO) {
                val setting = settings.string("attachments.image_compression", "large")
                val stripMetadata = settings.bool("privacy.strip_metadata")
                if (setting != "off" || stripMetadata) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(tmp.absolutePath, options)
                    val large = tmp.length() > 1_000_000L || options.outWidth > 2000 || options.outHeight > 2000
                    if (setting == "always" || large || stripMetadata) {
                        runCatching {
                            val bounds = BitmapFactory.Options().apply { inSampleSize = 1 }
                            while (options.outWidth / bounds.inSampleSize > 2400 || options.outHeight / bounds.inSampleSize > 2400) {
                                bounds.inSampleSize *= 2
                            }
                            val decoded = BitmapFactory.decodeFile(tmp.absolutePath, bounds) ?: return@runCatching
                            val exif = androidx.exifinterface.media.ExifInterface(tmp)
                            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                            val matrix = Matrix()
                            when (orientation) {
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            }
                            val bitmap = if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) decoded
                                else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                            val format = when {
                                mime == "image/png" -> Bitmap.CompressFormat.PNG
                                mime == "image/webp" -> Bitmap.CompressFormat.WEBP
                                else -> Bitmap.CompressFormat.JPEG
                            }
                            val compressed = File(context.cacheDir, "compressed-${tmp.name}")
                            compressed.outputStream().use { out ->
                                bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 85, out)
                            }
                            if (compressed.length() > 0 && (setting == "always" || compressed.length() < tmp.length() || stripMetadata)) {
                                tmp.delete()
                                compressed.renameTo(tmp)
                                if (format == Bitmap.CompressFormat.JPEG) mime = "image/jpeg"
                            } else compressed.delete()
                        }
                    }
                }
            }
        }
        if (mime.startsWith("video/")) {
            val quality = settings.string("attachments.video_quality", "medium")
            if (quality != "original") {
                withContext(Dispatchers.IO) {
                    val height = when (quality) { "low" -> 240; "high" -> 720; else -> 360 }
                    runCatching {
                        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(tmp)))
                            .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(height))))
                            .build()
                        val out = File(context.cacheDir, "video-${System.currentTimeMillis()}.mp4")
                        val latch = java.util.concurrent.CountDownLatch(1)
                        val result = java.util.concurrent.atomic.AtomicReference<File?>()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                val transformer = Transformer.Builder(context)
                                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                                    .build()
                                transformer.addListener(object : Transformer.Listener {
                                    override fun onTransformationCompleted(inputMediaItem: MediaItem) {
                                        result.set(out); latch.countDown()
                                    }
                                    override fun onTransformationError(inputMediaItem: MediaItem, exception: Exception) {
                                        latch.countDown()
                                    }
                                })
                                transformer.start(item, out.absolutePath)
                            }.onFailure { latch.countDown() }
                        }
                        latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
                        if (result.get() == null) out.delete()
                        if (out.exists() && out.length() > 0 && out.length() < tmp.length()) {
                            tmp.delete()
                            out.renameTo(tmp)
                            mime = "video/mp4"
                            name = name.substringBeforeLast('.', name) + ".mp4"
                        } else out.delete()
                    }
                }
            }
        }
        val acc = dao.account(chat.accountId) ?: return null
        val id = JidUtil.randomId()
        val placeholderAttachments = listOf(mapOf(
            "url" to "local://${tmp.absolutePath}", "local" to tmp.absolutePath,
            "name" to name, "mime" to mime, "size" to tmp.length(), "pending" to true
        ))
        val placeholder = MessageEntity(chatId = chat.id, stanzaId = id, originId = id, msgId = id,
            sender = acc.jid, senderJid = acc.jid, senderName = acc.displayName, direction = 1,
            timestamp = System.currentTimeMillis(), body = name, kind = "file",
            attachmentsJson = gson.toJson(placeholderAttachments), status = 0)
        val localId = dao.insertMessage(placeholder)
        if (localId < 0) return null
        val stored = placeholder.copy(id = localId)
        touchChat(chat, stored, outgoing = true)

        suspend fun fail(reason: String): MessageEntity {
            val failedAttachments = placeholderAttachments.map { it - "pending" }
            val failed = stored.copy(status = -1, error = reason,
                attachmentsJson = gson.toJson(failedAttachments))
            dao.updateMessage(failed)
            return failed
        }

        val encryptedChat = effectiveEncryption(chat, acc) != "off"
        var link = ""
        var fileEncrypted = false
        if (encryptedChat) {
            val enc = File(context.cacheDir, tmp.name + ".aesgcm")
            val keys = withContext(Dispatchers.IO) {
                runCatching { org.ward.android.util.FileCrypto.encrypt(tmp, enc) }.getOrNull()
            } ?: return fail("Could not encrypt file")
            val https = withContext(Dispatchers.IO) {
                runCatching { session.upload(enc, name + ".enc", "application/octet-stream") }.getOrNull()
            } ?: return fail("Upload failed")
            link = org.ward.android.util.FileCrypto.aesgcmUrl(https, keys.second, keys.first)
            fileEncrypted = true
        } else {
            link = withContext(Dispatchers.IO) {
                runCatching { session.upload(tmp, name, mime) }.getOrNull()
            } ?: return fail("Upload failed")
        }
        val attachments = listOf(mapOf(
            "url" to link, "local" to tmp.absolutePath, "name" to name, "mime" to mime,
            "size" to tmp.length(), "encrypted" to fileEncrypted
        ))
        val updated = stored.copy(attachmentsJson = gson.toJson(attachments), status = 0)
        dao.updateMessage(updated)
        session.sendFile(chat, updated, link, name, mime, tmp.length(), fileEncrypted)
        return updated
    }

    private fun cachedFileForUrl(cache: File, url: String): File? =
        cache.listFiles()?.firstOrNull {
            it.isFile && org.ward.android.util.FileCrypto.isCompleteCacheFile(it.name, url, it.length())
        }

    suspend fun localFile(url: String, name: String, mime: String): File? {
        val cache = File(context.cacheDir, "files").apply { mkdirs() }
        val noMedia = File(cache, ".nomedia")
        if (settings.bool("attachments.hide_gallery")) noMedia.createNewFile() else noMedia.delete()

        // The same attachment can be composed more than once (for example after a carbon copy or
        // when a LazyColumn item is recreated). Join the in-flight download instead of starting a
        // second transfer that writes the same temp file and makes the progress bar flicker.
        // In-flight first: a file that is still being written must never be handed out.
        downloadJobs[url]?.takeIf { !it.isCompleted }?.let { return it.await() }
        cachedFileForUrl(cache, url)?.let { return it }
        val job = downloadJobs.compute(url) { _, current ->
            if (current != null && !current.isCompleted) {
                current
            } else {
                scope.async(Dispatchers.IO) { downloadToCache(cache, url, name) }
            }
        } ?: return cachedFileForUrl(cache, url)
        if (job.isCompleted) downloadJobs.remove(url, job)
        else job.invokeOnCompletion { downloadJobs.remove(url, job) }
        return job.await()
    }

    private suspend fun downloadToCache(cache: File, url: String, name: String): File? {
        // Close the race where another caller finished between our initial lookup and starting
        // this job: reuse the completed file instead of downloading it again.
        cachedFileForUrl(cache, url)?.let { return it }
        val out = File(cache, org.ward.android.util.FileCrypto.cacheName(url, name))
        val link = org.ward.android.util.FileCrypto.parse(url)
        // Keep a partial transfer around so a large file that drops near the end resumes with a
        // Range request instead of downloading the whole thing again.
        val raw = File(cache, out.name + ".download")
        downloadProgress.update { it + (url to 0f) }
        return try {
            withContext(Dispatchers.IO) {
                val source = if (link != null) link.httpsUrl else url
                var existing = if (raw.isFile) raw.length() else 0L
                val connection = URL(source).openConnection() as java.net.HttpURLConnection
                if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
                val code = connection.responseCode
                if (code == 416) {
                    // The server no longer has the bytes we kept: start over instead of failing
                    // forever on the same stale partial file.
                    raw.delete()
                    connection.disconnect()
                    throw java.io.IOException("stale partial download for $url")
                }
                val resumed = existing > 0L && code == java.net.HttpURLConnection.HTTP_PARTIAL
                if (!resumed) {
                    existing = 0L
                    raw.delete()
                }
                val total = if (resumed) existing + connection.contentLengthLong else connection.contentLengthLong
                var copied = existing
                var lastUpdate = 0L
                connection.getInputStream().use { input ->
                    java.io.FileOutputStream(raw, resumed).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0 && (copied == total || System.currentTimeMillis() - lastUpdate > 100)) {
                                downloadProgress.update { progress ->
                                    progress + (url to (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                                lastUpdate = System.currentTimeMillis()
                            }
                        }
                    }
                }
                connection.disconnect()
                // Publish only a complete file: decrypt (or rename) into .part, then move it into
                // place in one step so no caller can ever see a half-written attachment.
                val part = File(cache, out.name + ".part")
                part.delete()
                if (link != null) {
                    try {
                        org.ward.android.util.FileCrypto.decrypt(raw, part, link.key, link.iv)
                    } catch (e: Exception) {
                        part.delete()
                        raw.delete()
                        throw e
                    }
                    raw.delete()
                } else if (!raw.renameTo(part)) {
                    raw.copyTo(part, overwrite = true)
                    raw.delete()
                }
                out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                downloadProgress.update { it + (url to 1f) }
                out
            }
        } catch (_: Exception) {
            null
        } finally {
            downloadProgress.update { it - url }
        }
    }

    private fun queryName(uri: Uri): String? = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }

    suspend fun react(chat: ChatEntity, msg: MessageEntity, emoji: String) {
        val reactions = parseReactions(msg.reactionsJson).toMutableList()
        val mine = reactions.firstOrNull { it.mine }
        if (mine != null) {
            val set = mine.emoji.toMutableList()
            if (emoji in set) set.remove(emoji) else set.add(emoji)
            reactions.removeAll { it.mine }
            if (set.isNotEmpty()) reactions.add(ReactionSet(set, "You", true))
        } else {
            reactions.add(ReactionSet(listOf(emoji), "You", true))
        }
        val updated = msg.copy(reactionsJson = gson.toJson(reactions))
        dao.updateMessage(updated)
        sessions[chat.accountId]?.sendReaction(chat, msg, reactions.filter { it.mine }.flatMap { it.emoji })
    }

    suspend fun edit(chat: ChatEntity, msg: MessageEntity, text: String) {
        val updated = msg.copy(body = text, edited = true)
        dao.updateMessage(updated)
        sessions[chat.accountId]?.sendEdit(chat, msg, text)
    }

    suspend fun retract(chat: ChatEntity, msg: MessageEntity, forEveryone: Boolean) {
        if (forEveryone) {
            sessions[chat.accountId]?.sendRetraction(chat, msg)
            dao.updateMessage(msg.copy(retracted = 1))
        } else {
            dao.updateMessage(msg.copy(retracted = 2))
        }
    }

    fun addXmlLog(accountId: Long, incoming: Boolean, text: String) {
        val entry = XmlLogEntry(System.currentTimeMillis(), accountId, incoming, text)
        _xml.value = (_xml.value + entry).takeLast(500)
    }
    fun clearXml() { _xml.value = emptyList() }
    private val _call = MutableStateFlow<org.ward.android.xmpp.CallUiState?>(null)
    val call: StateFlow<org.ward.android.xmpp.CallUiState?> = _call
    fun setCallState(state: org.ward.android.xmpp.CallUiState?) { _call.value = state }

    private val _xml = MutableStateFlow<List<XmlLogEntry>>(emptyList())
    val xml: StateFlow<List<XmlLogEntry>> = _xml
    val downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun sendRawXml(accountId: Long, xml: String): Boolean = sessions[accountId]?.sendRaw(xml) ?: false

    suspend fun shutdown() {
        sessions.values.forEach { it.stop() }
        sessions.clear()
    }

    companion object {
        @Volatile private var instance: WardCore? = null
        fun init(context: Context): WardCore = instance ?: synchronized(this) {
            instance ?: WardCore(context.applicationContext).also { instance = it }
        }
        fun get(): WardCore = instance ?: error("WardCore not initialized")
    }
}

data class XmlLogEntry(val time: Long, val accountId: Long, val incoming: Boolean, val text: String)
data class ReactionSet(val emoji: List<String>, val who: String, val mine: Boolean)
fun parseReactions(json: String): List<ReactionSet> = runCatching {
    WardCore.get().gson.fromJson<List<ReactionSet>>(json, object : TypeToken<List<ReactionSet>>() {}.type)
}.getOrNull() ?: emptyList()

object NsAdmin {
    const val ADMIN = "http://jabber.org/protocol/admin#"
    const val COMMANDS = "http://jabber.org/protocol/commands"
}
