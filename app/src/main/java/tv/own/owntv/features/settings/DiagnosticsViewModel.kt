package tv.own.owntv.features.settings

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.repository.SourceTester
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.detailLines
import tv.own.owntv.core.setup.headline
import tv.own.owntv.core.sports.SportsRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import java.text.DateFormat
import java.util.Date

/** OK / Warning / Problem — the same three-state read every check in [DiagnosticsReport] settles on. */
enum class DiagnosticStatus { OK, WARNING, PROBLEM }

/** Which link the device is on, from the OS; what a "no internet" verdict is explained by. */
enum class NetworkKind { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

/** Basic internet reachability: the OS link layer, then one small request over this app's own HTTP stack. */
data class ConnectionCheck(val status: DiagnosticStatus, val network: NetworkKind)

/** One playlist's reachability + account/subscription state, straight from [SourceTester], plus what it holds locally. */
data class SourceCheck(
    val sourceId: Long,
    val sourceName: String,
    val status: DiagnosticStatus,
    val result: SourceTestResult,
    val type: SourceType,
    /** The playlist's server, host name only — never the path, never the credentials. */
    val host: String?,
    val channels: Int,
    val movies: Int,
    val series: Int,
    /** The playlist's last catalog sync, or null when it has never finished one. */
    val lastSyncAt: Long?,
)

/** Whether this profile has a TV guide at all, and how stale it is. */
data class EpgCheck(
    val status: DiagnosticStatus,
    /** A playlist or standalone feed offers a guide at all (not necessarily synced yet). */
    val configured: Boolean,
    val hasData: Boolean,
    /** Most recent successful sync across every guide source, or null if unknown/never. */
    val freshestSyncAt: Long?,
    /** Programmes stored for this profile's guide sources. */
    val programmes: Int,
    /** Standalone feeds from Settings → EPG Sources: the ones "Re-sync TV guide" refreshes on their own. */
    val standaloneSources: Int,
)

/** Free space where downloads are written. */
data class StorageCheck(val status: DiagnosticStatus, val freeBytes: Long, val totalBytes: Long)

/** The Sports hub's board: paused, partly failing, or fine — read from what it already holds, nothing is fetched. */
data class SportsCheck(
    val status: DiagnosticStatus,
    val paused: Boolean,
    val failingFeeds: Int,
    val matches: Int,
    val rebuiltAt: Long?,
)

/** Which engine Movies & Series and Live TV start on. */
data class PlayerCheck(val vodEngine: EnginePreference, val liveEngine: EnginePreference)

/** This device and this build, the lines a support thread always asks for first. */
data class DeviceCheck(
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val appVersion: String,
    val appVersionCode: Int,
)

data class DiagnosticsReport(
    val connection: ConnectionCheck,
    val sources: List<SourceCheck>,
    val epg: EpgCheck,
    val storage: StorageCheck,
    val sports: SportsCheck,
    val player: PlayerCheck,
    val device: DeviceCheck,
    val finishedAt: Long,
)

sealed interface DiagnosticsUi {
    data object Idle : DiagnosticsUi
    data object Running : DiagnosticsUi
    data class Done(val report: DiagnosticsReport) : DiagnosticsUi
}

/**
 * Settings → Diagnostics, the "Diagnostic Center": the read-only checks (connection, each playlist,
 * TV guide, device storage, the Sports board, player engines, device and build), run on demand
 * rather than automatically — [SourceTester] briefly costs a live connection slot on an Xtream
 * account, exactly the reason "Test connection" on Add/Edit Source is also button-triggered rather
 * than silent — plus the handful of fixes a support thread always ends in: re-sync a playlist or the
 * guide, swap the Movies & Series engine, clear the image cache, and a report to copy or share.
 *
 * The fixes call exactly what the matching Settings rows call ([resyncPlaylist] is Manage Sources'
 * Re-sync, [resyncGuide] is EPG Sources' Re-sync, [switchVodEngine] is Video Player's engine choice),
 * so nothing here is a second way of doing anything — only a shorter way to the same one.
 */
class DiagnosticsViewModel(
    private val context: Context,
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val sourceTester: SourceTester,
    private val connectivity: ConnectivityObserver,
    private val httpClient: HttpClient,
    private val epgDao: EpgDao,
    private val epgSourceStore: EpgSourceStore,
    private val epgRepository: EpgRepository,
    private val downloadManager: DownloadManager,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val importFinalizer: ImportFinalizer,
    private val sportsRepository: SportsRepository,
) : ViewModel() {

    private val _diagnostics = MutableStateFlow<DiagnosticsUi>(DiagnosticsUi.Idle)
    val diagnostics: StateFlow<DiagnosticsUi> = _diagnostics.asStateFlow()

    /** The Movies & Series engine as set now — the row shows it before any check has run. */
    val vodEngine: StateFlow<EnginePreference?> =
        settings.vodEnginePreference.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The playlists the last report was built from, so a Re-sync row needs only the id. */
    private var lastSources: List<SourceEntity> = emptyList()

    fun runChecks() {
        if (_diagnostics.value is DiagnosticsUi.Running) return
        _diagnostics.value = DiagnosticsUi.Running
        viewModelScope.launch {
            _diagnostics.value = DiagnosticsUi.Done(buildReport())
        }
    }

    /** Manage Sources' Re-sync for this playlist (not a clean re-sync: nothing is pruned). */
    fun resyncPlaylist(sourceId: Long) {
        val source = lastSources.firstOrNull { it.id == sourceId } ?: return
        viewModelScope.launch {
            val counts = importFinalizer.contentCounts(source.id)
            catalogSyncScheduler.enqueueSync(
                source.id,
                reason = "manual_resync",
                contentTypes = SyncContentTypes.enabledOf(source),
                baseItemCount = counts.channels + counts.movies + counts.series,
            )
        }
    }

    /**
     * EPG Sources' Re-sync for every standalone guide feed. A playlist's own XMLTV guide syncs as part
     * of that playlist's catalog sync, so for it the Re-sync row of the playlist is the one to press.
     */
    fun resyncGuide() {
        viewModelScope.launch {
            epgSourceStore.getAll().forEach { source ->
                val base = epgDao.countForSources(listOf(source.id))
                epgSyncScheduler.enqueueSync(source.id, "manual_resync", base)
            }
        }
    }

    /**
     * Flips which engine Movies & Series start on: mpv-first becomes ExoPlayer-first and back. An
     * "only" choice becomes the other engine's "first" — the fix a stuck player needs is the other
     * engine, and the automatic fallback is kept rather than traded for another dead end.
     */
    fun switchVodEngine() {
        viewModelScope.launch {
            val current = settings.vodEnginePreference.first()
            settings.setVodEnginePreference(if (current.startsOnMpv) EnginePreference.EXO_FIRST else EnginePreference.MPV_FIRST)
        }
    }

    /** Drops every cached poster and backdrop (memory and disk); they load again as they are shown. */
    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        val loader = SingletonImageLoader.get(context)
        runCatching { loader.memoryCache?.clear() }
        runCatching { loader.diskCache?.clear() }
        Unit
    }

