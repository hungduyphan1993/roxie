package com.example.ui.adaptive

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class DeviceFormFactor {
    PHONE,
    TABLET,
    TV
}

data class AdaptiveLayoutSpec(
    val formFactor: DeviceFormFactor,
    val maxWidth: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val gridColumns: Int,
    val isTv: Boolean,
    val isWide: Boolean
)

@Composable
fun rememberAdaptiveLayoutSpec(availableWidth: Dp): AdaptiveLayoutSpec {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val isTelevisionUiMode = (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val hasTouchscreen = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    val formFactor = when {
        isTelevisionUiMode || (!hasTouchscreen && availableWidth >= 720.dp) -> DeviceFormFactor.TV
        availableWidth >= 600.dp -> DeviceFormFactor.TABLET
        else -> DeviceFormFactor.PHONE
    }

    val columns = when (formFactor) {
        DeviceFormFactor.PHONE -> 1
        DeviceFormFactor.TABLET -> if (availableWidth >= 900.dp) 3 else 2
        DeviceFormFactor.TV -> if (availableWidth >= 960.dp) 3 else 2
    }

    val hPad = when (formFactor) {
        DeviceFormFactor.PHONE -> 16.dp
        DeviceFormFactor.TABLET -> 24.dp
        DeviceFormFactor.TV -> 36.dp
    }

    val vPad = when (formFactor) {
        DeviceFormFactor.PHONE -> 12.dp
        DeviceFormFactor.TABLET -> 16.dp
        DeviceFormFactor.TV -> 22.dp
    }

    return AdaptiveLayoutSpec(
        formFactor = formFactor,
        maxWidth = availableWidth,
        horizontalPadding = hPad,
        verticalPadding = vPad,
        gridColumns = columns,
        isTv = formFactor == DeviceFormFactor.TV,
        isWide = formFactor != DeviceFormFactor.PHONE
    )
}

/**
 * Interactive surface modifier supporting both touch taps (with Material ripple)
 * and TV Remote / D-Pad focus navigation (with glowing primary focus ring and gentle spring scale).
 */
fun Modifier.adaptiveInteractiveSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color? = null,
    focusedContainerColor: Color? = null,
    unfocusedBorderColor: Color? = null,
    enabled: Boolean = true,
    scaleOnFocus: Float = 1.022f,
    onClick: () -> Unit
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused && enabled) scaleOnFocus else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "focusScale"
    )

    val activeBorderColor = if (isFocused && enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        unfocusedBorderColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
    }
    val borderWidth = if (isFocused && enabled) 2.5.dp else 1.dp
    val bgColor = when {
        isFocused && focusedContainerColor != null -> focusedContainerColor
        containerColor != null -> containerColor
        else -> Color.Transparent
    }

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .clip(shape)
        .background(bgColor, shape)
        .border(borderWidth, activeBorderColor, shape)
        .onFocusChanged { isFocused = it.isFocused }
        .clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )
}
