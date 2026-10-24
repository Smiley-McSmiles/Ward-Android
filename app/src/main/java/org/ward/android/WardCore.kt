package org.ward.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val existing = dao.accountsNow()
        val color = listOf("#3584e4", "#9141ac", "#26a269", "#e66100", "#c88800", "#2190a4")[existing.size % 6]
        val acc = AccountEntity(jid = JidUtil.bare(jid), encryptedPassword = if (remember) SecretStore.encrypt(password) else "",
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

    fun passwordOf(acc: AccountEntity): String = SecretStore.decrypt(acc.encryptedPassword)

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
        if (all.isEmpty()) return "No connected accounts"
        return all.joinToString("\n") { s -> s.repairOmemo2() ?: "${s.account.jid}: not connected" }
    }

    internal fun onState(accountId: Long, state: ConnectionState) { setState(accountId, state) }
    internal fun onAdmin(accountId: Long, commands: List<AdminCommand>) {
        _adminCommands.value = _adminCommands.value + (accountId to commands)
        scope.launch { dao.setAdmin(accountId, commands.any { it.node.startsWith(NsAdmin.ADMIN) }) }
    }

    suspend fun ensureChat(accountId: Long, jid: String, type: String = "chat", name: String = ""): ChatEntity {
        val bare = JidUtil.bare(jid)
        val existing = dao.chat(accountId, bare)
        if (existing != null) return existing
        val acc = dao.account(accountId)
        val contact = dao.contact(accountId, bare)
        val display = name.ifBlank { contact?.name ?: if (type == "groupchat") bare.substringBefore('@') else JidUtil.nick(bare) }
        val defaultEnc = acc?.let { accountEncryptionDefault(it) } ?: "default"
        val encryption = when {
            defaultEnc != "default" -> defaultEnc
            type == "chat" -> "omemo"
            else -> "default"
        }
        val id = dao.insertChat(ChatEntity(accountId = accountId, jid = bare, type = type, name = display,
            encryption = encryption))
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
            replyTo = replyTo?.stanzaId ?: "", replyPreview = replyTo?.body ?: "", replyAuthor = replyTo?.senderName ?: "",
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

    suspend fun touchChat(chat: ChatEntity, msg: MessageEntity, outgoing: Boolean = false, notify: Boolean = true) {
        val active = isChatActive(chat.id)
        val mentions = msg.mentionsMe
        val newUnread = if (!outgoing && !active) chat.unread + 1 else chat.unread
        val newMentions = if (!outgoing && !active && mentions) chat.mentions + 1 else chat.mentions
        val preview = when {
            msg.retracted > 0 -> "Message deleted"
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

    suspend fun markRead(chat: ChatEntity) {
        if (!isChatActive(chat.id)) return
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
    suspend fun setEncryption(chat: ChatEntity, encryption: String) = dao.updateChat(chat.copy(encryption = encryption))
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

    suspend fun sendFile(chat: ChatEntity, uri: Uri): MessageEntity? {
        val session = sessions[chat.accountId] ?: return null
        val name = queryName(uri) ?: "file"
        val mime = context.contentResolver.getType(uri) ?: org.ward.android.xmpp.guessMime(name)
        val tmp = File(context.cacheDir, "upload-${System.currentTimeMillis()}-${name.replace('/', '_')}")
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        val acc = dao.account(chat.accountId) ?: return null
        val encryptedChat = effectiveEncryption(chat, acc) != "off"
        var uploadFile = tmp
        var link = ""
        var fileEncrypted = false
        if (encryptedChat) {
            val enc = File(context.cacheDir, tmp.name + ".aesgcm")
            val (key, iv) = withContext(Dispatchers.IO) { org.ward.android.util.FileCrypto.encrypt(tmp, enc) }
            uploadFile = enc
            val https = session.upload(enc, name + ".enc", "application/octet-stream") ?: run {
                dao.insertMessage(MessageEntity(chatId = chat.id, sender = acc.jid, direction = 1,
                    body = name, kind = "file", status = -1, error = "Upload failed"))
                return null
            }
            link = org.ward.android.util.FileCrypto.aesgcmUrl(https, iv, key)
            fileEncrypted = true
        } else {
            link = session.upload(tmp, name, mime) ?: return null
        }
        val id = JidUtil.randomId()
        val size = uploadFile.length()
        val attachments = listOf(mapOf("url" to link, "name" to name, "mime" to mime, "size" to size,
            "encrypted" to fileEncrypted))
        val msg = MessageEntity(chatId = chat.id, stanzaId = id, originId = id, msgId = id,
            sender = acc.jid, direction = 1, timestamp = System.currentTimeMillis(), body = name,
            kind = if (mime.startsWith("audio/")) "file" else "file",
            attachmentsJson = gson.toJson(attachments), status = 0)
        val local = dao.insertMessage(msg)
        val stored = msg.copy(id = local)
        touchChat(chat, stored, outgoing = true)
        session.sendFile(chat, stored, link, name, mime, size, fileEncrypted)
        return stored
    }

    suspend fun localFile(url: String, name: String, mime: String): File? {
        val cache = File(context.cacheDir, "files").apply { mkdirs() }
        val out = File(cache, org.ward.android.util.FileCrypto.cacheName(url, name))
        if (out.exists() && out.length() > 0) return out
        val link = org.ward.android.util.FileCrypto.parse(url)
        return try {
            withContext(Dispatchers.IO) {
                val source = if (link != null) link.httpsUrl else url
                val raw = File(cache, out.name + ".download")
                URL(source).openStream().use { input -> raw.outputStream().use { input.copyTo(it) } }
                if (link != null) {
                    org.ward.android.util.FileCrypto.decrypt(raw, out, link.key, link.iv)
                    raw.delete()
                } else {
                    raw.renameTo(out)
                }
                out
            }
        } catch (_: Exception) { null }
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
