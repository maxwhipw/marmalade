package app.marmalade.android.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

/**
 * Desktop boot-recovery callback — same two idempotent demotions as Android
 * ([BOOT_RECOVERY_STATEMENTS]), expressed against the driver-path
 * [SQLiteConnection] instead of Android's `SupportSQLiteDatabase`.
 */
private val bootRecoveryCallback = object : RoomDatabase.Callback() {
    override fun onOpen(connection: SQLiteConnection) {
        BOOT_RECOVERY_STATEMENTS.forEach(connection::execSQL)
    }
}

/**
 * Build the desktop database at [dbFilePath]. Uses the bundled SQLite driver
 * (self-contained native SQLite, no system dependency) and, per Room-KMP,
 * requires an explicit query coroutine context. Not yet wired to an app — this
 * exists so `desktopMain` compiles the shared Room DB, proving the KMP move
 * carries the whole store to the desktop client (ADR 0011 / desktop-client
 * plan Phase 1). The desktop chat shell (Phase 2) is its first caller.
 */
fun buildDesktopDatabase(dbFilePath: String): AppDatabase {
    return Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .addCallback(bootRecoveryCallback)
        .build()
}
