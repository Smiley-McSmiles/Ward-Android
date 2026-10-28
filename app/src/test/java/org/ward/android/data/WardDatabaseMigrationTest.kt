package org.ward.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a version-1 database by hand (the app has always been at version 5 in this repository,
 * so no v1 schema JSON exists to hand to MigrationTestHelper), then opens it with the real Room
 * database and the production migration list. Room's schema validation on open makes this fail
 * if the migration chain does not produce exactly the current v5 schema.
 *
 * This runs as a Robolectric unit test so it needs no emulator and runs in the normal CI job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WardDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "ward-migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migratesVersion1ToCurrentAndKeepsData() {
        createVersion1Database()

        val room = Room.databaseBuilder(context, WardDatabase::class.java, dbName)
            .addMigrations(*WardDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase

            db.query("SELECT jid FROM accounts WHERE id = 1").use { c ->
                assertTrue("account survived migration", c.moveToFirst())
                assertEquals("alice@example.org", c.getString(0))
            }

            db.query("SELECT encryption, open, clearedAt FROM chats WHERE id = 1").use { c ->
                assertTrue("chat survived migration", c.moveToFirst())
                // 2 -> 3 rewrites the old value; 1 -> 2 and 4 -> 5 add columns with defaults.
                assertEquals("omemo", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(0, c.getLong(2))
            }

            db.query("SELECT body FROM messages WHERE id = 1").use { c ->
                assertTrue("message survived migration", c.moveToFirst())
                assertEquals("hello from v1", c.getString(0))
            }

            db.query("SELECT deviceId, protocol FROM devices WHERE deviceId = 7").use { c ->
                assertTrue("device survived the table rebuild", c.moveToFirst())
                assertEquals(7, c.getInt(0))
                assertEquals("omemo", c.getString(1))
            }

            db.query("SELECT COUNT(*) FROM pgp_keys").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }

            db.query("SELECT COUNT(*) FROM receipts").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM requests").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }

            db.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(5, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `accounts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jid` TEXT NOT NULL, `label` TEXT NOT NULL,
                `color` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `encryptedPassword` TEXT NOT NULL,
                `optionsJson` TEXT NOT NULL, `isAdmin` INTEGER NOT NULL, `nickname` TEXT NOT NULL,
                `avatar` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `contacts` (
                `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `rosterName` TEXT NOT NULL,
                `nickname` TEXT NOT NULL, `groups` TEXT NOT NULL, `subscription` TEXT NOT NULL,
                `ask` TEXT NOT NULL, `inRoster` INTEGER NOT NULL, `blocked` INTEGER NOT NULL,
                `presence` TEXT NOT NULL, `status` TEXT NOT NULL, `avatar` TEXT NOT NULL,
                `lastSeen` INTEGER NOT NULL, `isGateway` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `jid`))""")
            // v1 had neither chats.clearedAt (added in 4 -> 5) nor chats.open (added in 1 -> 2).
            db.execSQL("""CREATE TABLE IF NOT EXISTS `chats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL,
                `jid` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `pinned` INTEGER NOT NULL,
                `mutedUntil` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `isRequest` INTEGER NOT NULL,
                `draft` TEXT NOT NULL, `unread` INTEGER NOT NULL, `mentions` INTEGER NOT NULL,
                `lastActivity` INTEGER NOT NULL, `lastReadId` INTEGER NOT NULL, `preview` TEXT NOT NULL,
                `previewSender` TEXT NOT NULL, `encryption` TEXT NOT NULL, `notify` TEXT NOT NULL,
                `subject` TEXT NOT NULL, `memberCount` INTEGER NOT NULL, `joined` INTEGER NOT NULL,
                `joinError` TEXT NOT NULL, `ourNick` TEXT NOT NULL, `ourRole` TEXT NOT NULL,
                `ourAffiliation` TEXT NOT NULL, `avatar` TEXT NOT NULL, `optionsJson` TEXT NOT NULL)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chats_accountId_jid` ON `chats` (`accountId`, `jid`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL,
                `stanzaId` TEXT NOT NULL, `originId` TEXT NOT NULL, `msgId` TEXT NOT NULL,
                `sender` TEXT NOT NULL, `senderJid` TEXT NOT NULL, `senderName` TEXT NOT NULL,
                `occupantId` TEXT NOT NULL, `direction` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL,
                `body` TEXT NOT NULL, `isMarkdown` INTEGER NOT NULL, `kind` TEXT NOT NULL,
                `encryption` TEXT NOT NULL, `senderDevice` INTEGER NOT NULL, `trust` TEXT NOT NULL,
                `edited` INTEGER NOT NULL, `retracted` INTEGER NOT NULL, `replyTo` TEXT NOT NULL,
                `replyPreview` TEXT NOT NULL, `replyAuthor` TEXT NOT NULL, `status` INTEGER NOT NULL,
                `error` TEXT NOT NULL, `mentionsMe` INTEGER NOT NULL, `reactionsJson` TEXT NOT NULL,
                `attachmentsJson` TEXT NOT NULL, `rawJson` TEXT NOT NULL)""")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_chatId_stanzaId` ON `messages` (`chatId`, `stanzaId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId` ON `messages` (`chatId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timestamp` ON `messages` (`timestamp`)")
            // v1 devices did not have the protocol column or the four-column primary key.
            db.execSQL("""CREATE TABLE IF NOT EXISTS `devices` (
                `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `deviceId` INTEGER NOT NULL,
                `fingerprint` TEXT NOT NULL, `label` TEXT NOT NULL, `trust` TEXT NOT NULL,
                `active` INTEGER NOT NULL, `own` INTEGER NOT NULL, `thisDevice` INTEGER NOT NULL,
                `versions` TEXT NOT NULL, `firstSeen` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL,
                `kind` TEXT NOT NULL, PRIMARY KEY(`accountId`, `jid`, `deviceId`))""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `receipts` (
                `messageId` INTEGER NOT NULL, `recipient` TEXT NOT NULL, `deliveredAt` INTEGER NOT NULL,
                `displayedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `recipient`))""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `requests` (
                `accountId` INTEGER NOT NULL, `jid` TEXT NOT NULL, `kind` TEXT NOT NULL,
                `text` TEXT NOT NULL, `chatId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `jid`))""")

            db.execSQL("""INSERT INTO accounts
                (id, jid, label, color, enabled, encryptedPassword, optionsJson, isAdmin, nickname, avatar, createdAt)
                VALUES (1, 'alice@example.org', '', '#3584e4', 1, '', '{}', 0, '', '', 1)""")
            db.execSQL("""INSERT INTO chats
                (id, accountId, jid, type, name, pinned, mutedUntil, archived, isRequest, draft, unread,
                 mentions, lastActivity, lastReadId, preview, previewSender, encryption, notify, subject,
                 memberCount, joined, joinError, ourNick, ourRole, ourAffiliation, avatar, optionsJson)
                VALUES (1, 1, 'bob@example.org', 'chat', 'Bob', 0, 0, 0, 0, '', 0, 0, 1, 0, '', '',
                        'omemo2', '', '', 0, 1, '', '', '', '', '', '{}')""")
            db.execSQL("""INSERT INTO messages
                (id, chatId, stanzaId, originId, msgId, sender, senderJid, senderName, occupantId,
                 direction, timestamp, body, isMarkdown, kind, encryption, senderDevice, trust,
                 edited, retracted, replyTo, replyPreview, replyAuthor, status, error, mentionsMe,
                 reactionsJson, attachmentsJson, rawJson)
                VALUES (1, 1, 'stanza-1', '', '', 'Bob', 'bob@example.org', 'Bob', '', 0, 1,
                        'hello from v1', 0, 'text', 'omemo2', 7, 'trusted', 0, 0, '', '', '', 0, '', 0,
                        '[]', '[]', '')""")
            db.execSQL("""INSERT INTO devices
                (accountId, jid, deviceId, fingerprint, label, trust, active, own, thisDevice,
                 versions, firstSeen, lastUsed, kind)
                VALUES (1, 'bob@example.org', 7, 'fp', '', 'trusted', 1, 0, 0, '', 1, 1, 'omemo')""")
            db.execSQL("INSERT INTO receipts (messageId, recipient, deliveredAt, displayedAt) VALUES (1, 'alice@example.org', 1, 0)")
            db.execSQL("INSERT INTO requests (accountId, jid, kind, text, chatId, createdAt) VALUES (1, 'carol@example.org', 'subscription', 'hi', 1, 1)")

            db.version = 1
        } finally {
            db.close()
        }
    }
}
