package com.example.ui.adaptive

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * D-Pad & Touch friendly Search Bar:
 * - Remains a passive focusable button while navigating with D-Pad Up/Down/Left/Right so the
 *   on-screen keyboard never pops up unexpectedly during directional navigation.
 * - Activates text input + software keyboard only when the user explicitly presses OK/Enter
 *   (or taps on a touchscreen).
 * - Pressing Search/Done on the IME or Back on the remote closes the keyboard and returns
 *   directional D-Pad focus cleanly to the passive search bar.
 */
@Composable
fun DpadFriendlySearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String = stringResource(R.string.search_placeholder),
    testTag: String = "search_bar",
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val barFocusRequester = remember { FocusRequester() }
    val fieldFocusRequester = remember { FocusRequester() }

    var isEditing by remember { mutableStateOf(false) }
    var isBarFocused by remember { mutableStateOf(false) }
    var pendingBarFocusRestore by remember { mutableStateOf(false) }

    fun finishEditing(restoreBarFocus: Boolean) {
        pendingBarFocusRestore = restoreBarFocus
        isEditing = false
        keyboardController?.hide()
    }

    LaunchedEffect(isEditing, pendingBarFocusRestore) {
        if (!isEditing && pendingBarFocusRestore) {
            pendingBarFocusRestore = false
            keyboardController?.hide()
            runCatching { barFocusRequester.requestFocus() }
        }
    }

    if (!isEditing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 56.dp)
                    .focusRequester(barFocusRequester)
                    .onFocusChanged { isBarFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A
                        ) {
                            isEditing = true
                            true
                        } else {
                            false
                        }
                    }
                    .adaptiveInteractiveSurface(
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        scaleOnFocus = 1.012f,
                        onClick = { isEditing = true }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(testTag)
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (isBarFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value.ifEmpty { placeholderText },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isBarFocused) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.search_press_ok_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            if (value.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 48.dp, height = 56.dp)
                        .adaptiveInteractiveSurface(
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            scaleOnFocus = 1.04f,
                            onClick = { onValueChange("") }
                        )
                        .testTag("${testTag}_clear")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        var fieldFocusedOnce by remember { mutableStateOf(false) }
        var textFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    text = value,
                    selection = TextRange(value.length)
                )
            )
        }
        if (textFieldValue.text != value) {
            textFieldValue = textFieldValue.copy(
                text = value,
                selection = TextRange(value.length.coerceAtLeast(0))
            )
        }

        BackHandler(enabled = true) {
            finishEditing(restoreBarFocus = true)
        }

        LaunchedEffect(Unit) {
            runCatching { fieldFocusRequester.requestFocus() }
            keyboardController?.show()
        }

        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { updated ->
                textFieldValue = updated
                if (updated.text != value) {
                    onValueChange(updated.text)
                }
            },
            placeholder = {
                Text(
                    text = placeholderText,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                textFieldValue = TextFieldValue("")
                                onValueChange("")
                            },
                            modifier = Modifier.testTag("${testTag}_clear")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_clear)
                            )
                        }
                    }
                    IconButton(
                        onClick = { finishEditing(restoreBarFocus = true) },
                        modifier = Modifier.testTag("${testTag}_done")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.search_done),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { finishEditing(restoreBarFocus = true) },
                onDone = { finishEditing(restoreBarFocus = true) }
            ),
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(fieldFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                    ) {
                        finishEditing(restoreBarFocus = true)
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        fieldFocusedOnce = true
                    } else if (fieldFocusedOnce) {
                        finishEditing(restoreBarFocus = false)
                    }
                }
                .testTag("${testTag}_active_field")
        )
    }
}
