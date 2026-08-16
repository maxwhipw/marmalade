package app.marmalade.android.data.repository

import android.content.Context
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.getDatabase

@Volatile
private var INSTANCE: ChatRepository? = null

/**
 * Process-wide singleton accessor. Attached as an extension on the
 * [ChatRepository] companion (declared in `jvmSharedMain`) so the existing
 * `ChatRepository.getInstance(context)` call site keeps the same syntax after
 * the KMP move. Supplies the DAO from the Android compat-path database
 * (`AppDatabase.getDatabase`, increment 3b).
 */
fun ChatRepository.Companion.getInstance(context: Context): ChatRepository {
    return INSTANCE ?: synchronized(this) {
        INSTANCE ?: ChatRepository(
            AppDatabase.getDatabase(context.applicationContext).chatDao(),
        ).also { INSTANCE = it }
    }
}
