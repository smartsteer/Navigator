package eu.smartsteer.navigator.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Na Windows dotyk palcem przychodzi jak mysz, a zwykłe przewijanie Compose reaguje tylko na kółko myszy.
 * Tu przeciągnięcie palcem (albo myszą) też przewija, z rozpędem. Na Androidzie — zwykłe przewijanie.
 */
expect val dragToScroll: Boolean

fun Modifier.touchVerticalScroll(state: ScrollState? = null): Modifier = composed {
    val s = state ?: rememberScrollState()
    verticalScroll(s).then(dragModifier(s, Orientation.Vertical))
}

fun Modifier.touchHorizontalScroll(state: ScrollState? = null): Modifier = composed {
    val s = state ?: rememberScrollState()
    horizontalScroll(s).then(dragModifier(s, Orientation.Horizontal))
}

@Composable
private fun dragModifier(s: ScrollState, o: Orientation): Modifier {
    if (!dragToScroll) return Modifier
    val fling = ScrollableDefaults.flingBehavior()
    return Modifier.draggable(
        rememberDraggableState { d -> s.dispatchRawDelta(-d) }, o,
        onDragStopped = { v -> s.scroll { with(fling) { performFling(-v) } } },
    )
}
