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
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

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

/**
 * Przeciąganie z dodatkowym progiem: palec (zwłaszcza w rękawicy) przy dotknięciu przycisku − / + lekko się przesuwa,
 * a Windows przekazuje to jako ruch myszy — bez progu każde dotknięcie przycisku przewijało ekran i rzucało go rozpędem
 * („skakanie” ustawień). Przewijamy dopiero po [SLOP] dp ruchu, a rozpęd tylko po prawdziwym przeciągnięciu, z limitem prędkości.
 */
@Composable
private fun dragModifier(s: ScrollState, o: Orientation): Modifier {
    if (!dragToScroll) return Modifier
    val fling = ScrollableDefaults.flingBehavior()
    val density = LocalDensity.current
    val slop = with(density) { SLOP.toPx() }
    val maxV = with(density) { MAX_FLING.toPx() }
    val moved = remember { floatArrayOf(0f) }
    return Modifier.draggable(
        rememberDraggableState { d ->
            val before = abs(moved[0])
            moved[0] += d
            if (abs(moved[0]) > slop) {
                // pierwszy ruch po przekroczeniu progu: bez „nadrabiania” drogi progu (ekran nie skacze)
                s.dispatchRawDelta(-(if (before <= slop) moved[0] - sign(moved[0]) * slop else d))
            }
        },
        o,
        onDragStarted = { moved[0] = 0f },
        onDragStopped = { v ->
            val far = abs(moved[0]) > slop * 2
            moved[0] = 0f
            if (far) s.scroll { with(fling) { performFling(-v.coerceIn(-maxV, maxV)) } }
        },
    )
}

private val SLOP = 24.dp
private val MAX_FLING = 3000.dp
