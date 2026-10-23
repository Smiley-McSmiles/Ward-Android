package org.ward.android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WardDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt") fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts ORDER BY createdAt") suspend fun accountsNow(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE id=:id") suspend fun account(id: Long): AccountEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAccount(a: AccountEntity): Long
    @Update suspend fun updateAccount(a: AccountEntity)
    @Query("DELETE FROM accounts WHERE id=:id") suspend fun deleteAccount(id: Long)
    @Query("UPDATE accounts SET isAdmin=:admin WHERE id=:id") suspend fun setAdmin(id: Long, admin: Boolean)

    @Query("SELECT * FROM contacts ORDER BY jid") fun contacts(): Flow<List<ContactEntity>>
    @Query("SELECT * FROM contacts WHERE accountId=:accountId ORDER BY jid") fun contacts(accountId: Long): Flow<List<ContactEntity>>
    @Query("SELECT * FROM contacts WHERE accountId=:accountId ORDER BY jid") suspend fun contactsNow(accountId: Long): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE accountId=:accountId AND jid=:jid") suspend fun contact(accountId: Long, jid: String): ContactEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContact(c: ContactEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContacts(c: List<ContactEntity>)
    @Query("DELETE FROM contacts WHERE accountId=:accountId") suspend fun clearContacts(accountId: Long)

    @Query("SELECT * FROM chats ORDER BY pinned DESC, lastActivity DESC") fun chats(): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chats WHERE id=:id") suspend fun chat(id: Long): ChatEntity?
    @Query("SELECT * FROM chats ORDER BY pinned DESC, lastActivity DESC") suspend fun chatsNow(): List<ChatEntity>
    @Query("SELECT * FROM chats WHERE accountId=:accountId AND jid=:jid") suspend fun chat(accountId: Long, jid: String): ChatEntity?
    @Query("SELECT * FROM chats WHERE id=:id") fun chatFlow(id: Long): Flow<ChatEntity?>
    @Query("SELECT * FROM contacts WHERE accountId=:accountId AND jid=:jid") fun contactFlow(accountId: Long, jid: String): Flow<ContactEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChat(c: ChatEntity): Long
    @Update suspend fun updateChat(c: ChatEntity)
    @Query("DELETE FROM chats WHERE id=:id") suspend fun deleteChat(id: Long)
    @Query("UPDATE chats SET draft=:draft WHERE id=:id") suspend fun setDraft(id: Long, draft: String)

    @Query("SELECT * FROM messages WHERE chatId=:chatId ORDER BY timestamp ASC, id ASC") fun messages(chatId: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE chatId=:chatId ORDER BY timestamp ASC, id ASC LIMIT :limit") suspend fun messagesNow(chatId: Long, limit: Int = 500): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE chatId=:chatId ORDER BY timestamp DESC, id DESC LIMIT 1") suspend fun lastMessage(chatId: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE chatId=:chatId AND (stanzaId=:ref OR originId=:ref) LIMIT 1") suspend fun findByRef(chatId: Long, ref: String): MessageEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMessage(m: MessageEntity): Long
    @Update suspend fun updateMessage(m: MessageEntity)
    @Query("DELETE FROM messages WHERE chatId=:chatId") suspend fun clearMessages(chatId: Long)
    @Query("DELETE FROM messages") suspend fun clearAllMessages()
    @Query("DELETE FROM receipts") suspend fun clearAllReceipts()
    @Query("DELETE FROM devices") suspend fun clearAllDevices()
    @Query("DELETE FROM pgp_keys") suspend fun clearAllPgpKeys()
    @Query("UPDATE chats SET preview='', previewSender='', unread=0, mentions=0, lastReadId=0") suspend fun resetAllChats()
    @Query("DELETE FROM contacts WHERE accountId=:accountId AND jid=:jid") suspend fun deleteContact(accountId: Long, jid: String)
    @Query("DELETE FROM devices WHERE accountId=:accountId AND jid=:jid") suspend fun deleteDevicesFor(accountId: Long, jid: String)
    @Query("DELETE FROM devices WHERE accountId=:accountId AND jid=:jid AND deviceId=:deviceId AND protocol=:protocol") suspend fun deleteDevice(accountId: Long, jid: String, deviceId: Long, protocol: String)
    @Query("DELETE FROM pgp_keys WHERE accountId=:accountId AND jid=:jid") suspend fun deletePgpKeysFor(accountId: Long, jid: String)
    @Query("DELETE FROM messages WHERE chatId IN (SELECT id FROM chats WHERE accountId=:accountId AND jid=:jid)") suspend fun deleteMessagesForChatJid(accountId: Long, jid: String)
    @Query("DELETE FROM chats WHERE accountId=:accountId AND jid=:jid") suspend fun deleteChatForJid(accountId: Long, jid: String)

    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: Long)
    @Query("SELECT * FROM messages WHERE chatId=:chatId AND body LIKE '%'||:q||'%' ORDER BY timestamp DESC LIMIT 200") suspend fun search(chatId: Long, q: String): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE body LIKE '%'||:q||'%' ORDER BY timestamp DESC LIMIT 200") suspend fun searchAll(q: String): List<MessageEntity>

    @Query("SELECT * FROM devices WHERE accountId=:accountId AND jid=:jid ORDER BY deviceId") fun devices(accountId: Long, jid: String): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE accountId=:accountId AND jid=:jid ORDER BY deviceId") suspend fun devicesOnce(accountId: Long, jid: String): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE accountId=:accountId ORDER BY jid, deviceId") suspend fun devicesForAccountNow(accountId: Long): List<DeviceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDevice(d: DeviceEntity)
    @Query("UPDATE devices SET trust=:trust WHERE accountId=:accountId AND jid=:jid AND deviceId=:deviceId AND protocol=:protocol") suspend fun setTrust(accountId: Long, jid: String, deviceId: Long, protocol: String, trust: String)

    @Query("SELECT * FROM pgp_keys WHERE accountId=:accountId AND jid=:jid ORDER BY firstSeen") fun pgpKeys(accountId: Long, jid: String): Flow<List<PgpKeyEntity>>
    @Query("SELECT * FROM pgp_keys WHERE accountId=:accountId AND jid=:jid ORDER BY firstSeen") suspend fun pgpKeysNow(accountId: Long, jid: String): List<PgpKeyEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPgpKey(k: PgpKeyEntity)
    @Query("UPDATE pgp_keys SET trust=:trust WHERE accountId=:accountId AND jid=:jid AND fingerprint=:fp") suspend fun setPgpTrust(accountId: Long, jid: String, fp: String, trust: String)
    @Query("SELECT * FROM pgp_keys WHERE accountId=:accountId AND fingerprint=:fp LIMIT 1") suspend fun pgpKey(accountId: Long, fp: String): PgpKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun receipt(r: ReceiptEntity)
    @Query("SELECT * FROM receipts WHERE messageId=:id") suspend fun receipts(id: Long): List<ReceiptEntity>

    @Query("SELECT * FROM requests ORDER BY createdAt DESC") fun requests(): Flow<List<RequestEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRequest(r: RequestEntity)
    @Query("DELETE FROM requests WHERE accountId=:accountId AND jid=:jid") suspend fun deleteRequest(accountId: Long, jid: String)
}
