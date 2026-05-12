package com.hjw.qbremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.hjw.qbremote.data.AppLanguage
import com.hjw.qbremote.data.AppTheme
import com.hjw.qbremote.data.BackendConnectionError
import com.hjw.qbremote.data.CachedDashboardServerSnapshot
import com.hjw.qbremote.data.ConnectionSettings
import com.hjw.qbremote.data.ConnectionStore
import com.hjw.qbremote.data.CachedDailyTagUploadStat
import com.hjw.qbremote.data.DailyCountryUploadTrackingSnapshot
import com.hjw.qbremote.data.DailyUploadTrackingSnapshot
import com.hjw.qbremote.data.DashboardCacheSnapshot
import com.hjw.qbremote.data.HomeAggregateSpeedHistorySnapshot
import com.hjw.qbremote.data.HomeSpeedHistoryPoint
import com.hjw.qbremote.data.ServerBackendType
import com.hjw.qbremote.data.ServerCapabilities
import com.hjw.qbremote.data.ServerDashboardPreferences
import com.hjw.qbremote.data.ServerProfile
import com.hjw.qbremote.MainActivity
import com.hjw.qbremote.TorrentWidgetProvider
import com.hjw.qbremote.data.TorrentRepository
import com.hjw.qbremote.data.defaultCapabilitiesFor
import com.hjw.qbremote.data.model.AddTorrentFile
import com.hjw.qbremote.data.model.AddTorrentRequest
import com.hjw.qbremote.data.model.CountryPeerSnapshot
import com.hjw.qbremote.data.model.CountryUploadRecord
import com.hjw.qbremote.data.model.DailyCountryUploadStats
import com.hjw.qbremote.data.model.TorrentFileInfo
import com.hjw.qbremote.data.model.TorrentInfo
import com.hjw.qbremote.data.model.TorrentProperties
import com.hjw.qbremote.data.model.TorrentTracker
import com.hjw.qbremote.data.model.TransferInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

enum class RefreshScene {
    DASHBOARD,
    SERVER,
    TORRENT_DETAIL,
    SETTINGS,
}

data class DailyTagUploadStat(
    val tag: String,
    val uploadedBytes: Long,
    val torrentCount: Int,
    val isNoTag: Boolean = false,
)

data class RealtimeSpeedPoint(
    val timestamp: Long = 0L,
    val uploadSpeed: Long = 0L,
    val downloadSpeed: Long = 0L,
    val onlineServerCount: Int = 0,
)

data class DashboardAggregateState(
    val transferInfo: TransferInfo = TransferInfo(),
    val chartTransferInfo: TransferInfo? = null,
    val torrents: List<TorrentInfo> = emptyList(),
    val dailyTagUploadDate: String = "",
    val dailyTagUploadStats: List<DailyTagUploadStat> = emptyList(),
    val dailyCountryUploadDate: String = "",
    val dailyCountryUploadStats: List<CountryUploadRecord> = emptyList(),
    val realtimeSpeedSeries: List<RealtimeSpeedPoint> = emptyList(),
    val totalServerCount: Int = 0,
    val categoryCoverageServerCount: Int = 0,
    val countryCoverageServerCount: Int = 0,
)

data class PendingBackendRepair(
    val profileId: String,
    val profileName: String,
    val expectedBackend: ServerBackendType,
    val detectedBackend: ServerBackendType,
    val detail: String = "",
)

