package app.marmalade.android.node

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.marmalade.android.PermissionRequester
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.TimeZone

class CalendarHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getFirstWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val cursor = appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    private suspend fun ensureReadPermission(): Boolean {
        if (hasReadPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.READ_CALENDAR))
        return results[Manifest.permission.READ_CALENDAR] == true
    }

    private suspend fun ensureWritePermission(): Boolean {
        if (hasWritePermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.WRITE_CALENDAR))
        return results[Manifest.permission.WRITE_CALENDAR] == true
    }

    suspend fun handleEvents(paramsJson: String?): InvokeResult {
        if (!ensureReadPermission()) {
            return InvokeResult.error(
                code = "CALENDAR_READ_PERMISSION_REQUIRED",
                message = "CALENDAR_READ_PERMISSION_REQUIRED: grant Calendar read permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: System.currentTimeMillis()
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: (startTime + 86400000)

        val events = mutableListOf<JsonObject>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())
        val cursor = try {
            appContext.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
        } catch (e: SecurityException) {
            return InvokeResult.error(
                code = "CALENDAR_READ_PERMISSION_REQUIRED",
                message = "CALENDAR_READ_PERMISSION_REQUIRED: ${e.message}"
            )
        }

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            var count = 0
            while (it.moveToNext() && count < 10) {
                val id = it.getLong(idIndex)
                val title = it.getString(titleIndex)
                val start = it.getLong(startIndex)
                val end = it.getLong(endIndex)
                events.add(buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("title", JsonPrimitive(title))
                    put("startTime", JsonPrimitive(start))
                    put("endTime", JsonPrimitive(end))
                })
                count++
            }
        }

        val payload = buildJsonObject {
            put("events", buildJsonArray {
                events.forEach { add(it) }
            })
        }
        return InvokeResult.ok(payload.toString())
    }

    suspend fun handleAdd(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val calendarId = getFirstWritableCalendarId() ?: return InvokeResult.error("CALENDAR_NOT_FOUND", "No writable calendar found")

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val title = (params["title"] as? JsonPrimitive)?.content ?: ""
        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return InvokeResult.error("INVALID_REQUEST", "startTime is required")
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: (startTime + 3600000)

        if (title.isEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Title is required")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                InvokeResult.ok("""{"ok":true,"id":${uri.lastPathSegment}}""")
            } else {
                InvokeResult.error("CALENDAR_ADD_FAILED", "CALENDAR_ADD_FAILED: insert returned null")
            }
        } catch (e: Exception) {
            InvokeResult.error("CALENDAR_ADD_FAILED", "CALENDAR_ADD_FAILED: ${e.message}")
        }
    }

    suspend fun handleUpdate(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val id = (params["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            return InvokeResult.error("INVALID_REQUEST", "Valid event ID is required")
        }

        val title = (params["title"] as? JsonPrimitive)?.content
        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull()
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull()

        if (title.isNullOrEmpty() && startTime == null && endTime == null) {
             return InvokeResult.error("INVALID_REQUEST", "At least one of title, startTime, or endTime is required for update")
        }

        val values = ContentValues().apply {
            if (!title.isNullOrEmpty()) put(CalendarContract.Events.TITLE, title)
            if (startTime != null) put(CalendarContract.Events.DTSTART, startTime)
            if (endTime != null) put(CalendarContract.Events.DTEND, endTime)
        }

        val selection = "${CalendarContract.Events._ID}=?"
        val selectionArgs = arrayOf(id.toString())

        return try {
            val rows = appContext.contentResolver.update(CalendarContract.Events.CONTENT_URI, values, selection, selectionArgs)
            if (rows > 0) {
                 InvokeResult.ok("""{"ok":true}""")
            } else {
                 InvokeResult.error("CALENDAR_UPDATE_FAILED", "Event not found or no changes made")
            }
        } catch (e: Exception) {
            InvokeResult.error("CALENDAR_UPDATE_FAILED", "CALENDAR_UPDATE_FAILED: ${e.message}")
        }
    }

    suspend fun handleDelete(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val id = (params["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            return InvokeResult.error("INVALID_REQUEST", "Valid event ID is required")
        }

        val selection = "${CalendarContract.Events._ID}=?"
        val selectionArgs = arrayOf(id.toString())

        return try {
            val rows = appContext.contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, selectionArgs)
             if (rows > 0) {
                 InvokeResult.ok("""{"ok":true}""")
             } else {
                 InvokeResult.error("CALENDAR_DELETE_FAILED", "Event not found")
             }
        } catch (e: Exception) {
            InvokeResult.error("CALENDAR_DELETE_FAILED", "CALENDAR_DELETE_FAILED: ${e.message}")
        }
    }
}
