package tv.own.owntv.mobile.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.mobile.network.SmbClientPool
import tv.own.owntv.mobile.network.SmbEntry
import tv.own.owntv.mobile.network.SmbRules
import tv.own.owntv.mobile.network.SmbShare
import tv.own.owntv.mobile.network.SmbShareStore
import tv.own.owntv.mobile.network.SmbStreamServer
import tv.own.owntv.mobile.ui.screens.library.VodTuner

/** One folder inside a saved network share, as the sheet shows it. */
data class NetworkBrowse(
    val share: SmbShare,
    val path: String,
    val entries: List<SmbEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** MPTV: network folders (SMB / NAS) — the saved shares, browsing one, and playing a video from it. */
class NetworkFoldersViewModel(
    private val store: SmbShareStore,
    private val pool: SmbClientPool,
    private val server: SmbStreamServer,
    private val vodTuner: VodTuner,
) : ViewModel() {
    val shares: StateFlow<List<SmbShare>> = store.shares

    private val _browse = MutableStateFlow<NetworkBrowse?>(null)
    val browse: StateFlow<NetworkBrowse?> = _browse.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /** True once a video started in the built-in player, so the screen opens it. */
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

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

    /** One folder up; at the share's starting folder it closes the share instead. */
    fun up() {
        val b = _browse.value ?: return
        if (b.path.isBlank() || SmbRules.join(b.path) == SmbRules.join(b.share.startPath)) close()
        else load(b.share, SmbRules.parent(b.path))
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

    /** Checks the details by opening the folder, and saves them only if that worked. */
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

    fun play(entry: SmbEntry) {
        val b = _browse.value ?: return
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { server.urlFor(b.share, entry.path) }
            _playing.value = vodTuner.playNetwork(url, entry.name.substringBeforeLast('.'))
        }
    }

    fun playerOpened() { _playing.value = false }
}
