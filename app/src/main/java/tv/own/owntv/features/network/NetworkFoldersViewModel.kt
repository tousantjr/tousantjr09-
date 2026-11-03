package tv.own.owntv.features.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/** What the Network folders page shows: the saved list, or one folder inside a share. */
data class NetworkBrowse(
    val share: SmbShare,
    val path: String,
    val entries: List<SmbEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

class NetworkFoldersViewModel(
    private val store: SmbShareStore,
    private val pool: SmbClientPool,
    private val server: SmbStreamServer,
    private val settings: SettingsRepository,
    private val player: OwnTVPlayer,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
) : ViewModel() {
    val shares: StateFlow<List<SmbShare>> = store.shares

    private val _browse = MutableStateFlow<NetworkBrowse?>(null)
    val browse: StateFlow<NetworkBrowse?> = _browse.asStateFlow()

    /** Saving: null idle, true while checking the connection; [saveFailed] when the NAS said no. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private var loadJob: Job? = null

    fun open(share: SmbShare) = load(share, share.startPath)

    fun openFolder(entry: SmbEntry) {
        val b = _browse.value ?: return
        load(b.share, entry.path)
    }

    fun retry() {
        val b = _browse.value ?: return
        load(b.share, b.path)
    }

    /** One folder up; false at the share's starting folder, where Back closes the share. */
    fun up(): Boolean {
        val b = _browse.value ?: return false
        if (SmbRules.join(b.path) == SmbRules.join(b.share.startPath) || b.path.isBlank()) {
            close(); return false
        }
        load(b.share, SmbRules.parent(b.path))
        return true
    }

    fun close() {
        loadJob?.cancel()
        _browse.value = null
    }

    private fun load(share: SmbShare, path: String) {
        loadJob?.cancel()
        _browse.value = NetworkBrowse(share, path)
        loadJob = viewModelScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { pool.list(share, path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _browse.value = NetworkBrowse(share, path, entries.orEmpty(), loading = false, failed = entries == null)
        }
    }

    /**
     * Checks the details by opening the folder, and only saves them if that worked.
     * [onSaved] runs on success.
     */
    fun save(existingId: String?, name: String, server: String, shareName: String, username: String, password: String, onSaved: () -> Unit) {
        val (host, share, path) = SmbRules.parseServer(server, shareName)
        if (host.isBlank() || share.isBlank()) { _saveFailed.value = true; return }
        val candidate = SmbShare(
            id = existingId ?: store.newId(),
            name = name.trim().ifBlank { share },
            host = host, share = share, username = username.trim(), password = password, startPath = path,
        )
        _saving.value = true
        _saveFailed.value = false
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                pool.forget(candidate)
                runCatching { pool.list(candidate, candidate.startPath) }.isSuccess
            }
            _saving.value = false
            if (ok) { store.save(candidate); onSaved() } else _saveFailed.value = true
        }
    }

    fun clearSaveError() { _saveFailed.value = false }

    fun remove(share: SmbShare) {
        pool.forget(share)
        store.remove(share.id)
    }

    /** Plays [entry]; [onStarted] runs when the built-in player took it (not for an external app). */
    fun play(entry: SmbEntry, onStarted: () -> Unit) {
        val b = _browse.value ?: return
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { server.urlFor(b.share, entry.path) }
            val title = entry.name.substringBeforeLast('.')
            if (settings.externalPlayerFor(MediaType.MOVIE).first()) {
                externalPlayerLauncher.launch(url, title)
                return@launch
            }
            player.play(url, title = title, isLive = false)
            onStarted()
        }
    }
}
