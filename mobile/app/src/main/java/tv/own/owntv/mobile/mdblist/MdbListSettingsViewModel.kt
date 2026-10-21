package tv.own.owntv.mobile.mdblist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Layout → Home → MDBList: the two switches, the key, the list picker and "Test key".
 * App-level like the store behind it; a list toggled here changes Home for every profile.
 */
class MdbListSettingsViewModel(
    private val settings: MdbListSettings,
    private val repo: MdbListRepository,
) : ViewModel() {

    val config: StateFlow<MdbListConfig> = settings.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MdbListConfig())

    val status: StateFlow<MdbListRepository.Status> = repo.status

    private val _topLists = MutableStateFlow<List<MdbListInfo>>(emptyList())

    /** The public top lists the picker offers (cached a day), empty without a working key. */
    val topLists: StateFlow<List<MdbListInfo>> = _topLists.asStateFlow()

    private val _test = MutableStateFlow<MdbKeyTest?>(null)

    /** The last "Test key" result, until the key changes. */
    val test: StateFlow<MdbKeyTest?> = _test.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    init {
        viewModelScope.launch {
            settings.config.map { it.effectiveKey }.distinctUntilChanged().collectLatest { key ->
                _topLists.value = if (key.isBlank()) emptyList() else repo.topLists().take(TOP_SHOWN)
            }
        }
    }

    fun setCuratedRows(on: Boolean) {
        viewModelScope.launch { settings.setCuratedRows(on) }
    }

    fun setRatings(on: Boolean) {
        viewModelScope.launch { settings.setRatings(on) }
    }

    /** A new key starts afresh: the lists the old one fetched, and its test result, say nothing about this one. */
    fun setApiKey(key: String) {
        viewModelScope.launch {
            if (key.trim() == config.value.apiKey) return@launch
            settings.setApiKey(key)
            repo.forgetLists()
            _test.value = null
        }
    }

    /** Picks or unpicks one of the top lists; at most [MdbListSettings.MAX_LISTS] at a time. */
    fun toggleList(ref: String) {
        viewModelScope.launch {
            val now = config.value.lists
            when {
                ref in now -> settings.setLists(now - ref)
                now.size < MdbListSettings.MAX_LISTS -> settings.setLists(now + ref)
            }
        }
    }

    /**
     * Adds a list typed as its numeric id, `user/slug`, or its mdblist.com address. Returns false for
     * anything else, or when the five places are taken.
     */
    fun addList(typed: String): Boolean {
        val ref = normalizeRef(typed) ?: return false
        val now = config.value.lists
        if (ref in now) return true
        if (now.size >= MdbListSettings.MAX_LISTS) return false
        viewModelScope.launch { settings.setLists(now + ref) }
        return true
    }

    fun removeList(ref: String) {
        viewModelScope.launch { settings.setLists(config.value.lists - ref) }
    }

    fun testKey() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            try {
                val result = repo.testKey()
                _test.value = result
                if (result.ok) _topLists.value = repo.topLists().take(TOP_SHOWN)
            } finally {
                _testing.value = false
            }
        }
    }

    private companion object {
        /** How many of the top lists the picker shows. */
        const val TOP_SHOWN = 25
        const val LISTS_PATH = "/lists/"

        /** "https://mdblist.com/lists/user/slug/", "user/slug" or "12345" → "user/slug" / "12345"; null for anything else. */
        fun normalizeRef(typed: String): String? {
            val t = typed.trim()
            val afterPath = t.indexOf(LISTS_PATH).let { i -> if (i >= 0) t.substring(i + LISTS_PATH.length) else t }
            val ref = afterPath.trim().trim('/').substringBefore('?')
            if (ref.toLongOrNull() != null) return ref
            val parts = ref.split('/').map(String::trim)
            return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts.joinToString(separator = "/") else null
        }
    }
}
