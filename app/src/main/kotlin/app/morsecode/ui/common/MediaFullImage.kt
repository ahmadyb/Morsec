package app.morsecode.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.morsecode.R
import app.morsecode.core.design.component.MorseLoading
import app.morsecode.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-size artwork, without an image-loading dependency.
 *
 * The grid's [app.morsecode.ui.files.MediaThumbnail] asks the platform for a
 * thumbnail; a viewer has to show the photograph itself. A 12 MP frame is about
 * 48 MB decoded and the viewer shows one at a time, so the file is measured first
 * and then decoded at the power-of-two sample size that fits this screen — no
 * larger, because memory on a minSdk 23 device is not, and no smaller, because a
 * viewer that upscales is not showing the photo.
 *
 * Decoding runs on IO and the last few frames stay in a byte-sized [LruCache],
 * which is what makes swiping back to a photo already seen instant.
 */
@Composable
public fun MediaFullImage(
    item: MediaItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    /**
     * What a screen reader says for the frame. Null makes the frame decorative,
     * which is what a caller that describes the whole page wants: one announcement,
     * not two.
     */
    contentDescription: String? = item.displayName,
) {
    val context = LocalContext.current
    val uriString = item.uriString
    val loadingText = stringResource(R.string.viewer_loading)
    // The screen, in pixels, is the budget a decoded frame has to fit inside.
    val maxEdge = remember(context) {
        val display = context.resources.displayMetrics
        maxOf(display.widthPixels, display.heightPixels)
    }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriString, key2 = maxEdge) {
        value = if (uriString == null) {
            null
        } else {
            FullImageCache.get(uriString)?.asImageBitmap()
                ?: loadFullImage(context, uriString, maxEdge)?.also { decoded ->
                    FullImageCache.put(uriString, decoded)
                }?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            MorseLoading(contentDescription = loadingText)
        }
    }
}

/** A few decoded frames, sized by bytes rather than by count. */
internal object FullImageCache {

    private const val MAX_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, value: Bitmap) {
        if (cache.get(key) == null) cache.put(key, value)
    }
}

private suspend fun loadFullImage(context: Context, uriString: String, maxEdge: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext null
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@withContext null

        // inSampleSize only accepts powers of two, so this lands on the largest
        // sample that still leaves the frame at least as long as the screen.
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
