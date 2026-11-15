package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SavedStreamEntity
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.EventStatus
import com.example.data.model.ResolvedStream
import com.example.data.model.SportDisplayFormatter
import com.example.data.model.StreamEvent
import com.example.data.model.WhitelabelConfig
import com.example.ui.PrismUiState
import com.example.ui.adaptive.AdaptiveLayoutSpec
import com.example.ui.adaptive.adaptiveInteractiveSurface
import com.example.ui.theme.StatusLiveGreen
import java.text.NumberFormat

@Composable
fun LiveHubScreen(
    uiState: PrismUiState,
    whitelabelConfig: WhitelabelConfig,
    layoutSpec: AdaptiveLayoutSpec,
    savedStreams: List<SavedStreamEntity>,
    onRefresh: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPlayResolvedStream: (ResolvedStream) -> Unit,
    onToggleFavorite: (ResolvedStream) -> Unit,
    onSendStreamToMultiView: (ActivePlayableFeed) -> Unit,
    onResolveAndPlayEvent: (StreamEvent) -> Unit,
    onPlaySavedStream: (SavedStreamEntity) -> Unit,
    onRemoveFavorite: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSearchField by rememberSaveable { mutableStateOf(false) }
    val query = uiState.searchQuery.trim().lowercase()
    val selectedCat = uiState.selectedCategorySlug

    val favoriteKeys = remember(savedStreams) {
        savedStreams.flatMap { listOfNotNull(it.streamPath, it.streamUrl) }.toSet()
    }

    val liveStreams = remember(uiState.resolvedStreams, selectedCat, query, uiState.catalog) {
        uiState.resolvedStreams
            .filter { it.liveUrl != null }
            .filter { item ->
                val matchesCat = selectedCat == null ||
                    item.ref.sport.equals(selectedCat, ignoreCase = true) ||
                    uiState.catalog.events.any { ev ->
                        ev.category == selectedCat &&
                            item.ref.path in uiState.catalog.eventPaths[ev.route].orEmpty()
                    }
                val matchesQuery = query.isEmpty() ||
                    item.ref.displayTitle.lowercase().contains(query) ||
                    item.ref.displaySport.lowercase().contains(query)
                matchesCat && matchesQuery
            }
            .sortedWith(
                compareByDescending<ResolvedStream> { it.viewers }
                    .thenBy { it.ref.displayTitle }
            )
    }

    val liveFeaturedEvents = remember(uiState.catalog.events, selectedCat, query) {
        uiState.catalog.events
            .filter { it.status == EventStatus.LIVE }
            .filter { ev ->
                val matchesCat = selectedCat == null || ev.category == selectedCat
                val matchesQuery = query.isEmpty() ||
                    ev.displayLabel.lowercase().contains(query) ||
                    ev.displayCategory.lowercase().contains(query)
                matchesCat && matchesQuery
            }
            .sortedByDescending { it.viewers }
            .take(8)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(layoutSpec.gridColumns),
        contentPadding = PaddingValues(
            horizontal = layoutSpec.horizontalPadding,
            vertical = layoutSpec.verticalPadding
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize().testTag("live_hub_grid")
    ) {
        // 1. Brand Hero Bar on Phone & Tablet
        if (!layoutSpec.isTv) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                HomeBrandHeader(
                    config = whitelabelConfig,
                    isRefreshing = uiState.isRefreshing,
                    refreshStatusText = uiState.refreshStatusText,
                    liveCount = liveStreams.size,
                    showSearch = showSearchField,
                    onToggleSearch = { showSearchField = !showSearchField },
                    onRefresh = onRefresh
                )
            }
        } else if (uiState.isRefreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RefreshProgressBanner(statusText = uiState.refreshStatusText)
            }
        }

        // 2. Collapsible Search Bar
        if (showSearchField || uiState.searchQuery.isNotEmpty() || layoutSpec.isTv) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search"
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_search_input")
                )
            }
        }

        // 3. Sport Category Filter Pills ("Soccer" automatically displayed as "Football")
        item(span = { GridItemSpan(maxLineSpan) }) {
            CategoryFilterRow(
                categories = uiState.catalog.categories.filter { !it.isMultiview },
                selectedSlug = selectedCat,
                onSelectCategory = onSelectCategory
            )
        }

        // 4. Connection Notice (Friendly Home-User Message)
        if (uiState.refreshError != null && !uiState.isRefreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FriendlyNoticeCard(
                    message = uiState.refreshError,
                    onRetry = onRefresh
                )
            }
        }

        // 5. Favorites Shelf
        if (savedStreams.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitleRow(
                        title = stringResource(R.string.section_favorites),
                        countBadge = "${savedStreams.size}"
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(savedStreams, key = { it.id }) { fav ->
                            FavoriteChannelTile(
                                channel = fav,
                                onWatch = { onPlaySavedStream(fav) },
                                onRemove = { onRemoveFavorite(fav.id) }
                            )
                        }
                    }
                }
            }
        }

        // 6. On Air Now Live Channels Grid (Selecting any stream launches Fullscreen Player)
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionTitleRow(
                title = stringResource(R.string.section_featured_live),
                countBadge = "${liveStreams.size} LIVE"
            )
        }

        if (liveStreams.isEmpty() && !uiState.isRefreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyLiveStateCard(onRefresh = onRefresh)
            }
        } else {
            items(
                items = liveStreams,
                key = { it.liveUrl ?: it.ref.key }
            ) { stream ->
                val isFav = (stream.ref.path != null && stream.ref.path in favoriteKeys) ||
                    (stream.liveUrl != null && stream.liveUrl in favoriteKeys)
                BroadcastChannelCard(
                    stream = stream,
                    isFavorite = isFav,
                    isCurrentlyPlaying = uiState.activeFeed?.id == (stream.ref.key.ifEmpty { stream.liveUrl }),
                    onWatch = { onPlayResolvedStream(stream) },
                    onToggleFavorite = { onToggleFavorite(stream) },
                    onSendMultiView = {
                        val url = stream.liveUrl ?: return@BroadcastChannelCard
                        val baseHeaders = whitelabelConfig.buildStreamRequestHeaders(uiState.catalog.activeMirror)
                        onSendStreamToMultiView(
                            ActivePlayableFeed(
                                id = stream.ref.key.ifEmpty { url },
                                title = stream.ref.displayTitle,
                                subtitle = stream.ref.displaySport,
                                primaryUrl = url,
                                candidateUrls = (listOf(url) + stream.candidates).distinct(),
                                category = stream.ref.displaySport,
                                viewers = stream.viewers,
                                streamPath = stream.ref.path,
                                subdomain = stream.ref.subdomain,
                                customHeaders = baseHeaders + stream.ref.customHeaders
                            )
                        )
                    }
                )
            }
        }

        // 7. Live Events Highlight Shelf (Quick Fullscreen Tune-In)
        if (liveFeaturedEvents.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(4.dp))
                SectionTitleRow(
                    title = "Featured Matchups",
                    countBadge = "${liveFeaturedEvents.size}"
                )
            }
            items(
                items = liveFeaturedEvents,
                key = { "live_ev_${it.category}_${it.label}_${it.route}" }
            ) { event ->
                CompactMatchupCard(
                    event = event,
                    isResolving = uiState.resolvingEventRoute == event.route && event.route.isNotEmpty(),
                    onTuneIn = { onResolveAndPlayEvent(event) }
                )
            }
        }
    }
}

