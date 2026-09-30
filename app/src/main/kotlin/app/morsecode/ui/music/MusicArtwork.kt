package app.morsecode.ui.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.MediaItem
import app.morsecode.ui.files.ThumbnailCache
import app.morsecode.ui.files.loadThumbnail

/**
 * The player's artwork (§4.6): 190 dp square on the reference's 18 dp radius.
 *
 * When there is a bitmap it is the platform's own — `ContentResolver.loadThumbnail`
 * returns a track's embedded album art from API 29 — and it is decoded through the
 * same cache the Files grid uses, so scrolling the queue behind the player does not
 * decode it twice. Below API 29 the platform's thumbnail tables cover images and
 * video only, so a track shows the reference's stand-in: the amber-to-lime gradient
 * tile with a black note. A track that was never tagged with art shows it too.
 *
 * Nothing is downloaded and no artwork is invented.
 */
@Composable
public fun MusicArtwork(
    item: MediaItem,
    modifier: Modifier = Modifier,
) {
    val metrics = MorseTheme.metrics
    val context = LocalContext.current
    val density = LocalDensity.current
    val pixels = with(density) { metrics.musicArtwork.roundToPx() }
    val uriString = item.uriString

    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriString, key2 = pixels) {
        value = if (uriString == null) {
            null
        } else {
            ThumbnailCache.get("$uriString@$pixels")?.asImageBitmap()
                ?: loadThumbnail(context, item, pixels)?.also { decoded ->
                    ThumbnailCache.put("$uriString@$pixels", decoded)
                }?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .size(metrics.musicArtwork)
            .clip(RoundedCornerShape(metrics.musicArtworkRadius))
            .background(brush = Brush.linearGradient(colorStops = PlaceholderStops)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                // Decorative: the title and its artist line are the next things on
                // screen, so naming the file here would say it twice.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                painter = painterResource(MorseIcons.music),
                contentDescription = null,
                tint = PlaceholderGlyph,
                modifier = Modifier.size(metrics.musicArtworkGlyph),
            )
        }
    }
}

/**
 * `background:linear-gradient(135deg,#F59E0B,#EA580C 60%,#84CC16)` — the reference's
 * stand-in for album art, which Compose draws top-left to bottom-right by default.
 *
 * It is a picture rather than a theme colour, so it deliberately does not follow the
 * accent: a track's own artwork would not either.
 */
private val PlaceholderStops = listOf(
    Color(0xFFF59E0B) to 0f,
    Color(0xFFEA580C) to 0.6f,
    Color(0xFF84CC16) to 1f,
)

/** `color:#0B0B0B` — the note glyph on that gradient. */
private val PlaceholderGlyph = Color(0xFF0B0B0B)
