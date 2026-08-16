package app.marmalade.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Android boot-recovery callback. Keeps the compat (SupportSQLiteOpenHelper)
 * path — no `setDriver`, so foreign-key enforcement stays on by default,
 * `allowMainThreadQueries` remains available to tests, and the framework
 * SQLite engine is used exactly as under Room 2.6.1 (zero behavior change,
 * ADR 0011). Runs [BOOT_RECOVERY_STATEMENTS] synchronously on DB open.
 */
private val bootRecoveryCallback = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        BOOT_RECOVERY_STATEMENTS.forEach(db::execSQL)
    }
}

@Volatile
private var INSTANCE: AppDatabase? = null

/**
 * Process-wide singleton accessor. Attached as an extension on the
 * [AppDatabase] companion (declared empty in `jvmSharedMain`) so every existing
 * `AppDatabase.getDatabase(context)` call site is unchanged after the KMP move.
 *
 * Uses the Room-KMP reified builder against the same on-disk file the 2.6.1
 * relative-name builder resolved (`context.getDatabasePath(...)`), so the
 * database file and open semantics are identical to before.
 */
fun AppDatabase.Companion.getDatabase(context: Context): AppDatabase {
    return INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = context.applicationContext.getDatabasePath(DB_NAME).absolutePath,
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(bootRecoveryCallback)
            .build()
            .also { INSTANCE = it }
    }
}

private const val DB_NAME = "marmalade_database"
