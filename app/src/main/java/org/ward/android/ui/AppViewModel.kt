package org.ward.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.ward.android.WardCore
import org.ward.android.data.*
import org.ward.android.xmpp.OmemoBackup
import org.ward.android.util.JidUtil

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val core = WardCore.get()
    val settings = core.settings
    val accounts: StateFlow<List<AccountEntity>> = core.dao.accounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chats: StateFlow<List<ChatEntity>> = core.dao.chats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val contacts: StateFlow<List<ContactEntity>> = core.dao.contacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val requests: StateFlow<List<RequestEntity>> = core.dao.requests().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val states = core.states
    val typing = core.typing
    val xml = core.xml

    private val _selectedChat = MutableStateFlow<Long?>(null)
    val selectedChat: StateFlow<Long?> = _selectedChat
    val selectedChatEntity: StateFlow<ChatEntity?> = _selectedChat.flatMapLatest { id ->
        if (id == null) flowOf(null) else core.dao.chatFlow(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val messages: StateFlow<List<MessageEntity>> = _selectedChat.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else core.dao.messages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var search = ""
    var filter = "all"
    var showArchived = false

    init {
        viewModelScope.launch {
            core.dao.chats().collect { list ->
                if (_selectedChat.value != null && list.none { it.id == _selectedChat.value }) _selectedChat.value = null
            }
        }
    }

    fun selectChat(id: Long?) {
        _selectedChat.value = id
        core.setActiveChat(id)
        if (id != null) viewModelScope.launch {
            core.dao.chat(id)?.let { core.markRead(it) }
        }
    }

    fun selectChatById(id: Long) = selectChat(id)

    fun openChat(accountId: Long, jid: String, type: String = "chat") = viewModelScope.launch {
        val chat = core.ensureChat(accountId, jid, type)
        selectChat(chat.id)
    }

    fun send(text: String, replyTo: MessageEntity? = null) {
        val chat = selectedChatEntity.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            core.sendText(chat, text.trimEnd(), replyTo)
            core.dao.setDraft(chat.id, "")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun sendFile(uri: Uri) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.sendFile(chat, uri) }
    }

    /** Upload several picked files in order, so their messages keep the picker's order. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun sendFiles(uris: List<Uri>) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch {
            for (uri in uris) core.sendFile(chat, uri)
        }
    }

    fun forward(target: ChatEntity, message: MessageEntity) {
        viewModelScope.launch { core.forward(target, message) }
    }

    fun setDraft(text: String) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.setDraft(chat, text) }
    }

    fun typing(state: String) {
        val chat = selectedChatEntity.value ?: return
        core.session(chat.accountId)?.sendTyping(chat, state)
    }

    fun react(msg: MessageEntity, emoji: String) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.react(chat, msg, emoji) }
    }
    fun edit(msg: MessageEntity, text: String) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.edit(chat, msg, text) }
    }
    fun retract(msg: MessageEntity, everyone: Boolean) {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.retract(chat, msg, everyone) }
    }
    fun markRead() {
        val chat = selectedChatEntity.value ?: return
        viewModelScope.launch { core.markRead(chat) }
    }

    fun addAccount(jid: String, password: String, remember: Boolean, options: Map<String, String> = emptyMap(),
                   backup: ByteArray? = null, backupPassphrase: String = "",
                   onError: (String) -> Unit = {}) = viewModelScope.launch {
        try {
            // Keep the account disabled while the backup is installed. Otherwise the
            // foreground service can auto-connect it and generate a fresh OMEMO identity
            // before the imported keys are in place.
            val acc = core.addAccount(jid, password, remember, options, enabled = backup == null)
            if (backup != null) {
                try {
                    withContext(Dispatchers.IO) {
                        OmemoBackup.importDevice(core, acc, backup, backupPassphrase)
                    }
                } catch (e: Exception) {
                    runCatching { core.deleteAccount(acc) }
                    onError(e.message ?: "Could not import the device backup")
                    return@launch
                }
                // Connect explicitly first, then enable the account so the service sees an
                // existing session and does not race us with another connection.
                core.connectAccount(acc, password)
                core.updateAccount(acc.copy(enabled = true))
            } else {
                core.connectAccount(acc, password)
            }
        } catch (e: Exception) {
            onError(e.message ?: "Could not add the account")
        }
    }

    fun connect(acc: AccountEntity, password: String? = null) = viewModelScope.launch {
        core.connectAccount(acc, password ?: core.passwordOf(acc))
    }
    fun disconnect(acc: AccountEntity) = core.disconnect(acc.id)
    fun deleteAccount(acc: AccountEntity) = viewModelScope.launch { core.deleteAccount(acc) }

    fun addContact(accountId: Long, jid: String, name: String) {
        core.session(accountId)?.addContact(jid, name)
        viewModelScope.launch { core.ensureChat(accountId, jid) }
    }
    fun addContactAndOpen(accountId: Long, jid: String, name: String) = viewModelScope.launch {
        core.ensureChat(accountId, jid)
        core.session(accountId)?.addContact(jid, name)
        openChat(accountId, jid)
    }

    fun setPresence(show: String, status: String = "") {
        accounts.value.forEach { core.session(it.id)?.setPresence(show, status) }
    }

    fun leaveGroup(chat: ChatEntity) = viewModelScope.launch {
        core.session(chat.accountId)?.leaveRoom(chat)
        selectChat(null)
    }
    fun setSubject(chat: ChatEntity, subject: String) = viewModelScope.launch {
        core.session(chat.accountId)?.setSubject(chat, subject)
    }
    fun setPinned(chat: ChatEntity, pinned: Boolean) = viewModelScope.launch { core.setPinned(chat, pinned) }
    fun setMuted(chat: ChatEntity, until: Long) = viewModelScope.launch { core.setMuted(chat, until) }
    fun setArchived(chat: ChatEntity, archived: Boolean) = viewModelScope.launch { core.setArchived(chat, archived) }
    fun setEncryption(chat: ChatEntity, value: String) = viewModelScope.launch { core.setEncryption(chat, value) }
    fun setNotify(chat: ChatEntity, value: String) = viewModelScope.launch { core.setNotify(chat, value) }
    fun clearHistory(chat: ChatEntity) = viewModelScope.launch { core.clearHistory(chat) }
    fun closeChat(chat: ChatEntity) = viewModelScope.launch { core.closeChat(chat); selectChat(null) }

    fun acceptRequest(req: RequestEntity) = viewModelScope.launch {
        core.acceptRequest(req.accountId, req.jid)
        core.ensureChat(req.accountId, req.jid)
    }
    fun declineRequest(req: RequestEntity, block: Boolean = false) = viewModelScope.launch {
        core.declineRequest(req.accountId, req.jid, block)
    }
    fun block(accountId: Long, jid: String, block: Boolean) = viewModelScope.launch {
        core.blockContact(accountId, jid, block)
    }
    fun removeContact(accountId: Long, jid: String) = viewModelScope.launch {
        core.session(accountId)?.removeContact(jid)
    }
    fun forgetDevice(accountId: Long, jid: String, deviceId: Long, protocol: String) {
        core.session(accountId)?.forgetDevice(jid, deviceId, protocol)
    }
    fun repairOmemo2(onResult: (String) -> Unit) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { core.repairOmemo2() }.getOrElse { it.stackTraceToString() } }
        onResult(result)
    }
    fun createGroup(accountId: Long, room: String, name: String, private: Boolean, description: String, invitees: List<String>) = viewModelScope.launch {
        val chat = core.ensureChat(accountId, room, "groupchat", name)
        val acc = accounts.value.firstOrNull { it.id == accountId }
        val updated = chat.copy(name = name, ourNick = acc?.nickname?.ifBlank { acc.localpart } ?: "")
        core.dao.updateChat(updated)
        core.session(accountId)?.createGroup(updated, name, if (private) "private" else "public", invitees, description)
        selectChat(updated.id)
    }
    fun loadDevices(accountId: Long, jid: String) = viewModelScope.launch(Dispatchers.IO) {
        core.session(accountId)?.loadDevices(jid)
    }
    fun trustDevice(accountId: Long, jid: String, deviceId: Long, fingerprint: String, trusted: Boolean, protocol: String = "omemo") =
        viewModelScope.launch(Dispatchers.IO) {
            core.session(accountId)?.trustDevice(jid, deviceId, fingerprint, trusted, protocol)
        }
    fun renameContact(accountId: Long, jid: String, name: String) = viewModelScope.launch {
        core.session(accountId)?.renameContact(jid, name)
    }
}