    /** The system's own page for this app: storage, permissions, force stop. */
    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun buildReport(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val profileId = settings.activeProfileId.first()
        val sources = if (profileId >= 0) sourceRepository.observeSources(profileId).first() else emptyList()
        lastSources = sources
        coroutineScope {
            val connectionAsync = async { checkConnection() }
            val sourcesAsync = async { checkSources(sources) }
            val epgAsync = async { checkEpg(sources) }
            val storageAsync = async { checkStorage() }
            val playerAsync = async { PlayerCheck(settings.vodEnginePreference.first(), settings.liveEnginePreference.first()) }
            DiagnosticsReport(
                connection = connectionAsync.await(),
                sources = sourcesAsync.await(),
                epg = epgAsync.await(),
                storage = storageAsync.await(),
                sports = checkSports(),
                player = playerAsync.await(),
                device = DeviceCheck(
                    model = deviceModel(),
                    androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    apiLevel = Build.VERSION.SDK_INT,
                    appVersion = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                ),
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Two layers on purpose: [ConnectivityObserver] answers for the OS link (and its own
     * NET_CAPABILITY_VALIDATED check already does a system-level probe), while the HTTP call answers
     * for THIS app's own stack — custom DNS (Settings → DNS) or a proxy can leave the OS "online"
     * while the app itself cannot actually fetch anything.
     */
    private suspend fun checkConnection(): ConnectionCheck {
        val network = networkKind()
        if (!connectivity.isOnlineNow()) return ConnectionCheck(DiagnosticStatus.PROBLEM, network)
        return try {
            httpClient.get(CONNECTIVITY_CHECK_URL, maxAttempts = 1) { input -> input.readBytes() }
            ConnectionCheck(DiagnosticStatus.OK, network)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            ConnectionCheck(DiagnosticStatus.PROBLEM, network)
        }
    }

    private fun networkKind(): NetworkKind {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.OTHER
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return NetworkKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            else -> NetworkKind.OTHER
        }
    }

    /** One [SourceTester] run per real playlist — a restored local backup has no host to test. */
    private suspend fun checkSources(sources: List<SourceEntity>): List<SourceCheck> =
        sources.filter { it.type != SourceType.LOCAL_BACKUP }.map { source ->
            val result = try {
                sourceTester.test(source)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                SourceTestResult.Unreachable(e.message)
            }
            val counts = importFinalizer.contentCounts(source.id)
            SourceCheck(
                sourceId = source.id,
                sourceName = source.name,
                status = result.diagnosticStatus(),
                result = result,
                type = source.type,
                host = runCatching { Uri.parse(source.url).host }.getOrNull()?.takeIf { it.isNotBlank() },
                channels = counts.channels,
                movies = counts.movies,
                series = counts.series,
                lastSyncAt = source.lastSyncAt,
            )
        }

    /**
     * Presence and freshness of guide data for this profile. [SourceEntity.lastSyncAt] is the whole
     * playlist's last catalog sync, not an EPG-only timestamp — but a playlist's own XMLTV feed syncs
     * as part of that same pass, so it is the closest thing to an EPG freshness stamp a playlist-fed
     * guide has. Standalone EPG sources (Settings → EPG Sources) carry their own, exact
     * [tv.own.owntv.core.epg.EpgSource.lastSyncAt].
     */
    private suspend fun checkEpg(sources: List<SourceEntity>): EpgCheck {
        val epgSources = epgSourceStore.getAll()
        val guideSources = sources.filter { epgRepository.hasGuide(it) }
        val storeIds = guideSources.map { it.id } + epgSources.map { it.id }
        if (storeIds.isEmpty()) {
            return EpgCheck(DiagnosticStatus.WARNING, configured = false, hasData = false, freshestSyncAt = null, programmes = 0, standaloneSources = 0)
        }
        val programmes = epgDao.countForSources(storeIds)
        if (programmes <= 0) {
            return EpgCheck(DiagnosticStatus.PROBLEM, configured = true, hasData = false, freshestSyncAt = null, programmes = 0, standaloneSources = epgSources.size)
        }
        val freshest = (guideSources.mapNotNull { it.lastSyncAt } + epgSources.mapNotNull { it.lastSyncAt }).maxOrNull()
        val age = freshest?.let { System.currentTimeMillis() - it }
        val status = when {
            age == null -> DiagnosticStatus.WARNING
            age <= EPG_FRESH_WINDOW_MS -> DiagnosticStatus.OK
            age <= EPG_STALE_WINDOW_MS -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.PROBLEM
        }
        return EpgCheck(status, configured = true, hasData = true, freshestSyncAt = freshest, programmes = programmes, standaloneSources = epgSources.size)
    }

    /** Free space where downloads are written — [DownloadManager] already resolves the configured
     *  folder (or its fallback) and measures it via `StatFs`. */
    private suspend fun checkStorage(): StorageCheck {
        val info = downloadManager.storageInfo()
        val status = when {
            info.freeBytes < STORAGE_PROBLEM_BYTES -> DiagnosticStatus.PROBLEM
            info.freeBytes < STORAGE_WARNING_BYTES -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.OK
        }
        return StorageCheck(status, info.freeBytes, info.totalBytes)
    }

    /** What the Sports board holds right now — a read, never a fetch, so this costs nothing. */
    private fun checkSports(): SportsCheck {
        val board = sportsRepository.board.value
        val status = when {
            board.paused -> DiagnosticStatus.WARNING
            board.failing.isNotEmpty() -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.OK
        }
        return SportsCheck(status, board.paused, board.failing.size, board.matches.size, board.rebuiltAt)
    }

    private fun SourceTestResult.diagnosticStatus(): DiagnosticStatus = when (this) {
        is SourceTestResult.Ok -> DiagnosticStatus.OK
        SourceTestResult.AuthFailed, is SourceTestResult.Expired, is SourceTestResult.Unreachable -> DiagnosticStatus.PROBLEM
    }

    companion object {
        /**
         * Google's `generate_204` endpoint: the same lightweight connectivity-check target Android's
         * own NetworkMonitor (and Chromium) probe for captive portals — a 204 with no body, served
         * from Google's global edge over HTTPS. Reused here rather than inventing a target of our own
         * because it is already everybody's definition of "is there real internet", it never serves a
         * redirect or a login page, and its stability is load-bearing for the rest of the Android
         * ecosystem, not just this app.
         */
        private const val CONNECTIVITY_CHECK_URL = "https://www.gstatic.com/generate_204"

        private const val EPG_FRESH_WINDOW_MS = 26L * 60 * 60 * 1000 // a bit over a day
        private const val EPG_STALE_WINDOW_MS = 72L * 60 * 60 * 1000 // three days

        private const val STORAGE_PROBLEM_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val STORAGE_WARNING_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    }
}

/**
 * The report as plain text, for Copy and Share: the same lines the screen shows, in the same words
 * ([Resources], not Compose, so a share can be built off-screen). Credentials never appear — a
 * playlist is named as the user named it, with its server's host name and nothing else of its URL.
 */
fun DiagnosticsReport.toText(res: Resources, appName: String): String {
    fun status(s: DiagnosticStatus) = res.getString(
        when (s) {
            DiagnosticStatus.OK -> R.string.diagnostics_status_ok
            DiagnosticStatus.WARNING -> R.string.diagnostics_status_warning
            DiagnosticStatus.PROBLEM -> R.string.diagnostics_status_problem
        },
    )
    fun engine(p: EnginePreference): String {
        val exo = res.getString(R.string.settings_player_exoplayer)
        val mpv = res.getString(R.string.settings_player_mpv)
        return when (p) {
            EnginePreference.EXO_FIRST -> res.getString(R.string.settings_engine_order, exo, mpv)
            EnginePreference.MPV_FIRST -> res.getString(R.string.settings_engine_order, mpv, exo)
            EnginePreference.EXO_ONLY -> res.getString(R.string.settings_engine_only, exo)
            EnginePreference.MPV_ONLY -> res.getString(R.string.settings_engine_only, mpv)
        }
    }
    fun ago(ms: Long) = DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    fun pair(label: String, value: String) = res.getString(R.string.diagnostics_report_pair, label, value)
    val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(finishedAt))
    val sep = res.getString(R.string.content_epg_bits_separator)
    val lines = ArrayList<String>()
    // Each nesting level is two spaces in; a section ends with a blank line.
    fun line(depth: Int, text: String) {
        lines += buildString {
            repeat(depth * 2) { append(' ') }
            append(text)
        }
    }
    fun blank() {
        lines += buildString { }
    }

    line(0, res.getString(R.string.diagnostics_report_title))
    line(0, pair(appName, res.getString(R.string.diagnostics_app_version, device.appVersion, device.appVersionCode)))
    line(0, stamp)
    blank()

    line(0, pair(res.getString(R.string.diagnostics_connection_title), status(connection.status)))
    line(1, res.getString(connection.network.labelRes()))
    blank()

    line(0, res.getString(R.string.diagnostics_streaming_heading))
    if (sources.isEmpty()) line(1, res.getString(R.string.diagnostics_no_sources))
    sources.forEach { s ->
        line(1, pair(s.sourceName, status(s.status)))
        s.host?.let { line(2, listOf(s.type.name.lowercase(), it).joinToString(sep)) }
        line(2, s.result.headline(res))
        s.result.detailLines(res).forEach { line(2, it) }
        line(2, playlistCounts(res, s))
        line(2, s.lastSyncAt?.let { res.getString(R.string.settings_synced_at, ago(it)) } ?: res.getString(R.string.diagnostics_playlist_never_synced))
    }
    blank()

    line(0, pair(res.getString(R.string.diagnostics_epg_title), status(epg.status)))
    line(1, epgLine(res, epg))
    if (epg.hasData) line(1, counted(res, R.plurals.sync_count_epg, epg.programmes))
    blank()

    line(0, pair(res.getString(R.string.diagnostics_sports_title), status(sports.status)))
    line(1, sportsLine(res, sports))
    blank()

    line(0, pair(res.getString(R.string.diagnostics_storage_title), status(storage.status)))
    line(1, res.getString(R.string.content_downloads_storage_free, gb(storage.freeBytes), gb(storage.totalBytes)))
    blank()

    line(0, res.getString(R.string.diagnostics_player_title))
    line(1, pair(res.getString(R.string.settings_movies_series_player), engine(player.vodEngine)))
    line(1, pair(res.getString(R.string.settings_live_tv_player), engine(player.liveEngine)))
    blank()

    line(0, res.getString(R.string.diagnostics_device_title))
    line(1, res.getString(R.string.diagnostics_device_line, device.model, device.androidVersion, device.apiLevel))

    return lines.joinToString(System.lineSeparator())
}

/** "<manufacturer> <model>" as the device reports them, one of the two when the other is blank. */
internal fun deviceModel(): String = buildString {
    val maker = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    append(maker)
    if (maker.isNotEmpty() && model.isNotEmpty()) append(' ')
    append(model)
}

/** The guide's one-line verdict, shared by the screen's row and the text report. */
internal fun epgLine(res: Resources, epg: EpgCheck): String = when {
    !epg.configured -> res.getString(R.string.diagnostics_epg_no_source)
    !epg.hasData -> res.getString(R.string.diagnostics_epg_no_data)
    epg.freshestSyncAt == null -> res.getString(R.string.diagnostics_epg_unknown_age)
    else -> {
        val ago = DateUtils.getRelativeTimeSpanString(epg.freshestSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        res.getString(
            when (epg.status) {
                DiagnosticStatus.OK -> R.string.diagnostics_epg_fresh
                DiagnosticStatus.WARNING -> R.string.diagnostics_epg_stale
                DiagnosticStatus.PROBLEM -> R.string.diagnostics_epg_very_stale
            },
            ago,
        )
    }
}

/** The Sports board's one-line verdict, shared by the screen's row and the text report. */
internal fun sportsLine(res: Resources, sports: SportsCheck): String = when {
    sports.paused -> res.getString(R.string.sports_paused)
    sports.failingFeeds > 0 -> res.getQuantityString(R.plurals.diagnostics_sports_failing, sports.failingFeeds, sports.failingFeeds)
    sports.rebuiltAt == null -> res.getString(R.string.diagnostics_sports_none)
    else -> res.getQuantityString(R.plurals.sports_hub_matches, sports.matches, sports.matches) +
        res.getString(R.string.content_epg_bits_separator) +
        res.getString(
            R.string.sports_last_updated,
            DateUtils.getRelativeTimeSpanString(sports.rebuiltAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
        )
}

/** "<n> channels · <n> movies · <n> series", in core's own sync-status plurals. */
internal fun playlistCounts(res: Resources, source: SourceCheck): String = listOf(
    counted(res, R.plurals.sync_count_channels, source.channels),
    counted(res, R.plurals.sync_count_movies, source.movies),
    counted(res, R.plurals.sync_count_series, source.series),
).joinToString(res.getString(R.string.content_epg_bits_separator))

/** A core sync-status plural whose argument is the already-formatted count ("1,234 channels"). */
private fun counted(res: Resources, plural: Int, count: Int): String =
    res.getQuantityString(plural, count, java.text.NumberFormat.getIntegerInstance().format(count))

internal fun NetworkKind.labelRes(): Int = when (this) {
    NetworkKind.WIFI -> R.string.diagnostics_network_wifi
    NetworkKind.ETHERNET -> R.string.diagnostics_network_ethernet
    NetworkKind.CELLULAR -> R.string.diagnostics_network_cellular
    NetworkKind.OTHER -> R.string.diagnostics_network_other
    NetworkKind.NONE -> R.string.diagnostics_network_none
}

/** Bytes as whole-and-tenth gigabytes in the device's locale, as Downloads' own storage line prints them. */
private fun gb(bytes: Long): String = java.text.NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(bytes.coerceAtLeast(0) / 1_073_741_824.0)
