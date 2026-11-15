package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ProbeResult
import com.example.data.model.ProbeState
import com.example.data.model.ResolvedStream
import com.example.data.model.StreamRef
import com.example.data.model.WhitelabelConfig
import com.example.ui.NavTab
import com.example.ui.PrismCastViewModel
import com.example.ui.adaptive.DeviceFormFactor
import com.example.ui.adaptive.adaptiveInteractiveSurface
import com.example.ui.adaptive.rememberAdaptiveLayoutSpec
import com.example.ui.player.BroadcastStagePlayer
import com.example.ui.screens.LiveHubScreen
import com.example.ui.screens.MultiViewScreen
import com.example.ui.screens.ScheduleScreen
import com.example.ui.theme.BrandPalette
import com.example.ui.theme.PrismCastTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrismCastApp(
                onRequestPip = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .build()
                        enterPictureInPictureMode(params)
                    }
                }
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class NavDestination(
    val tab: NavTab,
    val labelRes: Int,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector,
    val tag: String
)

private val NAV_ITEMS = listOf(
    NavDestination(
        tab = NavTab.LIVE_NOW,
        labelRes = R.string.nav_live_tv,
        filledIcon = Icons.Filled.LiveTv,
        outlinedIcon = Icons.Outlined.LiveTv,
        tag = "nav_tab_live"
    ),
    NavDestination(
        tab = NavTab.SCHEDULE,
        labelRes = R.string.nav_schedule,
        filledIcon = Icons.Filled.CalendarMonth,
        outlinedIcon = Icons.Outlined.CalendarMonth,
        tag = "nav_tab_schedule"
    ),
    NavDestination(
        tab = NavTab.MULTIVIEW,
        labelRes = R.string.nav_multiview,
        filledIcon = Icons.Filled.GridView,
        outlinedIcon = Icons.Outlined.GridView,
        tag = "nav_tab_multiview"
    )
)

