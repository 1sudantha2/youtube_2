package app.you.tube.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draw-phase-only shimmer: the animated progress State is read inside
 * `drawBehind`, so the effect invalidates *draw* only — zero recompositions
 * per frame, zero layout passes, no steady-state allocations.
 */
@Composable
fun Modifier.shimmer(cornerRadius: Dp = 10.dp): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainer
    return drawBehind {
        val width = size.width
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(progress * width, 0f),
            end = Offset(progress * width + width, size.height)
        )
        drawRoundRect(brush = brush, cornerRadius = CornerRadius(cornerRadius.toPx()))
    }
}

/** Skeleton placeholder for a full-width video card. */
@Composable
fun ShimmerVideoCard(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(horizontal = 12.dp)
                .shimmer(12.dp)
        )
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).shimmer(18.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Box(Modifier.fillMaxWidth(0.95f).height(14.dp).shimmer(4.dp))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.55f).height(12.dp).shimmer(4.dp))
            }
        }
    }
}

/** Skeleton placeholder for a compact (row) video card — used in related lists. */
@Composable
fun ShimmerCompactCard(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth(0.45f).aspectRatio(16f / 9f).shimmer(8.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Box(Modifier.fillMaxWidth().height(13.dp).shimmer(4.dp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(11.dp).shimmer(4.dp))
        }
    }
}
