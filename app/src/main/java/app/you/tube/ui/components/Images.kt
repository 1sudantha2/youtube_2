package app.you.tube.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background

/**
 * Coil wrappers enforcing the low-memory image contract:
 *
 *  - Decode directly to the target view dimensions (640x360 / 96x96) with
 *    [Precision.EXACT] — a full 1280x720 ARGB_8888 bitmap would cost ~3.5 MB;
 *    the capped decode costs ~0.9 MB.
 *  - No transformations -> Coil uses Bitmap.Config.HARDWARE by default:
 *    pixel storage lives in GPU-only memory, invisible to the Dalvik/ART GC
 *    (no GC pauses while scrolling feeds).
 *  - Global memory cache is capped at 20% of heap (see YouTubeApp).
 */
@Composable
fun ThumbImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(640, 360)
            .precision(Precision.EXACT)
            .scale(Scale.FILL)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

@Composable
fun AvatarImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(96, 96)
            .precision(Precision.EXACT)
            .scale(Scale.FILL)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

/** Flat placeholder used behind thumbnails while they stream in. */
@Composable
fun ThumbPlaceholder(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.background(Color(0xFF1C1C1C))
    )
}