@Composable
fun PrismCastApp(
    viewModel: PrismCastViewModel = viewModel(),
    onRequestPip: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedStreams by viewModel.savedStreams.collectAsStateWithLifecycle()
    val config = viewModel.whitelabelConfig
    val snackbarHostState = remember { SnackbarHostState() }
    val view = LocalView.current

    val isImmersiveFullscreen =
        (uiState.isFullscreenPlayer && uiState.activeFeed != null) ||
            uiState.currentTab == NavTab.MULTIVIEW

    // Immersive edge-to-edge system bar management for Fullscreen Player & Frameless MultiView
    DisposableEffect(view, isImmersiveFullscreen) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isImmersiveFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissStatusMessage()
        }
    }

    // Linear Breadcrumb Back Navigation (compliant with Android TV & Mobile Back guidelines)
    BackHandler(enabled = uiState.isFullscreenPlayer) {
        viewModel.setFullscreenPlayer(false)
    }
    BackHandler(enabled = !uiState.isFullscreenPlayer && uiState.currentTab != NavTab.LIVE_NOW) {
        viewModel.selectTab(NavTab.LIVE_NOW)
    }

    PrismCastTheme(
        palette = BrandPalette.fromKey(config.paletteKey),
        darkTheme = config.darkTheme
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val layoutSpec = rememberAdaptiveLayoutSpec(maxWidth)
            val activeFeed = uiState.activeFeed

            // 1. Fullscreen Single-Stream Player with 4-Way Zapper + Transmorphic Info Bar
            if (uiState.isFullscreenPlayer && activeFeed != null) {
                val scopedStreams = remember(
                    uiState.resolvedStreams,
                    uiState.selectedCategorySlug,
                    uiState.catalog
                ) {
                    uiState.filteredLiveStreams().ifEmpty {
                        uiState.resolvedStreams
                            .filter { it.liveUrl != null }
                            .sortedByDescending { it.viewers }
                    }
                }
                val chIndex = remember(scopedStreams, activeFeed.id) {
                    val idx = scopedStreams.indexOfFirst {
                        (it.ref.key.ifEmpty { it.liveUrl }) == activeFeed.id
                    }
                    if (idx >= 0) idx + 1 else 1
                }

                BroadcastStagePlayer(
                    feed = activeFeed,
                    whitelabelConfig = config,
                    activeCategoryName = uiState.activeCategoryDisplayName,
                    channelIndexOneBased = chIndex,
                    totalChannelsInScope = scopedStreams.size,
                    isTv = layoutSpec.isTv,
                    onEnterPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !layoutSpec.isTv) {
                        onRequestPip
                    } else null,
                    onSwitchCandidate = viewModel::switchPrimaryCandidate,
                    onStepChannel = viewModel::stepLiveChannel,
                    onStepCategory = viewModel::stepCategoryInPlayer,
                    onSendToMultiView = viewModel::sendActiveFeedToMultiView,
                    onClose = viewModel::closePrimaryPlayer,
                    onPlaybackError = viewModel::onPrimaryPlayerError
                )
                return@BoxWithConstraints
            }

            // 2. 100% Fullscreen Frameless MultiScreen (no app navigation bars, titles, or buttons)
            if (uiState.currentTab == NavTab.MULTIVIEW) {
                MultiViewScreen(
                    uiState = uiState,
                    layoutSpec = layoutSpec,
                    userAgent = config.customUserAgent,
                    savedStreams = savedStreams,
                    onToggleQuadMode = viewModel::setMultiViewLayoutMode,
                    onFocusSlot = viewModel::focusMultiViewSlot,
                    onAssignSlot = viewModel::assignFeedToMultiView,
                    onExpandFeedToStage = { feed ->
                        viewModel.selectTab(NavTab.LIVE_NOW)
                        viewModel.playResolvedStream(
                            stream = ResolvedStream(
                                ref = StreamRef(
                                    label = feed.title,
                                    subdomain = feed.subdomain,
                                    path = feed.streamPath,
                                    hardcoded = feed.activeUrl,
                                    sport = feed.category,
                                    customHeaders = feed.customHeaders
                                ),
                                probe = ProbeResult(
                                    url = feed.activeUrl,
                                    state = ProbeState.LIVE
                                ),
                                candidates = feed.candidateUrls,
                                liveUrl = feed.activeUrl,
                                viewers = feed.viewers
                            ),
                            openFullscreen = true
                        )
                    },
                    onAutoFillLive = viewModel::autoFillMultiViewFromLive,
                    onClearAll = viewModel::clearMultiView,
                    onExitMultiView = { viewModel.selectTab(NavTab.LIVE_NOW) },
                    onSlotExhaustedCandidates = viewModel::onMultiViewSlotExhausted
                )
                return@BoxWithConstraints
            }

            // 3. Adaptive Hub & Schedule Shell (Phone Bottom Bar, Tablet Rail, TV Top Pill Bar)
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    if (layoutSpec.formFactor == DeviceFormFactor.TV) {
                        TvTopNavigationBar(
                            config = config,
                            currentTab = uiState.currentTab,
                            isRefreshing = uiState.isRefreshing,
                            horizontalPadding = layoutSpec.horizontalPadding,
                            onSelectTab = viewModel::selectTab,
                            onRefresh = viewModel::refreshLineup
                        )
                    }
                },
                bottomBar = {
                    if (layoutSpec.formFactor == DeviceFormFactor.PHONE) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            NAV_ITEMS.forEach { dest ->
                                val selected = uiState.currentTab == dest.tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(dest.tab) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.filledIcon else dest.outlinedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.tag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (layoutSpec.formFactor == DeviceFormFactor.TABLET) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            header = {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = config.monogram,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            },
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            NAV_ITEMS.forEach { dest ->
                                val selected = uiState.currentTab == dest.tab
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(dest.tab) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.filledIcon else dest.outlinedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.tag)
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (uiState.currentTab) {
                            NavTab.LIVE_NOW -> LiveHubScreen(
                                uiState = uiState,
                                whitelabelConfig = config,
                                layoutSpec = layoutSpec,
                                savedStreams = savedStreams,
                                onRefresh = viewModel::refreshLineup,
                                onSelectCategory = viewModel::setCategoryFilter,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onPlayResolvedStream = { stream ->
                                    viewModel.playResolvedStream(
                                        stream = stream,
                                        openFullscreen = true
                                    )
                                },
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSendStreamToMultiView = { feed ->
                                    val emptySlot = uiState.multiViewSlots.indexOfFirst { it == null }
                                        .let { if (it == -1) 0 else it }
                                    viewModel.assignFeedToMultiView(emptySlot, feed)
                                    viewModel.selectTab(NavTab.MULTIVIEW)
                                },
                                onResolveAndPlayEvent = { event ->
                                    viewModel.resolveAndPlayEvent(
                                        event = event,
                                        openFullscreen = true
                                    )
                                },
                                onPlaySavedStream = { saved ->
                                    viewModel.playSavedStream(
                                        saved = saved,
                                        openFullscreen = true
                                    )
                                },
                                onRemoveFavorite = viewModel::removeFavorite
                            )

                            NavTab.SCHEDULE -> ScheduleScreen(
                                uiState = uiState,
                                layoutSpec = layoutSpec,
                                onRefresh = viewModel::refreshLineup,
                                onSelectCategory = viewModel::setCategoryFilter,
                                onToggleLiveOnly = viewModel::toggleLiveOnlyFilter,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onTuneInEvent = { event ->
                                    viewModel.selectTab(NavTab.LIVE_NOW)
                                    viewModel.resolveAndPlayEvent(
                                        event = event,
                                        openFullscreen = true
                                    )
                                }
                            )

                            NavTab.MULTIVIEW -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTopNavigationBar(
    config: WhitelabelConfig,
    currentTab: NavTab,
    isRefreshing: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onSelectTab: (NavTab) -> Unit,
    onRefresh: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
            .testTag("tv_top_navigation_bar")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = primary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = config.monogram,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = config.brandName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = config.brandTagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = primary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_ITEMS.forEach { dest ->
                val selected = currentTab == dest.tab
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .adaptiveInteractiveSurface(
                            shape = RoundedCornerShape(50),
                            containerColor = if (selected) {
                                primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            focusedContainerColor = if (selected) {
                                primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            scaleOnFocus = 1.06f,
                            onClick = { onSelectTab(dest.tab) }
                        )
                        .padding(horizontal = 20.dp, vertical = 11.dp)
                        .testTag(dest.tag)
                ) {
                    Icon(
                        imageVector = if (selected) dest.filledIcon else dest.outlinedIcon,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(dest.labelRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .adaptiveInteractiveSurface(
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    enabled = !isRefreshing,
                    scaleOnFocus = 1.06f,
                    onClick = onRefresh
                )
                .padding(horizontal = 18.dp, vertical = 11.dp)
                .testTag("tv_refresh_button")
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_refreshing),
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_refresh),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
