package app.marmalade.android.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.marmalade.android.PermissionRequester
import app.marmalade.android.rpc.InvokeResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

class PhotosHandler(private val appContext: Context) {

    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensurePermission(): Boolean {
        if (hasPermission()) return true
        val requester = permissionRequester ?: return false
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val results = requester.requestIfMissing(listOf(permission))
        return results[permission] == true
    }

    suspend fun handleLatest(): InvokeResult {
        if (!ensurePermission()) {
            return InvokeResult.error(
                code = "PHOTOS_PERMISSION_REQUIRED",
                message = "PHOTOS_PERMISSION_REQUIRED: grant Photos/Storage permission"
            )
        }

        val photos = mutableListOf<JsonObject>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val cursor = try {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
        } catch (e: SecurityException) {
            return InvokeResult.error(
                code = "PHOTOS_PERMISSION_REQUIRED",
                message = "PHOTOS_PERMISSION_REQUIRED: ${e.message}"
            )
        }

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val mimeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            var count = 0
            while (it.moveToNext() && count < 5) {
                photos.add(buildJsonObject {
                    put("id", JsonPrimitive(it.getLong(idIndex)))
                    put("name", JsonPrimitive(it.getString(nameIndex)))
                    put("dateTaken", JsonPrimitive(it.getLong(dateIndex)))
                    put("mimeType", JsonPrimitive(it.getString(mimeIndex)))
                    put("size", JsonPrimitive(it.getLong(sizeIndex)))
                })
                count++
            }
        }

        val payload = buildJsonObject {
            put("photos", buildJsonArray {
                photos.forEach { add(it) }
            })
        }
        return InvokeResult.ok(payload.toString())
    }
}
