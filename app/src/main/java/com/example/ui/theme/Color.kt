package com.example.ui.theme

import androidx.compose.ui.graphics.Color

enum class BrandPalette(
    val key: String,
    val label: String,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val secondaryDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color
) {
    SIGNAL_MINT(
        key = "signal_mint",
        label = "Signal Mint",
        primaryDark = Color(0xFF00E5A3),
        onPrimaryDark = Color(0xFF00291B),
        primaryContainerDark = Color(0xFF063B2C),
        secondaryDark = Color(0xFF38BDF8),
        primaryLight = Color(0xFF00875E),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFC8FBE7)
    ),
    BROADCAST_CRIMSON(
        key = "broadcast_crimson",
        label = "Broadcast Crimson",
        primaryDark = Color(0xFFFF3B5C),
        onPrimaryDark = Color(0xFF36000A),
        primaryContainerDark = Color(0xFF4C0D19),
        secondaryDark = Color(0xFFFF9F43),
        primaryLight = Color(0xFFD1193E),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFD9DF)
    ),
    CYBER_CYAN(
        key = "cyber_cyan",
        label = "Cyber Cyan",
        primaryDark = Color(0xFF00D4FF),
        onPrimaryDark = Color(0xFF002730),
        primaryContainerDark = Color(0xFF073744),
        secondaryDark = Color(0xFF6366F1),
        primaryLight = Color(0xFF007799),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFCFF4FF)
    ),
    ELECTRIC_AMBER(
        key = "electric_amber",
        label = "Electric Amber",
        primaryDark = Color(0xFFFFB800),
        onPrimaryDark = Color(0xFF2B1D00),
        primaryContainerDark = Color(0xFF443000),
        secondaryDark = Color(0xFFFF6B4A),
        primaryLight = Color(0xFFB27B00),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFEFC2)
    ),
    ULTRAVIOLET(
        key = "ultraviolet",
        label = "Ultraviolet",
        primaryDark = Color(0xFFA855F7),
        onPrimaryDark = Color(0xFF240046),
        primaryContainerDark = Color(0xFF3B1261),
        secondaryDark = Color(0xFFF472B6),
        primaryLight = Color(0xFF7E22CE),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFF3E8FF)
    );

    companion object {
        fun fromKey(key: String): BrandPalette =
            entries.firstOrNull { it.key == key } ?: SIGNAL_MINT
    }
}

// Broadcast Obsidian Surfaces
val ObsidianBg = Color(0xFF070B10)
val ObsidianSurface = Color(0xFF0E141D)
val ObsidianSurfaceElevated = Color(0xFF151E2B)
val ObsidianSurfaceBright = Color(0xFF1E2A3B)
val ObsidianOutline = Color(0xFF28374C)
val ObsidianTextPrimary = Color(0xFFF1F5F9)
val ObsidianTextSecondary = Color(0xFF94A3B8)

// Studio Light Surfaces
val StudioLightBg = Color(0xFFF4F7FB)
val StudioLightSurface = Color(0xFFFFFFFF)
val StudioLightSurfaceVariant = Color(0xFFE8EEF5)
val StudioLightOutline = Color(0xFFCBD5E1)
val StudioLightTextPrimary = Color(0xFF0F172A)
val StudioLightTextSecondary = Color(0xFF475569)

// Semantic Telemetry Colors
val StatusLiveGreen = Color(0xFF10B981)
val StatusIdleAmber = Color(0xFFF59E0B)
val StatusDeadRed = Color(0xFFEF4444)
val StatusForbiddenPurple = Color(0xFFA855F7)
