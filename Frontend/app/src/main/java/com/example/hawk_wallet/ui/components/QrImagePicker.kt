package com.example.hawk_wallet.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Android-specific half of the QR upload: the system photo picker and
// decoding the picked content:// URI for the preview.

private const val MAX_PREVIEW_SIDE = 1600

/**
 * Returns a launcher for the system photo picker (images only; falls back to
 * the document picker on older devices). [onPicked] receives the image URI;
 * a cancelled pick calls nothing, so the current selection is kept.
 */
@Composable
fun rememberQrImagePicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) onPicked(uri.toString())
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Decodes [uri] off the main thread, downsampled for display. */
@Composable
fun rememberQrImage(uri: String): State<QrImageState> {
    val resolver = LocalContext.current.contentResolver
    return produceState<QrImageState>(initialValue = QrImageState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val parsed = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > MAX_PREVIEW_SIDE || bounds.outHeight / sample > MAX_PREVIEW_SIDE) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (bitmap != null) QrImageState.Loaded(bitmap.asImageBitmap()) else QrImageState.Failed
            } catch (e: Exception) {
                QrImageState.Failed
            }
        }
    }
}
