package app.marmalade.android.chat.messages

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.marmalade.android.data.local.AppDatabase
import app.marmalade.android.data.local.dao.ChatDao
import app.marmalade.android.data.local.entity.MessageEntity
import app.marmalade.android.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-Room pin for the v28 context-occupancy columns and the two hand-written
 * statements over them ([ChatDao.observeSessionContext],
 * [ChatDao.clearSessionContext]). [FakeChatDao] is the digital twin the
 * behaviour tests run against; this suite is what proves the twin is faithful
 * to actual SQLite — the columns exist, the projection maps by name, and the
 * clear is an UPDATE (no FK CASCADE wiping the session's messages).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionContextRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(ctx)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `context columns round-trip through Room`() = runBlocking {
        dao.insertSession(
            SessionEntity(key = "s1", thinkingLevel = "off", contextUsed = 32_900L, contextMax = 200_000L),
        )
        val stored = dao.getSessionByKey("s1")
        assertEquals(32_900L, stored?.contextUsed)
        assertEquals(200_000L, stored?.contextMax)

        val projected = dao.observeSessionContext("s1").first()
        assertEquals(32_900L, projected?.contextUsed)
        assertEquals(200_000L, projected?.contextMax)
    }

    @Test
    fun `a million-token window survives the column`() = runBlocking {
        // The big-window models overflow Int — the columns are INTEGER/Long.
        dao.insertSession(
            SessionEntity(key = "s-big", thinkingLevel = "off", contextUsed = 40_542L, contextMax = 1_000_000L),
        )
        assertEquals(1_000_000L, dao.observeSessionContext("s-big").first()?.contextMax)
    }

    @Test
    fun `an unstamped session projects nulls, not zeros`() = runBlocking {
        dao.insertSession(SessionEntity(key = "s2", thinkingLevel = "off"))
        val projected = dao.observeSessionContext("s2").first()
        assertNull("never-ran must read unknown, never 0%", projected?.contextUsed)
        assertNull(projected?.contextMax)
    }

    @Test
    fun `clearSessionContext nulls both columns without touching the messages`() = runBlocking {
        dao.insertSession(
            SessionEntity(key = "s3", thinkingLevel = "off", contextUsed = 32_900L, contextMax = 200_000L),
        )
        dao.insertMessage(
            MessageEntity(
                id = "m1", sessionKey = "s3", role = "user",
                contentJson = """[{"type":"text","text":"hi"}]""",
                timestampMs = 100L,
            ),
        )

        dao.clearSessionContext("s3")

        val projected = dao.observeSessionContext("s3").first()
        assertNull(projected?.contextUsed)
        assertNull(projected?.contextMax)
        assertEquals(
            "the clear is an UPDATE — it must not CASCADE the session's rows away",
            1, dao.getMessageCount("s3"),
        )
    }
}
