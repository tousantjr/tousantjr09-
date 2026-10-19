package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.download.DownloadManager
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.core.repository.SourceTester
import tv.own.owntv.core.settings.SettingsRepository

/** OK / Warning / Problem — the same three-state read every check in [DiagnosticsReport] settles on. */
enum class DiagnosticStatus { OK, WARNING, PROBLEM }

/** Basic internet reachability: the OS link layer, then one small request over this app's own HTTP stack. */
data class ConnectionCheck(val status: DiagnosticStatus)

/** One playlist's reachability + account/subscription state, straight from [SourceTester]. */
data class SourceCheck(
    val sourceId: Long,
    val sourceName: String,
    val status: DiagnosticStatus,
    val result: SourceTestResult,
)

/** Whether this profile has a TV guide at all, and how stale it is. */
data class EpgCheck(
    val status: DiagnosticStatus,
    /** A playlist or standalone feed offers a guide at all (not necessarily synced yet). */
    val configured: Boolean,
    val hasData: Boolean,
    /** Most recent successful sync across every guide source, or null if unknown/never. */
    val freshestSyncAt: Long?,
)

/** Free space where downloads and recordings are written. */
data class StorageCheck(val status: DiagnosticStatus, val freeBytes: Long, val totalBytes: Long)

data class DiagnosticsReport(
    val connection: ConnectionCheck,
    val sources: List<SourceCheck>,
    val epg: EpgCheck,
    val storage: StorageCheck,
    val finishedAt: Long,
)

sealed interface DiagnosticsUi {
    data object Idle : DiagnosticsUi
    data object Running : DiagnosticsUi
    data class Done(val report: DiagnosticsReport) : DiagnosticsUi
}

/**
 * Settings → Diagnostics: four read-only checks (connection, streaming service & playlist, TV guide,
 * device storage), run on demand rather than automatically — [SourceTester] briefly costs a live
 * connection slot on an Xtream account, exactly the reason "Test connection" on Add/Edit Source is
 * also button-triggered rather than silent. Nothing here changes any setting or stored data.
 */
class DiagnosticsViewModel(
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
    private val sourceTester: SourceTester,
    private val connectivity: ConnectivityObserver,
    private val httpClient: HttpClient,
    private val epgDao: tv.own.owntv.core.database.dao.EpgDao,
    private val epgSourceStore: EpgSourceStore,
    private val epgRepository: EpgRepository,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    private val _diagnostics = MutableStateFlow<DiagnosticsUi>(DiagnosticsUi.Idle)
    val diagnostics: StateFlow<DiagnosticsUi> = _diagnostics.asStateFlow()

    fun runChecks() {
        if (_diagnostics.value is DiagnosticsUi.Running) return
        _diagnostics.value = DiagnosticsUi.Running
        viewModelScope.launch {
            _diagnostics.value = DiagnosticsUi.Done(buildReport())
        }
    }

    private suspend fun buildReport(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val profileId = settings.activeProfileId.first()
        val sources = if (profileId >= 0) sourceRepository.observeSources(profileId).first() else emptyList()
        coroutineScope {
            val connectionAsync = async { checkConnection() }
            val sourcesAsync = async { checkSources(sources) }
            val epgAsync = async { checkEpg(sources) }
            val storageAsync = async { checkStorage() }
            DiagnosticsReport(
                connection = connectionAsync.await(),
                sources = sourcesAsync.await(),
                epg = epgAsync.await(),
                storage = storageAsync.await(),
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Two layers on purpose: [ConnectivityObserver] answers for the OS link (and its own
     * NET_CAPABILITY_VALIDATED check already does a system-level probe), while the HTTP call answers
     * for THIS app's own stack — custom DNS (Settings → DNS) or a proxy can leave the OS "online"
     * while OwnTV itself cannot actually fetch anything.
     */
    private suspend fun checkConnection(): ConnectionCheck {
        if (!connectivity.isOnlineNow()) return ConnectionCheck(DiagnosticStatus.PROBLEM)
        return try {
            httpClient.get(CONNECTIVITY_CHECK_URL, maxAttempts = 1) { input -> input.readBytes() }
            ConnectionCheck(DiagnosticStatus.OK)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            ConnectionCheck(DiagnosticStatus.PROBLEM)
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
            SourceCheck(source.id, source.name, result.diagnosticStatus(), result)
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
            return EpgCheck(DiagnosticStatus.WARNING, configured = false, hasData = false, freshestSyncAt = null)
        }
        val hasData = epgDao.countForSources(storeIds) > 0
        if (!hasData) {
            return EpgCheck(DiagnosticStatus.PROBLEM, configured = true, hasData = false, freshestSyncAt = null)
        }
        val freshest = (guideSources.mapNotNull { it.lastSyncAt } + epgSources.mapNotNull { it.lastSyncAt }).maxOrNull()
        val age = freshest?.let { System.currentTimeMillis() - it }
        val status = when {
            age == null -> DiagnosticStatus.WARNING
            age <= EPG_FRESH_WINDOW_MS -> DiagnosticStatus.OK
            age <= EPG_STALE_WINDOW_MS -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.PROBLEM
        }
        return EpgCheck(status, configured = true, hasData = true, freshestSyncAt = freshest)
    }

    /** Free space where downloads and recordings are written — [DownloadManager] already resolves
     *  the configured folder (or its fallback) and measures it via `StatFs`. */
    private suspend fun checkStorage(): StorageCheck {
        val info = downloadManager.storageInfo()
        val status = when {
            info.freeBytes < STORAGE_PROBLEM_BYTES -> DiagnosticStatus.PROBLEM
            info.freeBytes < STORAGE_WARNING_BYTES -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.OK
        }
        return StorageCheck(status, info.freeBytes, info.totalBytes)
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
