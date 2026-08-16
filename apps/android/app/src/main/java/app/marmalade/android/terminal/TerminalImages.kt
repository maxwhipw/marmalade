// Adapted from chuchu (MIT) —
// android/app/src/main/java/com/jossephus/chuchu/service/terminal/TerminalSnapshot.kt
//
// Changed: package (ours), this header, and the split out of TerminalSnapshot.kt.
// `ImagePlacement` and `parseImages` are the only parts of chuchu's snapshot
// decode that touch the Android framework (Bitmap, Log); keeping them here
// leaves the grid decode JVM-pure and unit-testable. `parseImages` was a member
// of `TerminalSnapshot.Companion` upstream and is now `TerminalImages.parseImages`
// — the body is unchanged.
//
// The wire format is written by `chuchu_build_image_snapshot` in
// native/src/bridge/chuchu_snapshot.zig.
//
// Copyright (c) 2026 jossephus — see native/licenses/chuchu.LICENSE

package app.marmalade.android.terminal

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ImagePlacement(
    val cellCol: Int,
    val cellRow: Int,
    val cellXOffset: Int,
    val cellYOffset: Int,
    val destW: Int,
    val destH: Int,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    val imgW: Int,
    val imgH: Int,
    val bitmap: Bitmap,
)

object TerminalImages {
    private const val TAG = "TerminalImages"
    private const val IMAGE_HEADER_BYTES = 52

    fun parseImages(buffer: ByteBuffer?): List<ImagePlacement> {
        if (buffer == null || buffer.capacity() < 4) return emptyList()
        val wrapped = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        wrapped.position(0)
        val count = wrapped.int
        if (count <= 0) return emptyList()

        val images = ArrayList<ImagePlacement>(count)
        for (i in 0 until count) {
            if (wrapped.remaining() < IMAGE_HEADER_BYTES) break
            val cellCol = wrapped.int
            val cellRow = wrapped.int
            val cellXOffset = wrapped.int
            val cellYOffset = wrapped.int
            val destW = wrapped.int
            val destH = wrapped.int
            val srcX = wrapped.int
            val srcY = wrapped.int
            val srcW = wrapped.int
            val srcH = wrapped.int
            val imgW = wrapped.int
            val imgH = wrapped.int
            val dataLen = wrapped.int

            val expectedLen = imgW.toLong() * imgH.toLong() * 4L
            if (imgW <= 0 || imgH <= 0 || dataLen <= 0 ||
                expectedLen > Int.MAX_VALUE ||
                dataLen != expectedLen.toInt() ||
                wrapped.remaining() < dataLen
            ) {
                Log.w(
                    TAG,
                    "bad image record: img=${imgW}x$imgH dataLen=$dataLen expected=$expectedLen remaining=${wrapped.remaining()}",
                )
                break
            }

            val pixelBytes = wrapped.slice().order(ByteOrder.nativeOrder())
            pixelBytes.limit(dataLen)

            val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixelBytes)
            wrapped.position(wrapped.position() + dataLen)

            images += ImagePlacement(
                cellCol = cellCol,
                cellRow = cellRow,
                cellXOffset = cellXOffset,
                cellYOffset = cellYOffset,
                destW = destW,
                destH = destH,
                srcX = srcX,
                srcY = srcY,
                srcW = srcW,
                srcH = srcH,
                imgW = imgW,
                imgH = imgH,
                bitmap = bitmap,
            )
        }
        return images
    }
}
