package dev.wonderdesk.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Rows that scroll sideways (chips, tool pickers, filters) used to look like they simply ended at the screen edge, so nobody knew
 * there was more. These modifiers fade the edge where more is hidden and put a small round arrow there; tapping the arrow
 * scrolls on, and a row with hidden items gives a short nudge the first time it shows so the movement gives it away.
 */

/** The rows that already nudged in this run of the app, so each one does it once and not every time the screen redraws. */
private val nudged = HashSet<String>()

/** Sideways scrolling with a visible hint that there is more: use instead of `horizontalScroll(rememberScrollState())`. */
fun Modifier.scrollRow(): Modifier = composed {
    val state = rememberScrollState()
    val key = currentCompositeKeyHashString()
    val nudge = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(Unit) {
        if (key in nudged) return@LaunchedEffect
        // Wait for the row to be measured, then peek a little way in and back so the eye catches that it moves.
        delay(700)
        if (state.maxValue > 0 && state.value == 0) {
            nudged += key
            val a = Animatable(0f)
            a.animateTo(nudge.coerceAtMost(state.maxValue.toFloat()), tween(380)) { state.dispatchRawDelta(value - state.value) }
            a.animateTo(0f, tween(420)) { state.dispatchRawDelta(value - state.value) }
        }
    }
    scrollHint(state).horizontalScroll(state)
}

@androidx.compose.runtime.Composable
private fun currentCompositeKeyHashString(): String = androidx.compose.runtime.currentCompositeKeyHash.toString()

/** Fades the edges of a sideways scrolling row where more is hidden, with a tappable arrow. Put it before the scroll modifier. */
fun Modifier.scrollHint(state: ScrollableState): Modifier = composed {
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val fade = with(density) { 40.dp.toPx() }
    val button = with(density) { 26.dp.toPx() }
    val hit = with(density) { 40.dp.toPx() }
    val arrowBg = remember(p) { p.accent }
    val arrowFg = remember(p) { p.onAccent }
    this
        .pointerInput(state) {
            // Taps on the arrows scroll by most of a screen; they are caught before the chips underneath see them.
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val w = size.width
                val forward = state.canScrollForward && down.position.x > w - hit
                val back = state.canScrollBackward && down.position.x < hit
                if (!forward && !back) return@awaitEachGesture
                down.consume()
                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                up.consume()
                scope.launch { state.animateScrollBy((if (forward) 1 else -1) * w * .75f) }
            }
        }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            if (w <= fade * 3) return@drawWithContent
            val more = state.canScrollForward; val less = state.canScrollBackward
            // Fade what is about to slide in or out, so the row visibly carries on past the edge.
            if (more) drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = w - fade * 1.6f, endX = w), blendMode = BlendMode.DstIn)
            if (less) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade * 1.2f), blendMode = BlendMode.DstIn)
            if (more) arrow(Offset(w - button / 2f - 2f, size.height / 2f), button, arrowBg, arrowFg, right = true)
            if (less) arrow(Offset(button / 2f + 2f, size.height / 2f), button, arrowBg, arrowFg, right = false)
        }
}

/** Same hint for a row that keeps its own [ScrollState]. */
fun Modifier.scrollRow(state: ScrollState): Modifier = scrollHint(state).horizontalScroll(state)

private fun DrawScope.arrow(c: Offset, d: Float, bg: Color, fg: Color, right: Boolean) {
    val r = d / 2f
    drawCircle(Color.Black.copy(alpha = .18f), r + 1.5f, c.copy(y = c.y + 1.5f))
    drawCircle(bg.copy(alpha = .92f), r, c)
    val s = r * .36f; val dir = if (right) 1f else -1f
    val path = Path().apply { moveTo(c.x - s * .5f * dir, c.y - s); lineTo(c.x + s * .6f * dir, c.y); lineTo(c.x - s * .5f * dir, c.y + s) }
    drawPath(path, fg, style = Stroke(r * .22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
