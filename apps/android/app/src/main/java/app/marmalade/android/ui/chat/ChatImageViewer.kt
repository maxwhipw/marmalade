package app.marmalade.android.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.marmalade.android.ui.theme.marmaladeColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Sealed class representing the source of an image to display in the full-screen viewer.
 */
sealed class ImageData {
    /** URL-based image (loaded via Coil). */
    data class UrlImage(val url: String) : ImageData()

    /** Base64-encoded image from the chat protocol. */
    data class Base64Image(val base64: String, val mimeType: String? = null) : ImageData()
}

/**
 * Full-screen image viewer with:
 * - Dark overlay background
 * - Pinch-to-zoom (1x to 5x)
 * - Pan when zoomed in
 * - Swipe down to dismiss (>100dp threshold)
 * - Close X button (top-right)
 * - Save and Share buttons (bottom)
 *
 * Renders above everything using a Dialog composable.
 */
@Composable
fun ChatImageViewer(
    imageData: ImageData,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Transform state for pinch-to-zoom and pan
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Swipe-to-dismiss tracking
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = 300f // ~100dp at 3x density

    // Overlay alpha based on drag distance
    val overlayAlpha = (1f - (dragOffsetY.absoluteValue / (dismissThreshold * 2f))).coerceIn(0.3f, 1f)

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        if (scale > 1f) {
            offset += panChange
        } else {
            offset = Offset.Zero
        }
    }

    // Decoded bitmap for base64 images
    var decodedBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var rawBytes by remember { mutableStateOf<ByteArray?>(null) }

    if (imageData is ImageData.Base64Image) {
        LaunchedEffect(imageData.base64) {
            withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(imageData.base64, Base64.DEFAULT)
                    rawBytes = bytes
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    decodedBitmap = bitmap?.asImageBitmap()
                } catch (_: Throwable) {
                    // Failed to decode -- will show nothing
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f * overlayAlpha))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY.absoluteValue > dismissThreshold && scale <= 1.1f) {
                                onDismiss()
                            } else {
                                dragOffsetY = 0f
                            }
                        },
                        onVerticalDrag = { _, dragAmount ->
                            if (scale <= 1.1f) {
                                dragOffsetY += dragAmount
                            }
                        },
                    )
                },
        ) {
            // Image centered with zoom and pan
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(state = transformableState)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                // Double-tap to reset or zoom to 2x
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (imageData) {
                    is ImageData.UrlImage -> {
                        AsyncImage(
                            model = imageData.url,
                            contentDescription = "Full-screen image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    is ImageData.Base64Image -> {
                        if (decodedBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = decodedBitmap!!,
                                contentDescription = "Full-screen image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // Close button (top-right)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(4.dp),
                )
            }

            // Bottom action buttons (Save + Share)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .alpha(overlayAlpha),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                // Save button
                Button(
                    onClick = { saveImage(context, imageData, rawBytes) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Save", modifier = Modifier.padding(start = 4.dp))
                }

                // Share button
                Button(
                    onClick = { shareImage(context, imageData, rawBytes) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Share", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/**
 * Save image to device Pictures/Marmalade/ via MediaStore.
 */
private fun saveImage(context: Context, imageData: ImageData, rawBytes: ByteArray?) {
    try {
        val bytes = when (imageData) {
            is ImageData.Base64Image -> rawBytes ?: Base64.decode(imageData.base64, Base64.DEFAULT)
            is ImageData.UrlImage -> {
                Toast.makeText(context, "Save is available for received images", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val fileName = "marmalade_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Marmalade")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
            }
            Toast.makeText(context, "Saved to Pictures/Marmalade", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Throwable) {
        Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Share image via Android share sheet with ACTION_SEND.
 */
private fun shareImage(context: Context, imageData: ImageData, rawBytes: ByteArray?) {
    try {
        val bytes = when (imageData) {
            is ImageData.Base64Image -> rawBytes ?: Base64.decode(imageData.base64, Base64.DEFAULT)
            is ImageData.UrlImage -> {
                Toast.makeText(context, "Share is available for received images", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Write to a temp file for sharing
        val fileName = "marmalade_share_${System.currentTimeMillis()}.jpg"
        val cacheDir = java.io.File(context.cacheDir, "share_images")
        cacheDir.mkdirs()
        val tempFile = java.io.File(cacheDir, fileName)
        tempFile.writeBytes(bytes)

        val shareUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile,
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share image"))
    } catch (e: Throwable) {
        Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
