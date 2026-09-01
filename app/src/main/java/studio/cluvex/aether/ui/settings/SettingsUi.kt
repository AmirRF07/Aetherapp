package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy750
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.Navy950
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkDim
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * The settings design system.
 *
 * ## What this replaces, and why it had to be replaced
 *
 * Every setting in the app used to live in ONE composable: a single collapsible
 * "Advanced settings" card, roughly 900 lines and about fifty controls, rendered
 * inside a `Column` + `verticalScroll`. That has three consequences, and all
 * three are exactly what the app was reported for:
 *
 *  1. **It opened slowly and janked.** A `Column` + `verticalScroll` composes and
 *     MEASURES every child, on screen or not, so opening the panel built all
 *     fifty controls in one frame - and it did so while a bottom sheet was
 *     animating in, which is the worst possible moment. The old code even
 *     admitted this, deferring the content by a frame and reserving a 320dp
 *     placeholder to hide the stutter. The stutter was the layout, not the
 *     animation.
 *  2. **Every change re-ran all of it.** `ConnectionProfile` was inferred as an
 *     unstable Compose type (see the `@Immutable` note there), so one keystroke
 *     in one text field recomposed the whole card.
 *  3. **It did not look like a settings screen.** One giant scroll with bare
 *     labels and no grouping, hierarchy or navigation.
 *
 * The rewrite follows the pattern the big vendors' own system apps use: a
 * top-level list of CATEGORIES, each opening its own page, with controls grouped
 * into cards, choices presented in a bottom sheet, and one clear title per
 * screen. Every page is a [LazyColumn], so a page only ever composes the rows
 * that are actually visible; combined with the stability fix, a switch now
 * recomposes its own row and nothing else.
 *
 * The whole system is pinned to the navy ramp rather than to a wallpaper-derived
 * palette, so it looks the same on every device (see
 * [studio.cluvex.aether.ui.theme.AetherTheme]).
 */

/** Horizontal inset shared by every settings surface, so nothing is ragged. */
private val PagePadding = 16.dp

/** Corner radius of a settings group card. */
private val GroupRadius = 22.dp

/**
 * A settings page: large collapsing title, back arrow, and a lazily composed body.
 *
 * @param content the page body, as [LazyColumn] items. Use [settingsSection] and
 *   the row composables below rather than laying out rows by hand, so every page
 *   keeps identical metrics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Navy950,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            // AutoMirrored: the arrow has to point the other way
                            // in Persian, and a hand-picked ArrowBack does not.
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Navy950,
                    scrolledContainerColor = AppBarScrolled,
                    titleContentColor = OnDark,
                    navigationIconContentColor = OnDark,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = 4.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** The app-bar colour once the large title has collapsed, and the sheet surface. */
private val AppBarScrolled = Navy850

/**
 * One vertical slice of a page, emitted as a single lazy item.
 *
 * IMPORTANT, and the reason this is not `settingsGroup(caption = stringResource(...))`:
 * a `LazyListScope` body is NOT a composable scope, so nothing inside it may call
 * `stringResource` or any other composable. An `item { }` body IS composable, so
 * captions, footers and rows are all built in here where resources can actually
 * be read. Getting this backwards compiles into "@Composable invocations can only
 * happen from the context of a @Composable function", which is exactly the trap a
 * settings screen full of labels walks into.
 *
 * A section is one lazy item rather than one item per row because the rows of a
 * group share a single rounded background: splitting them would draw the card in
 * slices. Sections are still lazy with respect to each OTHER, which is what keeps
 * an off-screen group from being composed at all.
 */
fun LazyListScope.settingsSection(
    content: @Composable ColumnScope.() -> Unit,
) = item {
    Column(modifier = Modifier.fillMaxWidth(), content = content)
}

/** The small coloured caption above a group. */
@Composable
fun GroupCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp),
    )
}

/** The explanatory footnote under a group. */
@Composable
fun GroupFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = OnDarkDim,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
    )
}

/** The rounded card that holds a group's rows. */
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GroupRadius))
            .background(Navy850),
        content = content,
    )
}

/** A hairline between two rows of the same group, inset like a real settings list. */
@Composable
fun RowDivider(inset: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (inset) 60.dp else 0.dp),
        thickness = 0.5.dp,
        color = Navy700,
    )
}

