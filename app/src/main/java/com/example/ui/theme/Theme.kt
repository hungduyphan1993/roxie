package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun PrismCastTheme(
    palette: BrandPalette = BrandPalette.SIGNAL_MINT,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primaryDark,
            onPrimary = palette.onPrimaryDark,
            primaryContainer = palette.primaryContainerDark,
            onPrimaryContainer = ObsidianTextPrimary,
            secondary = palette.secondaryDark,
            onSecondary = Color(0xFF04131E),
            secondaryContainer = ObsidianSurfaceBright,
            onSecondaryContainer = ObsidianTextPrimary,
            tertiary = StatusLiveGreen,
            background = ObsidianBg,
            onBackground = ObsidianTextPrimary,
            surface = ObsidianSurface,
            onSurface = ObsidianTextPrimary,
            surfaceVariant = ObsidianSurfaceElevated,
            onSurfaceVariant = ObsidianTextSecondary,
            surfaceContainerLowest = ObsidianBg,
            surfaceContainerLow = ObsidianSurface,
            surfaceContainer = ObsidianSurfaceElevated,
            surfaceContainerHigh = ObsidianSurfaceBright,
            outline = ObsidianOutline,
            error = StatusDeadRed
        )
    } else {
        lightColorScheme(
            primary = palette.primaryLight,
            onPrimary = palette.onPrimaryLight,
            primaryContainer = palette.primaryContainerLight,
            onPrimaryContainer = StudioLightTextPrimary,
            secondary = palette.secondaryDark,
            onSecondary = Color.White,
            secondaryContainer = StudioLightSurfaceVariant,
            onSecondaryContainer = StudioLightTextPrimary,
            tertiary = Color(0xFF059669),
            background = StudioLightBg,
            onBackground = StudioLightTextPrimary,
            surface = StudioLightSurface,
            onSurface = StudioLightTextPrimary,
            surfaceVariant = StudioLightSurfaceVariant,
            onSurfaceVariant = StudioLightTextSecondary,
            surfaceContainerLow = StudioLightSurface,
            surfaceContainer = StudioLightSurfaceVariant,
            outline = StudioLightOutline,
            error = Color(0xFFDC2626)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    PrismCastTheme(darkTheme = darkTheme, content = content)
}
