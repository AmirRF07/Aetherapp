package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A compact pill-style segmented control that animates the selected segment.
 *
 * ## Why this wraps into rows (1.3.0-r2 layout fix)
 *
 * Every option used to get `weight(1f)` in ONE row. That is fine for three
 * choices and falls apart at five: adding the `MIM` protocol (`MASQUE x2`) in
 * 1.3.0 left each segment 20% of the card width, which is narrower than the word
 * "WireGuard" -- so the label broke across two lines mid-word while its
 * neighbours stayed on one, and the whole control read as damaged. Persian makes
 * it worse, not better: the labels are longer, and a cramped RTL row has no room
 * to breathe.
 *
 * So a control with more than [MAX_PER_ROW] options becomes a GRID of equal
 * cells: five options are 3 + 2, six are 3 + 3, seven are 4 + 3. The short last
 * row is padded with a weighted [Spacer], so a cell in row two is exactly as
 * wide as a cell in row one instead of stretching to fill the gap - the cells
 * stay a uniform grid and the missing slot reads as intentional.
 *
 * Labels are held to a single line ([TextOverflow.Ellipsis] as the last resort):
 * a wrapped label is what this fix exists to prevent, and at a third of the width
 * every label the app ships fits comfortably.
 *
 * Three or fewer options keep the exact single-row look they always had.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Balanced rows: never more than MAX_PER_ROW per row, and never a row with a
    // single lonely cell when it can be avoided (5 -> 3+2, not 4+1).
    val perRow = if (options.size <= MAX_PER_ROW) {
        options.size.coerceAtLeast(1)
    } else {
        val rows = (options.size + MAX_PER_ROW - 1) / MAX_PER_ROW
        (options.size + rows - 1) / rows
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // The track sits INSIDE a settings card, which is already
            // surfaceVariant: a translucent surfaceVariant on top of it was
            // very nearly invisible, so the control read as bare text. It now
            // uses the next step up the elevation ramp, which is what makes a
            // nested control legible on a dark surface.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { option ->
                    Segment(
                        text = label(option),
                        isSelected = option == selected,
                        enabled = enabled,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the cells of a short last row the same width as the rest.
                val missing = perRow - row.size
                if (missing > 0) Spacer(Modifier.weight(missing.toFloat()))
            }
        }
    }
}

@Composable
private fun Segment(
    text: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(160),
        label = "segbg",
    )
    val fg by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(160),
        label = "segfg",
    )
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = fg,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
    )
}

/**
 * Most segments the control will put in one row. Three is what fits the longest
 * label the app ships ("WireGuard", "MASQUE x2", Persian "وارپ×۲") on a 360dp
 * phone without shrinking the type.
 */
private const val MAX_PER_ROW = 3
