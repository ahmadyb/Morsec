package app.morsecode.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grid thumbnails without an image-loading dependency.
 *
 * Every bitmap comes from the platform's own thumbnail source — `ContentResolver
 * .loadThumbnail` from API 29, the MediaStore thumbnail tables on API 23-28 — so
 * no artwork is invented and nothing is downloaded. Decoding happens on the IO
 * dispatcher and results are cached in a memory [LruCache] keyed by uri and
 * target size, which is what keeps a 3-column grid smooth while scrolling.
 */
internal object ThumbnailCache {

    private const val MAX_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        if (cache.get(key) == null) cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}

/**
 * Loads and draws one thumbnail. While loading (and for kinds the platform has
 * no artwork for) the cell shows the file-kind icon on its tinted wash, exactly
 * like the reference's placeholder tiles.
 */
@Composable
public fun MediaThumbnail(
    item: MediaItem,
    modifier: Modifier = Modifier,
    targetSize: Dp = 120.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = with(density) { targetSize.roundToPx() }
    val uriString = item.uriString
    val showArtwork = item.isImage || item.isVideo

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriString, key2 = pixels, key3 = showArtwork) {
        value = if (!showArtwork || uriString == null) {
            null
        } else {
            ThumbnailCache.get("$uriString@$pixels")?.asImageBitmap()
                ?: loadThumbnail(context, uriString, item.id, pixels)?.also { decoded ->
                    ThumbnailCache.put("$uriString@$pixels", decoded)
                }?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier.background(MorseTheme.colors.kindWash(item.kind)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            FileKindIcon(kind = item.kind)
        }
    }
}

private suspend fun loadThumbnail(
    context: Context,
    uriString: String,
    itemId: String,
    pixels: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            resolver.loadThumbnail(uri, android.util.Size(pixels, pixels), null)
        }.getOrNull()
    } else {
        legacyThumbnail(resolver, itemId, pixels)
    }
}

/**
 * API 23-28 path: the MediaStore thumbnail tables. Both entries are deprecated
 * (removed in favour of `loadThumbnail`) but are the only platform source on
 * those versions, and the deprecation is contained here.
 */
@Suppress("DEPRECATION")
private fun legacyThumbnail(
    resolver: android.content.ContentResolver,
    itemId: String,
    pixels: Int,
): Bitmap? {
    val rowId = itemId.substringAfter(':', "").toLongOrNull() ?: return null
    val kind = if (pixels > 240) {
        MediaStore.Images.Thumbnails.MINI_KIND
    } else {
        MediaStore.Images.Thumbnails.MICRO_KIND
    }
    val image = runCatching {
        MediaStore.Images.Thumbnails.getThumbnail(resolver, rowId, kind, null)
    }.getOrNull()
    if (image != null) return image
    return runCatching {
        MediaStore.Video.Thumbnails.getThumbnail(resolver, rowId, kind, null)
    }.getOrNull()
}
