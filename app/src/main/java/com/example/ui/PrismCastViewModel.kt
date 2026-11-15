package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.PrismDatabase
import com.example.data.local.SavedStreamEntity
import com.example.data.local.WhitelabelPreferences
import com.example.data.model.ActivePlayableFeed
import com.example.data.model.Catalog
import com.example.data.model.LastPlaybackSession
import com.example.data.model.ResolvedStream
import com.example.data.model.SportDisplayFormatter
import com.example.data.model.StreamEvent
import com.example.data.model.WhitelabelConfig
import com.example.data.repository.StreamRepository
import com.example.util.NetworkConnectivityObserver
import com.example.util.RetryBackoffPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class NavTab {
    LIVE_NOW,
    SCHEDULE,
    MULTIVIEW
}

data class PrismUiState(
    val currentTab: NavTab = NavTab.LIVE_NOW,
    val isRefreshing: Boolean = false,
    val refreshStatusText: String = "",
    val refreshError: String? = null,
    val catalog: Catalog = Catalog(),
    val resolvedStreams: List<ResolvedStream> = emptyList(),
    val selectedCategorySlug: String? = null,
    val searchQuery: String = "",
    val showOnlyLiveEvents: Boolean = false,
    val resolvingEventRoute: String? = null,
    val activeFeed: ActivePlayableFeed? = null,
    val isFullscreenPlayer: Boolean = false,
    val multiViewSlots: List<ActivePlayableFeed?> = listOf(null, null, null, null),
    val focusedMultiViewSlot: Int = 0,
    val isQuadMode: Boolean = true,
    val statusMessage: String? = null
) {
    val activeCategoryDisplayName: String
        get() {
            val slug = selectedCategorySlug ?: return "All Sports"
            val fromCatalog = catalog.categories.firstOrNull { it.slug == slug }?.displayTitle
            return fromCatalog ?: SportDisplayFormatter.formatSportName(slug)
        }

    fun filteredLiveStreams(): List<ResolvedStream> {
        val cat = selectedCategorySlug
        val allLive = resolvedStreams.filter { it.liveUrl != null }
        if (cat == null) return allLive.sortedByDescending { it.viewers }
        return allLive.filter { item ->
            item.ref.sport.equals(cat, ignoreCase = true) ||
                catalog.events.any { ev ->
                    ev.category == cat &&
                        item.ref.path in catalog.eventPaths[ev.route].orEmpty()
                }
        }.sortedByDescending { it.viewers }
    }
}

