package org.ward.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AccountEntity::class, ContactEntity::class, ChatEntity::class, MessageEntity::class,
    DeviceEntity::class, ReceiptEntity::class, RequestEntity::class, PgpKeyEntity::class], version = 5, exportSchema = true)
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
        /**
         * The complete migration chain. Migration tests use this same list so a migration
         * cannot be added to the app but forgotten by the tests (or vice versa).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

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
