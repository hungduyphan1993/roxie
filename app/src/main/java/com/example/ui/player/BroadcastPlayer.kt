package com.example.ui.player

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.WhitelabelConfig
import com.example.ui.theme.StatusLiveGreen
import com.example.util.NetworkConnectivityObserver
import com.example.util.RetryBackoffPolicy
import java.text.NumberFormat
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

data class VideoQualityTrack(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val height: Int,
    val bitrate: Int
)

@OptIn(UnstableApi::class)
@Composable
fun BroadcastStagePlayer(
    feed: ActivePlayableFeed,
    whitelabelConfig: WhitelabelConfig,
    activeCategoryName: String,
    channelIndexOneBased: Int,
    totalChannelsInScope: Int,
    isTv: Boolean,
    onEnterPip: (() -> Unit)?,
    onSwitchCandidate: (Int) -> Unit,
    onStepChannel: (Int) -> Unit,
    onStepCategory: (Int) -> Unit,
    onSendToMultiView: () -> Unit,
    onClose: () -> Unit,
    onPlaybackError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val rootFocusRequester = remember { FocusRequester() }
    val latestOnPlaybackError by rememberUpdatedState(onPlaybackError)

    var userPaused by remember(feed.id) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var isReconnecting by remember(feed.id) { mutableStateOf(false) }
    var retryAttempt by remember(feed.id) { mutableIntStateOf(0) }
    var pendingRetryTick by remember(feed.id) { mutableIntStateOf(0) }

    var isMuted by remember { mutableStateOf(false) }
    var showInfoBar by remember { mutableStateOf(true) }
    var hudActivityTick by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var videoTracks by remember { mutableStateOf<List<VideoQualityTrack>>(emptyList()) }
    var selectedQualityLabel by remember { mutableStateOf("AUTO") }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showFeedMenu by remember { mutableStateOf(false) }
    var currentResolutionBadge by remember { mutableStateOf<String?>(null) }

    fun wakeInfoBar() {
        showInfoBar = true
        hudActivityTick++
    }

    val exoPlayer = remember(whitelabelConfig.customUserAgent, feed.customHeaders) {
        val effectiveUa = feed.customHeaders["User-Agent"] ?: whitelabelConfig.customUserAgent
        val extraHeaders = feed.customHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(effectiveUa)
            .setDefaultRequestProperties(extraHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(12000)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(RetryBackoffPolicy.MAX_SEGMENT_RETRIES)
            )

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    fun resumeLivePlayback() {
        userPaused = false
        if (exoPlayer.playbackState == Player.STATE_IDLE ||
            exoPlayer.playbackState == Player.STATE_ENDED ||
            exoPlayer.playerError != null
        ) {
            exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
        } else if (exoPlayer.isCurrentMediaItemLive) {
            exoPlayer.seekToDefaultPosition()
        }
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun toggleUserPlayPause() {
        if (userPaused || !exoPlayer.playWhenReady) {
            resumeLivePlayback()
        } else {
            userPaused = true
            isReconnecting = false
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
        wakeInfoBar()
    }

    // Wake HUD only on explicit channel or category switch, not during silent background auto-retries
    LaunchedEffect(feed.id, activeCategoryName) {
        selectedQualityLabel = "AUTO"
        wakeInfoBar()
    }

    // Load or reload media item (fires on initial tune, candidate failover, or retryNonce increment)
    LaunchedEffect(feed.activeUrl, feed.retryNonce, exoPlayer) {
        isBuffering = true
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(feed.activeUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem, true)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = !userPaused
    }

    // Exponential backoff auto-retry loop when stream drops or ends unexpectedly
    LaunchedEffect(pendingRetryTick, userPaused) {
        if (pendingRetryTick > 0 && !userPaused && whitelabelConfig.autoFailover) {
            val waitMs = RetryBackoffPolicy.computeRetryDelayMs(retryAttempt)
            delay(waitMs)
            if (!userPaused) {
                latestOnPlaybackError()
            }
        }
    }

    // Stall / freeze watchdog: if buffering continuously for >14s without progress, auto-reconnect
    LaunchedEffect(isBuffering, userPaused, isReconnecting, feed.activeUrl, feed.retryNonce) {
        if (isBuffering && !userPaused && !isReconnecting && whitelabelConfig.autoFailover) {
            delay(RetryBackoffPolicy.STALL_WATCHDOG_MS)
            if (isBuffering && !userPaused) {
                isReconnecting = true
                retryAttempt += 1
                latestOnPlaybackError()
            }
        }
    }

    // Instant resume when network connectivity is restored after a drop
    LaunchedEffect(exoPlayer) {
        NetworkConnectivityObserver.observeNetworkAvailable(context)
            .drop(1)
            .collect { available ->
                if (available && !userPaused) {
                    if (isReconnecting ||
                        exoPlayer.playbackState == Player.STATE_IDLE ||
                        exoPlayer.playerError != null
                    ) {
                        retryAttempt = 0
                        isReconnecting = true
                        latestOnPlaybackError()
                    } else if (!exoPlayer.isPlaying) {
                        resumeLivePlayback()
                    }
                }
            }
    }

    LaunchedEffect(Unit) {
        try {
            rootFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(showInfoBar, hudActivityTick, isPlaying, userPaused, showQualityMenu, showFeedMenu) {
        if (showInfoBar && isPlaying && !userPaused && !showQualityMenu && !showFeedMenu) {
            delay(4500L)
            showInfoBar = false
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        isReconnecting = false
                        retryAttempt = 0
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        if (!userPaused && whitelabelConfig.autoFailover) {
                            isReconnecting = true
                            retryAttempt += 1
                            pendingRetryTick += 1
                        }
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isReconnecting = false
                    retryAttempt = 0
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val extracted = mutableListOf<VideoQualityTrack>()
                tracks.groups.forEachIndexed { groupIdx, group ->
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        for (tIdx in 0 until group.length) {
                            if (group.isTrackSupported(tIdx)) {
                                val fmt = group.getTrackFormat(tIdx)
                                if (fmt.height > 0) {
                                    val mbps = if (fmt.bitrate > 0) {
                                        String.format("%.1f Mbps", fmt.bitrate / 1_000_000f)
                                    } else ""
                                    val label = buildString {
                                        append("${fmt.height}p")
                                        if (mbps.isNotEmpty()) append(" • $mbps")
                                    }
                                    extracted.add(
                                        VideoQualityTrack(
                                            label = label,
                                            groupIndex = groupIdx,
                                            trackIndex = tIdx,
                                            height = fmt.height,
                                            bitrate = fmt.bitrate
                                        )
                                    )
                                }
                                if (group.isTrackSelected(tIdx) && fmt.height > 0) {
                                    currentResolutionBadge = "${fmt.height}p"
                                }
                            }
                        }
                    }
                }
                videoTracks = extracted
                    .distinctBy { it.height to it.bitrate }
                    .sortedByDescending { it.height }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = !userPaused
                    return
                }
                isBuffering = false
                if (!userPaused && whitelabelConfig.autoFailover) {
                    isReconnecting = true
                    retryAttempt += 1
                    pendingRetryTick += 1
                }
            }
        }
        exoPlayer.addListener(listener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (!userPaused) {
                        resumeLivePlayback()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val swipeThresholdPx = with(density) { 52.dp.toPx() }
    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("broadcast_stage_player")
            .focusRequester(rootFocusRequester)
            .onPreviewKeyEvent { event ->
                if (showQualityMenu || showFeedMenu) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    // UP / DOWN -> Navigate Streams
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        wakeInfoBar()
                        onStepChannel(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        wakeInfoBar()
                        onStepChannel(1)
                        true
                    }
                    // LEFT / RIGHT -> Change Sport Category
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        wakeInfoBar()
                        onStepCategory(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        wakeInfoBar()
                        onStepCategory(1)
                        true
                    }
                    // OK / ENTER -> Wake Info Bar or Toggle Play/Pause
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if (!showInfoBar) {
                            wakeInfoBar()
                        } else {
                            toggleUserPlayPause()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_MENU -> {
                        showInfoBar = !showInfoBar
                        if (showInfoBar) hudActivityTick++
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_MUTE,
                    KeyEvent.KEYCODE_M -> {
                        isMuted = !isMuted
                        exoPlayer.volume = if (isMuted) 0f else 1f
                        wakeInfoBar()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_SPACE -> {
                        toggleUserPlayPause()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        resumeLivePlayback()
                        wakeInfoBar()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        userPaused = true
                        isReconnecting = false
                        exoPlayer.playWhenReady = false
                        exoPlayer.pause()
                        wakeInfoBar()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragAccumX = 0f
                        dragAccumY = 0f
                    },
                    onDragEnd = {
                        if (abs(dragAccumY) > abs(dragAccumX) && abs(dragAccumY) > swipeThresholdPx) {
                            wakeInfoBar()
                            if (dragAccumY < 0) onStepChannel(1) else onStepChannel(-1)
                        } else if (abs(dragAccumX) > abs(dragAccumY) && abs(dragAccumX) > swipeThresholdPx) {
                            wakeInfoBar()
                            if (dragAccumX < 0) onStepCategory(1) else onStepCategory(-1)
                        }
                        dragAccumX = 0f
                        dragAccumY = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragAccumX += dragAmount.x
                    dragAccumY += dragAmount.y
                }
            }
            .clickable {
                if (showInfoBar) {
                    showInfoBar = false
                } else {
                    wakeInfoBar()
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering || isReconnecting) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp)
                )
                if (isReconnecting) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (feed.candidateUrls.size > 1) {
                            "Switching to backup feed…"
                        } else {
                            "Reconnecting stream…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF060C16).copy(alpha = 0.72f))
                            .border(0.75.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("player_reconnecting_badge")
                    )
                }
            }
        }

        // Floating Transmorphic Info Bar
        AnimatedVisibility(
            visible = showInfoBar,
            enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    initialOffsetY = { it / 2 }
                ),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            TransmorphicInfoBar(
                feed = feed,
                whitelabelConfig = whitelabelConfig,
                activeCategoryName = activeCategoryName,
                channelIndexOneBased = channelIndexOneBased,
                totalChannelsInScope = totalChannelsInScope,
                currentResolutionBadge = currentResolutionBadge,
                isPlaying = !userPaused && (isPlaying || isBuffering || isReconnecting),
                isMuted = isMuted,
                isTv = isTv,
                selectedQualityLabel = selectedQualityLabel,
                videoTracks = videoTracks,
                showQualityMenu = showQualityMenu,
                showFeedMenu = showFeedMenu,
                onTogglePlayPause = ::toggleUserPlayPause,
                onToggleMute = {
                    isMuted = !isMuted
                    exoPlayer.volume = if (isMuted) 0f else 1f
                    wakeInfoBar()
                },
                onStepChannel = { delta ->
                    wakeInfoBar()
                    onStepChannel(delta)
                },
                onStepCategory = { delta ->
                    wakeInfoBar()
                    onStepCategory(delta)
                },
                onSyncLive = {
                    resumeLivePlayback()
                    wakeInfoBar()
                },
                onShowFeedMenuChange = {
                    showFeedMenu = it
                    wakeInfoBar()
                },
                onSwitchCandidate = { idx ->
                    showFeedMenu = false
                    userPaused = false
                    isReconnecting = false
                    retryAttempt = 0
                    wakeInfoBar()
                    onSwitchCandidate(idx)
                },
                onShowQualityMenuChange = {
                    showQualityMenu = it
                    wakeInfoBar()
                },
                onSelectAutoQuality = {
                    selectedQualityLabel = "AUTO"
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                    showQualityMenu = false
                    wakeInfoBar()
                },
                onSelectVideoTrack = { track ->
                    val groups = exoPlayer.currentTracks.groups
                    if (track.groupIndex < groups.size) {
                        val mediaTrackGroup = groups[track.groupIndex].mediaTrackGroup
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(mediaTrackGroup, track.trackIndex)
                            )
                            .build()
                        selectedQualityLabel = "${track.height}p"
                    }
                    showQualityMenu = false
                    wakeInfoBar()
                },
                onCycleAspectRatio = {
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM ->
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    wakeInfoBar()
                },
                onSendToMultiView = onSendToMultiView,
                onEnterPip = onEnterPip,
                onClose = onClose
            )
        }
    }
}

@Composable
private fun TransmorphicInfoBar(
    feed: ActivePlayableFeed,
    whitelabelConfig: WhitelabelConfig,
    activeCategoryName: String,
    channelIndexOneBased: Int,
    totalChannelsInScope: Int,
    currentResolutionBadge: String?,
    isPlaying: Boolean,
    isMuted: Boolean,
    isTv: Boolean,
    selectedQualityLabel: String,
    videoTracks: List<VideoQualityTrack>,
    showQualityMenu: Boolean,
    showFeedMenu: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onStepChannel: (Int) -> Unit,
    onStepCategory: (Int) -> Unit,
    onSyncLive: () -> Unit,
    onShowFeedMenuChange: (Boolean) -> Unit,
    onSwitchCandidate: (Int) -> Unit,
    onShowQualityMenuChange: (Boolean) -> Unit,
    onSelectAutoQuality: () -> Unit,
    onSelectVideoTrack: (VideoQualityTrack) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onSendToMultiView: () -> Unit,
    onEnterPip: (() -> Unit)?,
    onClose: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val capsuleShape = RoundedCornerShape(28.dp)

    BoxWithConstraints(
        modifier = Modifier
            .widthIn(max = 860.dp)
            .fillMaxWidth()
            .clip(capsuleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF060C16).copy(alpha = 0.78f),
                        Color(0xFF0F1C2E).copy(alpha = 0.66f),
                        primary.copy(alpha = 0.16f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.36f),
                        primary.copy(alpha = 0.34f),
                        Color.White.copy(alpha = 0.10f)
                    )
                ),
                shape = capsuleShape
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .focusProperties { canFocus = false }
            .clickable(enabled = true, onClick = {})
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("transmorphic_info_bar")
    ) {
        val isWideHud = maxWidth >= 580.dp

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(0.75.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(50))
                        .padding(horizontal = 2.dp)
                ) {
                    GlassTouchIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Category",
                        visualSizeDp = 28,
                        onClick = { onStepCategory(-1) },
                        testTag = "hud_prev_category"
                    )
                    AnimatedContent(
                        targetState = activeCategoryName,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "categoryMorph"
                    ) { categoryTitle ->
                        Text(
                            text = categoryTitle.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                    GlassTouchIcon(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Category",
                        visualSizeDp = 28,
                        onClick = { onStepCategory(1) },
                        testTag = "hud_next_category"
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (totalChannelsInScope > 0) {
                        AnimatedContent(
                            targetState = channelIndexOneBased to totalChannelsInScope,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "channelNumberMorph"
                        ) { (idx, total) ->
                            Text(
                                text = "CH %02d/%02d".format(idx, total),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(StatusLiveGreen.copy(alpha = 0.22f))
                            .clickable { onSyncLive() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(StatusLiveGreen)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.player_live_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (currentResolutionBadge != null && isWideHud) {
                        Text(
                            text = currentResolutionBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }

                    if (feed.viewers > 0 && isWideHud) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = NumberFormat.getIntegerInstance().format(feed.viewers),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }

                    GlassTouchIcon(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.player_close),
                        visualSizeDp = 32,
                        onClick = onClose,
                        testTag = "player_close_button"
                    )
                }
            }

            if (isWideHud) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StreamIdentityBlock(
                        whitelabelConfig = whitelabelConfig,
                        title = feed.title,
                        subtitle = feed.subtitle,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    TransmorphicControlsCluster(
                        feed = feed,
                        isPlaying = isPlaying,
                        isMuted = isMuted,
                        isTv = isTv,
                        selectedQualityLabel = selectedQualityLabel,
                        videoTracks = videoTracks,
                        showQualityMenu = showQualityMenu,
                        showFeedMenu = showFeedMenu,
                        onStepChannel = onStepChannel,
                        onTogglePlayPause = onTogglePlayPause,
                        onToggleMute = onToggleMute,
                        onShowFeedMenuChange = onShowFeedMenuChange,
                        onSwitchCandidate = onSwitchCandidate,
                        onShowQualityMenuChange = onShowQualityMenuChange,
                        onSelectAutoQuality = onSelectAutoQuality,
                        onSelectVideoTrack = onSelectVideoTrack,
                        onCycleAspectRatio = onCycleAspectRatio,
                        onSendToMultiView = onSendToMultiView,
                        onEnterPip = onEnterPip
                    )
                }
            } else {
                StreamIdentityBlock(
                    whitelabelConfig = whitelabelConfig,
                    title = feed.title,
                    subtitle = feed.subtitle,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TransmorphicControlsCluster(
                        feed = feed,
                        isPlaying = isPlaying,
                        isMuted = isMuted,
                        isTv = isTv,
                        selectedQualityLabel = selectedQualityLabel,
                        videoTracks = videoTracks,
                        showQualityMenu = showQualityMenu,
                        showFeedMenu = showFeedMenu,
                        onStepChannel = onStepChannel,
                        onTogglePlayPause = onTogglePlayPause,
                        onToggleMute = onToggleMute,
                        onShowFeedMenuChange = onShowFeedMenuChange,
                        onSwitchCandidate = onSwitchCandidate,
                        onShowQualityMenuChange = onShowQualityMenuChange,
                        onSelectAutoQuality = onSelectAutoQuality,
                        onSelectVideoTrack = onSelectVideoTrack,
                        onCycleAspectRatio = onCycleAspectRatio,
                        onSendToMultiView = onSendToMultiView,
                        onEnterPip = onEnterPip
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamIdentityBlock(
    whitelabelConfig: WhitelabelConfig,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Surface(
            color = primary.copy(alpha = 0.24f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, primary.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = whitelabelConfig.monogram,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(12.dp))

        AnimatedContent(
            targetState = title to subtitle,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 3 }) togetherWith
                    (fadeOut() + slideOutVertically { -it / 3 })
            },
            label = "streamTitleMorph",
            modifier = Modifier.weight(1f)
        ) { (currTitle, currSubtitle) ->
            Column {
                Text(
                    text = currTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$currSubtitle • ▲/▼ Stream • ◀/▶ Category",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TransmorphicControlsCluster(
    feed: ActivePlayableFeed,
    isPlaying: Boolean,
    isMuted: Boolean,
    isTv: Boolean,
    selectedQualityLabel: String,
    videoTracks: List<VideoQualityTrack>,
    showQualityMenu: Boolean,
    showFeedMenu: Boolean,
    onStepChannel: (Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onShowFeedMenuChange: (Boolean) -> Unit,
    onSwitchCandidate: (Int) -> Unit,
    onShowQualityMenuChange: (Boolean) -> Unit,
    onSelectAutoQuality: () -> Unit,
    onSelectVideoTrack: (VideoQualityTrack) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onSendToMultiView: () -> Unit,
    onEnterPip: (() -> Unit)?
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.10f))
                .border(0.75.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(50))
        ) {
            GlassTouchIcon(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous Stream",
                visualSizeDp = 32,
                onClick = { onStepChannel(-1) },
                testTag = "hud_prev_stream"
            )
            GlassTouchIcon(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next Stream",
                visualSizeDp = 32,
                onClick = { onStepChannel(1) },
                testTag = "hud_next_stream"
            )
        }

        GlassTouchIcon(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.player_play_pause),
            visualSizeDp = 36,
            tint = primary,
            onClick = onTogglePlayPause,
            testTag = "player_play_pause_button"
        )

        GlassTouchIcon(
            icon = if (isMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = stringResource(R.string.player_mute_toggle),
            visualSizeDp = 34,
            onClick = onToggleMute,
            testTag = "player_mute_button"
        )

        if (feed.candidateUrls.size > 1) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f))
                        .focusProperties { canFocus = false }
                        .clickable { onShowFeedMenuChange(true) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .testTag("player_feed_selector")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SyncAlt,
                        contentDescription = stringResource(R.string.player_switch_feed),
                        tint = primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${feed.currentCandidateIndex + 1}/${feed.candidateUrls.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
                DropdownMenu(
                    expanded = showFeedMenu,
                    onDismissRequest = { onShowFeedMenuChange(false) }
                ) {
                    feed.candidateUrls.indices.forEach { idx ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (idx == 0) {
                                        "Feed 1 (Primary HD)"
                                    } else {
                                        "Feed ${idx + 1} (Backup)"
                                    },
                                    fontWeight = if (idx == feed.currentCandidateIndex) {
                                        FontWeight.Bold
                                    } else FontWeight.Normal
                                )
                            },
                            onClick = { onSwitchCandidate(idx) }
                        )
                    }
                }
            }
        }

        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.12f))
                    .focusProperties { canFocus = false }
                    .clickable { onShowQualityMenuChange(true) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("player_quality_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.HighQuality,
                    contentDescription = "Video Quality",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = selectedQualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            DropdownMenu(
                expanded = showQualityMenu,
                onDismissRequest = { onShowQualityMenuChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Auto HD (Recommended)") },
                    onClick = onSelectAutoQuality
                )
                videoTracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(track.label) },
                        onClick = { onSelectVideoTrack(track) }
                    )
                }
            }
        }

        GlassTouchIcon(
            icon = Icons.Filled.AspectRatio,
            contentDescription = "Aspect Ratio",
            visualSizeDp = 34,
            onClick = onCycleAspectRatio
        )

        GlassTouchIcon(
            icon = Icons.Filled.GridView,
            contentDescription = "Send to MultiView",
            visualSizeDp = 34,
            onClick = onSendToMultiView,
            testTag = "player_send_multiview_button"
        )

        if (onEnterPip != null && !isTv) {
            GlassTouchIcon(
                icon = Icons.Filled.PictureInPictureAlt,
                contentDescription = stringResource(R.string.player_pip),
                visualSizeDp = 34,
                onClick = onEnterPip,
                testTag = "player_pip_button"
            )
        }
    }
}

