package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val MobilePlayDarkScheme = darkColorScheme(
    primary = ActionBlue,
    onPrimary = SoftIceText,
    primaryContainer = MutedCyanContainer,
    onPrimaryContainer = SoftIceText,
    secondary = TacticalCoral,
    onSecondary = ObsidianBg,
    secondaryContainer = TacticalCoralContainer,
    onSecondaryContainer = SoftIceText,
    tertiary = StatusAmber,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = SoftIceText,
    surface = CharcoalSurface,
    surfaceContainer = CharcoalSurface,
    surfaceContainerHigh = CharcoalSurfaceHigh,
    onSurface = SoftIceText,
    surfaceVariant = CharcoalSurfaceHigh,
    onSurfaceVariant = SlateMutedText,
    outline = CharcoalBorder,
    outlineVariant = CharcoalBorder,
    error = TacticalCoral,
    onError = ObsidianBg
)

private val MobilePlayShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun MobilePlayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MobilePlayDarkScheme,
        typography = MobilePlayTypography,
        shapes = MobilePlayShapes,
        content = content
    )
}