/** The tinted, rounded icon container every leading icon sits in. */
@Composable
private fun RowIcon(icon: ImageVector, enabled: Boolean, tint: Color? = null) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                (tint ?: MaterialTheme.colorScheme.primary)
                    .copy(alpha = if (enabled) 0.16f else 0.07f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (tint ?: MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * The shared row body: leading icon, title, summary, trailing slot.
 *
 * Everything that looks like a row in the settings screens goes through here, so
 * heights, insets and disabled alpha can never drift between two pages.
 */
@Composable
private fun BaseRow(
    title: String,
    summary: String?,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    titleColor: Color? = null,
    iconTint: Color? = null,
    trailing: @Composable () -> Unit = {},
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon = icon, enabled = enabled, tint = iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = (titleColor ?: OnDark).copy(alpha = alpha),
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkMuted.copy(alpha = alpha),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** A row that opens another page. */
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
) = BaseRow(
    title = title,
    summary = summary,
    icon = icon,
    enabled = enabled,
    onClick = onClick,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            // AutoMirrored so the chevron points left in Persian.
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = OnDarkDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A row carrying a switch. Tapping anywhere on the row toggles it. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = BaseRow(
    title = title,
    summary = summary,
    icon = icon,
    enabled = enabled,
    onClick = { onCheckedChange(!checked) },
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = OnDarkMuted,
            uncheckedTrackColor = Navy800,
            uncheckedBorderColor = Navy700,
        ),
    )
}

/**
 * A row whose value is picked from a list, in a bottom sheet.
 *
 * A sheet rather than the old inline dropdown for two reasons: a list of 56 exit
 * countries in a `DropdownMenu` is unusable on a phone, and a sheet gives every
 * option a full-width, thumb-sized target with the current one ticked - which is
 * how the platform's own settings present a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    sheetTitle: String = title,
) {
    var open by remember { mutableStateOf(false) }
    BaseRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        onClick = { open = true },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label(selected),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    OnDarkDim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = OnDarkDim,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = AppBarScrolled,
        ) {
            Text(
                text = sheetTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = OnDark,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            // Lazily composed: the exit-country picker is 56 rows and only about
            // eight of them are ever on screen.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(options.size) { index ->
                    val option = options[index]
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                open = false
                            }
                            .padding(horizontal = 24.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                OnDark
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A read-only fact with a monospace, always-LTR value (ports, addresses). */
@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    trailing: @Composable () -> Unit = {},
) = BaseRow(
    title = title,
    summary = null,
    icon = icon,
    enabled = true,
    onClick = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            // Technical values are inherently LTR and must not be reordered by
            // the BiDi algorithm in the Persian layout.
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            fontFamily = FontFamily.Monospace,
            color = OnDark,
        )
        trailing()
    }
}

/** A tappable action (reset, run a test, open a link). */
@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) = BaseRow(
    title = title,
    summary = summary,
    icon = icon,
    enabled = enabled,
    onClick = onClick,
    titleColor = if (destructive) MaterialTheme.colorScheme.error else null,
    iconTint = if (destructive) MaterialTheme.colorScheme.error else null,
)

/**
 * A block that hosts a full-width control (a text field, a segmented selector).
 *
 * Kept separate from [BaseRow] because these controls own their own height and
 * label, so forcing them into the leading-icon grid would misalign them.
 */
@Composable
fun SettingsBlock(
    title: String? = null,
    helper: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = OnDarkMuted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        content()
        if (!helper.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkDim,
            )
        }
    }
}

/**
 * One option of a single-choice list, presented in place rather than in a sheet.
 *
 * Used for the language, where a picker would be the wrong control: a user who
 * cannot read the language the app is currently in still has to be able to find
 * the row that says "English" or "فارسی", and both have to be visible at once.
 */
@Composable
fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = BaseRow(
    title = title,
    summary = summary,
    icon = icon,
    enabled = enabled,
    onClick = onSelect,
) {
    RadioButton(
        selected = selected,
        onClick = onSelect,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = MaterialTheme.colorScheme.primary,
            unselectedColor = OnDarkDim,
        ),
    )
}

/** A non-interactive explanation inside a group (why something is greyed out). */
@Composable
fun SettingsNoticeRow(text: String, icon: ImageVector? = null) = BaseRow(
    title = text,
    summary = null,
    icon = icon,
    enabled = true,
    onClick = null,
)

/** The surface colour a nested control (input, segmented track) sits on. */
val ControlSurface: Color = Navy800

/** The pressed / selected track colour of a nested control. */
val ControlSurfaceSelected: Color = Navy750
