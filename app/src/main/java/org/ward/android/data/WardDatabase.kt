package org.ward.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AccountEntity::class, ContactEntity::class, ChatEntity::class, MessageEntity::class,
    DeviceEntity::class, ReceiptEntity::class, RequestEntity::class, PgpKeyEntity::class], version = 6, exportSchema = true)
abstract class WardDatabase : RoomDatabase() {
    abstract fun dao(): WardDao
    companion object {
        @Volatile private var instance: WardDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `pgp_keys` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `fingerprint` TEXT NOT NULL,
                    `armored` TEXT NOT NULL, `name` TEXT NOT NULL, `trust` TEXT NOT NULL,
                    `announced` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`, `fingerprint`))""")
                // Older development builds may have missed the "open" column.
                var hasOpen = false
                db.query("PRAGMA table_info(chats)").use { c ->
                    val name = c.getColumnIndex("name")
                    while (c.moveToNext()) if (name >= 0 && c.getString(name) == "open") hasOpen = true
                }
                if (!hasOpen) db.execSQL("ALTER TABLE chats ADD COLUMN open INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE chats SET encryption = 'omemo' WHERE encryption = 'omemo2'")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN clearedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `devices_new` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `deviceId` INTEGER NOT NULL,
                    `fingerprint` TEXT NOT NULL, `label` TEXT NOT NULL, `trust` TEXT NOT NULL,
                    `active` INTEGER NOT NULL, `own` INTEGER NOT NULL, `thisDevice` INTEGER NOT NULL,
                    `versions` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL, `protocol` TEXT NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`, `deviceId`, `protocol`))""")
                db.execSQL("""INSERT OR REPLACE INTO devices_new
                    (accountId, jid, deviceId, fingerprint, label, trust, active, own, thisDevice,
                     versions, firstSeen, lastUsed, kind, protocol)
                    SELECT accountId, jid, deviceId, fingerprint, label, trust, active, own, thisDevice,
                     versions, firstSeen, lastUsed, kind, 'omemo' FROM devices""")
                db.execSQL("DROP TABLE devices")
                db.execSQL("ALTER TABLE devices_new RENAME TO devices")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM contacts WHERE accountId NOT IN (SELECT id FROM accounts)")
                db.execSQL("DELETE FROM chats WHERE accountId NOT IN (SELECT id FROM accounts)")
                db.execSQL("DELETE FROM messages WHERE chatId NOT IN (SELECT id FROM chats)")
                db.execSQL("DELETE FROM devices WHERE accountId NOT IN (SELECT id FROM accounts)")
                db.execSQL("DELETE FROM receipts WHERE messageId NOT IN (SELECT id FROM messages)")
                db.execSQL("DELETE FROM requests WHERE accountId NOT IN (SELECT id FROM accounts)")
                db.execSQL("DELETE FROM pgp_keys WHERE accountId NOT IN (SELECT id FROM accounts)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `contacts_new` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `rosterName` TEXT NOT NULL,
                    `nickname` TEXT NOT NULL, `groups` TEXT NOT NULL, `subscription` TEXT NOT NULL,
                    `ask` TEXT NOT NULL, `inRoster` INTEGER NOT NULL, `blocked` INTEGER NOT NULL,
                    `presence` TEXT NOT NULL, `status` TEXT NOT NULL, `avatar` TEXT NOT NULL,
                    `lastSeen` INTEGER NOT NULL, `isGateway` INTEGER NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`),
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO contacts_new SELECT * FROM contacts")
                db.execSQL("DROP TABLE contacts")
                db.execSQL("ALTER TABLE contacts_new RENAME TO contacts")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `chats_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL,
                    `jid` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `pinned` INTEGER NOT NULL,
                    `mutedUntil` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `isRequest` INTEGER NOT NULL,
                    `draft` TEXT NOT NULL, `unread` INTEGER NOT NULL, `mentions` INTEGER NOT NULL,
                    `lastActivity` INTEGER NOT NULL, `lastReadId` INTEGER NOT NULL, `clearedAt` INTEGER NOT NULL,
                    `preview` TEXT NOT NULL, `previewSender` TEXT NOT NULL, `encryption` TEXT NOT NULL,
                    `notify` TEXT NOT NULL, `subject` TEXT NOT NULL, `memberCount` INTEGER NOT NULL,
                    `joined` INTEGER NOT NULL, `open` INTEGER NOT NULL, `joinError` TEXT NOT NULL, `ourNick` TEXT NOT NULL,
                    `ourRole` TEXT NOT NULL, `ourAffiliation` TEXT NOT NULL, `avatar` TEXT NOT NULL,
                    `optionsJson` TEXT NOT NULL,
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("""INSERT INTO chats_new
                    (id, accountId, jid, type, name, pinned, mutedUntil, archived, isRequest, draft, unread,
                     mentions, lastActivity, lastReadId, clearedAt, preview, previewSender, encryption, notify,
                     subject, memberCount, joined, open, joinError, ourNick, ourRole, ourAffiliation, avatar, optionsJson)
                    SELECT id, accountId, jid, type, name, pinned, mutedUntil, archived, isRequest, draft, unread,
                     mentions, lastActivity, lastReadId, clearedAt, preview, previewSender, encryption, notify,
                     subject, memberCount, joined, open, joinError, ourNick, ourRole, ourAffiliation, avatar, optionsJson
                    FROM chats""")
                db.execSQL("DROP TABLE chats")
                db.execSQL("ALTER TABLE chats_new RENAME TO chats")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chats_accountId_jid` ON `chats` (`accountId`, `jid`)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `messages_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL,
                    `stanzaId` TEXT, `originId` TEXT NOT NULL, `msgId` TEXT NOT NULL,
                    `sender` TEXT NOT NULL, `senderJid` TEXT NOT NULL, `senderName` TEXT NOT NULL,
                    `occupantId` TEXT NOT NULL, `direction` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL,
                    `body` TEXT NOT NULL, `isMarkdown` INTEGER NOT NULL, `kind` TEXT NOT NULL,
                    `encryption` TEXT NOT NULL, `senderDevice` INTEGER NOT NULL, `trust` TEXT NOT NULL,
                    `edited` INTEGER NOT NULL, `retracted` INTEGER NOT NULL, `replyTo` TEXT NOT NULL,
                    `replyPreview` TEXT NOT NULL, `replyAuthor` TEXT NOT NULL, `status` INTEGER NOT NULL,
                    `error` TEXT NOT NULL, `mentionsMe` INTEGER NOT NULL, `reactionsJson` TEXT NOT NULL,
                    `attachmentsJson` TEXT NOT NULL, `rawJson` TEXT NOT NULL,
                    FOREIGN KEY(`chatId`) REFERENCES `chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("""INSERT INTO messages_new
                    (id, chatId, stanzaId, originId, msgId, sender, senderJid, senderName, occupantId,
                     direction, timestamp, body, isMarkdown, kind, encryption, senderDevice, trust,
                     edited, retracted, replyTo, replyPreview, replyAuthor, status, error, mentionsMe,
                     reactionsJson, attachmentsJson, rawJson)
                    SELECT id, chatId, NULLIF(stanzaId, ''), originId, msgId, sender, senderJid, senderName, occupantId,
                     direction, timestamp, body, isMarkdown, kind, encryption, senderDevice, trust,
                     edited, retracted, replyTo, replyPreview, replyAuthor, status, error, mentionsMe,
                     reactionsJson, attachmentsJson, rawJson FROM messages""")
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_chatId_stanzaId` ON `messages` (`chatId`, `stanzaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `devices_new` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `deviceId` INTEGER NOT NULL,
                    `fingerprint` TEXT NOT NULL, `label` TEXT NOT NULL, `trust` TEXT NOT NULL,
                    `active` INTEGER NOT NULL, `own` INTEGER NOT NULL, `thisDevice` INTEGER NOT NULL,
                    `versions` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL, `protocol` TEXT NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`, `deviceId`, `protocol`),
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO devices_new SELECT * FROM devices")
                db.execSQL("DROP TABLE devices")
                db.execSQL("ALTER TABLE devices_new RENAME TO devices")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `receipts_new` (
                    `messageId` INTEGER NOT NULL, `recipient` TEXT NOT NULL, `deliveredAt` INTEGER NOT NULL,
                    `displayedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`messageId`, `recipient`),
                    FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO receipts_new SELECT * FROM receipts")
                db.execSQL("DROP TABLE receipts")
                db.execSQL("ALTER TABLE receipts_new RENAME TO receipts")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `requests_new` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `kind` TEXT NOT NULL,
                    `text` TEXT NOT NULL, `chatId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`),
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO requests_new SELECT * FROM requests")
                db.execSQL("DROP TABLE requests")
                db.execSQL("ALTER TABLE requests_new RENAME TO requests")

                db.execSQL("""CREATE TABLE IF NOT EXISTS `pgp_keys_new` (
                    `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `fingerprint` TEXT NOT NULL,
                    `armored` TEXT NOT NULL, `name` TEXT NOT NULL, `trust` TEXT NOT NULL,
                    `announced` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL,
                    PRIMARY KEY(`accountId`, `jid`, `fingerprint`),
                    FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO pgp_keys_new SELECT * FROM pgp_keys")
                db.execSQL("DROP TABLE pgp_keys")
                db.execSQL("ALTER TABLE pgp_keys_new RENAME TO pgp_keys")
            }
        }
        /**
         * The complete migration chain. Migration tests use this same list so a migration
         * cannot be added to the app but forgotten by the tests (or vice versa).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun get(context: Context): WardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WardDatabase::class.java, "ward.db")
                .addMigrations(*MIGRATIONS)
                // Never fall back to a destructive migration: wiping the database changes account
                // ids and device rows, and can make the app look like it lost its OMEMO identity
                // on an update. Any schema change must ship an explicit migration instead.
                .build().also { instance = it }
        }
    }
}
