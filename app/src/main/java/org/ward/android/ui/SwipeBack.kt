package org.ward.android.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Right-swipe to leave a page, the same gesture the chat screen uses. Only a mostly horizontal
 * drag is claimed, so lists inside the content keep their vertical scrolling.
 *
 * [enterFromRight] starts the page off-screen and slides it in, which makes a newly opened
 * settings subpage feel like it is being pushed over the page underneath.
 */
@Composable
fun SwipeBack(onBack: () -> Unit, enterFromRight: Boolean = false,
              content: @Composable BoxScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val maxSwipe = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val scope = rememberCoroutineScope()
    var offsetPx by remember { mutableFloatStateOf(if (enterFromRight) maxSwipe else 0f) }
    LaunchedEffect(enterFromRight, maxSwipe) {
        if (enterFromRight && offsetPx > 0f) {
            animate(offsetPx, 0f, animationSpec = tween(240)) { value, _ -> offsetPx = value }
        }
    }
    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(offsetPx.roundToInt(), 0) }
            .pointerInput(maxSwipe) {
                awaitEachGesture {
                    // Start even when the content's own clickable has seen the down.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var totalX = 0f
                    var totalY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y
                        if (!dragging) {
                            if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) break
                            if (totalX > viewConfiguration.touchSlop && totalX > abs(totalY)) {
                                dragging = true
                                change.consume()
                            }
                        }
                        if (dragging) {
                            change.consume()
                            offsetPx = (offsetPx + delta.x).coerceIn(0f, maxSwipe)
                        }
                    }
                    if (dragging) {
                        val shouldClose = offsetPx > maxSwipe * 0.28f
                        scope.launch {
                            animate(offsetPx, if (shouldClose) maxSwipe else 0f, animationSpec = tween(220)) { value, _ ->
                                offsetPx = value
                            }
                            // Leave a completed swipe off-screen. Resetting to 0 here makes the
                            // page flash back before the caller's navigation animation starts.
                            if (shouldClose) onBack()
                        }
                    }
                }
            }
    ) { content() }
}
