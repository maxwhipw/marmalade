package app.marmalade.android.node

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.marmalade.android.PermissionRequester
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class ContactsHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureReadPermission(): Boolean {
        if (hasReadPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.READ_CONTACTS))
        return results[Manifest.permission.READ_CONTACTS] == true
    }

    private suspend fun ensureWritePermission(): Boolean {
        if (hasWritePermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.WRITE_CONTACTS))
        return results[Manifest.permission.WRITE_CONTACTS] == true
    }

    suspend fun handleSearch(paramsJson: String?): InvokeResult {
        if (!ensureReadPermission()) {
            return InvokeResult.error(
                code = "CONTACTS_READ_PERMISSION_REQUIRED",
                message = "CONTACTS_READ_PERMISSION_REQUIRED: grant Contacts read permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val query = (params["query"] as? JsonPrimitive)?.content ?: ""

        if (query.isEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Query is required")
        }

        val contacts = mutableListOf<JsonObject>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val cursor = try {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
        } catch (e: SecurityException) {
            return InvokeResult.error(
                code = "CONTACTS_READ_PERMISSION_REQUIRED",
                message = "CONTACTS_READ_PERMISSION_REQUIRED: ${e.message}"
            )
        }

        cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            var count = 0
            while (it.moveToNext() && count < 10) {
                val name = it.getString(nameIndex)
                val number = it.getString(numberIndex)
                val id = it.getLong(idIndex)
                contacts.add(buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("name", JsonPrimitive(name))
                    put("number", JsonPrimitive(number))
                })
                count++
            }
        }

        val payload = buildJsonObject {
            put("contacts", buildJsonArray {
                contacts.forEach { add(it) }
            })
        }
        return InvokeResult.ok(payload.toString())
    }

    suspend fun handleAdd(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val name = (params["name"] as? JsonPrimitive)?.content ?: ""
        val number = (params["number"] as? JsonPrimitive)?.content ?: ""

        if (name.isEmpty() || number.isEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Name and number are required")
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build())

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            InvokeResult.error("CONTACTS_ADD_FAILED", "CONTACTS_ADD_FAILED: ${e.message}")
        }
    }

    suspend fun handleUpdate(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
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
            return InvokeResult.error("INVALID_REQUEST", "Valid contact ID is required")
        }

        val name = (params["name"] as? JsonPrimitive)?.content
        val number = (params["number"] as? JsonPrimitive)?.content

        if (name.isNullOrEmpty() && number.isNullOrEmpty()) {
            return InvokeResult.error("INVALID_REQUEST", "Either name or number is required for update")
        }

        val ops = ArrayList<ContentProviderOperation>()

        if (!name.isNullOrEmpty()) {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
        }

        if (!number.isNullOrEmpty()) {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .build())
        }

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            InvokeResult.error("CONTACTS_UPDATE_FAILED", "CONTACTS_UPDATE_FAILED: ${e.message}")
        }
    }

    suspend fun handleDelete(paramsJson: String?): InvokeResult {
        if (!ensureWritePermission()) {
            return InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
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
            return InvokeResult.error("INVALID_REQUEST", "Valid contact ID is required")
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
            .withSelection("${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString()))
            .build())

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            InvokeResult.error("CONTACTS_DELETE_FAILED", "CONTACTS_DELETE_FAILED: ${e.message}")
        }
    }
}
