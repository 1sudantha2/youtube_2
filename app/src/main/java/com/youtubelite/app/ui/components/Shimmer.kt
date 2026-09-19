package com.youtubelite.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * ONE shared infinite transition per screen drives every skeleton row.
 * The animated phase is read inside the draw lambda (draw-phase read), so the
 * animation runs at display refresh rate with ZERO recompositions of the
 * placeholder hierarchy — a single DisplayList re-record per frame.
 */
@Composable
fun rememberShimmerPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing)),
        label = "shimmerPhase",
    )
}

private val ShimmerColors = listOf(
    Color(0xFF232323),
    Color(0xFF343434),
    Color(0xFF232323),
)

fun Modifier.shimmer(phase: State<Float>): Modifier = drawBehind {
    val p = phase.value
    val w = size.width.coerceAtLeast(1f)
    val x = (p * 2f - 0.5f) * w
    drawRect(
        Brush.linearGradient(
            colors = ShimmerColors,
            start = Offset(x - w, 0f),
            end = Offset(x + w, size.height),
        )
    )
}