class PrismCastViewModel(
    application: Application,
    private val repository: StreamRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = StreamRepository(
            dao = PrismDatabase.getInstance(application).prismDao(),
            preferences = WhitelabelPreferences(application)
        )
    )

    val whitelabelConfig: WhitelabelConfig = repository.whitelabelConfig

    private val _uiState = MutableStateFlow(PrismUiState())
    val uiState: StateFlow<PrismUiState> = _uiState.asStateFlow()

    val savedStreams: StateFlow<List<SavedStreamEntity>> = repository.savedStreams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var refreshJob: Job? = null
    private var lineupRetryJob: Job? = null
    private var sessionRestoreJob: Job? = null
    private var reResolveJob: Job? = null
    private var viewerHeartbeatJob: Job? = null
    private var consecutiveLineupFailures: Int = 0

    init {
        restoreInterruptedSession()
        observeNetworkForAutoResume()
        refreshLineup()
    }

    private fun restoreInterruptedSession() {
        sessionRestoreJob = viewModelScope.launch {
            val session = repository.loadPlaybackSession() ?: return@launch
            when (session.mode) {
                "SINGLE" -> {
                    val feed = session.activeFeed ?: return@launch
                    _uiState.update { state ->
                        if (state.activeFeed != null) {
                            state
                        } else {
                            state.copy(
                                selectedCategorySlug = session.selectedCategorySlug ?: state.selectedCategorySlug,
                                activeFeed = feed,
                                isFullscreenPlayer = true
                            )
                        }
                    }
                    startViewerHeartbeatIfNeeded(feed.eventRoute)
                }
                "MULTIVIEW" -> {
                    if (session.multiViewSlots.any { it != null }) {
                        _uiState.update { state ->
                            if (state.activeFeed != null) {
                                state
                            } else {
                                state.copy(
                                    currentTab = NavTab.MULTIVIEW,
                                    multiViewSlots = session.multiViewSlots,
                                    focusedMultiViewSlot = session.focusedMultiViewSlot,
                                    isQuadMode = session.isQuadMode
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeNetworkForAutoResume() {
        viewModelScope.launch {
            NetworkConnectivityObserver.observeNetworkAvailable(getApplication()).collect { available ->
                if (available) {
                    val state = _uiState.value
                    if (!state.isRefreshing &&
                        (state.refreshError != null || state.resolvedStreams.isEmpty())
                    ) {
                        consecutiveLineupFailures = 0
                        refreshLineup()
                    }
                }
            }
        }
    }

    fun selectTab(tab: NavTab) {
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == NavTab.MULTIVIEW) {
            if (_uiState.value.multiViewSlots.all { it == null }) {
                autoFillMultiViewFromLive(silentIfEmpty = true)
            }
            persistCurrentSession()
        } else {
            // Leaving MultiView back to Live Hub or Schedule clears MultiView auto-resume state
            if (!_uiState.value.isFullscreenPlayer) {
                clearPersistedSession()
            }
        }
    }

    internal fun seedCatalogForTest(
        catalog: Catalog,
        resolvedStreams: List<ResolvedStream>
    ) {
        sessionRestoreJob?.cancel()
        refreshJob?.cancel()
        lineupRetryJob?.cancel()
        _uiState.update {
            it.copy(
                isRefreshing = false,
                refreshError = null,
                catalog = catalog,
                resolvedStreams = resolvedStreams
            )
        }
    }

    fun setCategoryFilter(slug: String?) {
        _uiState.update { it.copy(selectedCategorySlug = slug) }
        if (_uiState.value.isFullscreenPlayer) {
            persistCurrentSession()
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleLiveOnlyFilter() {
        _uiState.update { it.copy(showOnlyLiveEvents = !it.showOnlyLiveEvents) }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun setFullscreenPlayer(fullscreen: Boolean) {
        if (!fullscreen) {
            closePrimaryPlayer()
        } else {
            _uiState.update { it.copy(isFullscreenPlayer = true) }
            persistCurrentSession()
        }
    }

    fun refreshLineup() {
        if (refreshJob?.isActive == true) return
        lineupRetryJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    refreshError = null,
                    refreshStatusText = "Updating live channels…"
                )
            }
            try {
                val report = repository.runFullSweep(whitelabelConfig) { progress ->
                    _uiState.update { state -> state.copy(refreshStatusText = progress) }
                }
                consecutiveLineupFailures = 0
                val liveCount = report.resolvedStreams.count { it.liveUrl != null }
                _uiState.update { state ->
                    val upgradedActive = state.activeFeed?.let { active ->
                        upgradeFeedFromResolvedList(active, report.resolvedStreams)
                    }
                    val upgradedSlots = state.multiViewSlots.map { slot ->
                        slot?.let { upgradeFeedFromResolvedList(it, report.resolvedStreams) }
                    }
                    state.copy(
                        isRefreshing = false,
                        refreshStatusText = "",
                        refreshError = null,
                        catalog = report.catalog,
                        resolvedStreams = report.resolvedStreams,
                        activeFeed = upgradedActive,
                        multiViewSlots = upgradedSlots,
                        statusMessage = if (state.isFullscreenPlayer || state.currentTab == NavTab.MULTIVIEW) {
                            null
                        } else if (liveCount > 0) {
                            "$liveCount live broadcasts ready"
                        } else {
                            "Channel schedule updated"
                        }
                    )
                }
                if (_uiState.value.currentTab == NavTab.MULTIVIEW &&
                    _uiState.value.multiViewSlots.all { it == null }
                ) {
                    autoFillMultiViewFromLive(silentIfEmpty = true)
                }
            } catch (_: Exception) {
                consecutiveLineupFailures += 1
                _uiState.update { state ->
                    state.copy(
                        isRefreshing = false,
                        refreshStatusText = "",
                        refreshError = "Reconnecting to broadcast guide automatically…"
                    )
                }
                scheduleLineupAutoRetry(consecutiveLineupFailures)
            }
        }
    }

    private fun scheduleLineupAutoRetry(attempt: Int) {
        if (attempt > 5) return
        lineupRetryJob?.cancel()
        lineupRetryJob = viewModelScope.launch {
            delay(RetryBackoffPolicy.computeRetryDelayMs(attempt) * 2)
            if (!_uiState.value.isRefreshing && _uiState.value.resolvedStreams.isEmpty()) {
                refreshLineup()
            }
        }
    }

    private fun upgradeFeedFromResolvedList(
        feed: ActivePlayableFeed,
        resolved: List<ResolvedStream>
    ): ActivePlayableFeed {
        val match = resolved.firstOrNull { item ->
            item.liveUrl != null && (
                (!feed.streamPath.isNullOrBlank() && item.ref.path == feed.streamPath) ||
                    item.ref.displayTitle.equals(feed.title, ignoreCase = true)
                )
        } ?: return feed

        val freshLive = match.liveUrl ?: return feed
        val merged = buildList {
            addAll(feed.candidateUrls)
            if (freshLive !in this) add(0, freshLive)
            match.candidates.forEach { if (it !in this) add(it) }
        }
        val activeUrl = feed.activeUrl
        val preservedIndex = merged.indexOf(activeUrl).coerceAtLeast(0)
        return feed.copy(
            primaryUrl = merged.first(),
            candidateUrls = merged,
            currentCandidateIndex = preservedIndex,
            viewers = if (match.viewers > 0) match.viewers else feed.viewers
        )
    }

    fun playResolvedStream(
        stream: ResolvedStream,
        eventRoute: String? = null,
        openFullscreen: Boolean = true
    ) {
        val url = stream.liveUrl ?: stream.candidates.firstOrNull()
        if (url.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "This broadcast is not on air yet.") }
            return
        }
        val candidates = buildList {
            add(url)
            stream.candidates.forEach { if (it !in this) add(it) }
        }
        val sportDisplay = stream.ref.displaySport
        val feed = ActivePlayableFeed(
            id = stream.ref.key.ifEmpty { url },
            title = stream.ref.displayTitle,
            subtitle = sportDisplay,
            primaryUrl = url,
            candidateUrls = candidates,
            currentCandidateIndex = 0,
            category = sportDisplay,
            viewers = stream.viewers,
            eventRoute = eventRoute,
            streamPath = stream.ref.path,
            subdomain = stream.ref.subdomain,
            customHeaders = stream.ref.customHeaders
        )
        startPrimaryPlayback(feed, openFullscreen = openFullscreen)
    }

    fun playSavedStream(saved: SavedStreamEntity, openFullscreen: Boolean = true) {
        val matchingLive = _uiState.value.resolvedStreams.firstOrNull {
            it.liveUrl != null && (
                (!saved.streamPath.isNullOrBlank() && it.ref.path == saved.streamPath) ||
                    it.ref.displayTitle.equals(saved.title, ignoreCase = true)
                )
        }
        if (matchingLive != null) {
            playResolvedStream(matchingLive, openFullscreen = openFullscreen)
            return
        }

        val category = SportDisplayFormatter.formatSportName(saved.groupName)
        val feed = ActivePlayableFeed(
            id = "saved_${saved.id}",
            title = SportDisplayFormatter.cleanConsumerTitle(saved.title, null, category, null),
            subtitle = category,
            primaryUrl = saved.streamUrl,
            candidateUrls = listOf(saved.streamUrl),
            category = category,
            streamPath = saved.streamPath,
            subdomain = saved.subdomain
        )
        startPrimaryPlayback(feed, openFullscreen = openFullscreen)
    }

    fun resolveAndPlayEvent(event: StreamEvent, openFullscreen: Boolean = true) {
        val state = _uiState.value
        val knownPaths = state.catalog.eventPaths[event.route].orEmpty()
        val preResolved = state.resolvedStreams.filter { item ->
            item.liveUrl != null && (
                (item.ref.path != null && item.ref.path in knownPaths) ||
                    item.ref.group.equals(event.label, ignoreCase = true)
                )
        }
        if (preResolved.isNotEmpty()) {
            val best = preResolved.first()
            val mergedCandidates = preResolved.flatMap {
                listOfNotNull(it.liveUrl) + it.candidates
            }.distinct()
            playResolvedStream(
                best.copy(candidates = mergedCandidates),
                eventRoute = event.route,
                openFullscreen = openFullscreen
            )
            return
        }

        if (event.route.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Broadcast will open closer to start time.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(resolvingEventRoute = event.route) }
            try {
                val results = repository.resolveSingleEventStreams(
                    event = event,
                    knownPool = _uiState.value.catalog.pool,
                    config = whitelabelConfig
                )
                val playable = results.filter { it.liveUrl != null || it.probe.ok }
                _uiState.update { it.copy(resolvingEventRoute = null) }
                if (playable.isNotEmpty()) {
                    val primary = playable.first()
                    val allCandidates = playable.flatMap {
                        listOfNotNull(it.liveUrl) + it.candidates
                    }.distinct()
                    playResolvedStream(
                        primary.copy(candidates = allCandidates),
                        eventRoute = event.route,
                        openFullscreen = openFullscreen
                    )
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "${event.displayLabel} is not broadcasting yet.")
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        resolvingEventRoute = null,
                        statusMessage = "Broadcast currently unavailable. Please try refreshing."
                    )
                }
            }
        }
    }

    /**
     * Up / Down in Fullscreen Player: navigates previous / next live stream in the current category
     * (falling back to all live streams if current category has <= 1 live stream).
     */
    fun stepLiveChannel(delta: Int) {
        val state = _uiState.value
        val categoryStreams = state.filteredLiveStreams()
        val pool = if (categoryStreams.isNotEmpty()) {
            categoryStreams
        } else {
            state.resolvedStreams.filter { it.liveUrl != null }.sortedByDescending { it.viewers }
        }
        if (pool.isEmpty()) return

        val currentId = state.activeFeed?.id
        val currentIdx = pool.indexOfFirst { (it.ref.key.ifEmpty { it.liveUrl }) == currentId }
        val nextIdx = if (currentIdx == -1) {
            0
        } else {
            ((currentIdx + delta) % pool.size + pool.size) % pool.size
        }
        playResolvedStream(
            stream = pool[nextIdx],
            openFullscreen = true
        )
    }

    /**
     * Left / Right in Fullscreen Player: cycles through Sport Categories and tunes into the
     * top live broadcast of that category if available.
     */
    fun stepCategoryInPlayer(delta: Int) {
        val state = _uiState.value
        val allLive = state.resolvedStreams.filter { it.liveUrl != null }

        val nonMultiviewCats = state.catalog.categories.filter { !it.isMultiview }
        val liveCatSlugs = nonMultiviewCats.filter { cat ->
            allLive.any { item ->
                item.ref.sport.equals(cat.slug, ignoreCase = true) ||
                    state.catalog.events.any { ev ->
                        ev.category == cat.slug &&
                            item.ref.path in state.catalog.eventPaths[ev.route].orEmpty()
                    }
            }
        }.map { it.slug }

        val slugsToCycle: List<String?> = buildList {
            add(null)
            if (liveCatSlugs.isNotEmpty()) {
                addAll(liveCatSlugs)
            } else {
                addAll(nonMultiviewCats.map { it.slug })
            }
        }.distinct()

        if (slugsToCycle.size <= 1) return

        val currentIdx = slugsToCycle.indexOf(state.selectedCategorySlug).let {
            if (it == -1) 0 else it
        }
        val nextSlug = slugsToCycle[((currentIdx + delta) % slugsToCycle.size + slugsToCycle.size) % slugsToCycle.size]
        _uiState.update { it.copy(selectedCategorySlug = nextSlug) }

        val updatedState = _uiState.value
        val nextCategoryStreams = updatedState.filteredLiveStreams()
        val currentId = updatedState.activeFeed?.id
        val stillInCategory = nextCategoryStreams.any {
            (it.ref.key.ifEmpty { it.liveUrl }) == currentId
        }
        if (!stillInCategory && nextCategoryStreams.isNotEmpty()) {
            playResolvedStream(nextCategoryStreams.first(), openFullscreen = true)
        } else {
            persistCurrentSession()
        }
    }

    fun switchPrimaryCandidate(nextIndex: Int) {
        val current = _uiState.value.activeFeed ?: return
        if (current.candidateUrls.isEmpty()) return
        val wrapped = ((nextIndex % current.candidateUrls.size) + current.candidateUrls.size) %
            current.candidateUrls.size
        _uiState.update {
            it.copy(
                activeFeed = current.copy(
                    currentCandidateIndex = wrapped,
                    retryNonce = current.retryNonce + 1
                )
            )
        }
        persistCurrentSession()
    }

    /**
     * Automatic failover & continuous retry handler:
     * Cycles through all backup candidate feeds, wraps around so it never stops retrying,
     * increments retryNonce to force re-preparation even on single-URL feeds, and silently
     * re-resolves upstream pool domains whenever a full candidate cycle completes.
     */
    fun onPrimaryPlayerError() {
        val current = _uiState.value.activeFeed ?: return
        if (!whitelabelConfig.autoFailover) return

        val count = current.candidateUrls.size.coerceAtLeast(1)
        val nextIdx = (current.currentCandidateIndex + 1) % count
        val wrappedCycle = nextIdx == 0

        _uiState.update {
            it.copy(
                activeFeed = current.copy(
                    currentCandidateIndex = nextIdx,
                    retryNonce = current.retryNonce + 1
                )
            )
        }

        if (wrappedCycle) {
            reResolveActiveFeedSilently(current)
        }
    }

    private fun reResolveActiveFeedSilently(targetFeed: ActivePlayableFeed) {
        if (reResolveJob?.isActive == true) return
        reResolveJob = viewModelScope.launch {
            runCatching {
                val freshCandidates = repository.refreshFeedCandidates(
                    feed = targetFeed,
                    knownPool = _uiState.value.catalog.pool,
                    config = whitelabelConfig
                )
                if (freshCandidates.isNotEmpty()) {
                    _uiState.update { state ->
                        val active = state.activeFeed
                        if (active != null && active.id == targetFeed.id) {
                            val merged = (freshCandidates + active.candidateUrls).distinct()
                            state.copy(
                                activeFeed = active.copy(
                                    primaryUrl = merged.first(),
                                    candidateUrls = merged,
                                    currentCandidateIndex = 0,
                                    retryNonce = active.retryNonce + 1
                                )
                            )
                        } else {
                            state
                        }
                    }
                    persistCurrentSession()
                }
            }
        }
    }

    fun onMultiViewSlotExhausted(slotIndex: Int) {
        val idx = slotIndex.coerceIn(0, 3)
        val slotFeed = _uiState.value.multiViewSlots.getOrNull(idx) ?: return
        viewModelScope.launch {
            runCatching {
                val fresh = repository.refreshFeedCandidates(
                    feed = slotFeed,
                    knownPool = _uiState.value.catalog.pool,
                    config = whitelabelConfig
                )
                if (fresh.isNotEmpty()) {
                    _uiState.update { state ->
                        val currentSlot = state.multiViewSlots.getOrNull(idx)
                        if (currentSlot != null && currentSlot.id == slotFeed.id) {
                            val merged = (fresh + currentSlot.candidateUrls).distinct()
                            val updatedSlots = state.multiViewSlots.toMutableList()
                            updatedSlots[idx] = currentSlot.copy(
                                primaryUrl = merged.first(),
                                candidateUrls = merged,
                                currentCandidateIndex = 0,
                                retryNonce = currentSlot.retryNonce + 1
                            )
                            state.copy(multiViewSlots = updatedSlots)
                        } else {
                            state
                        }
                    }
                    persistCurrentSession()
                }
            }
        }
    }

    fun closePrimaryPlayer() {
        reResolveJob?.cancel()
        val oldRoute = _uiState.value.activeFeed?.eventRoute
        stopViewerHeartbeat(oldRoute)
        _uiState.update { it.copy(activeFeed = null, isFullscreenPlayer = false) }
        clearPersistedSession()
    }

    fun toggleFavorite(stream: ResolvedStream) {
        val url = stream.liveUrl ?: return
        val existing = savedStreams.value.firstOrNull {
            (!stream.ref.path.isNullOrBlank() && it.streamPath == stream.ref.path) ||
                it.streamUrl == url
        }
        viewModelScope.launch {
            if (existing != null) {
                repository.deleteSavedStream(existing.id)
                _uiState.update { it.copy(statusMessage = "Removed from Favorites") }
            } else {
                repository.addFavoriteStream(
                    title = stream.ref.displayTitle,
                    groupName = stream.ref.displaySport,
                    streamUrl = url,
                    streamPath = stream.ref.path,
                    subdomain = stream.ref.subdomain
                )
                _uiState.update { it.copy(statusMessage = "Added ${stream.ref.displayTitle} to Favorites") }
            }
        }
    }

    fun removeFavorite(id: Int) {
        viewModelScope.launch {
            repository.deleteSavedStream(id)
        }
    }

    // --------------------------------------------------------- MultiView Controls

    fun setMultiViewLayoutMode(quad: Boolean) {
        _uiState.update { it.copy(isQuadMode = quad) }
        persistCurrentSession()
    }

    fun focusMultiViewSlot(slotIndex: Int) {
        _uiState.update { it.copy(focusedMultiViewSlot = slotIndex.coerceIn(0, 3)) }
        persistCurrentSession()
    }

    fun assignFeedToMultiView(slotIndex: Int, feed: ActivePlayableFeed?) {
        val idx = slotIndex.coerceIn(0, 3)
        _uiState.update { state ->
            val updated = state.multiViewSlots.toMutableList()
            updated[idx] = feed
            state.copy(multiViewSlots = updated)
        }
        persistCurrentSession()
    }

    fun sendActiveFeedToMultiView() {
        val feed = _uiState.value.activeFeed ?: return
        val slots = _uiState.value.multiViewSlots
        val targetSlot = slots.indexOfFirst { it == null }.let { if (it == -1) 0 else it }
        assignFeedToMultiView(targetSlot, feed)
        _uiState.update {
            it.copy(
                currentTab = NavTab.MULTIVIEW,
                isFullscreenPlayer = false,
                activeFeed = null,
                focusedMultiViewSlot = targetSlot
            )
        }
        persistCurrentSession()
    }

    fun autoFillMultiViewFromLive(silentIfEmpty: Boolean = false) {
        val liveFeeds = _uiState.value.resolvedStreams
            .filter { it.liveUrl != null }
            .sortedByDescending { it.viewers }
            .map { stream ->
                val url = stream.liveUrl.orEmpty()
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
                    customHeaders = stream.ref.customHeaders
                )
            }
        val savedFeeds = savedStreams.value.map { s ->
            val cat = SportDisplayFormatter.formatSportName(s.groupName)
            ActivePlayableFeed(
                id = "saved_${s.id}",
                title = SportDisplayFormatter.cleanConsumerTitle(s.title, null, cat, null),
                subtitle = cat,
                primaryUrl = s.streamUrl,
                candidateUrls = listOf(s.streamUrl),
                category = cat,
                streamPath = s.streamPath,
                subdomain = s.subdomain
            )
        }
        val combined = (liveFeeds + savedFeeds).distinctBy { it.primaryUrl }.take(4)
        if (combined.isEmpty()) {
            if (!silentIfEmpty) {
                _uiState.update {
                    it.copy(statusMessage = "No live broadcasts on air right now to fill MultiView.")
                }
            }
            return
        }
        val padded = List(4) { i -> combined.getOrNull(i) }
        _uiState.update {
            it.copy(
                multiViewSlots = padded,
                isQuadMode = combined.size > 2,
                focusedMultiViewSlot = 0
            )
        }
        persistCurrentSession()
    }

    fun clearMultiView() {
        _uiState.update { it.copy(multiViewSlots = listOf(null, null, null, null)) }
        clearPersistedSession()
    }

    private fun startPrimaryPlayback(feed: ActivePlayableFeed, openFullscreen: Boolean = true) {
        reResolveJob?.cancel()
        val oldRoute = _uiState.value.activeFeed?.eventRoute
        if (oldRoute != feed.eventRoute) {
            stopViewerHeartbeat(oldRoute)
        }
        _uiState.update {
            it.copy(
                activeFeed = feed,
                isFullscreenPlayer = openFullscreen
            )
        }
        persistCurrentSession()
        startViewerHeartbeatIfNeeded(feed.eventRoute)
    }

    private fun startViewerHeartbeatIfNeeded(eventRoute: String?) {
        viewerHeartbeatJob?.cancel()
        if (!eventRoute.isNullOrBlank()) {
            viewerHeartbeatJob = viewModelScope.launch {
                while (isActive) {
                    repository.pingWatchingEvent(
                        eventRoute = eventRoute,
                        leave = false,
                        config = whitelabelConfig
                    )
                    delay(35_000L)
                }
            }
        }
    }

    private fun persistCurrentSession() {
        val state = _uiState.value
        val session = when {
            state.isFullscreenPlayer && state.activeFeed != null -> LastPlaybackSession(
                mode = "SINGLE",
                activeFeed = state.activeFeed,
                selectedCategorySlug = state.selectedCategorySlug
            )
            state.currentTab == NavTab.MULTIVIEW && state.multiViewSlots.any { it != null } -> LastPlaybackSession(
                mode = "MULTIVIEW",
                multiViewSlots = state.multiViewSlots,
                focusedMultiViewSlot = state.focusedMultiViewSlot,
                isQuadMode = state.isQuadMode
            )
            else -> null
        }
        viewModelScope.launch {
            repository.savePlaybackSession(session)
        }
    }

    private fun clearPersistedSession() {
        viewModelScope.launch {
            repository.savePlaybackSession(null)
        }
    }

    private fun stopViewerHeartbeat(route: String?) {
        viewerHeartbeatJob?.cancel()
        viewerHeartbeatJob = null
        if (!route.isNullOrBlank()) {
            viewModelScope.launch {
                repository.pingWatchingEvent(
                    eventRoute = route,
                    leave = true,
                    config = whitelabelConfig
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewerHeartbeatJob?.cancel()
        reResolveJob?.cancel()
        lineupRetryJob?.cancel()
    }
}
