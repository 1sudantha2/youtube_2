package com.ultra.youtube.app.player

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The video output surface.
 *
 * **`SurfaceView`, not `TextureView`.** A `TextureView` renders into the app's view-layer
 * bitmap, so every frame is copied through app memory before compositing; a `SurfaceView`
 * gets its own hardware layer composited by SurfaceFlinger and can hand the decoder
 * output straight to the display controller. For a video app that is the difference
 * between one and two full-frame buffers resident per video.
 *
 * The trade-off is that `SurfaceView` does not scale with the view hierarchy, so it must
 * measure itself to the video's aspect ratio — which is what [PlayerSurfaceView] does.
 */
class PlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    /** Set from [PlayerState.aspectRatio]; triggers a re-measure. */
    var videoAspectRatio: Float = 16f / 9f
        set(value) {
            val clamped = value.coerceIn(0.2f, 8f)
            if (clamped == field) return
            field = clamped
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (parentWidth == 0 || parentHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // FIT: the largest box that keeps the video's aspect ratio inside the parent.
        var width = parentWidth
        var height = (width / videoAspectRatio).toInt()
        if (height > parentHeight) {
            height = parentHeight
            width = (height * videoAspectRatio).toInt()
        }
        setMeasuredDimension(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}

/**
 * Hosts [PlayerSurfaceView] in Compose and keeps [LowRamPlayer] attached to it.
 *
 * The surface is released on dispose so the decoder's output buffer is returned to the
 * system immediately when the player screen leaves the composition.
 */
@Composable
fun VideoSurface(
    player: LowRamPlayer,
    state: PlayerState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember { PlayerSurfaceView(context) }

    DisposableEffect(player, surfaceView) {
        player.attach(surfaceView.holder.surface)
        onDispose { player.detachSurface() }
    }

    // View mutation must not happen during composition: run it after commit. The setter is
    // a no-op when the ratio is unchanged, so this never re-measures needlessly.
    androidx.compose.runtime.SideEffect {
        surfaceView.videoAspectRatio = state.aspectRatio
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
