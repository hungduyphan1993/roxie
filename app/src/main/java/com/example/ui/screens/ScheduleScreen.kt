package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.EventStatus
import com.example.data.model.StreamEvent
import com.example.ui.PrismUiState
import com.example.ui.adaptive.AdaptiveLayoutSpec
import com.example.ui.adaptive.DpadFriendlySearchBar
import com.example.ui.adaptive.adaptiveInteractiveSurface
import com.example.ui.theme.StatusIdleAmber
import com.example.ui.theme.StatusLiveGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(
    uiState: PrismUiState,
    layoutSpec: AdaptiveLayoutSpec,
    onRefresh: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onToggleLiveOnly: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTuneInEvent: (StreamEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val query = uiState.searchQuery.trim().lowercase()
    val selectedCat = uiState.selectedCategorySlug

    val events = remember(
        uiState.catalog.events,
        selectedCat,
        query,
        uiState.showOnlyLiveEvents
    ) {
        uiState.catalog.events
            .filter { ev ->
                val statusOk = if (uiState.showOnlyLiveEvents) {
                    ev.status == EventStatus.LIVE
                } else {
                    ev.status != EventStatus.ENDED
                }
                val catOk = selectedCat == null || ev.category == selectedCat
                val queryOk = query.isEmpty() ||
                    ev.displayLabel.lowercase().contains(query) ||
                    ev.displayCategory.lowercase().contains(query)
                statusOk && catOk && queryOk
            }
            .sortedWith(
                compareBy<StreamEvent> {
                    when (it.status) {
                        EventStatus.LIVE -> 0
                        EventStatus.UPCOMING -> 1
                        else -> 2
                    }
                }
                    .thenByDescending { it.viewers }
                    .thenBy { it.startUtcMillis ?: Long.MAX_VALUE }
            )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(layoutSpec.gridColumns),
        contentPadding = PaddingValues(
            horizontal = layoutSpec.horizontalPadding,
            vertical = layoutSpec.verticalPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize().testTag("schedule_grid")
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.section_upcoming_events),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "Live & scheduled broadcasts in your local time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!layoutSpec.isTv) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .adaptiveInteractiveSurface(
                                shape = RoundedCornerShape(12.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                enabled = !uiState.isRefreshing,
                                onClick = onRefresh
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_refresh),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        if (uiState.isRefreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RefreshProgressBanner(statusText = uiState.refreshStatusText)
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            DpadFriendlySearchBar(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                testTag = "schedule_search_input"
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FocusableFilterPill(
                        label = stringResource(R.string.filter_all_sports),
                        selected = selectedCat == null,
                        onClick = { onSelectCategory(null) }
                    )
                }
                item {
                    FocusableFilterPill(
                        label = stringResource(R.string.filter_live_now),
                        selected = uiState.showOnlyLiveEvents,
                        leadingDotColor = StatusLiveGreen,
                        onClick = onToggleLiveOnly
                    )
                }
                items(
                    items = uiState.catalog.categories.filter { !it.isMultiview },
                    key = { it.slug }
                ) { cat ->
                    FocusableFilterPill(
                        label = cat.displayTitle,
                        selected = selectedCat == cat.slug,
                        onClick = {
                            onSelectCategory(if (selectedCat == cat.slug) null else cat.slug)
                        }
                    )
                }
            }
        }

        if (events.isEmpty() && !uiState.isRefreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "No Scheduled Broadcasts Match Your Filter",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Try selecting All Sports or refreshing the schedule.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(
                items = events,
                key = { "${it.category}_${it.label}_${it.route}" }
            ) { event ->
                ScheduleEventCard(
                    event = event,
                    isResolving = uiState.resolvingEventRoute == event.route && event.route.isNotEmpty(),
                    onTuneIn = { onTuneInEvent(event) }
                )
            }
        }
    }
}

@Composable
private fun ScheduleEventCard(
    event: StreamEvent,
    isResolving: Boolean,
    onTuneIn: () -> Unit
) {
    val isLive = event.status == EventStatus.LIVE
    val statusColor = if (isLive) StatusLiveGreen else StatusIdleAmber

    val formattedTime = remember(event.startUtcMillis, event.startTimeText) {
        if (event.startUtcMillis != null) {
            SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault())
                .format(Date(event.startUtcMillis))
        } else {
            event.startTimeText ?: "Upcoming"
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveInteractiveSurface(
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                enabled = !isResolving && event.route.isNotBlank(),
                onClick = onTuneIn
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(StatusLiveGreen)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = if (isLive) "LIVE NOW" else "UPCOMING",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = event.displayCategory,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (event.viewers > 0) {
                Text(
                    text = "${event.viewers} watching",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = event.displayLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .adaptiveInteractiveSurface(
                        shape = RoundedCornerShape(10.dp),
                        containerColor = if (isLive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        enabled = !isResolving && event.route.isNotBlank(),
                        scaleOnFocus = 1.04f,
                        onClick = onTuneIn
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                if (isResolving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (isLive) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isLive) {
                            stringResource(R.string.btn_watch_live)
                        } else {
                            stringResource(R.string.btn_tune_in)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isLive) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
