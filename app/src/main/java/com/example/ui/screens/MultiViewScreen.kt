package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SavedStreamEntity
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.SportDisplayFormatter
import com.example.ui.PrismUiState
import com.example.ui.adaptive.AdaptiveLayoutSpec
import com.example.ui.adaptive.adaptiveInteractiveSurface
import com.example.ui.player.MultiViewTilePlayer

@Composable
fun MultiViewScreen(
    uiState: PrismUiState,
    layoutSpec: AdaptiveLayoutSpec,
    userAgent: String,
    savedStreams: List<SavedStreamEntity>,
    onToggleQuadMode: (Boolean) -> Unit,
    onFocusSlot: (Int) -> Unit,
    onAssignSlot: (Int, ActivePlayableFeed?) -> Unit,
    onExpandFeedToStage: (ActivePlayableFeed) -> Unit,
    onAutoFillLive: () -> Unit,
    onClearAll: () -> Unit,
    onExitMultiView: () -> Unit,
    onSlotExhaustedCandidates: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pickingForSlot by remember { mutableStateOf<Int?>(null) }

    val availableFeeds = remember(uiState.resolvedStreams, savedStreams) {
        val fromLive = uiState.resolvedStreams.filter { it.liveUrl != null }.map { s ->
            val url = s.liveUrl.orEmpty()
            ActivePlayableFeed(
                id = s.ref.key.ifEmpty { url },
                title = s.ref.displayTitle,
                subtitle = s.ref.displaySport,
                primaryUrl = url,
                candidateUrls = (listOf(url) + s.candidates).distinct(),
                category = s.ref.displaySport,
                viewers = s.viewers,
                customHeaders = s.ref.customHeaders
            )
        }
        val fromSaved = savedStreams.map { ch ->
            val cat = SportDisplayFormatter.formatSportName(ch.groupName)
            ActivePlayableFeed(
                id = "saved_${ch.id}",
                title = SportDisplayFormatter.cleanConsumerTitle(ch.title, null, cat, null),
                subtitle = cat,
                primaryUrl = ch.streamUrl,
                candidateUrls = listOf(ch.streamUrl),
                category = cat
            )
        }
        (fromLive + fromSaved).distinctBy { it.primaryUrl }
    }

    // 100% Fullscreen Frameless Broadcast Wall — zero titles, zero toolbars, zero wasted pixels
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("multiview_screen")
    ) {
        val isLandscapeWall = maxWidth >= maxHeight || layoutSpec.isWide

        if (!uiState.isQuadMode) {
            if (isLandscapeWall) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    for (slot in 0..1) {
                        MultiViewSlotCell(
                            slotIndex = slot,
                            feed = uiState.multiViewSlots.getOrNull(slot),
                            isAudioFocused = uiState.focusedMultiViewSlot == slot,
                            userAgent = userAgent,
                            onSelectFocus = { onFocusSlot(slot) },
                            onRequestChangeChannel = { pickingForSlot = slot },
                            onExpand = onExpandFeedToStage,
                            onExhaustedCandidates = { onSlotExhaustedCandidates(slot) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    for (slot in 0..1) {
                        MultiViewSlotCell(
                            slotIndex = slot,
                            feed = uiState.multiViewSlots.getOrNull(slot),
                            isAudioFocused = uiState.focusedMultiViewSlot == slot,
                            userAgent = userAgent,
                            onSelectFocus = { onFocusSlot(slot) },
                            onRequestChangeChannel = { pickingForSlot = slot },
                            onExpand = onExpandFeedToStage,
                            onExhaustedCandidates = { onSlotExhaustedCandidates(slot) },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                for (row in 0..1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        for (col in 0..1) {
                            val slot = row * 2 + col
                            MultiViewSlotCell(
                                slotIndex = slot,
                                feed = uiState.multiViewSlots.getOrNull(slot),
                                isAudioFocused = uiState.focusedMultiViewSlot == slot,
                                userAgent = userAgent,
                                onSelectFocus = { onFocusSlot(slot) },
                                onRequestChangeChannel = { pickingForSlot = slot },
                                onExpand = onExpandFeedToStage,
                                onExhaustedCandidates = { onSlotExhaustedCandidates(slot) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }

    // On-demand Channel & Layout Picker Modal (takes zero permanent screen space)
    if (pickingForSlot != null) {
        val slotIdx = pickingForSlot ?: 0
        val currentSlotFeed = uiState.multiViewSlots.getOrNull(slotIdx)

        AlertDialog(
            onDismissRequest = { pickingForSlot = null },
            title = { Text("Screen ${slotIdx + 1}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Quick Grid Layout & Wall Actions inside modal
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FocusableFilterPill(
                            label = "2 Screens",
                            selected = !uiState.isQuadMode,
                            onClick = { onToggleQuadMode(false) }
                        )
                        FocusableFilterPill(
                            label = "4 Screens",
                            selected = uiState.isQuadMode,
                            onClick = { onToggleQuadMode(true) }
                        )
                        FocusableFilterPill(
                            label = stringResource(R.string.multiview_autofill),
                            selected = false,
                            onClick = {
                                onAutoFillLive()
                                pickingForSlot = null
                            }
                        )
                    }

                    if (currentSlotFeed != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FocusableFilterPill(
                                label = "Watch Fullscreen",
                                selected = false,
                                onClick = {
                                    pickingForSlot = null
                                    onExpandFeedToStage(currentSlotFeed)
                                }
                            )
                            FocusableFilterPill(
                                label = "Clear Screen",
                                selected = false,
                                onClick = {
                                    onAssignSlot(slotIdx, null)
                                    pickingForSlot = null
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    if (availableFeeds.isEmpty()) {
                        Text(
                            text = "No live broadcasts are on air right now. Exit MultiView and select Refresh on Live Now.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            items(availableFeeds, key = { "${it.id}_${it.primaryUrl}" }) { candidate ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .adaptiveInteractiveSurface(
                                            shape = RoundedCornerShape(12.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            scaleOnFocus = 1.02f,
                                            onClick = {
                                                onAssignSlot(slotIdx, candidate)
                                                onFocusSlot(slotIdx)
                                                pickingForSlot = null
                                            }
                                        )
                                        .padding(horizontal = 14.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        text = candidate.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = candidate.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickingForSlot = null
                        onExitMultiView()
                    }
                ) {
                    Text("Exit MultiView")
                }
            },
            dismissButton = {
                if (uiState.multiViewSlots.any { it != null }) {
                    TextButton(
                        onClick = {
                            onClearAll()
                            pickingForSlot = null
                        }
                    ) {
                        Text(stringResource(R.string.multiview_clear_all))
                    }
                }
                TextButton(onClick = { pickingForSlot = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun MultiViewSlotCell(
    slotIndex: Int,
    feed: ActivePlayableFeed?,
    isAudioFocused: Boolean,
    userAgent: String,
    onSelectFocus: () -> Unit,
    onRequestChangeChannel: () -> Unit,
    onExpand: (ActivePlayableFeed) -> Unit,
    onExhaustedCandidates: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (feed != null) {
        MultiViewTilePlayer(
            slotIndex = slotIndex,
            feed = feed,
            isAudioFocused = isAudioFocused,
            userAgent = userAgent,
            onSelectFocus = onSelectFocus,
            onRequestChangeChannel = onRequestChangeChannel,
            onExpandSingle = { onExpand(feed) },
            onSlotExhaustedCandidates = onExhaustedCandidates,
            modifier = modifier
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .adaptiveInteractiveSurface(
                    shape = RectangleShape,
                    containerColor = Color(0xFF0A101B),
                    focusedContainerColor = Color(0xFF142033),
                    scaleOnFocus = 1.0f,
                    onClick = onRequestChangeChannel
                )
                .testTag("multiview_empty_slot_$slotIndex")
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.multiview_add_slot),
                tint = Color.White.copy(alpha = 0.28f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
