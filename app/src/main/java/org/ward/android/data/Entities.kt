package org.ward.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jid: String,
    val label: String = "",
    val color: String = "#3584e4",
    val enabled: Boolean = true,
    val encryptedPassword: String = "",
    val optionsJson: String = "{}",
    val isAdmin: Boolean = false,
    val nickname: String = "",
    val avatar: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val domain: String get() = jid.substringAfter('@', jid)
    val localpart: String get() = jid.substringBefore('@')
    val displayName: String get() = label.ifBlank { nickname.ifBlank { jid } }
}

@Entity(tableName = "contacts", primaryKeys = ["accountId", "jid"])
data class ContactEntity(
    val accountId: Long,
    val jid: String,
    val rosterName: String = "",
    val nickname: String = "",
    val groups: String = "",
    val subscription: String = "none",
    val ask: String = "",
    val inRoster: Boolean = true,
    val blocked: Boolean = false,
    val presence: String = "offline",
    val status: String = "",
    val avatar: String = "",
    val lastSeen: Long = 0,
    val isGateway: Boolean = false
) {
    val name: String get() = nickname.ifBlank { rosterName.ifBlank { jid.substringBefore('@') } }
}

@Entity(tableName = "chats", indices = [Index(value = ["accountId", "jid"], unique = true)])
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val jid: String,
    val type: String = "chat", // chat | groupchat | pm
    val name: String = "",
    val pinned: Boolean = false,
    val mutedUntil: Long = 0,
    val archived: Boolean = false,
    val isRequest: Boolean = false,
    val draft: String = "",
    val unread: Int = 0,
    val mentions: Int = 0,
    val lastActivity: Long = System.currentTimeMillis(),
    val lastReadId: Long = 0,
    val clearedAt: Long = 0,
    val preview: String = "",
    val previewSender: String = "",
    val encryption: String = "default", // default | omemo | pgp | off
    val notify: String = "default",
    val subject: String = "",
    val memberCount: Int = 0,
    val joined: Boolean = false,
    val open: Boolean = true,
    val joinError: String = "",
    val ourNick: String = "",
    val ourRole: String = "",
    val ourAffiliation: String = "",
    val avatar: String = "",
    val optionsJson: String = "{}"
) {
    val isGroup: Boolean get() = type == "groupchat"
    val displayName: String get() = name.ifBlank { jid }
}

@Entity(tableName = "messages", indices = [Index(value = ["chatId", "stanzaId"], unique = true), Index("chatId"), Index("timestamp")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val stanzaId: String = "",
    val originId: String = "",
    val msgId: String = "",
    val sender: String = "",
    val senderJid: String = "",
    val senderName: String = "",
    val occupantId: String = "",
    val direction: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val body: String = "",
    val isMarkdown: Boolean = false,
    val kind: String = "text", // text | me | file | status | system
    val encryption: String = "",
    val senderDevice: Long = 0,
    val trust: String = "",
    val edited: Boolean = false,
    val retracted: Int = 0,
    val replyTo: String = "",
    val replyPreview: String = "",
    val replyAuthor: String = "",
    val status: Int = 0,
    val error: String = "",
    val mentionsMe: Boolean = false,
    val reactionsJson: String = "[]",
    val attachmentsJson: String = "[]",
    val rawJson: String = ""
)

@Entity(tableName = "devices", primaryKeys = ["accountId", "jid", "deviceId", "protocol"])
data class DeviceEntity(
    val accountId: Long,
    val jid: String,
    val deviceId: Long,
    val fingerprint: String = "",
    val label: String = "",
    val trust: String = "undecided",
    val active: Boolean = true,
    val own: Boolean = false,
    val thisDevice: Boolean = false,
    val versions: String = "OMEMO 2, legacy",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastUsed: Long = 0,
    val kind: String = "omemo",
    val protocol: String = "omemo"
)

@Entity(tableName = "receipts", primaryKeys = ["messageId", "recipient"])
data class ReceiptEntity(
    val messageId: Long,
    val recipient: String,
    val deliveredAt: Long = 0,
    val displayedAt: Long = 0
)

@Entity(tableName = "requests", primaryKeys = ["accountId", "jid"])
data class RequestEntity(
    val accountId: Long,
    val jid: String,
    val kind: String = "subscription", // subscription | message
    val text: String = "",
    val chatId: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pgp_keys", primaryKeys = ["accountId", "jid", "fingerprint"])
data class PgpKeyEntity(
    val accountId: Long,
    val jid: String,
    val fingerprint: String,
    val armored: String,
    val name: String = "",
    val trust: String = "undecided",
    val announced: Boolean = true,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)