@Composable
private fun HomeBrandHeader(
    config: WhitelabelConfig,
    isRefreshing: Boolean,
    refreshStatusText: String,
    liveCount: Int,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            )
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = primary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = config.monogram,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = config.brandName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (liveCount > 0) StatusLiveGreen else primary)
                            )
                            Text(
                                text = if (liveCount > 0) {
                                    "$liveCount Channels Live • ${config.brandTagline}"
                                } else {
                                    config.brandTagline
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .adaptiveInteractiveSurface(
                                shape = RoundedCornerShape(12.dp),
                                containerColor = if (showSearch) {
                                    primary.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                },
                                onClick = onToggleSearch
                            )
                            .testTag("toggle_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search Channels",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .adaptiveInteractiveSurface(
                                shape = RoundedCornerShape(12.dp),
                                containerColor = primary,
                                focusedContainerColor = primary,
                                enabled = !isRefreshing,
                                onClick = onRefresh
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("refresh_lineup_button")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_refreshing),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_refresh),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isRefreshing) {
                RefreshProgressBanner(statusText = refreshStatusText)
            }
        }
    }
}

@Composable
fun RefreshProgressBanner(statusText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
        )
        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CategoryFilterRow(
    categories: List<com.example.data.model.Category>,
    selectedSlug: String?,
    onSelectCategory: (String?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            FocusableFilterPill(
                label = stringResource(R.string.filter_all_sports),
                selected = selectedSlug == null,
                onClick = { onSelectCategory(null) },
                testTag = "chip_category_all"
            )
        }
        items(categories, key = { it.slug }) { category ->
            FocusableFilterPill(
                label = category.displayTitle,
                selected = selectedSlug == category.slug,
                onClick = {
                    onSelectCategory(if (selectedSlug == category.slug) null else category.slug)
                },
                testTag = "chip_category_${category.slug}"
            )
        }
    }
}

