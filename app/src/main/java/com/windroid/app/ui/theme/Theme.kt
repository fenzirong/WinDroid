package com.windroid.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Windows 11 Fluent colour tokens.
 *
 * Dark surfaces follow the Mica base ramp (#202020 -> #343434) and the accent is
 * the dark-friendly Fluent accent #4CC2FF. Light surfaces follow the Snow ramp
 * (#F3F3F3 -> #FFFFFF) with the base accent #005FB8.
 */
private object FluentDark {
    val Accent = Color(0xFF4CC2FF)
    val OnAccent = Color(0xFF00344C)
    val AccentContainer = Color(0xFF00496A)
    val OnAccentContainer = Color(0xFFC9E7FF)
    val Base = Color(0xFF202020)
    val BaseHighest = Color(0xFF343434)
    val OnBase = Color(0xFFF3F3F3)
    val OnBaseMuted = Color(0xFFC7C7C7)
    val Outline = Color(0xFF5C5C5C)
    val OutlineSubtle = Color(0xFF414141)
    val Critical = Color(0xFFFFB4AB)
}

private object FluentLight {
    val Accent = Color(0xFF005FB8)
    val OnAccent = Color(0xFFFFFFFF)
    val AccentContainer = Color(0xFFD6E7F8)
    val OnAccentContainer = Color(0xFF00203C)
    val Base = Color(0xFFF3F3F3)
    val BaseHighest = Color(0xFFFFFFFF)
    val OnBase = Color(0xFF1A1A1A)
    val OnBaseMuted = Color(0xFF454545)
    val Outline = Color(0xFF8A8A8A)
    val OutlineSubtle = Color(0xFFCFCFCF)
    val Critical = Color(0xFFC42B1C)
}

private val WinDroidDarkColorScheme = darkColorScheme(
    primary = FluentDark.Accent,
    onPrimary = FluentDark.OnAccent,
    primaryContainer = FluentDark.AccentContainer,
    onPrimaryContainer = FluentDark.OnAccentContainer,
    inversePrimary = FluentLight.Accent,
    secondary = Color(0xFFB7C9D8),
    onSecondary = Color(0xFF1E303C),
    secondaryContainer = Color(0xFF2C3F4B),
    onSecondaryContainer = Color(0xFFD3E5F3),
    tertiary = Color(0xFFC7C6DD),
    onTertiary = Color(0xFF2E2E42),
    tertiaryContainer = Color(0xFF44445A),
    onTertiaryContainer = Color(0xFFE3E1F9),
    background = FluentDark.Base,
    onBackground = FluentDark.OnBase,
    surface = FluentDark.Base,
    onSurface = FluentDark.OnBase,
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = FluentDark.OnBaseMuted,
    surfaceTint = FluentDark.Accent,
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF161616),
    surfaceContainerLowest = Color(0xFF141414),
    surfaceContainerLow = Color(0xFF232323),
    surfaceContainer = Color(0xFF272727),
    surfaceContainerHigh = Color(0xFF2D2D2D),
    surfaceContainerHighest = FluentDark.BaseHighest,
    inverseSurface = FluentDark.OnBase,
    inverseOnSurface = FluentDark.Base,
    error = FluentDark.Critical,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = FluentDark.Outline,
    outlineVariant = FluentDark.OutlineSubtle,
    scrim = Color(0xFF000000),
)

private val WinDroidLightColorScheme = lightColorScheme(
    primary = FluentLight.Accent,
    onPrimary = FluentLight.OnAccent,
    primaryContainer = FluentLight.AccentContainer,
    onPrimaryContainer = FluentLight.OnAccentContainer,
    inversePrimary = FluentDark.Accent,
    secondary = Color(0xFF4B5F6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E6F2),
    onSecondaryContainer = Color(0xFF101E27),
    tertiary = Color(0xFF5A5870),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E1F7),
    onTertiaryContainer = Color(0xFF18162B),
    background = FluentLight.Base,
    onBackground = FluentLight.OnBase,
    surface = FluentLight.Base,
    onSurface = FluentLight.OnBase,
    surfaceVariant = Color(0xFFE7E7E7),
    onSurfaceVariant = FluentLight.OnBaseMuted,
    surfaceTint = FluentLight.Accent,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCDCDC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = FluentLight.BaseHighest,
    surfaceContainerHighest = Color(0xFFEDEDED),
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = Color(0xFFF3F3F3),
    error = FluentLight.Critical,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = FluentLight.Outline,
    outlineVariant = FluentLight.OutlineSubtle,
    scrim = Color(0xFF000000),
)

/** Fluent geometry: slightly tighter corners than the Material defaults. */
private val WinDroidShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/**
 * Applies the WinDroid Material 3 theme.
 *
 * @param darkTheme whether to use the Fluent dark ramp; defaults to the system
 *   setting so the container list matches the OS appearance.
 */
@Composable
fun WinDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WinDroidDarkColorScheme else WinDroidLightColorScheme,
        shapes = WinDroidShapes,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Shared design-system atoms
//
// Kept in the theme package on purpose: every screen composes out of these, so
// the Fluent look stays consistent and the screens carry no duplicated
// surface/elevation plumbing.
// ---------------------------------------------------------------------------

/**
 * Rounded Fluent "card" with an optional title, subtitle and trailing slot.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

/** Small rounded label used for driver / state badges. */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor,
                )
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Status indicator dot (runtime ready / external device attached). */
@Composable
fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF6CCB5F),
    inactiveColor: Color = MaterialTheme.colorScheme.outline,
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color = if (active) activeColor else inactiveColor, shape = CircleShape),
    )
}

/** Outlined text field with a Fluent-left-aligned label above it. */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            shape = MaterialTheme.shapes.small,
            placeholder = placeholder?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Compact dropdown picker for enum-like option lists.
 *
 * Implemented as an outlined button plus a [DropdownMenu] rather than an
 * exposed-dropdown text field: the whole row stays tappable on touch devices and
 * the keyboard never pops up for a pure selection.
 */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = optionLabel(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                        trailingIcon = {
                            if (option == selected) {
                                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Title + optional explanation on the left, a switch on the right. */
@Composable
fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Title + live value label above a slider. */
@Composable
fun SliderSetting(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0.25f..3f,
    steps: Int = 10,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