@Composable
private fun GlassTouchIcon(
    icon: ImageVector,
    contentDescription: String,
    visualSizeDp: Int = 34,
    tint: Color = Color.White,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val mod = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier = mod
            .minimumInteractiveComponentSize()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(visualSizeDp.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size((visualSizeDp * 0.54f).dp)
            )
        }
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiViewTilePlayer(
    slotIndex: Int,
    feed: ActivePlayableFeed,
    isAudioFocused: Boolean,
    userAgent: String,
    onSelectFocus: () -> Unit,
    onRequestChangeChannel: () -> Unit,
    onExpandSingle: () -> Unit,
    onSlotExhaustedCandidates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnExhausted by rememberUpdatedState(onSlotExhaustedCandidates)

    var isBuffering by remember { mutableStateOf(true) }
    var isReconnecting by remember(feed.id) { mutableStateOf(false) }
    var candidateIdx by remember(feed.id, feed.retryNonce) {
        mutableIntStateOf(feed.currentCandidateIndex)
    }
    var localRetryNonce by remember(feed.id) { mutableIntStateOf(0) }
    var retryAttempt by remember(feed.id) { mutableIntStateOf(0) }
    var pendingRetryTick by remember(feed.id) { mutableIntStateOf(0) }

    var dpadFocused by remember { mutableStateOf(false) }
    var showTransientBadge by remember { mutableStateOf(true) }
    var badgeTick by remember { mutableIntStateOf(0) }

    val activeUrl = feed.candidateUrls.getOrElse(candidateIdx) { feed.primaryUrl }

    val exoPlayer = remember(userAgent, feed.customHeaders) {
        val effectiveUa = feed.customHeaders["User-Agent"] ?: userAgent
        val extraHeaders = feed.customHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(effectiveUa)
            .setDefaultRequestProperties(extraHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(12000)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(RetryBackoffPolicy.MAX_SEGMENT_RETRIES)
            )

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    fun advanceOrRetryTile() {
        val count = feed.candidateUrls.size.coerceAtLeast(1)
        val nextIdx = (candidateIdx + 1) % count
        candidateIdx = nextIdx
        localRetryNonce += 1
        if (nextIdx == 0) {
            latestOnExhausted()
        }
    }

    LaunchedEffect(isAudioFocused, exoPlayer) {
        exoPlayer.volume = if (isAudioFocused) 1f else 0f
        if (isAudioFocused) {
            showTransientBadge = true
            badgeTick++
        }
    }

    LaunchedEffect(showTransientBadge, badgeTick) {
        if (showTransientBadge) {
            delay(2000L)
            showTransientBadge = false
        }
    }

    LaunchedEffect(activeUrl, feed.retryNonce, localRetryNonce, exoPlayer) {
        isBuffering = true
        val mediaItem = MediaItem.Builder()
            .setUri(activeUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem, true)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Continuous exponential backoff auto-retry loop for MultiView tile
    LaunchedEffect(pendingRetryTick) {
        if (pendingRetryTick > 0) {
            delay(RetryBackoffPolicy.computeRetryDelayMs(retryAttempt))
            advanceOrRetryTile()
        }
    }

    // Stall / freeze watchdog for MultiView tile
    LaunchedEffect(isBuffering, isReconnecting, activeUrl, localRetryNonce) {
        if (isBuffering && !isReconnecting) {
            delay(RetryBackoffPolicy.STALL_WATCHDOG_MS)
            if (isBuffering) {
                isReconnecting = true
                retryAttempt += 1
                advanceOrRetryTile()
            }
        }
    }

    // Instant recovery on network restoration
    LaunchedEffect(exoPlayer) {
        NetworkConnectivityObserver.observeNetworkAvailable(context)
            .drop(1)
            .collect { available ->
                if (available) {
                    if (isReconnecting ||
                        exoPlayer.playbackState == Player.STATE_IDLE ||
                        exoPlayer.playerError != null
                    ) {
                        retryAttempt = 0
                        isReconnecting = true
                        advanceOrRetryTile()
                    } else if (!exoPlayer.isPlaying) {
                        if (exoPlayer.isCurrentMediaItemLive) {
                            exoPlayer.seekToDefaultPosition()
                        }
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    }
                }
            }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        isReconnecting = false
                        retryAttempt = 0
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        isReconnecting = true
                        retryAttempt += 1
                        pendingRetryTick += 1
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    isReconnecting = false
                    retryAttempt = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    return
                }
                isBuffering = false
                isReconnecting = true
                retryAttempt += 1
                pendingRetryTick += 1
            }
        }
        exoPlayer.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> {
                    if (exoPlayer.playbackState == Player.STATE_IDLE ||
                        exoPlayer.playbackState == Player.STATE_ENDED ||
                        exoPlayer.playerError != null
                    ) {
                        exoPlayer.seekToDefaultPosition()
                        exoPlayer.prepare()
                    } else if (exoPlayer.isCurrentMediaItemLive) {
                        exoPlayer.seekToDefaultPosition()
                    }
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val borderColor = when {
        dpadFocused -> MaterialTheme.colorScheme.primary
        isAudioFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        else -> Color.White.copy(alpha = 0.06f)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(
                width = if (dpadFocused) 2.dp else if (isAudioFocused) 1.dp else 0.5.dp,
                color = borderColor
            )
            .onFocusChanged {
                dpadFocused = it.isFocused
                if (it.isFocused) {
                    showTransientBadge = true
                    badgeTick++
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_INFO)
                ) {
                    onRequestChangeChannel()
                    true
                } else {
                    false
                }
            }
            .testTag("multiview_tile_$slotIndex")
            .combinedClickable(
                onClick = {
                    if (isAudioFocused && showTransientBadge) {
                        onRequestChangeChannel()
                    } else {
                        onSelectFocus()
                        showTransientBadge = true
                        badgeTick++
                    }
                },
                onDoubleClick = onExpandSingle,
                onLongClick = onRequestChangeChannel
            )
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering || isReconnecting) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(24.dp)
            )
        }

        AnimatedVisibility(
            visible = showTransientBadge,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF060C16).copy(alpha = 0.68f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (isAudioFocused) {
                        Icons.AutoMirrored.Filled.VolumeUp
                    } else {
                        Icons.AutoMirrored.Filled.VolumeOff
                    },
                    contentDescription = null,
                    tint = if (isAudioFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = feed.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            }
        }
    }
}
