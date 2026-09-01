package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density

/**
 * Lays [content] out so that it ALWAYS fits the height it is given, by shrinking
 * the whole subtree's density instead of clipping it or handing the user a
 * scrollbar.
 *
 * ## The problem this exists for
 *
 * The home screen is a fixed composition - title, connect button, connection card
 * - and every phone gives it a different amount of room. Up to 1.2.7 the answer
 * was `verticalScroll`, so on a shorter screen (or with a larger system font, or
 * in Persian where several strings wrap to two lines) the bottom of the
 * connection card sat below the fold: the user had to scroll to see the end of
 * the block, and even then part of it stayed under the navigation bar. Hand-tuned
 * `Spacer` heights only ever move the problem to the next screen size.
 *
 * ## How it is fixed
 *
 * `LocalDensity` is what turns every `dp` and `sp` in a subtree into pixels, so
 * overriding it with `density * factor` scales the ENTIRE subtree - paddings,
 * icon sizes, corner radii, stroke widths and type - by one factor, in one place.
 * Nothing needs a `scale` parameter and nothing can be forgotten.
 *
 * The factor is measured, not guessed:
 *
 *  1. measure the content against an UNBOUNDED height to learn what it naturally
 *     wants;
 *  2. if that is taller than the room available, set
 *     `factor *= available / natural` (with a 0.5% margin for pixel rounding),
 *     which invalidates the subtree and re-runs the pass;
 *  3. repeat at most [MAX_PASSES] times, and never grow - the search starts at
 *     `1f` and only ever shrinks, so it is monotone and cannot oscillate. In
 *     practice it converges in one or two passes, inside the same frame.
 *
 * Because this is a real layout at a real density, text is rasterised at the size
 * it ends up being: a scaled-down composition is exactly as sharp as an unscaled
 * one. That is why the density is overridden rather than the far easier
 * `graphicsLayer { scaleX = factor }`, which would scale rendered pixels and
 * leave the whole screen soft.
 *
 * [minFactor] is the floor: past it the content is left to overflow rather than
 * shrunk into unreadability. At the home screen's proportions the floor is
 * unreachable on any shipping phone.
 *
 * The content is drawn transparent until the first pass settles, so the initial
 * frame cannot flash at the wrong size.
 */
@Composable
fun FitToHeight(
    modifier: Modifier = Modifier,
    minFactor: Float = MIN_FIT_FACTOR,
    content: @Composable () -> Unit,
) {
    val outer = LocalDensity.current
    val fit = remember { FitState() }

    Layout(
        modifier = modifier,
        content = {
            CompositionLocalProvider(
                LocalDensity provides Density(outer.density * fit.factor, outer.fontScale),
            ) {
                // The alpha is read inside the layer block, so settling costs a
                // redraw and not a recomposition.
                Box(modifier = Modifier.graphicsLayer { alpha = if (fit.settled) 1f else 0f }) {
                    content()
                }
            }
        },
    ) { measurables, constraints ->
        val available = constraints.maxHeight

        // A new viewport (rotation, split screen, a change to the system font
        // scale) restarts the search from 1f.
        if (available != fit.available ||
            outer.density != fit.density ||
            outer.fontScale != fit.fontScale
        ) {
            fit.available = available
            fit.density = outer.density
            fit.fontScale = outer.fontScale
            fit.passes = 0
            fit.settled = false
            fit.factor = 1f
        }

        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val natural = placeable.height

        var shrinking = false
        if (constraints.hasBoundedHeight && natural > 0) {
            val ratio = available.toFloat() / natural.toFloat()
            if (ratio < 1f && fit.passes < MAX_PASSES) {
                val next = (fit.factor * ratio * FIT_MARGIN).coerceIn(minFactor, 1f)
                if (next < fit.factor - FACTOR_EPSILON) {
                    fit.factor = next
                    fit.passes++
                    shrinking = true
                }
            }
        }
        if (!shrinking) fit.settled = true

        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        val height = if (constraints.hasBoundedHeight) available else natural
        layout(width, height) { placeable.place(0, 0) }
    }
}

/**
 * Search state. Deliberately NOT a `data class` and deliberately outside the
 * composition's snapshot for the bookkeeping fields: only [factor] and [settled]
 * feed the UI, and only they should invalidate anything.
 */
private class FitState {
    var factor by mutableFloatStateOf(1f)
    var settled by mutableStateOf(false)
    var passes: Int = 0
    var available: Int = Int.MIN_VALUE
    var density: Float = -1f
    var fontScale: Float = -1f
}

/** Never shrink the UI below this share of its natural size. */
private const val MIN_FIT_FACTOR = 0.55f

/** Pixel-rounding headroom, so a converged pass is never one pixel over. */
private const val FIT_MARGIN = 0.995f

private const val FACTOR_EPSILON = 0.002f
private const val MAX_PASSES = 4
