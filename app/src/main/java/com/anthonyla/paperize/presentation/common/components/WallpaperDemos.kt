package com.anthonyla.paperize.presentation.common.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.core.ScalingType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Small drawn phones that show what a wallpaper setting does, so a choice explains itself before it
 * is made. The pictures are drawn, not loaded, so they work before any album exists.
 */

private val DEMO_SKIES = listOf(
    listOf(Color(0xFFFFC3A0), Color(0xFFFF7E79), Color(0xFF6A4C93)),
    listOf(Color(0xFFA8E6FF), Color(0xFF4FA3D9), Color(0xFF1B4F8A)),
    listOf(Color(0xFFD9F2C4), Color(0xFF7CC47F), Color(0xFF2E6B47))
)
private const val PICTURE_MS = 2400
private val PHONE_SHAPE = RoundedCornerShape(10.dp)

/** Whether a demo is on screen, so one scrolled out of sight stops asking for frames. */
private class OnScreen {
    var visible by mutableStateOf(true)
    val tracker = Modifier.onGloballyPositioned { visible = it.boundsInWindow().height > 0f }
}

/** A phone screen [draw] paints into, sized like a lock screen thumbnail. */
@Composable
fun DemoPhone(modifier: Modifier = Modifier, draw: DrawScope.() -> Unit) {
    Canvas(
        modifier
            .size(width = 54.dp, height = 104.dp)
            .clip(PHONE_SHAPE)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, PHONE_SHAPE),
        onDraw = draw
    )
}

/**
 * Draw demo picture [index]: a picture half again as wide as the screen, slid by [pan] from its left
 * edge (0) to its right edge (1), so a moving sun shows the picture moving.
 */
fun DrawScope.drawDemoPicture(index: Int, pan: Float = 0.5f, alpha: Float = 1f) {
    val sky = DEMO_SKIES[index.mod(DEMO_SKIES.size)]
    val width = size.width * 1.5f
    val left = -(width - size.width) * pan
    drawRect(Brush.linearGradient(sky, start = Offset(left, 0f), end = Offset(left + width, size.height)), alpha = alpha)
    drawCircle(Color.White.copy(alpha = 0.8f), radius = size.width * 0.14f, center = Offset(left + width * 0.62f, size.height * 0.28f), alpha = alpha)
    drawCircle(Color.Black.copy(alpha = 0.18f), radius = width * 0.6f, center = Offset(left + width * 0.3f, size.height * 1.3f), alpha = alpha)
}

/**
 * Draw a landscape photo (3:2) on this phone the way [scaling] sets one: Fill crops it to cover,
 * Fit shows all of it with bars, Stretch squeezes it to the screen, and Original keeps its own size,
 * here a typical photo's, capped like the real one at twice the screen.
 */
fun DrawScope.drawDemoPhoto(scaling: ScalingType) {
    val photo = Size(PHOTO_WIDTH, PHOTO_HEIGHT)
    val (width, height) = when (scaling) {
        ScalingType.FILL -> max(size.width / photo.width, size.height / photo.height).let { photo.width * it to photo.height * it }
        ScalingType.FIT -> min(size.width / photo.width, size.height / photo.height).let { photo.width * it to photo.height * it }
        ScalingType.STRETCH -> size.width to size.height
        ScalingType.NONE -> size.width * 2f to size.width * 2f * photo.height / photo.width
    }
    withTransform({
        translate((size.width - width) / 2f, (size.height - height) / 2f)
        scale(width / photo.width, height / photo.height, pivot = Offset.Zero)
        clipRect(0f, 0f, photo.width, photo.height)
    }) {
        drawRect(Brush.verticalGradient(DEMO_SKIES[0]), size = photo)
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 24f, center = Offset(210f, 70f))
        drawCircle(Color(0xFF3B2A5C), radius = 170f, center = Offset(90f, 300f))
        drawCircle(Color(0xFF2A1D42), radius = 150f, center = Offset(270f, 320f))
    }
}
private const val PHOTO_WIDTH = 300f
private const val PHOTO_HEIGHT = 200f

/** Static mode: each picture cuts straight to the next and holds still. */
@Composable
fun StaticModeDemo(animate: Boolean, modifier: Modifier = Modifier) {
    val onScreen = remember { OnScreen() }
    val phone = modifier.then(onScreen.tracker)
    if (!animate || !onScreen.visible) {
        DemoPhone(phone) { drawDemoPicture(0) }
        return
    }
    val time by rememberInfiniteTransition(label = "static demo").animateFloat(
        initialValue = 0f,
        targetValue = DEMO_SKIES.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(DEMO_SKIES.size * PICTURE_MS, easing = LinearEasing)),
        label = "picture"
    )
    DemoPhone(phone) { drawDemoPicture(time.toInt()) }
}

/** Live mode: the picture glides as home pages are swiped, then fades into the next one. */
@Composable
fun LiveModeDemo(animate: Boolean, modifier: Modifier = Modifier) {
    val onScreen = remember { OnScreen() }
    val phone = modifier.then(onScreen.tracker)
    if (!animate || !onScreen.visible) {
        DemoPhone(phone) { drawDemoPicture(0) }
        return
    }
    val time by rememberInfiniteTransition(label = "live demo").animateFloat(
        initialValue = 0f,
        targetValue = DEMO_SKIES.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(DEMO_SKIES.size * PICTURE_MS, easing = LinearEasing)),
        label = "picture"
    )
    DemoPhone(phone) {
        val picture = time.toInt()
        val pan = 0.5f + 0.5f * sin(time * 2f * PI.toFloat())
        drawDemoPicture(picture, pan)
        val fade = ((time - picture - 0.65f) / 0.35f).coerceIn(0f, 1f)
        if (fade > 0f) drawDemoPicture(picture + 1, pan, fade)
    }
}

/**
 * A home screen swiped across three pages: its icons move a whole page each time, and with
 * [scrolling] on the picture moves with them a little; off, it stays put.
 */
@Composable
fun ScrollingDemo(scrolling: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val onScreen = remember { OnScreen() }
    // Read while drawing, so each frame redraws the phone without recomposing it.
    val pageState: State<Float> = if (!animate || !onScreen.visible) remember { mutableFloatStateOf(0f) } else rememberInfiniteTransition(label = "scrolling demo").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 6000
                0f at 0
                0f at 700 using FastOutSlowInEasing
                1f at 1300
                1f at 2000 using FastOutSlowInEasing
                2f at 2600
                2f at 3300 using FastOutSlowInEasing
                1f at 3900
                1f at 4600 using FastOutSlowInEasing
                0f at 5200
            }
        ),
        label = "page"
    )
    DemoPhone(modifier.then(onScreen.tracker)) {
        val page = pageState.value
        drawDemoPicture(index = 1, pan = if (scrolling) page / 2f else 0.5f)
        val icon = size.width * 0.2f
        val gap = (size.width - icon * 3) / 4
        for (p in 0..2) {
            val pageLeft = (p - page) * size.width
            for (row in 0..1) for (col in 0..2) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(pageLeft + gap + col * (icon + gap), size.height * 0.45f + row * (icon + gap)),
                    size = Size(icon, icon),
                    cornerRadius = CornerRadius(icon / 3)
                )
            }
        }
        val dot = size.width * 0.05f
        for (p in 0..2) {
            val current = 1f - abs(page - p).coerceAtMost(1f)
            drawCircle(
                color = Color.White.copy(alpha = 0.45f + 0.5f * current),
                radius = dot,
                center = Offset(size.width / 2 + (p - 1) * dot * 3.5f, size.height * 0.9f)
            )
        }
    }
}