@androidx.compose.runtime.Immutable
data class MainUiState(
    val settings: ConnectionSettings = ConnectionSettings(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val activeServerProfileId: String? = null,
    val activeCapabilities: ServerCapabilities = defaultCapabilitiesFor(ServerBackendType.QBITTORRENT),
    val aggregateOnlineServerCount: Int = 0,
    val isConnecting: Boolean = false,
    val isManualRefreshing: Boolean = false,
    val connected: Boolean = false,
    val serverVersion: String = "-",
    val transferInfo: TransferInfo = TransferInfo(),
    val torrents: List<TorrentInfo> = emptyList(),
    val detailHash: String = "",
    val detailLoading: Boolean = false,
    val detailProperties: TorrentProperties? = null,
    val detailFiles: List<TorrentFileInfo> = emptyList(),
    val detailTrackers: List<TorrentTracker> = emptyList(),
    val categoryOptions: List<String> = emptyList(),
    val tagOptions: List<String> = emptyList(),
    val dailyTagUploadDate: String = "",
    val dailyTagUploadStats: List<DailyTagUploadStat> = emptyList(),
    val dailyCountryUploadDate: String = "",
    val dailyCountryUploadStats: List<CountryUploadRecord> = emptyList(),
    val dashboardServerSnapshots: List<CachedDashboardServerSnapshot> = emptyList(),
    val serverDashboardPreferences: Map<String, ServerDashboardPreferences> = emptyMap(),
    val selectedDashboardProfileId: String? = null,
    val dashboardSessionToken: Long = 0L,
    val dashboardRefreshHoldProfileId: String? = null,
    val dashboardAggregate: DashboardAggregateState = DashboardAggregateState(),
    val dashboardCacheHydrated: Boolean = false,
    val hasDashboardSnapshot: Boolean = false,
    val startupRestoreComplete: Boolean = false,
    val refreshScene: RefreshScene = RefreshScene.DASHBOARD,
    val pendingActionKeys: Set<String> = emptySet(),
    val pendingBackendRepair: PendingBackendRepair? = null,
    val sharedMagnetUrl: String = "",
    val errorMessage: String? = null,
)

internal data class DashboardReorderHoldReleaseResult(
    val nextHeldProfileId: String? = null,
    val profileIdToRefreshImmediately: String? = null,
)

internal fun shouldSkipRefreshForDashboardReorderHold(
    heldProfileId: String?,
    profileId: String,
): Boolean {
    val normalizedHeldProfileId = heldProfileId?.trim().orEmpty()
    val normalizedProfileId = profileId.trim()
    return normalizedHeldProfileId.isNotBlank() &&
        normalizedProfileId.isNotBlank() &&
        normalizedHeldProfileId == normalizedProfileId
}

internal fun releaseDashboardReorderHold(
    state: MainUiState,
): DashboardReorderHoldReleaseResult {
    val heldProfileId = state.dashboardRefreshHoldProfileId?.trim().orEmpty()
    if (heldProfileId.isBlank()) return DashboardReorderHoldReleaseResult()
    return DashboardReorderHoldReleaseResult(
        nextHeldProfileId = null,
        profileIdToRefreshImmediately = heldProfileId,
    )
}

private sealed interface DashboardSnapshotRefreshResult {
    val profile: ServerProfile
    val previousSnapshot: CachedDashboardServerSnapshot?

    data class Fresh(
        override val profile: ServerProfile,
        val settings: ConnectionSettings,
        val fetched: com.hjw.qbremote.data.DashboardSnapshotFetchResult,
        override val previousSnapshot: CachedDashboardServerSnapshot?,
    ) : DashboardSnapshotRefreshResult

    data class Failure(
        override val profile: ServerProfile,
        val error: Throwable,
        override val previousSnapshot: CachedDashboardServerSnapshot?,
    ) : DashboardSnapshotRefreshResult
}

private data class DashboardStatsRefreshInput(
    val profile: ServerProfile,
    val settings: ConnectionSettings,
    val torrents: List<TorrentInfo>,
    val baseSnapshot: CachedDashboardServerSnapshot,
)

internal fun buildPendingActionKey(
    profileId: String,
    hash: String,
): String {
    return "${profileId.trim()}|${hash.trim()}"
}

internal fun shouldApplyActiveProfileAsyncResult(
    requestedProfileId: String,
    requestVersion: Long,
    activeProfileId: String?,
    activeRequestVersion: Long,
): Boolean {
    val normalizedProfileId = requestedProfileId.trim()
    return normalizedProfileId.isNotBlank() &&
        activeProfileId == normalizedProfileId &&
        activeRequestVersion == requestVersion
}

internal fun buildDailyUploadTrackingScopeKey(
    activeProfileId: String?,
    settings: ConnectionSettings,
): String {
    val normalizedProfileId = activeProfileId.orEmpty().trim()
    if (normalizedProfileId.isNotBlank()) {
        return "profile:$normalizedProfileId"
    }

    val host = settings.host.trim().lowercase()
    return if (host.isNotBlank()) {
        "server:${settings.useHttps}|$host|${settings.port}"
    } else {
        "default"
    }
}

internal fun normalizeProfileIdsForRefresh(
    profiles: List<ServerProfile>,
): List<String> {
    return profiles
        .map { profile -> profile.id.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

internal fun resolvePreferredProfileId(
    availableIds: List<String>,
    primaryCandidate: String?,
    secondaryCandidate: String?,
): String? {
    if (availableIds.isEmpty()) return null
    val availableIdSet = availableIds.toHashSet()
    return primaryCandidate?.takeIf { it in availableIdSet }
        ?: secondaryCandidate?.takeIf { it in availableIdSet }
        ?: availableIds.firstOrNull()
}

internal fun filterDashboardPreferencesForProfiles(
    preferences: Map<String, ServerDashboardPreferences>,
    profiles: List<ServerProfile>,
): Map<String, ServerDashboardPreferences> {
    if (preferences.isEmpty() || profiles.isEmpty()) return emptyMap()
    val profileIdSet = profiles.mapTo(mutableSetOf()) { profile -> profile.id }
    return preferences.filterKeys { profileId -> profileId in profileIdSet }
}

internal fun resolveSelectedDashboardProfileId(
    activeProfileId: String?,
    selectedDashboardProfileId: String?,
    snapshots: List<CachedDashboardServerSnapshot>,
): String? {
    return resolvePreferredProfileId(
        availableIds = snapshots.map { snapshot -> snapshot.profileId },
        primaryCandidate = activeProfileId,
        secondaryCandidate = selectedDashboardProfileId,
    )
}

internal fun applyDashboardSnapshotsToState(
    current: MainUiState,
    orderedSnapshots: List<CachedDashboardServerSnapshot>,
    aggregate: DashboardAggregateState,
): MainUiState {
    return current.copy(
        dashboardServerSnapshots = orderedSnapshots,
        selectedDashboardProfileId = resolveSelectedDashboardProfileId(
            activeProfileId = current.activeServerProfileId,
            selectedDashboardProfileId = current.selectedDashboardProfileId,
            snapshots = orderedSnapshots,
        ),
        dashboardAggregate = aggregate.copy(
            chartTransferInfo = current.dashboardAggregate.chartTransferInfo,
        ),
        aggregateOnlineServerCount = orderedSnapshots.count { !it.isStale },
    )
}

internal fun restoreHomeRealtimeSpeedSeries(
    snapshot: HomeAggregateSpeedHistorySnapshot,
    maxPoints: Int,
): List<RealtimeSpeedPoint> {
    if (maxPoints <= 0) return emptyList()
    return snapshot.points
        .map { point ->
            RealtimeSpeedPoint(
                timestamp = point.timestamp.coerceAtLeast(0L),
                uploadSpeed = point.uploadSpeed.coerceAtLeast(0L),
                downloadSpeed = point.downloadSpeed.coerceAtLeast(0L),
                onlineServerCount = point.onlineServerCount.coerceAtLeast(0),
            )
        }
        .sortedBy { point -> point.timestamp }
        .toList()
        .takeLast(maxPoints)
}

internal fun buildHomeRealtimeSpeedScopeKey(
    profileIds: List<String>,
    fallbackScopeKey: String,
): String {
    val profileSetKey = profileIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .joinToString(",")
    return if (profileSetKey.isNotBlank()) {
        "profiles:$profileSetKey"
    } else {
        "fallback:${fallbackScopeKey.trim()}"
    }
}

internal fun restoreHomeRealtimeSpeedSeriesForScope(
    snapshot: HomeAggregateSpeedHistorySnapshot?,
    scopeKey: String,
    maxPoints: Int,
): List<RealtimeSpeedPoint> {
    if (snapshot == null) return emptyList()
    val normalizedScopeKey = scopeKey.trim()
    if (normalizedScopeKey.isBlank() || snapshot.scopeKey != normalizedScopeKey) return emptyList()
    return restoreHomeRealtimeSpeedSeries(snapshot, maxPoints)
}

internal fun resolveHomeSpeedRefreshIntervalSeconds(scene: RefreshScene): Int? {
    return if (scene == RefreshScene.DASHBOARD) 3 else null
}

internal fun buildHomeChartTransferInfo(
    transferInfos: Collection<TransferInfo>,
): TransferInfo {
    return transferInfos.fold(TransferInfo()) { acc, transferInfo ->
        TransferInfo(
            downloadSpeed = acc.downloadSpeed + transferInfo.downloadSpeed,
            uploadSpeed = acc.uploadSpeed + transferInfo.uploadSpeed,
            downloadedTotal = acc.downloadedTotal + transferInfo.downloadedTotal,
            uploadedTotal = acc.uploadedTotal + transferInfo.uploadedTotal,
            downloadRateLimit = acc.downloadRateLimit + transferInfo.downloadRateLimit,
            uploadRateLimit = acc.uploadRateLimit + transferInfo.uploadRateLimit,
            freeSpaceOnDisk = acc.freeSpaceOnDisk + transferInfo.freeSpaceOnDisk,
            dhtNodes = acc.dhtNodes + transferInfo.dhtNodes,
        )
    }
}

internal fun applyHomeChartRefreshToAggregate(
    aggregate: DashboardAggregateState,
    chartTransferInfo: TransferInfo,
    chartSeries: List<RealtimeSpeedPoint>,
): DashboardAggregateState {
    return aggregate.copy(
        chartTransferInfo = chartTransferInfo,
        realtimeSpeedSeries = chartSeries,
    )
}

internal fun prepareConnectingProfileState(
    current: MainUiState,
    settings: ConnectionSettings,
    profileId: String,
    capabilities: ServerCapabilities,
): MainUiState {
    return current.copy(
        settings = settings,
        activeServerProfileId = profileId,
        selectedDashboardProfileId = profileId,
        dashboardSessionToken = current.dashboardSessionToken + 1L,
        activeCapabilities = capabilities,
        isConnecting = true,
        connected = false,
        pendingBackendRepair = null,
        errorMessage = null,
        serverVersion = "-",
        transferInfo = TransferInfo(),
        torrents = emptyList(),
        dailyTagUploadDate = "",
        dailyTagUploadStats = emptyList(),
        dailyCountryUploadDate = "",
        dailyCountryUploadStats = emptyList(),
        categoryOptions = emptyList(),
        tagOptions = emptyList(),
        detailHash = "",
        detailLoading = false,
        detailProperties = null,
        detailFiles = emptyList(),
        detailTrackers = emptyList(),
        pendingActionKeys = emptySet(),
    )
}

class MainViewModel(
    private val connectionStore: ConnectionStore,
    private val repository: TorrentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val torrentListFilterState = MutableStateFlow(TorrentListFilterState())
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val torrentListDisplayState: StateFlow<TorrentListDisplayState> = combine(
        _uiState.map { it.torrents }.distinctUntilChanged(),
        torrentListFilterState,
    ) { torrents, filterState ->
        torrents to filterState
    }.mapLatest { (torrents, filterState) ->
        withContext(Dispatchers.Default) {
            buildTorrentListDisplayState(
                torrents = torrents,
                filterState = filterState,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TorrentListDisplayState(),
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val serverDashboardDisplayState: StateFlow<ServerDashboardDisplayState> = _uiState
        .map(::buildServerDashboardDisplayInput)
        .distinctUntilChanged()
        .mapLatest { state ->
            withContext(Dispatchers.Default) {
                buildServerDashboardDisplayState(state)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServerDashboardDisplayState(),
        )

    private val backgroundJobManager = BackgroundJobManager(
        scope = viewModelScope,
        getState = { _uiState.value },
        onAutoRefresh = { refresh() },
        onHomeChartRefresh = { refreshHomeDashboardChartTransferInfo() },
    )
    private var countryPeerTrackerJob: Job? = null
    private var dashboardCacheHydrationJob: Job? = null
    private var dashboardAggregationJob: Job? = null
    private var serverSchedulerJob: Job? = null
    private var autoConnectAttempted = false
    private var isRefreshInProgress = false
    private var hydratedDashboardScopeKey: String? = null
    private var initialSettingsLoaded = false
    private var initialServerProfilesLoaded = false
    private var initialDashboardCacheHydrated = false
    private var initialDashboardSnapshotsHydrated = false
    private var activeProfileRequestVersion = 0L
    private val previousTorrentStates = mutableMapOf<String, String>()

    private val realtimeSpeedTracker = RealtimeSpeedTracker(connectionStore)
    private val dailyCountryUploadTracker = DailyCountryUploadTracker(connectionStore, repository)
    private val serverRefreshMutex = Mutex()
    private val cachedProfileSettings = mutableMapOf<String, ConnectionSettings>()
    private val nextServerRefreshAt = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            connectionStore.migrateLegacyPasswordIfNeeded()
            connectionStore.cleanupLegacyGlobalChartSettingsIfNeeded()
            launch {
                connectionStore.settingsFlow.collect { settings ->
                    _uiState.update { current ->
                        current.copy(
                            settings = settings,
                            activeCapabilities = repository.capabilitiesFor(settings),
                        )
                    }
                    hydrateDashboardCacheForCurrentScope()
                    markInitialSettingsLoaded()
                }
            }
            launch {
                connectionStore.serverProfilesFlow.collect { profilesState ->
                    val previousActiveProfileId = _uiState.value.activeServerProfileId
                    if (profilesState.activeProfileId != previousActiveProfileId) {
                        bumpActiveProfileRequestVersion()
                    }
                    pruneCachedProfileSettingsInMemory(profilesState.profiles)
                    repository.selectProfile(profilesState.activeProfileId)
                    val dashboardPreferences = connectionStore.loadServerDashboardPreferences()
                    val availableProfileIds = profilesState.profiles.map { profile -> profile.id }
                    val availableProfileIdSet = availableProfileIds.toHashSet()
                    _uiState.update { current ->
                        current.copy(
                            serverProfiles = profilesState.profiles,
                            serverDashboardPreferences = filterDashboardPreferencesForProfiles(
                                preferences = dashboardPreferences,
                                profiles = profilesState.profiles,
                            ),
                            activeServerProfileId = profilesState.activeProfileId,
                            selectedDashboardProfileId = resolvePreferredProfileId(
                                availableIds = availableProfileIds,
                                primaryCandidate = current.selectedDashboardProfileId,
                                secondaryCandidate = profilesState.activeProfileId,
                            ),
                            pendingBackendRepair = current.pendingBackendRepair
                                ?.takeIf { pending -> pending.profileId in availableProfileIdSet },
                        )
                    }
                    seedCachedSettingsForProfile(profilesState.activeProfileId)
                    hydrateDashboardCacheForCurrentScope()
                    hydrateDashboardServerSnapshots()
                    synchronizeServerScheduler()
                    startHomeChartRefresh()
                    autoConnectIfNeeded(_uiState.value.settings)
                    markInitialServerProfilesLoaded()
                }
            }
        }
    }

    internal fun updateTorrentSearchQuery(query: String) {
        torrentListFilterState.update { current ->
            if (current.query == query) current else current.copy(query = query)
        }
    }

    internal fun updateTorrentListSortOption(sortOption: TorrentListSortOption) {
        torrentListFilterState.update { current ->
            if (current.sortOption == sortOption) current else current.copy(sortOption = sortOption)
        }
    }

    internal fun updateTorrentListSortDirection(descending: Boolean) {
        torrentListFilterState.update { current ->
            if (current.descending == descending) current else current.copy(descending = descending)
        }
    }

    internal fun updateTorrentListStateFilter(stateFilter: TorrentStateFilter) {
        torrentListFilterState.update { current ->
            if (current.stateFilter == stateFilter) current else current.copy(stateFilter = stateFilter)
        }
    }

    internal fun updateTorrentListCategoryFilter(category: String) {
        torrentListFilterState.update { current ->
            val next = if (current.categoryFilter == category) "" else category
            if (current.categoryFilter == next) current else current.copy(categoryFilter = next)
        }
    }

    internal fun updateTorrentListTagFilter(tag: String) {
        torrentListFilterState.update { current ->
            val next = if (current.tagFilter == tag) "" else tag
            if (current.tagFilter == next) current else current.copy(tagFilter = next)
        }
    }

    private fun markInitialSettingsLoaded() {
        if (!initialSettingsLoaded) {
            initialSettingsLoaded = true
            maybeMarkStartupRestoreComplete()
        }
    }

    private fun markInitialServerProfilesLoaded() {
        if (!initialServerProfilesLoaded) {
            initialServerProfilesLoaded = true
            maybeMarkStartupRestoreComplete()
        }
    }

    private fun markInitialDashboardCacheHydrated() {
        if (!initialDashboardCacheHydrated) {
            initialDashboardCacheHydrated = true
            maybeMarkStartupRestoreComplete()
        }
    }

    private fun markInitialDashboardSnapshotsHydrated() {
        if (!initialDashboardSnapshotsHydrated) {
            initialDashboardSnapshotsHydrated = true
            maybeMarkStartupRestoreComplete()
        }
    }

    private fun maybeMarkStartupRestoreComplete() {
        if (
            _uiState.value.startupRestoreComplete ||
            !initialSettingsLoaded ||
            !initialServerProfilesLoaded ||
            !initialDashboardCacheHydrated ||
            !initialDashboardSnapshotsHydrated
        ) {
            return
        }
        _uiState.update { current ->
            if (current.startupRestoreComplete) current else current.copy(startupRestoreComplete = true)
        }
    }

    fun updateHost(value: String) = updateSettings { current ->
        val parsed = parseHostInputHints(value)
        current.copy(
            host = value,
            port = parsed?.port ?: current.port,
            useHttps = parsed?.useHttps ?: current.useHttps,
        )
    }
    fun updatePort(value: String) = updateSettings { it.copy(port = value.toIntOrNull() ?: 0) }
    fun updateUseHttps(value: Boolean) = updateSettings { it.copy(useHttps = value) }
    fun updateUsername(value: String) = updateSettings { it.copy(username = value) }
    fun updatePassword(value: String) = updateSettings { it.copy(password = value) }
    fun updateServerBackendType(value: ServerBackendType) = updateSettings { it.copy(serverBackendType = value) }
    fun updateRefreshSeconds(value: String) {
        val sec = value.toIntOrNull()?.coerceIn(5, 120) ?: 5
        updateSettings { it.copy(refreshSeconds = sec) }
    }

    fun updateAppLanguage(value: AppLanguage) = updateAndPersistSettings {
        it.copy(appLanguage = value)
    }

    fun updateAppTheme(value: AppTheme) = updateAndPersistSettings {
        it.copy(appTheme = value)
    }

    fun applyCustomThemeBackground(
        imagePath: String,
        toneIsLight: Boolean,
    ) = updateAndPersistSettings {
        it.copy(
            appTheme = AppTheme.CUSTOM,
            customBackgroundImagePath = imagePath,
            customBackgroundToneIsLight = toneIsLight,
        )
    }

    fun updateDeleteFilesDefault(value: Boolean) = updateAndPersistSettings {
        it.copy(deleteFilesDefault = value)
    }

    fun updateDeleteFilesWhenNoSeeders(value: Boolean) = updateAndPersistSettings {
        it.copy(deleteFilesWhenNoSeeders = value)
    }

    fun dismissHomeTorrentEntryHint() = updateAndPersistSettings {
        if (it.homeTorrentEntryHintDismissed) {
            it
        } else {
            it.copy(homeTorrentEntryHintDismissed = true)
        }
    }

    fun markDashboardHideHintSeen() = updateAndPersistSettings {
        if (it.hasSeenDashboardHideHint) it else it.copy(hasSeenDashboardHideHint = true)
    }

    fun markDashboardHiddenSnackSeen() = updateAndPersistSettings {
        if (it.hasSeenDashboardHiddenSnack) it else it.copy(hasSeenDashboardHiddenSnack = true)
    }

    fun updateRefreshScene(scene: RefreshScene) {
        _uiState.update { current ->
            if (current.refreshScene == scene) current else current.copy(refreshScene = scene)
        }
    }

    fun setDashboardReorderHold(profileId: String?) {
        val normalizedProfileId = profileId?.trim().orEmpty().ifBlank { null }
        var releaseResult = DashboardReorderHoldReleaseResult()
        _uiState.update { current ->
            if (normalizedProfileId != null) {
                if (current.dashboardRefreshHoldProfileId == normalizedProfileId) {
                    current
                } else {
                    current.copy(dashboardRefreshHoldProfileId = normalizedProfileId)
                }
            } else {
                releaseResult = releaseDashboardReorderHold(current)
                if (releaseResult.profileIdToRefreshImmediately == null) {
                    current
                } else {
                    current.copy(dashboardRefreshHoldProfileId = releaseResult.nextHeldProfileId)
                }
            }
        }
        val releasedProfileId = releaseResult.profileIdToRefreshImmediately ?: return
        nextServerRefreshAt[releasedProfileId] = 0L
        viewModelScope.launch {
            refreshServerSnapshotNow(
                profileId = releasedProfileId,
                showSelectedError = false,
            )
        }
    }

    fun prepareServerDashboardTransition(profileId: String) {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return
        _uiState.update { current ->
            current.copy(
                selectedDashboardProfileId = normalizedProfileId,
                dashboardSessionToken = current.dashboardSessionToken + 1L,
                isConnecting = true,
                connected = false,
                errorMessage = null,
                pendingBackendRepair = current.pendingBackendRepair
                    ?.takeUnless { it.profileId != normalizedProfileId },
                serverVersion = "-",
                transferInfo = TransferInfo(),
                torrents = emptyList(),
                dailyTagUploadDate = "",
                dailyTagUploadStats = emptyList(),
                dailyCountryUploadDate = "",
                dailyCountryUploadStats = emptyList(),
                categoryOptions = emptyList(),
                tagOptions = emptyList(),
                dashboardCacheHydrated = false,
                hasDashboardSnapshot = false,
                detailHash = "",
                detailLoading = false,
                detailProperties = null,
                detailFiles = emptyList(),
                detailTrackers = emptyList(),
                pendingActionKeys = emptySet(),
            )
        }
    }

    fun connect() {
        viewModelScope.launch {
            runCatching {
                val currentState = _uiState.value
                val targetProfileId = when {
                    !currentState.activeServerProfileId.isNullOrBlank() -> currentState.activeServerProfileId
                    currentState.settings.host.trim().isNotBlank() && currentState.settings.username.trim().isNotBlank() -> {
                        connectionStore.save(currentState.settings)
                        connectionStore.serverProfilesFlow.first().activeProfileId
                    }

                    else -> null
                } ?: error("请先添加服务器。")

                val targetSettings = connectionStore.switchToServerProfile(targetProfileId)
                repository.selectProfile(targetProfileId)
                bumpActiveProfileRequestVersion()
                val capabilities = repository.capabilitiesFor(targetSettings)
                _uiState.update { current ->
                    prepareConnectingProfileState(
                        current = current,
                        settings = targetSettings,
                        profileId = targetProfileId,
                        capabilities = capabilities,
                    )
                }
                hydrateDashboardCacheForCurrentScope(force = true)
                synchronizeServerScheduler()
                nextServerRefreshAt[targetProfileId] = 0L
                refreshServerSnapshotNow(
                    profileId = targetProfileId,
                    showSelectedError = true,
                    forceSettings = targetSettings,
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connected = false,
                        errorMessage = error.message ?: "连接服务器失败",
                    )
                }
            }
        }
    }

    fun addServerProfile(
        name: String,
        backendType: ServerBackendType,
        host: String,
        port: String,
        useHttps: Boolean,
        username: String,
        password: String,
        refreshSeconds: String,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val nextSettings = buildProfileSettingsDraft(
                    backendType = backendType,
                    host = host,
                    port = port,
                    useHttps = useHttps,
                    username = username,
                    password = password,
                    refreshSeconds = refreshSeconds,
                )

                val profile = connectionStore.addServerProfile(name = name, settings = nextSettings)
                val switched = connectionStore.switchToServerProfile(profile.id)
                repository.selectProfile(profile.id)
                bumpActiveProfileRequestVersion()
                val capabilities = repository.capabilitiesFor(switched)
                _uiState.update { current ->
                    prepareConnectingProfileState(
                        current = current,
                        settings = switched,
                        profileId = profile.id,
                        capabilities = capabilities,
                    )
                }
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "添加服务器失败")
                }
            }
            if (result.isSuccess) {
                hydrateDashboardCacheForCurrentScope(force = true)
                synchronizeServerScheduler()
                val profileId = _uiState.value.activeServerProfileId ?: return@launch
                nextServerRefreshAt[profileId] = 0L
                refreshServerSnapshotNow(profileId = profileId, showSelectedError = true)
            }
        }
    }

    fun updateServerProfile(
        profileId: String,
        name: String,
        backendType: ServerBackendType,
        host: String,
        port: String,
        useHttps: Boolean,
        username: String,
        password: String,
        refreshSeconds: String,
    ) {
        if (profileId.isBlank()) return
        viewModelScope.launch {
            val wasActive = _uiState.value.activeServerProfileId == profileId
            val result = runCatching {
                val existingSettings = connectionStore.loadSettingsForProfile(profileId)
                    ?: error("服务器配置不存在")
                val nextSettings = buildProfileSettingsDraft(
                    baseSettings = existingSettings,
                    backendType = backendType,
                    host = host,
                    port = port,
                    useHttps = useHttps,
                    username = username,
                    password = password.ifBlank { existingSettings.password },
                    refreshSeconds = refreshSeconds,
                )
                connectionStore.updateServerProfile(
                    profileId = profileId,
                    name = name,
                    settings = nextSettings,
                    passwordOverride = password.takeIf { it.isNotBlank() },
                )
                repository.removeProfile(profileId)
                nextServerRefreshAt[profileId] = 0L
                if (wasActive) {
                    val switched = connectionStore.switchToServerProfile(profileId)
                    repository.selectProfile(profileId)
                    bumpActiveProfileRequestVersion()
                    val capabilities = repository.capabilitiesFor(switched)
                    _uiState.update { current ->
                        prepareConnectingProfileState(
                            current = current,
                            settings = switched,
                            profileId = profileId,
                            capabilities = capabilities,
                        )
                    }
                }
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "更新服务器失败")
                }
            }
            if (result.isSuccess) {
                hydrateDashboardServerSnapshots()
                synchronizeServerScheduler()
                refreshServerSnapshotNow(profileId = profileId, showSelectedError = wasActive)
            }
        }
    }

    fun deleteServerProfile(profileId: String) {
        if (profileId.isBlank()) return
        viewModelScope.launch {
            val result = runCatching {
                connectionStore.deleteServerProfile(profileId)
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "删除服务器失败")
                }
            }
            result.getOrNull()?.let { resultValue ->
                repository.removeProfile(profileId)
                nextServerRefreshAt.remove(profileId)
                hydrateDashboardServerSnapshots()

                val nextProfileId = resultValue.activeProfileId
                if (nextProfileId.isNullOrBlank()) {
                    serverSchedulerJob?.cancel()
                    serverSchedulerJob = null
                    repository.clearAllSessions()
                    bumpActiveProfileRequestVersion()
                    _uiState.update { current ->
                        current.copy(
                            activeServerProfileId = null,
                            selectedDashboardProfileId = null,
                            dashboardSessionToken = current.dashboardSessionToken + 1L,
                            connected = false,
                            isConnecting = false,
                            serverVersion = "-",
                            transferInfo = TransferInfo(),
                            torrents = emptyList(),
                            dailyTagUploadDate = "",
                            dailyTagUploadStats = emptyList(),
                            dailyCountryUploadDate = "",
                            dailyCountryUploadStats = emptyList(),
                            dashboardServerSnapshots = emptyList(),
                            dashboardAggregate = DashboardAggregateState(),
                            categoryOptions = emptyList(),
                            tagOptions = emptyList(),
                            pendingBackendRepair = null,
                            detailHash = "",
                            detailLoading = false,
                            detailProperties = null,
                            detailFiles = emptyList(),
                            detailTrackers = emptyList(),
                            pendingActionKeys = emptySet(),
                        )
                    }
                } else {
                    repository.selectProfile(nextProfileId)
                    val nextSettings = resultValue.settings
                        ?: connectionStore.loadSettingsForProfile(nextProfileId)
                        ?: _uiState.value.settings
                    bumpActiveProfileRequestVersion()
                    val capabilities = repository.capabilitiesFor(nextSettings)
                    _uiState.update { current ->
                        prepareConnectingProfileState(
                            current = current,
                            settings = nextSettings,
                            profileId = nextProfileId,
                            capabilities = capabilities,
                        )
                    }
                    hydrateDashboardCacheForCurrentScope(force = true)
                    synchronizeServerScheduler()
                    nextServerRefreshAt[nextProfileId] = 0L
                    refreshServerSnapshotNow(profileId = nextProfileId, showSelectedError = false)
                }
            }
        }
    }

    fun switchServerProfile(profileId: String) {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return
        prepareServerDashboardTransition(normalizedProfileId)
        viewModelScope.launch {
            val result = runCatching {
                val switched = connectionStore.switchToServerProfile(normalizedProfileId)
                repository.selectProfile(normalizedProfileId)
                bumpActiveProfileRequestVersion()
                _uiState.update { current ->
                    current.copy(
                        settings = switched,
                        activeServerProfileId = normalizedProfileId,
                        selectedDashboardProfileId = normalizedProfileId,
                        activeCapabilities = repository.capabilitiesFor(switched),
                        isConnecting = true,
                        pendingBackendRepair = null,
                    )
                }
                updateCachedProfileSettings(normalizedProfileId, switched)
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "切换服务器失败")
                }
            }
            if (result.isSuccess) {
                hydrateDashboardCacheForCurrentScope(force = true)
                synchronizeServerScheduler()
                nextServerRefreshAt[normalizedProfileId] = 0L
                refreshServerSnapshotNow(profileId = normalizedProfileId, showSelectedError = true)
            }
        }
    }

    fun selectDashboardProfile(profileId: String) {
        if (profileId.isBlank()) return
        switchServerProfile(profileId)
    }

    fun reorderServerProfiles(profileIds: List<String>) {
        val normalizedIds = profileIds.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                connectionStore.reorderServerProfiles(normalizedIds)
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(errorMessage = error.message ?: "调整服务器顺序失败")
                }
            }
        }
    }

    fun updateServerDashboardCardVisibility(
        profileId: String,
        card: DashboardChartCard,
        visible: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (profileId.isBlank()) return
        viewModelScope.launch {
            val fallbackSettings = resolveProfileSettings(profileId) ?: _uiState.value.settings
            runCatching {
                connectionStore.updateServerDashboardPreferences(profileId, fallbackSettings) { current ->
                    val visibleCards = current.visibleCards.toMutableList()
                    if (visible) {
                        if (!visibleCards.contains(card.storageKey)) visibleCards += card.storageKey
                    } else {
                        visibleCards.remove(card.storageKey)
                    }
                    current.copy(visibleCards = visibleCards)
                }
            }.onSuccess { preferences ->
                _uiState.update { current ->
                    current.copy(
                        serverDashboardPreferences = current.serverDashboardPreferences + (profileId to preferences),
                    )
                }
                onComplete(true)
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(errorMessage = error.message ?: "更新图表显示失败")
                }
                onComplete(false)
            }
        }
    }

    fun updateServerDashboardCardsVisibility(
        profileId: String,
        cards: List<DashboardChartCard>,
        visible: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (profileId.isBlank()) return
        val normalizedCards = cards.distinct()
        if (normalizedCards.isEmpty()) {
            onComplete(true)
            return
        }
        viewModelScope.launch {
            val fallbackSettings = resolveProfileSettings(profileId) ?: _uiState.value.settings
            runCatching {
                connectionStore.updateServerDashboardPreferences(profileId, fallbackSettings) { current ->
                    val visibleCards = current.visibleCards.toMutableList()
                    normalizedCards.forEach { card ->
                        if (visible) {
                            if (card.storageKey !in visibleCards) {
                                visibleCards += card.storageKey
                            }
                        } else {
                            visibleCards.remove(card.storageKey)
                        }
                    }
                    current.copy(
                        visibleCards = visibleCards.toSet().toList(),
                    )
                }
            }.onSuccess { updatedPreferences ->
                _uiState.update { current ->
                    current.copy(
                        serverDashboardPreferences = current.serverDashboardPreferences
                            .toMutableMap()
                            .apply { this[profileId] = updatedPreferences },
                    )
                }
                onComplete(true)
            }.onFailure {
                onComplete(false)
            }
        }
    }

    fun updateServerDashboardCardOrder(
        profileId: String,
        order: List<DashboardChartCard>,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (profileId.isBlank()) return
        viewModelScope.launch {
            val fallbackSettings = resolveProfileSettings(profileId) ?: _uiState.value.settings
            runCatching {
                connectionStore.updateServerDashboardPreferences(profileId, fallbackSettings) { current ->
                    current.copy(cardOrder = order.joinToString(",") { it.storageKey })
                }
            }.onSuccess { preferences ->
                _uiState.update { current ->
                    current.copy(
                        serverDashboardPreferences = current.serverDashboardPreferences + (profileId to preferences),
                    )
                }
                onComplete(true)
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(errorMessage = error.message ?: "更新图表排序失败")
                }
                onComplete(false)
            }
        }
    }

    fun resetServerDashboardPreferences(
        profileId: String,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (profileId.isBlank()) return
        viewModelScope.launch {
            val fallbackSettings = resolveProfileSettings(profileId) ?: _uiState.value.settings
            val defaults = defaultServerDashboardPreferences(fallbackSettings)
            runCatching {
                connectionStore.saveServerDashboardPreferences(profileId, defaults)
                defaults
            }.onSuccess { preferences ->
                _uiState.update { current ->
                    current.copy(
                        serverDashboardPreferences = current.serverDashboardPreferences + (profileId to preferences),
                    )
                }
                onComplete(true)
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(errorMessage = error.message ?: "恢复图表设置失败")
                }
                onComplete(false)
            }
        }
    }

    fun markServerStackReorderHintSeen() = updateAndPersistSettings { current ->
        current.copy(hasSeenServerStackReorderHint = true)
    }

    fun markServerDashboardSwipeHintSeen() = updateAndPersistSettings { current ->
        current.copy(hasSeenServerDashboardSwipeHint = true)
    }

    fun markServerDashboardCardHintSeen() = updateAndPersistSettings { current ->
        current.copy(hasSeenServerDashboardCardHint = true)
    }

    fun exportTorrentFile(
        hash: String,
        onSuccess: (ByteArray) -> Unit,
    ) {
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        val normalizedHash = hash.trim()
        if (profileId.isBlank() || normalizedHash.isBlank()) return
        val requestVersion = currentActiveProfileRequestVersion()
        viewModelScope.launch {
            repository.exportTorrentFile(profileId, normalizedHash)
                .onSuccess { bytes -> onSuccess(bytes) }
                .onFailure { error ->
                    if (isActiveProfileRequestValid(profileId, requestVersion)) {
                        _uiState.update {
                            it.copy(errorMessage = error.message ?: "导出种子失败")
                        }
                    }
                }
        }
    }

    private fun autoConnectIfNeeded(settings: ConnectionSettings) {
        if (autoConnectAttempted) return
        if (settings.host.isBlank() || settings.username.isBlank()) return
        val state = _uiState.value
        if (state.serverProfiles.isNotEmpty() && state.activeServerProfileId.isNullOrBlank()) return
        autoConnectAttempted = true
        connectInternal(persistSettings = false, showErrorOnFailure = false)
    }

    private fun defaultServerDashboardPreferences(settings: ConnectionSettings): ServerDashboardPreferences {
        val isTransmission = settings.serverBackendType == ServerBackendType.TRANSMISSION
        val defaultKeys = if (isTransmission) {
            listOf(
                DashboardChartCard.CATEGORY_SHARE.storageKey,
                DashboardChartCard.TAG_UPLOAD.storageKey,
                DashboardChartCard.TORRENT_STATE.storageKey,
                DashboardChartCard.TRACKER_SITE.storageKey,
            )
        } else {
            listOf(
                DashboardChartCard.COUNTRY_FLOW.storageKey,
                DashboardChartCard.CATEGORY_SHARE.storageKey,
                DashboardChartCard.DAILY_UPLOAD.storageKey,
            )
        }
        return ServerDashboardPreferences(
            visibleCards = defaultKeys,
            cardOrder = defaultKeys.joinToString(","),
        )
    }

    private fun bumpActiveProfileRequestVersion() {
        activeProfileRequestVersion += 1
    }

    private fun currentActiveProfileRequestVersion(): Long = activeProfileRequestVersion

    private fun isActiveProfileRequestValid(
        profileId: String,
        requestVersion: Long,
    ): Boolean {
        return shouldApplyActiveProfileAsyncResult(
            requestedProfileId = profileId,
            requestVersion = requestVersion,
            activeProfileId = _uiState.value.activeServerProfileId,
            activeRequestVersion = activeProfileRequestVersion,
        )
    }

    private fun isDetailRequestValid(
        profileId: String,
        hash: String,
        requestVersion: Long,
    ): Boolean {
        val normalizedHash = hash.trim()
        return normalizedHash.isNotBlank() &&
            isActiveProfileRequestValid(profileId, requestVersion) &&
            _uiState.value.detailHash == normalizedHash
    }

    private fun connectInternal(
        persistSettings: Boolean,
        showErrorOnFailure: Boolean,
    ) {
        if (_uiState.value.isConnecting) return
        viewModelScope.launch {
            resetDailyCountryUploadTrackingState()
            _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
            val settings = _uiState.value.settings
            if (persistSettings) {
                connectionStore.save(settings)
            }
            _uiState.value.activeServerProfileId?.let { activeProfileId ->
                updateCachedProfileSettings(activeProfileId, settings)
            }
            hydrateDashboardCacheForCurrentScope()

            repository.connect(settings)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connected = true,
                            activeCapabilities = repository.activeCapabilities(),
                        )
                    }
                    refreshServerVersion()
                    refresh()
                    loadGlobalSelectionOptions()
                    startAutoRefresh()
                    startHomeChartRefresh()
                    startHourlyBoundaryRefresh()
                    if (repository.activeCapabilities().supportsCountryDistribution) {
                        startCountryPeerTracker()
                    } else {
                        countryPeerTrackerJob?.cancel()
                        _uiState.update {
                            if (it.dailyCountryUploadStats.isEmpty()) it
                            else it.copy(
                                dailyCountryUploadDate = "",
                                dailyCountryUploadStats = emptyList(),
                            )
                        }
                    }
                    refreshDashboardServerSnapshotsAsync()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connected = false,
                            errorMessage = if (showErrorOnFailure) {
                                error.message ?: "Connection failed."
                            } else {
                                null
                            }
                        )
                    }
                    backgroundJobManager.stopAll()
                    countryPeerTrackerJob?.cancel()
                }
        }
    }

    private fun stopBackgroundJobs() {
        backgroundJobManager.stopAll()
        countryPeerTrackerJob?.cancel()
        dashboardAggregationJob?.cancel()
        serverSchedulerJob?.cancel()
        countryPeerTrackerJob = null
        dashboardAggregationJob = null
        serverSchedulerJob = null
    }

    private fun buildProfileSettingsDraft(
        baseSettings: ConnectionSettings = _uiState.value.settings,
        backendType: ServerBackendType,
        host: String,
        port: String,
        useHttps: Boolean,
        username: String,
        password: String,
        refreshSeconds: String,
    ): ConnectionSettings {
        val normalizedHost = host.trim()
        val parsed = parseHostInputHints(normalizedHost)
        val defaultPort = when (backendType) {
            ServerBackendType.QBITTORRENT -> 8080
            ServerBackendType.TRANSMISSION -> 9091
        }
        val resolvedPort = parsed?.port ?: (port.toIntOrNull() ?: defaultPort)
        val resolvedUseHttps = parsed?.useHttps ?: useHttps
        val nextSettings = baseSettings.copy(
            host = normalizedHost,
            port = resolvedPort.coerceIn(1, 65535),
            useHttps = resolvedUseHttps,
            username = username.trim(),
            password = password,
            serverBackendType = backendType,
            refreshSeconds = (refreshSeconds.toIntOrNull() ?: 5).coerceIn(5, 120),
        )
        require(nextSettings.host.isNotBlank()) { "主机不能为空" }
        require(nextSettings.username.isNotBlank()) { "用户名不能为空" }
        return nextSettings
    }

    private fun resetUiForServerSwitch(
        settings: ConnectionSettings,
        activeProfileId: String?,
    ) {
        _uiState.update {
            it.copy(
                settings = settings,
                activeServerProfileId = activeProfileId,
                activeCapabilities = repository.capabilitiesFor(settings),
                connected = false,
                serverVersion = "-",
                transferInfo = TransferInfo(),
                torrents = emptyList(),
                dailyTagUploadDate = "",
                dailyTagUploadStats = emptyList(),
                dailyCountryUploadDate = "",
                dailyCountryUploadStats = emptyList(),
                selectedDashboardProfileId = activeProfileId ?: it.selectedDashboardProfileId,
                dashboardCacheHydrated = false,
                hasDashboardSnapshot = false,
                detailHash = "",
                detailLoading = false,
                detailProperties = null,
                detailFiles = emptyList(),
                detailTrackers = emptyList(),
                pendingActionKeys = emptySet(),
            )
        }
    }

    fun refresh(manual: Boolean = false) {
        if (isRefreshInProgress) return
        isRefreshInProgress = true
        viewModelScope.launch {
            try {
                if (manual) {
                    _uiState.update {
                        it.copy(
                            isManualRefreshing = true,
                            errorMessage = null,
                        )
                    }
                }

                val state = _uiState.value
                val refreshAllServers = state.refreshScene == RefreshScene.DASHBOARD &&
                    state.serverProfiles.size > 1

                if (refreshAllServers) {
                    state.serverProfiles.forEach { profile ->
                        if (
                            shouldSkipRefreshForDashboardReorderHold(
                                heldProfileId = state.dashboardRefreshHoldProfileId,
                                profileId = profile.id,
                            )
                        ) {
                            return@forEach
                        }
                        refreshServerSnapshotNow(
                            profileId = profile.id,
                            showSelectedError = manual && profile.id == state.activeServerProfileId,
                        )
                    }
                } else {
                    val activeProfileId = state.activeServerProfileId
                    if (!activeProfileId.isNullOrBlank()) {
                        if (
                            shouldSkipRefreshForDashboardReorderHold(
                                heldProfileId = state.dashboardRefreshHoldProfileId,
                                profileId = activeProfileId,
                            )
                        ) {
                            return@launch
                        }
                        refreshServerSnapshotNow(
                            profileId = activeProfileId,
                            showSelectedError = manual,
                        )
                    }
                }
            } finally {
                isRefreshInProgress = false
                if (manual) {
                    _uiState.update {
                        if (it.isManualRefreshing) {
                            it.copy(isManualRefreshing = false)
                        } else {
                            it
                        }
                    }
                }
                detectCompletedTorrents()
                updateWidgetData()
            }
        }
    }

    private fun detectCompletedTorrents() {
        val state = _uiState.value
        val torrents = state.torrents
        torrents.forEach { torrent ->
            val hash = torrent.hash.ifBlank { return@forEach }
            val prevState = previousTorrentStates[hash]
            val currentState = torrent.state.trim().lowercase()
            previousTorrentStates[hash] = currentState
            if (prevState == null) return@forEach
            val wasDownloading = prevState in setOf("downloading", "forceddl", "stalldl", "queueddl")
            val isNowCompleted = currentState in setOf("uploading", "forcedup", "stalledup", "queuedup", "pausedup")
                    || (torrent.progress >= 1f && currentState !in setOf("checking", "checkingup", "checkingdl", "moving", "error", "missingfiles"))
            if (wasDownloading && isNowCompleted) {
                val context = connectionStore.context.applicationContext
                MainActivity.notifyTorrentCompleted(
                    context, torrent.name.ifBlank { hash.take(12) }, vibrate = true
                )
            }
        }
    }

    private fun updateWidgetData() {
        val state = _uiState.value
        val chartInfo = state.dashboardAggregate.chartTransferInfo
        val allSnapshots = state.dashboardServerSnapshots
        val totalTorrents = if (allSnapshots.isNotEmpty()) {
            allSnapshots.sumOf { it.torrents.size }
        } else {
            state.torrents.size
        }
        TorrentWidgetProvider.updateData(
            downloadSpeed = chartInfo?.downloadSpeed ?: state.transferInfo.downloadSpeed,
            uploadSpeed = chartInfo?.uploadSpeed ?: state.transferInfo.uploadSpeed,
            torrentCount = totalTorrents,
        )
        TorrentWidgetProvider.refreshWidgets(connectionStore.context.applicationContext)
    }

    fun pauseTorrent(hash: String) = runTorrentAction(hash) { profileId ->
        repository.pauseTorrent(profileId, hash).getOrThrow()
    }

    fun resumeTorrent(hash: String) = runTorrentAction(hash) { profileId ->
        repository.resumeTorrent(profileId, hash).getOrThrow()
    }

    fun deleteTorrent(hash: String, deleteFiles: Boolean) = runTorrentAction(hash) { profileId ->
        repository.deleteTorrent(profileId, hash, deleteFiles).getOrThrow()
    }

    fun reannounceTorrent(hash: String) = runDetailAction(hash) { profileId ->
        repository.reannounceTorrent(profileId, hash).getOrThrow()
    }

    fun recheckTorrent(hash: String) = runDetailAction(hash) { profileId ->
        repository.recheckTorrent(profileId, hash).getOrThrow()
    }

    fun loadTorrentDetail(hash: String) {
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        val normalizedHash = hash.trim()
        if (profileId.isBlank() || normalizedHash.isBlank()) return
        val requestVersion = currentActiveProfileRequestVersion()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    detailHash = normalizedHash,
                    detailLoading = true,
                    errorMessage = null,
                )
            }
            repository.fetchTorrentDetail(profileId, normalizedHash)
                .onSuccess { detail ->
                    val trackers = repository.fetchTorrentTrackers(profileId, normalizedHash)
                        .getOrElse { emptyList() }
                    val categoryOptions = repository.fetchCategoryOptions(profileId)
                        .getOrElse { emptyList() }
                    val tagOptions = repository.fetchTagOptions(profileId)
                        .getOrElse { emptyList() }
                    _uiState.update { current ->
                        if (!isDetailRequestValid(profileId, normalizedHash, requestVersion)) {
                            current
                        } else {
                            current.copy(
                                detailLoading = false,
                                detailProperties = detail.properties,
                                detailFiles = detail.files,
                                detailTrackers = trackers,
                                categoryOptions = categoryOptions,
                                tagOptions = tagOptions,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { current ->
                        if (!isDetailRequestValid(profileId, normalizedHash, requestVersion)) {
                            current
                        } else {
                            current.copy(
                                detailLoading = false,
                                detailProperties = null,
                                detailFiles = emptyList(),
                                detailTrackers = emptyList(),
                                errorMessage = error.message ?: "加载种子详情失败",
                            )
                        }
                    }
                }
        }
    }

    fun renameTorrent(hash: String, newName: String) = runDetailAction(hash) { profileId ->
        repository.renameTorrent(profileId, hash, newName).getOrThrow()
    }

    fun setTorrentLocation(hash: String, location: String) = runDetailAction(hash) { profileId ->
        repository.setTorrentLocation(profileId, hash, location).getOrThrow()
    }

    fun setTorrentCategory(hash: String, category: String) = runDetailAction(hash) { profileId ->
        repository.setTorrentCategory(profileId, hash, category).getOrThrow()
    }

    fun setTorrentTags(hash: String, oldTags: String, newTags: String) = runDetailAction(hash) { profileId ->
        repository.setTorrentTags(profileId, hash, oldTags, newTags).getOrThrow()
    }

    fun setTorrentSpeedLimit(hash: String, downloadLimitKb: String, uploadLimitKb: String) = runDetailAction(hash) { profileId ->
        val dl = parseLimitKbToBytes(downloadLimitKb)
        val up = parseLimitKbToBytes(uploadLimitKb)
        repository.setTorrentSpeedLimit(profileId, hash, dl, up).getOrThrow()
    }

    fun setTorrentShareRatio(hash: String, ratio: String) = runDetailAction(hash) { profileId ->
        val value = ratio.trim().toDoubleOrNull() ?: throw IllegalArgumentException("分享比率格式无效")
        repository.setTorrentShareRatio(profileId, hash, value).getOrThrow()
    }

    fun addTracker(hash: String, trackerUrl: String) = runDetailAction(hash) { profileId ->
        repository.addTracker(profileId, hash, trackerUrl.trim()).getOrThrow()
    }

    fun editTracker(
        hash: String,
        tracker: TorrentTracker,
        newUrl: String,
    ) = runDetailAction(hash) { profileId ->
        repository.editTracker(
            profileId = profileId,
            hash = hash,
            tracker = tracker,
            newUrl = newUrl.trim(),
        ).getOrThrow()
    }

    fun removeTracker(
        hash: String,
        tracker: TorrentTracker,
    ) = runDetailAction(hash) { profileId ->
        repository.removeTracker(
            profileId = profileId,
            hash = hash,
            tracker = tracker,
        ).getOrThrow()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissPendingBackendRepair() {
        _uiState.update { current ->
            current.copy(pendingBackendRepair = null)
        }
    }

    fun confirmPendingBackendRepair() {
        val pending = _uiState.value.pendingBackendRepair ?: return
        viewModelScope.launch {
            runCatching {
                val profile = _uiState.value.serverProfiles.firstOrNull { it.id == pending.profileId }
                    ?: error("服务器配置不存在")
                val existingSettings = connectionStore.loadSettingsForProfile(pending.profileId)
                    ?: error("服务器配置不存在")
                val updatedSettings = existingSettings.copy(serverBackendType = pending.detectedBackend)
                connectionStore.updateServerProfile(
                    profileId = pending.profileId,
                    name = profile.name,
                    settings = updatedSettings,
                    passwordOverride = null,
                )
                repository.removeProfile(pending.profileId)
                nextServerRefreshAt[pending.profileId] = 0L
                val isActive = _uiState.value.activeServerProfileId == pending.profileId
                if (isActive) {
                    val switched = connectionStore.switchToServerProfile(pending.profileId)
                    repository.selectProfile(pending.profileId)
                    bumpActiveProfileRequestVersion()
                    val capabilities = repository.capabilitiesFor(switched)
                    _uiState.update { current ->
                        prepareConnectingProfileState(
                            current = current,
                            settings = switched,
                            profileId = pending.profileId,
                            capabilities = capabilities,
                        )
                    }
                    hydrateDashboardCacheForCurrentScope(force = true)
                } else {
                    _uiState.update { current ->
                        current.copy(
                            pendingBackendRepair = null,
                            errorMessage = null,
                        )
                    }
                }
                hydrateDashboardServerSnapshots()
                synchronizeServerScheduler()
                refreshServerSnapshotNow(
                    profileId = pending.profileId,
                    showSelectedError = true,
                )
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(
                        pendingBackendRepair = null,
                        errorMessage = userFacingConnectionMessage(error),
                    )
                }
            }
        }
    }

    fun loadGlobalSelectionOptions() {
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        if (!_uiState.value.connected || profileId.isBlank()) return
        val requestVersion = currentActiveProfileRequestVersion()
        viewModelScope.launch {
            val categoryOptions = repository.fetchCategoryOptions(profileId).getOrElse { emptyList() }
            val tagOptions = repository.fetchTagOptions(profileId).getOrElse { emptyList() }
            _uiState.update { current ->
                if (!isActiveProfileRequestValid(profileId, requestVersion)) {
                    current
                } else {
                    current.copy(
                        categoryOptions = categoryOptions,
                        tagOptions = tagOptions,
                    )
                }
            }
        }
    }

    fun handleSharedMagnet(url: String) {
        _uiState.update { it.copy(sharedMagnetUrl = url.trim()) }
    }

    fun clearSharedMagnetUrl() {
        _uiState.update { it.copy(sharedMagnetUrl = "") }
    }

    fun addTorrent(
        urls: String,
        files: List<AddTorrentFile>,
        autoTmm: Boolean,
        category: String,
        tags: String,
        savePath: String,
        paused: Boolean,
        skipChecking: Boolean,
        sequentialDownload: Boolean,
        firstLastPiecePrio: Boolean,
        uploadLimitKb: String,
        downloadLimitKb: String,
    ) {
        if (!_uiState.value.connected) {
            _uiState.update { it.copy(errorMessage = "请先连接服务器。") }
            return
        }
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        if (profileId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请先选择服务器。") }
            return
        }
        val requestVersion = currentActiveProfileRequestVersion()
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            runCatching {
                val request = AddTorrentRequest(
                    urls = urls.trim(),
                    files = files,
                    autoTmm = autoTmm,
                    category = category.trim(),
                    tags = tags.trim(),
                    savePath = savePath.trim(),
                    paused = paused,
                    skipChecking = skipChecking,
                    sequentialDownload = sequentialDownload,
                    firstLastPiecePrio = firstLastPiecePrio,
                    uploadLimitBytes = parseLimitKbToBytes(uploadLimitKb),
                    downloadLimitBytes = parseLimitKbToBytes(downloadLimitKb),
                )
                repository.addTorrent(profileId, request).getOrThrow()
            }.onSuccess {
                if (isActiveProfileRequestValid(profileId, requestVersion)) {
                    loadGlobalSelectionOptions()
                    refresh()
                } else {
                    nextServerRefreshAt[profileId] = 0L
                }
            }.onFailure { error ->
                if (isActiveProfileRequestValid(profileId, requestVersion)) {
                    _uiState.update { it.copy(errorMessage = error.message ?: "添加种子失败。") }
                }
            }
        }
    }

    private fun runTorrentAction(
        hash: String,
        action: suspend (String) -> Unit,
    ) {
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        val normalizedHash = hash.trim()
        if (profileId.isBlank() || normalizedHash.isBlank()) return
        val pendingActionKey = buildPendingActionKey(profileId, normalizedHash)
        if (_uiState.value.pendingActionKeys.contains(pendingActionKey)) return
        val requestVersion = currentActiveProfileRequestVersion()

        viewModelScope.launch {
            _uiState.update {
                it.copy(pendingActionKeys = it.pendingActionKeys + pendingActionKey, errorMessage = null)
            }
            runCatching { action(profileId) }
                .onSuccess {
                    if (isActiveProfileRequestValid(profileId, requestVersion)) {
                        refresh()
                    } else {
                        nextServerRefreshAt[profileId] = 0L
                    }
                }
                .onFailure { error ->
                    if (isActiveProfileRequestValid(profileId, requestVersion)) {
                        _uiState.update {
                            it.copy(errorMessage = error.message ?: "Action failed.")
                        }
                    }
                }
            _uiState.update {
                it.copy(pendingActionKeys = it.pendingActionKeys - pendingActionKey)
            }
        }
    }

    private fun runDetailAction(
        hash: String,
        action: suspend (String) -> Unit,
    ) {
        val normalizedHash = hash.trim()
        if (normalizedHash.isBlank()) return
        val requestVersion = currentActiveProfileRequestVersion()
        runTorrentAction(normalizedHash) { profileId ->
            action(profileId)
            val detail = repository.fetchTorrentDetail(profileId, normalizedHash).getOrThrow()
            val trackers = repository.fetchTorrentTrackers(profileId, normalizedHash).getOrElse { emptyList() }
            val categoryOptions = repository.fetchCategoryOptions(profileId).getOrElse { emptyList() }
            val tagOptions = repository.fetchTagOptions(profileId).getOrElse { emptyList() }
            _uiState.update { current ->
                if (!isDetailRequestValid(profileId, normalizedHash, requestVersion)) {
                    current
                } else {
                    current.copy(
                        detailHash = normalizedHash,
                        detailLoading = false,
                        detailProperties = detail.properties,
                        detailFiles = detail.files,
                        detailTrackers = trackers,
                        categoryOptions = categoryOptions,
                        tagOptions = tagOptions,
                    )
                }
            }
        }
    }

    private suspend fun refreshDetailSnapshot(
        profileId: String,
        hash: String,
        requestVersion: Long,
    ) {
        val detail = repository.fetchTorrentDetail(profileId, hash).getOrNull() ?: return
        val trackers = repository.fetchTorrentTrackers(profileId, hash).getOrElse { emptyList() }
        _uiState.update { current ->
            if (!isDetailRequestValid(profileId, hash, requestVersion)) {
                current
            } else {
                current.copy(
                    detailProperties = detail.properties,
                    detailFiles = detail.files,
                    detailTrackers = trackers,
                )
            }
        }
    }

    private fun refreshServerVersion() {
        val profileId = _uiState.value.activeServerProfileId?.trim().orEmpty()
        if (profileId.isBlank()) return
        val requestVersion = currentActiveProfileRequestVersion()
        viewModelScope.launch {
            repository.fetchServerVersion(profileId)
                .onSuccess { version ->
                    if (!isActiveProfileRequestValid(profileId, requestVersion)) return@onSuccess
                    var updatedState: MainUiState? = null
                    _uiState.update { current ->
                        if (!isActiveProfileRequestValid(profileId, requestVersion)) {
                            current
                        } else {
                            current.copy(serverVersion = version.ifBlank { "-" })
                                .also { updatedState = it }
                        }
                    }
                    updatedState?.let { stateSnapshot ->
                        saveDashboardServerSnapshotForProfile(
                            profileId = profileId,
                            stateSnapshot = stateSnapshot,
                        )
                    }
                }
        }
    }

    private fun saveDashboardCache(
        stateSnapshot: MainUiState = _uiState.value,
        scopeKey: String = buildDailyUploadTrackingScopeKey(
            activeProfileId = stateSnapshot.activeServerProfileId,
            settings = stateSnapshot.settings,
        ),
    ) {
        viewModelScope.launch {
            connectionStore.saveDashboardCacheSnapshot(
                scopeKey = scopeKey,
                snapshot = DashboardCacheSnapshot(
                    transferInfo = stateSnapshot.transferInfo,
                    torrents = stateSnapshot.torrents,
                    dailyTagUploadDate = stateSnapshot.dailyTagUploadDate,
                    dailyTagUploadStats = stateSnapshot.dailyTagUploadStats.map { stat ->
                        CachedDailyTagUploadStat(
                            tag = stat.tag,
                            uploadedBytes = stat.uploadedBytes,
                            torrentCount = stat.torrentCount,
                            isNoTag = stat.isNoTag,
                        )
                    },
                    dailyCountryUploadDate = stateSnapshot.dailyCountryUploadDate,
                    dailyCountryUploadStats = stateSnapshot.dailyCountryUploadStats,
                ),
            )
        }
    }

    private fun hydrateDashboardServerSnapshots() {
        dashboardAggregationJob?.cancel()
        dashboardAggregationJob = viewModelScope.launch {
            val ordered = orderedDashboardServerSnapshots(
                profiles = _uiState.value.serverProfiles,
                snapshotsById = connectionStore.loadDashboardServerSnapshots(),
            )
            val aggregate = buildDashboardAggregateWithHistory(
                snapshots = ordered,
                sampleFreshData = false,
            )
            _uiState.update { current ->
                applyDashboardSnapshotsToState(
                    current = current,
                    orderedSnapshots = ordered,
                    aggregate = aggregate,
                )
            }
            syncSelectedUiFromStoredSnapshot()
            markInitialDashboardSnapshotsHydrated()
        }
    }

    private fun synchronizeServerScheduler() {
        val profiles = _uiState.value.serverProfiles
        if (profiles.isEmpty()) {
            serverSchedulerJob?.cancel()
            serverSchedulerJob = null
            nextServerRefreshAt.clear()
            repository.clearAllSessions()
            return
        }

        val activeIds = profiles.map { it.id }.toSet()
        nextServerRefreshAt.keys.retainAll(activeIds)
        profiles.forEach { profile ->
            nextServerRefreshAt.putIfAbsent(profile.id, 0L)
        }
        repository.selectProfile(_uiState.value.activeServerProfileId)

        if (serverSchedulerJob?.isActive == true) return
        serverSchedulerJob = viewModelScope.launch {
            while (isActive) {
                val currentProfiles = _uiState.value.serverProfiles
                if (currentProfiles.isEmpty()) break
                val now = System.currentTimeMillis()
                currentProfiles.forEach { profile ->
                    val dueAt = nextServerRefreshAt[profile.id] ?: 0L
                    if (
                        shouldSkipRefreshForDashboardReorderHold(
                            heldProfileId = _uiState.value.dashboardRefreshHoldProfileId,
                            profileId = profile.id,
                        )
                    ) {
                        return@forEach
                    }
                    if (now >= dueAt) {
                        refreshServerSnapshotNow(
                            profileId = profile.id,
                            showSelectedError = false,
                        )
                    }
                }
                delay(1_000L)
            }
        }
    }

    private suspend fun refreshServerSnapshotNow(
        profileId: String,
        showSelectedError: Boolean,
        forceSettings: ConnectionSettings? = null,
    ) {
        if (profileId.isBlank()) return
        serverRefreshMutex.withLock {
            val state = _uiState.value
            val profile = state.serverProfiles.firstOrNull { it.id == profileId }
            val settings = forceSettings ?: resolveProfileSettings(profileId) ?: return
            val isSelectedProfile = state.activeServerProfileId == profileId
            val selectedRequestVersion = currentActiveProfileRequestVersion()
            updateCachedProfileSettings(profileId, settings)
            if (isSelectedProfile) {
                _uiState.update { current ->
                    current.copy(
                        isConnecting = true,
                        errorMessage = if (showSelectedError) null else current.errorMessage,
                    )
                }
            }

            val result = runCatching {
                repository.connect(profileId, settings).getOrThrow()
                val serverVersion = repository.fetchServerVersion(profileId).getOrElse { "-" }
                val dashboardData = repository.fetchDashboard(profileId).getOrThrow()
                val (tagDate, tagStats) = buildDashboardTagUploadStatsForScope(
                    scopeKey = "profile:$profileId",
                    torrents = dashboardData.torrents,
                )
                val countryStats = if (repository.capabilitiesFor(settings).supportsCountryDistribution) {
                    buildDashboardCountryUploadStatsForScope(
                        scopeKey = "profile:$profileId",
                        torrents = dashboardData.torrents,
                        fetchPeerSnapshots = { hashes ->
                            repository.fetchCountryPeerSnapshots(profileId, hashes)
                                .getOrElse { emptyList() }
                        },
                    )
                } else {
                    DailyCountryUploadStats(
                        dateLabel = tagDate,
                        countries = emptyList(),
                    )
                }
                CachedDashboardServerSnapshot(
                    profileId = profileId,
                    profileName = profile?.name ?: settings.host,
                    backendType = profile?.backendType ?: settings.serverBackendType,
                    host = profile?.host ?: settings.host,
                    port = profile?.port ?: settings.port,
                    useHttps = profile?.useHttps ?: settings.useHttps,
                    serverVersion = serverVersion.ifBlank { "-" },
                    transferInfo = dashboardData.transferInfo,
                    torrents = dashboardData.torrents,
                    dailyTagUploadDate = tagDate,
                    dailyTagUploadStats = tagStats.map { stat ->
                        CachedDailyTagUploadStat(
                            tag = stat.tag,
                            uploadedBytes = stat.uploadedBytes,
                            torrentCount = stat.torrentCount,
                            isNoTag = stat.isNoTag,
                        )
                    },
                    dailyCountryUploadDate = countryStats.dateLabel,
                    dailyCountryUploadStats = countryStats.countries,
                    lastUpdatedAt = System.currentTimeMillis(),
                    errorMessage = "",
                    isStale = false,
                )
            }

            result.onSuccess { snapshot ->
                persistDashboardSnapshot(snapshot)
                mergeDashboardSnapshot(snapshot, sampleFreshData = true)
                nextServerRefreshAt[profileId] = System.currentTimeMillis() + nextRefreshIntervalMs(settings)

                if (isSelectedProfile) {
                    repository.selectProfile(profileId)
                    if (isActiveProfileRequestValid(profileId, selectedRequestVersion)) {
                        syncSelectedUiFromSnapshot(
                            profileId = profileId,
                            settings = settings,
                            snapshot = snapshot,
                            connected = true,
                            selectedErrorMessage = null,
                            requestVersion = selectedRequestVersion,
                        )
                    }
                }
            }.onFailure { error ->
                Log.w("QBRemote", "refreshServerSnapshotNow failed for profile=$profileId", error)
                val summaryMessage = userFacingConnectionMessage(error)
                val currentSnapshot = _uiState.value.dashboardServerSnapshots
                    .firstOrNull { it.profileId == profileId }
                    ?: loadStoredDashboardSnapshot(profileId)
                val staleSnapshot = (currentSnapshot ?: CachedDashboardServerSnapshot(
                    profileId = profileId,
                    profileName = profile?.name ?: settings.host,
                    backendType = profile?.backendType ?: settings.serverBackendType,
                    host = profile?.host ?: settings.host,
                    port = profile?.port ?: settings.port,
                    useHttps = profile?.useHttps ?: settings.useHttps,
                )).copy(
                    profileName = profile?.name ?: currentSnapshot?.profileName ?: settings.host,
                    backendType = profile?.backendType ?: currentSnapshot?.backendType ?: settings.serverBackendType,
                    host = profile?.host ?: currentSnapshot?.host ?: settings.host,
                    port = profile?.port ?: currentSnapshot?.port ?: settings.port,
                    useHttps = profile?.useHttps ?: currentSnapshot?.useHttps ?: settings.useHttps,
                    errorMessage = summaryMessage,
                    isStale = true,
                )
                persistDashboardSnapshot(staleSnapshot)
                mergeDashboardSnapshot(staleSnapshot, sampleFreshData = false)
                nextServerRefreshAt[profileId] = System.currentTimeMillis() + nextRefreshIntervalMs(settings)

                if (isSelectedProfile && error is BackendConnectionError.WrongBackend) {
                    maybeQueueBackendRepair(
                        profileId = profileId,
                        profileName = profile?.name ?: staleSnapshot.profileName,
                        error = error,
                    )
                }

                if (isSelectedProfile) {
                    repository.selectProfile(profileId)
                    if (isActiveProfileRequestValid(profileId, selectedRequestVersion)) {
                        syncSelectedUiFromSnapshot(
                            profileId = profileId,
                            settings = settings,
                            snapshot = staleSnapshot,
                            connected = false,
                            selectedErrorMessage = if (error is BackendConnectionError.WrongBackend) {
                                null
                            } else if (showSelectedError && !shouldSuppressRefreshError(summaryMessage)) {
                                summaryMessage
                            } else {
                                null
                            },
                            requestVersion = selectedRequestVersion,
                        )
                    }
                }
            }
        }
    }

    private suspend fun syncSelectedUiFromStoredSnapshot() {
        val profileId = _uiState.value.activeServerProfileId ?: return
        val settings = resolveProfileSettings(profileId) ?: return
        val snapshot = _uiState.value.dashboardServerSnapshots.firstOrNull { it.profileId == profileId }
            ?: loadStoredDashboardSnapshot(profileId)
        repository.selectProfile(profileId)
        val requestVersion = currentActiveProfileRequestVersion()
        syncSelectedUiFromSnapshot(
            profileId = profileId,
            settings = settings,
            snapshot = snapshot,
            connected = repository.isConnected(profileId) && snapshot?.isStale == false,
            selectedErrorMessage = null,
            requestVersion = requestVersion,
        )
    }

    private suspend fun syncSelectedUiFromSnapshot(
        profileId: String,
        settings: ConnectionSettings,
        snapshot: CachedDashboardServerSnapshot?,
        connected: Boolean,
        selectedErrorMessage: String?,
        requestVersion: Long,
    ) {
        if (!isActiveProfileRequestValid(profileId, requestVersion)) return

        val categoryOptions = if (connected) {
            repository.fetchCategoryOptions(profileId).getOrElse { emptyList() }
        } else {
            emptyList()
        }
        val tagOptions = if (connected) {
            repository.fetchTagOptions(profileId).getOrElse { emptyList() }
        } else {
            emptyList()
        }

        _uiState.update { current ->
            if (!isActiveProfileRequestValid(profileId, requestVersion)) {
                current
            } else {
                current.copy(
                    settings = settings,
                    activeCapabilities = repository.capabilitiesFor(settings),
                    isConnecting = false,
                    connected = connected,
                    serverVersion = snapshot?.serverVersion?.ifBlank { "-" } ?: "-",
                    transferInfo = snapshot?.transferInfo ?: TransferInfo(),
                    torrents = snapshot?.torrents ?: emptyList(),
                    dailyTagUploadDate = snapshot?.dailyTagUploadDate.orEmpty(),
                    dailyTagUploadStats = snapshot?.dailyTagUploadStats?.map { stat ->
                        DailyTagUploadStat(
                            tag = stat.tag,
                            uploadedBytes = stat.uploadedBytes,
                            torrentCount = stat.torrentCount,
                            isNoTag = stat.isNoTag,
                        )
                    }.orEmpty(),
                    dailyCountryUploadDate = snapshot?.dailyCountryUploadDate.orEmpty(),
                    dailyCountryUploadStats = snapshot?.dailyCountryUploadStats.orEmpty(),
                    categoryOptions = categoryOptions,
                    tagOptions = tagOptions,
                    dashboardCacheHydrated = true,
                    hasDashboardSnapshot = snapshot != null,
                    pendingBackendRepair = current.pendingBackendRepair
                        ?.takeUnless { connected && it.profileId == profileId },
                    errorMessage = selectedErrorMessage,
                )
            }
        }

        if (snapshot != null && isActiveProfileRequestValid(profileId, requestVersion)) {
            saveDashboardCache()
        }

        val detailHash = _uiState.value.detailHash
        if (connected && _uiState.value.refreshScene == RefreshScene.TORRENT_DETAIL && detailHash.isNotBlank()) {
            refreshDetailSnapshot(profileId, detailHash, requestVersion)
        }
    }

    private suspend fun mergeDashboardSnapshot(
        snapshot: CachedDashboardServerSnapshot,
        sampleFreshData: Boolean,
    ) {
        val current = _uiState.value
        val snapshotsById = current.dashboardServerSnapshots
            .associateBy { it.profileId }
            .toMutableMap()
        snapshotsById[snapshot.profileId] = snapshot
        val ordered = orderedDashboardServerSnapshots(current.serverProfiles, snapshotsById)
        val aggregate = buildDashboardAggregateWithHistory(
            snapshots = ordered,
            sampleFreshData = sampleFreshData,
        )
        _uiState.update { latest ->
            applyDashboardSnapshotsToState(
                current = latest,
                orderedSnapshots = ordered,
                aggregate = aggregate,
            )
        }
    }

    private fun nextRefreshIntervalMs(settings: ConnectionSettings): Long {
        return settings.refreshSeconds.coerceIn(5, 120) * 1_000L
    }

    private fun refreshDashboardServerSnapshotsAsync(skipActive: Boolean = false) {
        dashboardAggregationJob?.cancel()
        dashboardAggregationJob = viewModelScope.launch {
            val profiles = _uiState.value.serverProfiles
            if (profiles.isEmpty()) {
                realtimeSpeedTracker.mutex.withLock {
                    resetHomeRealtimeSpeedSeriesStateLocked(clearPersisted = true)
                }
                _uiState.update { current ->
                    current.copy(
                        dashboardServerSnapshots = emptyList(),
                        selectedDashboardProfileId = null,
                        dashboardAggregate = DashboardAggregateState(),
                        aggregateOnlineServerCount = 0,
                    )
                }
                markInitialDashboardSnapshotsHydrated()
                return@launch
            }

            val snapshots = loadDashboardSnapshotsMap()
            val activeProfileId = _uiState.value.activeServerProfileId
            val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

            if (!skipActive && _uiState.value.connected && activeProfile != null) {
                val activeSnapshot = buildActiveDashboardServerSnapshot(activeProfile, _uiState.value)
                persistDashboardSnapshot(activeSnapshot, snapshots)
            }

            val refreshResults = supervisorScope {
                profiles.mapNotNull { profile ->
                    if (profile.id == activeProfileId && _uiState.value.connected) {
                        null
                    } else {
                        async {
                            val previousSnapshot = snapshots[profile.id]
                            val settings = resolveProfileSettings(profile.id)
                            if (settings == null) {
                                DashboardSnapshotRefreshResult.Failure(
                                    profile = profile,
                                    error = IllegalStateException("Missing saved settings."),
                                    previousSnapshot = previousSnapshot,
                                )
                            } else {
                                repository.fetchDashboardSnapshot(settings).fold(
                                    onSuccess = { fetched ->
                                        DashboardSnapshotRefreshResult.Fresh(
                                            profile = profile,
                                            settings = settings,
                                            fetched = fetched,
                                            previousSnapshot = previousSnapshot,
                                        )
                                    },
                                    onFailure = { error ->
                                        DashboardSnapshotRefreshResult.Failure(
                                            profile = profile,
                                            error = error,
                                            previousSnapshot = previousSnapshot,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            val pendingStatsRefreshes = mutableListOf<DashboardStatsRefreshInput>()
            refreshResults.forEach { result ->
                when (result) {
                    is DashboardSnapshotRefreshResult.Fresh -> {
                        val baseSnapshot = buildCachedDashboardSnapshotFromFetch(
                            profile = result.profile,
                            fetched = result.fetched,
                            previousSnapshot = result.previousSnapshot,
                        )
                        persistDashboardSnapshot(baseSnapshot, snapshots)
                        pendingStatsRefreshes += DashboardStatsRefreshInput(
                            profile = result.profile,
                            settings = result.settings,
                            torrents = result.fetched.dashboardData.torrents,
                            baseSnapshot = baseSnapshot,
                        )
                    }

                    is DashboardSnapshotRefreshResult.Failure -> {
                        val staleSnapshot = (result.previousSnapshot ?: CachedDashboardServerSnapshot(
                            profileId = result.profile.id,
                            profileName = result.profile.name,
                            backendType = result.profile.backendType,
                            host = result.profile.host,
                            port = result.profile.port,
                            useHttps = result.profile.useHttps,
                        )).copy(
                            profileName = result.profile.name,
                            backendType = result.profile.backendType,
                            host = result.profile.host,
                            port = result.profile.port,
                            useHttps = result.profile.useHttps,
                            errorMessage = result.error.message ?: "Refresh failed.",
                            isStale = true,
                        )
                        persistDashboardSnapshot(staleSnapshot, snapshots)
                    }
                }
            }

            val ordered = orderedDashboardServerSnapshots(profiles, snapshots)
            val aggregate = buildDashboardAggregateWithHistory(
                snapshots = ordered,
                sampleFreshData = true,
            )
            _uiState.update { current ->
                applyDashboardSnapshotsToState(
                    current = current,
                    orderedSnapshots = ordered,
                    aggregate = aggregate,
                )
            }
            markInitialDashboardSnapshotsHydrated()

            if (pendingStatsRefreshes.isEmpty()) return@launch

            val enrichedSnapshots = supervisorScope {
                pendingStatsRefreshes.map { input ->
                    async {
                        enrichDashboardSnapshotStats(input)
                    }
                }.awaitAll()
            }

            if (!isActive) return@launch

            enrichedSnapshots.forEach { snapshot ->
                persistDashboardSnapshot(snapshot, snapshots)
            }

            val orderedEnriched = orderedDashboardServerSnapshots(profiles, snapshots)
            val aggregateWithEnrichedStats = buildDashboardAggregateWithHistory(
                snapshots = orderedEnriched,
                sampleFreshData = false,
            )
            _uiState.update { current ->
                applyDashboardSnapshotsToState(
                    current = current,
                    orderedSnapshots = orderedEnriched,
                    aggregate = aggregateWithEnrichedStats,
                )
            }
            markInitialDashboardSnapshotsHydrated()
        }
    }

    private fun buildCachedDashboardSnapshotFromFetch(
        profile: ServerProfile,
        fetched: com.hjw.qbremote.data.DashboardSnapshotFetchResult,
        previousSnapshot: CachedDashboardServerSnapshot?,
    ): CachedDashboardServerSnapshot {
        val preservedCountryDate = if (defaultCapabilitiesFor(profile.backendType).supportsCountryDistribution) {
            previousSnapshot?.dailyCountryUploadDate.orEmpty()
        } else {
            ""
        }
        val preservedCountryStats = if (defaultCapabilitiesFor(profile.backendType).supportsCountryDistribution) {
            previousSnapshot?.dailyCountryUploadStats ?: emptyList()
        } else {
            emptyList()
        }
        return CachedDashboardServerSnapshot(
            profileId = profile.id,
            profileName = profile.name,
            backendType = profile.backendType,
            host = profile.host,
            port = profile.port,
            useHttps = profile.useHttps,
            serverVersion = fetched.serverVersion,
            transferInfo = fetched.dashboardData.transferInfo,
            torrents = fetched.dashboardData.torrents,
            dailyTagUploadDate = previousSnapshot?.dailyTagUploadDate.orEmpty(),
            dailyTagUploadStats = previousSnapshot?.dailyTagUploadStats ?: emptyList(),
            dailyCountryUploadDate = preservedCountryDate,
            dailyCountryUploadStats = preservedCountryStats,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = "",
            isStale = false,
        )
    }

    private suspend fun enrichDashboardSnapshotStats(
        input: DashboardStatsRefreshInput,
    ): CachedDashboardServerSnapshot {
        val tagStats = buildDashboardTagUploadStatsForScope(
            scopeKey = "profile:${input.profile.id}",
            torrents = input.torrents,
        )
        val countryStats = if (repository.capabilitiesFor(input.settings).supportsCountryDistribution) {
            buildDashboardCountryUploadStatsForScope(
                scopeKey = "profile:${input.profile.id}",
                torrents = input.torrents,
                fetchPeerSnapshots = { hashes ->
                    repository.fetchCountryPeerSnapshots(input.settings, hashes)
                        .getOrElse { emptyList() }
                },
            )
        } else {
            DailyCountryUploadStats(
                dateLabel = tagStats.first,
                countries = emptyList(),
            )
        }
        return input.baseSnapshot.copy(
            dailyTagUploadDate = tagStats.first,
            dailyTagUploadStats = tagStats.second.map { stat ->
                CachedDailyTagUploadStat(
                    tag = stat.tag,
                    uploadedBytes = stat.uploadedBytes,
                    torrentCount = stat.torrentCount,
                    isNoTag = stat.isNoTag,
                )
            },
            dailyCountryUploadDate = countryStats.dateLabel,
            dailyCountryUploadStats = countryStats.countries,
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun saveActiveDashboardServerSnapshot() {
        val state = _uiState.value
        val activeProfileId = state.activeServerProfileId ?: return
        saveDashboardServerSnapshotForProfile(
            profileId = activeProfileId,
            stateSnapshot = state,
        )
    }

    private suspend fun saveDashboardServerSnapshotForProfile(
        profileId: String,
        stateSnapshot: MainUiState,
    ) {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return
        val targetProfile = stateSnapshot.serverProfiles.firstOrNull { it.id == normalizedProfileId } ?: return
        val snapshot = buildActiveDashboardServerSnapshot(targetProfile, stateSnapshot)
        persistDashboardSnapshot(snapshot)
    }

    private suspend fun loadDashboardSnapshotsMap(): MutableMap<String, CachedDashboardServerSnapshot> {
        return connectionStore.loadDashboardServerSnapshots().toMutableMap()
    }

    private suspend fun loadStoredDashboardSnapshot(
        profileId: String,
    ): CachedDashboardServerSnapshot? {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return null
        return connectionStore.loadDashboardServerSnapshots()[normalizedProfileId]
    }

    private suspend fun persistDashboardSnapshot(
        snapshot: CachedDashboardServerSnapshot,
        snapshots: MutableMap<String, CachedDashboardServerSnapshot>? = null,
    ) {
        val normalizedProfileId = snapshot.profileId.trim()
        if (normalizedProfileId.isBlank()) return
        val normalizedSnapshot = if (normalizedProfileId == snapshot.profileId) {
            snapshot
        } else {
            snapshot.copy(profileId = normalizedProfileId)
        }
        snapshots?.set(normalizedSnapshot.profileId, normalizedSnapshot)
        connectionStore.saveDashboardServerSnapshot(normalizedSnapshot)
    }

    private fun buildActiveDashboardServerSnapshot(
        profile: ServerProfile,
        state: MainUiState,
    ): CachedDashboardServerSnapshot {
        return CachedDashboardServerSnapshot(
            profileId = profile.id,
            profileName = profile.name,
            backendType = profile.backendType,
            host = profile.host,
            port = profile.port,
            useHttps = profile.useHttps,
            serverVersion = state.serverVersion,
            transferInfo = state.transferInfo,
            torrents = state.torrents,
            dailyTagUploadDate = state.dailyTagUploadDate,
            dailyTagUploadStats = state.dailyTagUploadStats.map { stat ->
                CachedDailyTagUploadStat(
                    tag = stat.tag,
                    uploadedBytes = stat.uploadedBytes,
                    torrentCount = stat.torrentCount,
                    isNoTag = stat.isNoTag,
                )
            },
            dailyCountryUploadDate = state.dailyCountryUploadDate,
            dailyCountryUploadStats = state.dailyCountryUploadStats,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = "",
            isStale = false,
        )
    }

    private fun orderedDashboardServerSnapshots(
        profiles: List<ServerProfile>,
        snapshotsById: Map<String, CachedDashboardServerSnapshot>,
    ): List<CachedDashboardServerSnapshot> {
        return profiles.map { profile ->
            snapshotsById[profile.id]?.copy(
                profileName = profile.name,
                backendType = profile.backendType,
                host = profile.host,
                port = profile.port,
                useHttps = profile.useHttps,
            ) ?: CachedDashboardServerSnapshot(
                profileId = profile.id,
                profileName = profile.name,
                backendType = profile.backendType,
                host = profile.host,
                port = profile.port,
                useHttps = profile.useHttps,
                isStale = true,
            )
        }
    }

    private suspend fun buildDashboardAggregateWithHistory(
        snapshots: List<CachedDashboardServerSnapshot>,
        sampleFreshData: Boolean,
    ): DashboardAggregateState {
        if (snapshots.isEmpty()) {
            realtimeSpeedTracker.mutex.withLock {
                resetHomeRealtimeSpeedSeriesStateLocked(clearPersisted = true)
            }
            return DashboardAggregateState()
        }
        val scopeKey = resolveHomeRealtimeSpeedScopeKey(snapshots)
        val aggregate = buildDashboardAggregateFromSnapshots(snapshots)
        val liveServerCount = snapshots.count { !it.isStale }
        val realtimeSpeedSeries = realtimeSpeedTracker.mutex.withLock {
            ensureHomeRealtimeSpeedSeriesLoadedLocked(scopeKey)
            when {
                liveServerCount <= 0 -> {
                    clearHomeRealtimeSpeedSeriesLocked(scopeKey)
                    emptyList()
                }
                sampleFreshData -> sampleHomeRealtimeSpeedPointLocked(
                    transferInfo = aggregate.transferInfo,
                    onlineServerCount = liveServerCount,
                    scopeKey = scopeKey,
                )
                else -> realtimeSpeedTracker.series.toList()
            }
        }
        return aggregate.copy(
            chartTransferInfo = null,
            realtimeSpeedSeries = realtimeSpeedSeries,
        )
    }

    private suspend fun buildDashboardTagUploadStatsForScope(
        scopeKey: String,
        torrents: List<TorrentInfo>,
    ): Pair<String, List<DailyTagUploadStat>> {
        val today = LocalDate.now()
        val (updatedSnapshot, stats) = advanceDailyUploadTrackingSnapshot(
            previousSnapshot = connectionStore.loadDailyUploadTrackingSnapshot(scopeKey),
            today = today,
            torrents = torrents,
        )
        connectionStore.saveDailyUploadTrackingSnapshot(
            scopeKey = scopeKey,
            snapshot = updatedSnapshot,
        )
        return updatedSnapshot.date.ifBlank { today.toString() } to stats
    }

    private suspend fun buildDashboardCountryUploadStatsForScope(
        scopeKey: String,
        torrents: List<TorrentInfo>,
        fetchPeerSnapshots: suspend (List<String>) -> List<CountryPeerSnapshot>,
    ): com.hjw.qbremote.data.model.DailyCountryUploadStats {
        val snapshot = connectionStore.loadDailyCountryUploadTrackingSnapshot(scopeKey)
        val today = LocalDate.now()
        val totalsByCountry = snapshot?.totalsByCountry?.toMutableMap() ?: mutableMapOf()
        val peerSnapshots = snapshot?.peerSnapshots?.toMutableMap() ?: mutableMapOf()
        val lastSeenByTorrent = snapshot?.lastSeenByTorrent?.toMutableMap() ?: mutableMapOf()
        val snapshotDate = runCatching {
            snapshot?.date?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        }.getOrNull()

        if (snapshotDate != today) {
            totalsByCountry.clear()
            peerSnapshots.clear()
            lastSeenByTorrent.clear()
        }

        val activeKeys = torrents.map(::torrentTrackingKey).toSet()
        lastSeenByTorrent.keys.retainAll(activeKeys)

        val activeHashes = mutableListOf<String>()
        torrents.forEach { torrent ->
            val trackingKey = torrentTrackingKey(torrent)
            val hash = torrent.hash.trim()
            if (hash.isBlank()) return@forEach
            val currentUploaded = torrent.uploaded.coerceAtLeast(0L)
            val previousUploaded = lastSeenByTorrent[trackingKey]
            lastSeenByTorrent[trackingKey] = currentUploaded
            if (previousUploaded == null) {
                if (torrent.uploadSpeed > 0L) {
                    activeHashes += hash
                }
                return@forEach
            }
            if (currentUploaded > previousUploaded || torrent.uploadSpeed > 0L) {
                activeHashes += hash
            }
        }

        val samples = fetchPeerSnapshots(activeHashes.distinct())
        val currentPeerSnapshots = samples.associateBy { it.key }
        val fallbackNames = samples
            .groupBy { it.countryCode.trim().uppercase(Locale.US) }
            .mapValues { (_, entries) ->
                entries.firstNotNullOfOrNull { it.countryName.trim().takeIf(String::isNotBlank) }.orEmpty()
            }

        samples.forEach { entry ->
            val countryCode = entry.countryCode.trim().uppercase(Locale.US)
            if (countryCode.isBlank()) return@forEach
            val previous = peerSnapshots[entry.key]
            val previousUploaded = previous?.uploadedBytes?.coerceAtLeast(0L)
            val currentUploaded = entry.uploadedBytes.coerceAtLeast(0L)
            val delta = when {
                previousUploaded == null -> 0L
                currentUploaded < previousUploaded -> currentUploaded
                else -> currentUploaded - previousUploaded
            }
            if (delta <= 0L) return@forEach
            totalsByCountry[countryCode] = (totalsByCountry[countryCode] ?: 0L) + delta
        }

        peerSnapshots.keys.retainAll(currentPeerSnapshots.keys)
        peerSnapshots.putAll(currentPeerSnapshots)

        connectionStore.saveDailyCountryUploadTrackingSnapshot(
            scopeKey = scopeKey,
            snapshot = DailyCountryUploadTrackingSnapshot(
                date = today.toString(),
                totalsByCountry = totalsByCountry,
                peerSnapshots = peerSnapshots,
                lastSeenByTorrent = lastSeenByTorrent,
            ),
        )

        return com.hjw.qbremote.data.model.DailyCountryUploadStats(
            dateLabel = today.toString(),
            countries = totalsByCountry.entries
                .filter { it.value > 0L }
                .sortedByDescending { it.value }
                .map { (countryCode, uploadedBytes) ->
                    CountryUploadRecord(
                        countryCode = countryCode,
                        countryName = fallbackNames[countryCode].orEmpty(),
                        uploadedBytes = uploadedBytes,
                    )
                },
        )
    }

    private suspend fun sampleHomeRealtimeSpeedPointLocked(
        transferInfo: TransferInfo,
        onlineServerCount: Int,
        scopeKey: String,
    ): List<RealtimeSpeedPoint> {
        return realtimeSpeedTracker.sampleLocked(transferInfo, onlineServerCount, scopeKey)
    }

    private suspend fun clearHomeRealtimeSpeedSeriesLocked(scopeKey: String) {
        realtimeSpeedTracker.clearLocked(scopeKey)
    }

    private suspend fun resetHomeRealtimeSpeedSeriesStateLocked(clearPersisted: Boolean) {
        realtimeSpeedTracker.resetLocked(clearPersisted)
    }

    private suspend fun ensureHomeRealtimeSpeedSeriesLoadedLocked(scopeKey: String) {
        realtimeSpeedTracker.ensureLoadedLocked(scopeKey)
    }

    private fun resolveHomeRealtimeSpeedScopeKey(
        snapshots: List<CachedDashboardServerSnapshot>,
    ): String {
        return realtimeSpeedTracker.resolveScopeKey(snapshots, currentDailyUploadTrackingScopeKey())
    }


    private fun parseLimitKbToBytes(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return -1L
        val kb = trimmed.toLongOrNull() ?: throw IllegalArgumentException("限速值必须是数字")
        if (kb < 0L) return -1L
        return kb * 1024L
    }

    private fun shouldSuppressRefreshError(message: String?): Boolean {
        val normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized.contains("unable to resolve host") ||
            normalized.contains("no address associated with hostname")
    }

    private fun maybeQueueBackendRepair(
        profileId: String,
        profileName: String,
        error: BackendConnectionError.WrongBackend,
    ) {
        _uiState.update { current ->
            current.copy(
                pendingBackendRepair = PendingBackendRepair(
                    profileId = profileId,
                    profileName = profileName.ifBlank { profileId },
                    expectedBackend = error.expected,
                    detectedBackend = error.detected,
                    detail = error.detail,
                ),
            )
        }
    }

    private fun userFacingConnectionMessage(error: Throwable): String {
        return when (error) {
            is BackendConnectionError.WrongBackend -> {
                "服务器类型不匹配，目标看起来是 ${backendDisplayName(error.detected)}。"
            }

            is BackendConnectionError.RpcPathNotFound -> {
                if (error.failureSummary.isBlank()) {
                    "Transmission RPC 路径未找到。"
                } else {
                    "Transmission RPC 路径未找到。${error.failureSummary}"
                }
            }

            is BackendConnectionError.AuthFailed -> "${backendDisplayName(error.backendType)} 认证失败。"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "刷新失败"
        }
    }

    private fun backendDisplayName(type: ServerBackendType): String {
        return when (type) {
            ServerBackendType.QBITTORRENT -> "qBittorrent"
            ServerBackendType.TRANSMISSION -> "Transmission"
        }
    }

    private fun hydrateDashboardCacheForCurrentScope(force: Boolean = false) {
        val scopeKey = currentDailyUploadTrackingScopeKey()
        if (!force && scopeKey == hydratedDashboardScopeKey && _uiState.value.dashboardCacheHydrated) {
            return
        }

        hydratedDashboardScopeKey = scopeKey
        dashboardCacheHydrationJob?.cancel()
        _uiState.update { current ->
            current.copy(
                dashboardCacheHydrated = false,
            )
        }

        dashboardCacheHydrationJob = viewModelScope.launch {
            val cache = connectionStore.loadDashboardCacheSnapshot(scopeKey)
            if (hydratedDashboardScopeKey != scopeKey) return@launch

            _uiState.update { current ->
                if (hydratedDashboardScopeKey != scopeKey) {
                    current
                } else {
                    applyDashboardCacheHydration(
                        current = current,
                        cache = cache,
                    )
                }
            }
            markInitialDashboardCacheHydrated()
        }
    }

    private fun updateSettings(update: (ConnectionSettings) -> ConnectionSettings) {
        _uiState.update { current ->
            val nextSettings = update(current.settings)
            if (nextSettings == current.settings) {
                current
            } else {
                current.copy(settings = nextSettings)
            }
        }
    }

    private fun updateAndPersistSettings(update: (ConnectionSettings) -> ConnectionSettings) {
        var changed = false
        _uiState.update { current ->
            val nextSettings = update(current.settings)
            if (nextSettings == current.settings) {
                current
            } else {
                changed = true
                current.copy(settings = nextSettings)
            }
        }
        if (!changed) return
        val settingsToPersist = _uiState.value.settings
        viewModelScope.launch {
            connectionStore.save(settingsToPersist)
        }
    }

    private fun startAutoRefresh() = backgroundJobManager.startAutoRefresh()
    private fun startHomeChartRefresh() = backgroundJobManager.startHomeChartRefresh()
    private fun startHourlyBoundaryRefresh() = backgroundJobManager.startHourlyBoundaryRefresh()

    private suspend fun refreshHomeDashboardChartTransferInfo() {
        val state = _uiState.value
        if (state.refreshScene != RefreshScene.DASHBOARD) return
        val profiles = state.serverProfiles
        if (profiles.isEmpty()) return
        val requestedProfileIds = normalizeProfileIdsForRefresh(profiles)

        val activeProfileId = state.activeServerProfileId
        val transferInfoByProfileId = supervisorScope {
            profiles.map { profile ->
                async {
                    val settings = resolveProfileSettings(profile.id)
                        ?: return@async null
                    val result = if (
                        profile.id == activeProfileId &&
                        repository.isConnected(profile.id)
                    ) {
                        repository.fetchTransferInfo(profile.id)
                    } else {
                        repository.fetchTransferInfo(settings)
                    }
                    result.getOrNull()?.let { transferInfo ->
                        profile.id to transferInfo
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .toMap()
        }
        if (transferInfoByProfileId.isEmpty()) return
        if (state.dashboardServerSnapshots.isEmpty()) return

        val latestState = _uiState.value
        if (latestState.refreshScene != RefreshScene.DASHBOARD) return
        val latestProfileIds = normalizeProfileIdsForRefresh(latestState.serverProfiles)
        if (latestProfileIds != requestedProfileIds) return

        val chartTransferInfo = buildHomeChartTransferInfo(transferInfoByProfileId.values)
        val scopeKey = resolveHomeRealtimeSpeedScopeKey(latestState.dashboardServerSnapshots)
        val chartSeries = realtimeSpeedTracker.mutex.withLock {
            ensureHomeRealtimeSpeedSeriesLoadedLocked(scopeKey)
            sampleHomeRealtimeSpeedPointLocked(
                transferInfo = chartTransferInfo,
                onlineServerCount = transferInfoByProfileId.size.coerceAtLeast(1),
                scopeKey = scopeKey,
            )
        }

        val latestStateAfterSampling = _uiState.value
        if (latestStateAfterSampling.refreshScene != RefreshScene.DASHBOARD) return
        val latestProfileIdsAfterSampling = normalizeProfileIdsForRefresh(latestStateAfterSampling.serverProfiles)
        if (latestProfileIdsAfterSampling != requestedProfileIds) return

        _uiState.update { current ->
            current.copy(
                dashboardAggregate = applyHomeChartRefreshToAggregate(
                    aggregate = current.dashboardAggregate,
                    chartTransferInfo = chartTransferInfo,
                    chartSeries = chartSeries,
                ),
            )
        }
    }

    private fun startCountryPeerTracker() {
        countryPeerTrackerJob?.cancel()
        countryPeerTrackerJob = viewModelScope.launch {
            while (isActive) {
                delay(COUNTRY_TRACKER_SAMPLE_INTERVAL_MS)
                val state = _uiState.value
                if (!state.connected) continue
                if (!state.activeCapabilities.supportsCountryDistribution) continue
                val profileId = state.activeServerProfileId?.trim().orEmpty()
                if (profileId.isBlank()) continue
                val requestVersion = currentActiveProfileRequestVersion()
                val scopeKey = buildDailyUploadTrackingScopeKey(
                    activeProfileId = profileId,
                    settings = state.settings,
                )

                val countryStats = dailyCountryUploadTracker.mutex.withLock {
                    dailyCountryUploadTracker.sample(
                        profileId = profileId,
                        key = scopeKey,
                        torrents = state.torrents,
                    )
                }
                var updatedState: MainUiState? = null
                _uiState.update { current ->
                    if (!isActiveProfileRequestValid(profileId, requestVersion)) {
                        current
                    } else {
                        current.copy(
                            dailyCountryUploadDate = countryStats.dateLabel,
                            dailyCountryUploadStats = countryStats.countries,
                        ).also { next ->
                            updatedState = next
                        }
                    }
                }
                updatedState?.let { stateSnapshot ->
                    saveDashboardCache(
                        stateSnapshot = stateSnapshot,
                        scopeKey = scopeKey,
                    )
                    saveDashboardServerSnapshotForProfile(
                        profileId = profileId,
                        stateSnapshot = stateSnapshot,
                    )
                }
            }
        }
    }

    private fun resetDailyCountryUploadTrackingState() {
        dailyCountryUploadTracker.reset()
        _uiState.update {
            it.copy(
                dailyCountryUploadDate = "",
                dailyCountryUploadStats = emptyList(),
            )
        }
    }
    private fun pruneCachedProfileSettingsInMemory(profiles: List<ServerProfile>) {
        val pruned = pruneCachedProfileSettings(
            cache = cachedProfileSettings,
            profiles = profiles,
        )
        cachedProfileSettings.clear()
        cachedProfileSettings.putAll(pruned)
    }

    private fun updateCachedProfileSettings(
        profileId: String,
        settings: ConnectionSettings,
    ) {
        val updated = cacheProfileSettings(
            cache = cachedProfileSettings,
            profileId = profileId,
            settings = settings,
        )
        if (updated === cachedProfileSettings) return
        cachedProfileSettings.clear()
        cachedProfileSettings.putAll(updated)
    }

    private suspend fun resolveProfileSettings(profileId: String): ConnectionSettings? {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) return null
        val activeProfile = _uiState.value.serverProfiles.firstOrNull { it.id == normalizedProfileId }
        val currentState = _uiState.value
        resolveActiveOrCachedProfileSettings(
            profileId = normalizedProfileId,
            activeProfileId = currentState.activeServerProfileId,
            activeProfile = activeProfile,
            currentSettings = currentState.settings,
            cachedSettings = cachedProfileSettings[normalizedProfileId],
        )?.let { resolved ->
            updateCachedProfileSettings(normalizedProfileId, resolved)
            return resolved
        }

        val loaded = connectionStore.loadSettingsForProfile(normalizedProfileId) ?: return null
        updateCachedProfileSettings(normalizedProfileId, loaded)
        return loaded
    }

    private fun currentDailyUploadTrackingScopeKey(): String {
        val state = _uiState.value
        val preferredProfileId = state.selectedDashboardProfileId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: state.activeServerProfileId
        return buildDailyUploadTrackingScopeKey(
            activeProfileId = preferredProfileId,
            settings = state.settings,
        )
    }

    private fun torrentTrackingKey(torrent: TorrentInfo): String {
        return dailyCountryTorrentTrackingKey(torrent)
    }

    private suspend fun seedCachedSettingsForProfile(profileId: String?) {
        val normalizedProfileId = profileId?.trim().orEmpty()
        if (normalizedProfileId.isBlank()) return
        val activeProfile = _uiState.value.serverProfiles.firstOrNull { it.id == normalizedProfileId }
        val currentSettings = _uiState.value.settings
        val settings = if (activeProfile != null && settingsBelongToProfile(activeProfile, currentSettings)) {
            currentSettings
        } else {
            connectionStore.loadSettingsForProfile(normalizedProfileId)
        } ?: return
        updateCachedProfileSettings(normalizedProfileId, settings)
    }

    override fun onCleared() {
        backgroundJobManager.stopAll()
        countryPeerTrackerJob?.cancel()
        dashboardAggregationJob?.cancel()
        serverSchedulerJob?.cancel()
        repository.clearAllSessions()
        super.onCleared()
    }

    companion object {
        private const val COUNTRY_TRACKER_SAMPLE_INTERVAL_MS = 1_500L

        fun factory(
            connectionStore: ConnectionStore,
            repository: TorrentRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(connectionStore, repository) as T
            }
        }
    }
}