@Composable
fun FocusableFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingDotColor: Color? = null,
    testTag: String? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val container = if (selected) primary else MaterialTheme.colorScheme.surfaceContainer
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val mod = if (testTag != null) Modifier.testTag(testTag) else Modifier

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = mod
            .adaptiveInteractiveSurface(
                shape = RoundedCornerShape(50),
                containerColor = container,
                focusedContainerColor = if (selected) primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                scaleOnFocus = 1.05f,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (leadingDotColor != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun BroadcastChannelCard(
    stream: ResolvedStream,
    isFavorite: Boolean,
    isCurrentlyPlaying: Boolean,
    onWatch: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSendMultiView: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val container = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveInteractiveSurface(
                shape = RoundedCornerShape(18.dp),
                containerColor = container,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = if (isCurrentlyPlaying) primary else null,
                onClick = onWatch
            )
            .padding(16.dp)
            .testTag("channel_card_${stream.ref.key}")
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
                    color = StatusLiveGreen.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(StatusLiveGreen)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isCurrentlyPlaying) "NOW PLAYING" else "LIVE HD",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusLiveGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = stream.ref.displaySport,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (stream.viewers > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = "Viewers",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = NumberFormat.getIntegerInstance().format(stream.viewers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stream.ref.displayTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (stream.candidates.size > 1) {
                Text(
                    text = "${stream.candidates.size} broadcast feeds available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .adaptiveInteractiveSurface(
                        shape = RoundedCornerShape(10.dp),
                        containerColor = primary,
                        focusedContainerColor = primary,
                        scaleOnFocus = 1.04f,
                        onClick = onWatch
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.btn_watch_live),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .adaptiveInteractiveSurface(
                            shape = RoundedCornerShape(10.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            scaleOnFocus = 1.08f,
                            onClick = onSendMultiView
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.GridView,
                        contentDescription = "Add to MultiView",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .adaptiveInteractiveSurface(
                            shape = RoundedCornerShape(10.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            scaleOnFocus = 1.08f,
                            onClick = onToggleFavorite
                        )
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = stringResource(R.string.btn_favorite),
                        tint = if (isFavorite) primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteChannelTile(
    channel: SavedStreamEntity,
    onWatch: () -> Unit,
    onRemove: () -> Unit
) {
    val sportLabel = SportDisplayFormatter.formatSportName(channel.groupName)
    val cleanTitle = SportDisplayFormatter.cleanConsumerTitle(channel.title, null, sportLabel, null)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .width(240.dp)
            .adaptiveInteractiveSurface(
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = onWatch
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sportLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .adaptiveInteractiveSurface(
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    scaleOnFocus = 1.08f,
                    onClick = onRemove
                )
        ) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = "Remove Favorite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CompactMatchupCard(
    event: StreamEvent,
    isResolving: Boolean,
    onTuneIn: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveInteractiveSurface(
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                enabled = !isResolving,
                onClick = onTuneIn
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(StatusLiveGreen)
                )
                Text(
                    text = event.displayCategory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.displayLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        if (isResolving) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.btn_tune_in),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SectionTitleRow(title: String, countBadge: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = countBadge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FriendlyNoticeCard(
    message: String,
    onRetry: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .adaptiveInteractiveSurface(
                    shape = RoundedCornerShape(10.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = onRetry
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.action_refresh),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
private fun EmptyLiveStateCard(onRefresh: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(28.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_live_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_live_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .adaptiveInteractiveSurface(
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    onClick = onRefresh
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_refresh),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
