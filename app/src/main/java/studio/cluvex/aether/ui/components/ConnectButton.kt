package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/**
 * The centrepiece action: a circular power button with a soft glowing halo, an
 * animated progress ring while busy, and a colour that reflects the current mode.
 *
 * WHAT CHANGED IN THIS REVISION, and why:
 *
 *  - **No travelling ring around the disc.** 1.2.7 had put the connection
 *    card's multi-colour light show around the button as well. Two light shows
 *    on one screen fight each other for attention, the ring's bloom needed a
 *    220 dp box for a 150 dp button (70 dp of pure padding at the top of the
 *    screen - almost exactly the height the content block was missing at the
 *    bottom), and it cost a second set of additive strokes on every frame. The
 *    ring is gone; the travelling light lives on the connection card only.
 *  - **A tick, not a bolt.** The connected glyph is one large rounded tick. A
 *    bolt reads as "power", which is what the *idle* button already says; a tick
 *    reads as "you are through", which is the only thing this button has to
 *    communicate once the tunnel is up.
 *  - **Nothing animates unless it must.** The halo pulse is composed only while
 *    connected and the sweep only while busy, so an idle screen subscribes to no
 *    frame callbacks at all. Both are read inside draw/layer lambdas, so a frame
 *    costs a redraw and never a recomposition.
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = mode == ButtonMode.CONNECTED
    val busy = mode == ButtonMode.BUSY
    val accent = when (mode) {
        ButtonMode.IDLE -> Color(0xFF4C8DFF)
        ButtonMode.BUSY -> Color(0xFF4C8DFF)
        ButtonMode.CONNECTED -> Color(0xFF32E0C4)
        ButtonMode.ERROR -> Color(0xFFFF5C7A)
    }
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val haloPulse = if (connected) rememberHaloPulse() else null
    val spin = if (busy) rememberSpin() else null

    val interaction = remember { MutableInteractionSource() }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(BUTTON_BOX)) {
        // Soft glowing halo behind the button.
        Canvas(modifier = Modifier.size(BUTTON_BOX)) {
            val radius = size.minDimension / 2f * (haloPulse?.value ?: 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedAccent.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
                radius = radius,
            )
        }

        // Inner gradient disc.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DISC)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            animatedAccent.copy(alpha = 0.28f),
                            Color(0xFF0F1626),
                        ),
                    ),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            // Progress sweep while busy.
            if (spin != null) {
                Canvas(modifier = Modifier.size(SWEEP)) {
                    rotate(degrees = spin.value) {
                        drawArc(
                            color = animatedAccent,
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }

            // Soft additive core behind the tick, so the glyph glows out of the
            // disc instead of sitting flat on it.
            if (connected) {
                Canvas(modifier = Modifier.size(CORE)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedAccent.copy(alpha = 0.30f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            val icon = when (mode) {
                ButtonMode.CONNECTED -> Icons.Rounded.Check
                ButtonMode.BUSY -> Icons.Rounded.Autorenew
                else -> Icons.Rounded.PowerSettingsNew
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedAccent,
                modifier = Modifier
                    // One big tick when connected; the other glyphs keep their
                    // original weight, where a huge icon would just look loud.
                    .size(if (connected) TICK_SIZE else ICON_SIZE)
                    .then(
                        if (spin != null) {
                            Modifier.graphicsLayer { rotationZ = spin.value }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** The connected halo's slow breathing. Composed only while connected. */
@Composable
private fun rememberHaloPulse(): State<Float> =
    rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.93f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloPulse",
    )

/** The busy sweep. Composed only while busy. */
@Composable
private fun rememberSpin(): State<Float> =
    rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "spinAngle",
    )

// ---------------------------------------------------------------- geometry
//
// The sizes came down with the ring: with no bloom to leave room for, the box no
// longer needs 70 dp of padding around the disc. Every dp given back here is
// height the content block gets to keep.

private val BUTTON_BOX = 190.dp
private val DISC = 132.dp
private val SWEEP = 116.dp
private val CORE = 112.dp
private val ICON_SIZE = 52.dp

/** The connected tick, sized to fill the disc without touching its rim. */
private val TICK_SIZE = 84.dp
