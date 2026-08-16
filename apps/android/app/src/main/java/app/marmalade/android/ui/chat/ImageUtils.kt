package app.marmalade.android.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Image compression for chat attachments. Large picked images are re-encoded
 * before upload so `image.attach_bytes` frames stay small on the WS:
 *
 * - Maximum 2048px longest side
 * - JPEG, starting quality 85%, stepped down until ≤ [MAX_RAW_BYTES]
 *
 * Output goes to a staged FILE (see AttachmentStaging), never an in-memory
 * base64 string — the staged path is what persists in the outbox row.
 */
object ImageUtils {

    private const val MAX_DIMENSION = 2048
    private const val START_QUALITY = 85

    /** Raw ceiling such that the base64 upload stays under ~5 MB. */
    const val MAX_RAW_BYTES = (5 * 1024 * 1024 / 4) * 3

    /**
     * Decode [uri], downscale/re-encode to JPEG within the size budget, apply
     * EXIF rotation, and write the result to [dest]. Runs on [Dispatchers.IO].
     */
    suspend fun compressToJpegFile(context: Context, uri: Uri, dest: File): Unit =
        withContext(Dispatchers.IO) {
            fun openStream() = if (uri.scheme == "file") {
                File(uri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }

            // Pass 1: bounds only (no pixel allocation). decodeStream always
            // returns null with inJustDecodeBounds, so null-check the STREAM,
            // not the use{} result.
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = openStream()
                ?: throw IllegalStateException("Cannot open image URI")
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            require(origWidth > 0 && origHeight > 0) { "Invalid image dimensions" }

            // Pass 2: decode with power-of-2 downsampling, then fine-scale.
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(origWidth, origHeight, MAX_DIMENSION)
            }
            val rawBitmap = openStream()?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: throw IllegalStateException("Cannot decode image")

            // Camera JPEGs store raw sensor orientation in EXIF; without the
            // rotate the uploaded image renders sideways.
            val rotated = applyExifRotation(context, rawBitmap, uri)
            val scaled = scaleToMaxDimension(rotated, MAX_DIMENSION)

            val bytes = compressToLimit(scaled, START_QUALITY, MAX_RAW_BYTES)

            if (scaled !== rotated) rotated.recycle()
            if (rotated !== rawBitmap) rawBitmap.recycle()
            scaled.recycle()

            dest.writeBytes(bytes)
        }

    /**
     * Largest power-of-2 inSampleSize that keeps the longest side at or above
     * [maxDim]. Rough pass — fine scaling happens after decode.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val longest = max(width, height)
        if (longest > maxDim * 2) {
            val halfLongest = longest / 2
            while (halfLongest / inSampleSize >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** Scale so the longest side is ≤ [maxDim]; identity when already within. */
    private fun scaleToMaxDimension(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap

        val scale = maxDim.toFloat() / longest.toFloat()
        val newWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /** JPEG-encode, stepping quality down 25% per iteration until ≤ [maxBytes]. */
    private fun compressToLimit(
        bitmap: Bitmap,
        startQuality: Int,
        maxBytes: Int,
        minQuality: Int = 20,
    ): ByteArray {
        var quality = startQuality
        while (quality >= minQuality) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()
            if (bytes.size <= maxBytes) return bytes
            quality = max(minQuality, (quality * 0.75).roundToInt())
        }
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, minQuality, baos)
        return baos.toByteArray()
    }

    private fun applyExifRotation(context: Context, bitmap: Bitmap, uri: Uri): Bitmap {
        val orientation = runCatching {
            val stream = if (uri.scheme == "file") {
                File(uri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
            stream?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
