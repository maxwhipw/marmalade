package app.marmalade.android.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.marmalade.android.chat.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Copies a picked content URI into app-private storage and describes it as an
 * [OutgoingAttachment]. Staging runs at pick time because SAF grants are
 * transient — by drain time (which can be minutes later, after an offline
 * queue + reconnect) the URI is no longer readable, so the bytes must already
 * live under our own [filesDir] (not cacheDir: the OS may purge cache while a
 * send waits in the outbox).
 *
 * Routing:
 * - images within the upload budget → straight copy (preserves PNG/GIF/WebP)
 * - larger images → [ImageUtils.compressToJpegFile] (≤2048px JPEG)
 * - anything else → straight copy, rejected over [MAX_FILE_BYTES]
 */
object AttachmentStaging {

    /**
     * Raw cap for non-image files. `file.attach` uploads ride a single WS
     * frame as base64 (~4/3 inflation): 10 MB raw ≈ 13.4 MB frame, safely
     * under uvicorn's 16 MB default `ws_max_size` on the gateway. Desktop
     * caps at 16 MB raw (electron/hardening.cjs) and can exceed that frame
     * limit — don't copy its number.
     */
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    /** Server-recognized image extensions (`tui_gateway/server.py:6490`). */
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** Staged files older than this are orphans (their outbox row is long
     *  acked or the chip was abandoned) — pruned on the next staging call. */
    private const val PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000

    class TooLargeException(name: String, sizeBytes: Long) : Exception(
        "$name is too large to upload (${sizeBytes / (1024 * 1024)} MB; cap is ${MAX_FILE_BYTES / (1024 * 1024)} MB)",
    )

    fun attachmentsDir(context: Context): File = File(context.filesDir, "attachments")

    /**
     * Stage [uri] and return its attachment descriptor. Throws
     * [TooLargeException] for oversized non-image files and
     * [IllegalStateException] for unreadable URIs — callers surface both as
     * a snackbar and drop the pick.
     */
    suspend fun stage(context: Context, uri: Uri): OutgoingAttachment =
        withContext(Dispatchers.IO) {
            val dir = attachmentsDir(context).apply { mkdirs() }
            pruneOrphans(dir)

            val (displayName, declaredSize) = queryNameAndSize(context, uri)
            val mime = context.contentResolver.getType(uri)
                ?: mimeFromName(displayName)
                ?: "application/octet-stream"
            val isImage = mime.startsWith("image/") ||
                displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

            if (isImage) {
                return@withContext stageImage(context, uri, dir, displayName, declaredSize)
            }

            if (declaredSize != null && declaredSize > MAX_FILE_BYTES) {
                throw TooLargeException(displayName, declaredSize)
            }
            val dest = uniqueDest(dir, displayName)
            try {
                val copied = copyCapped(context, uri, dest, MAX_FILE_BYTES)
                OutgoingAttachment(
                    kind = OutgoingAttachment.KIND_FILE,
                    name = displayName,
                    mimeType = mime,
                    path = dest.absolutePath,
                    sizeBytes = copied,
                )
            } catch (t: Throwable) {
                dest.delete()
                if (t is CapExceeded) throw TooLargeException(displayName, declaredSize ?: MAX_FILE_BYTES)
                throw t
            }
        }

    /** Delete a staged file when the user removes its chip before sending. */
    fun discard(attachment: OutgoingAttachment) {
        runCatching { File(attachment.path).delete() }
    }

    private suspend fun stageImage(
        context: Context,
        uri: Uri,
        dir: File,
        displayName: String,
        declaredSize: Long?,
    ): OutgoingAttachment {
        // Small images copy as-is so PNG screenshots and GIFs keep their
        // format; only oversized ones pay the JPEG re-encode.
        if (declaredSize != null && declaredSize <= ImageUtils.MAX_RAW_BYTES) {
            val dest = uniqueDest(dir, displayName)
            try {
                val copied = copyCapped(context, uri, dest, ImageUtils.MAX_RAW_BYTES.toLong())
                return OutgoingAttachment(
                    kind = OutgoingAttachment.KIND_IMAGE,
                    name = displayName,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*",
                    path = dest.absolutePath,
                    sizeBytes = copied,
                )
            } catch (t: Throwable) {
                dest.delete()
                if (t !is CapExceeded) throw t
                // Declared size lied — fall through to compression.
            }
        }

        val jpegName = displayName.substringBeforeLast('.').ifBlank { "image" } + ".jpg"
        val dest = uniqueDest(dir, jpegName)
        try {
            ImageUtils.compressToJpegFile(context, uri, dest)
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
        return OutgoingAttachment(
            kind = OutgoingAttachment.KIND_IMAGE,
            name = jpegName,
            mimeType = "image/jpeg",
            path = dest.absolutePath,
            sizeBytes = dest.length(),
        )
    }

    private fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment") to size
    }

    private fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun uniqueDest(dir: File, name: String): File {
        // UUID prefix keeps same-named picks distinct; the display name stays
        // in the filename so bubble chips and gateway uploads read naturally.
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(dir, "${UUID.randomUUID().toString().take(8)}-$safe")
    }

    private class CapExceeded : Exception()

    /** Stream-copy [uri] → [dest], aborting past [cap] bytes. Returns size. */
    private fun copyCapped(context: Context, uri: Uri, dest: File, cap: Long): Long {
        val input = if (uri.scheme == "file") {
            File(uri.path!!).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: throw IllegalStateException("Cannot open $uri")
        var total = 0L
        input.use { source ->
            dest.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > cap) throw CapExceeded()
                    sink.write(buffer, 0, read)
                }
            }
        }
        return total
    }

    private fun pruneOrphans(dir: File) {
        val cutoff = System.currentTimeMillis() - PRUNE_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
