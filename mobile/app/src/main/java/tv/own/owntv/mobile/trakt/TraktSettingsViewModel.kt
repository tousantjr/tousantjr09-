package tv.own.owntv.mobile.trakt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A device-code login in progress, as the Connect row shows it; [code] is Trakt's status when it declined. */
data class TraktLogin(
    val userCode: String,
    val verificationUrl: String,
    val state: State,
    val code: Int = 0,
) {
    enum class State { WAITING, EXPIRED, DENIED, FAILED }
}

/**
 * Settings → Layout → Home → Trakt: Connect / Disconnect, the two switches, the client pair and the
 * status line. App-level like the store behind it; the account connected here scrobbles for every
 * profile.
 */
class TraktSettingsViewModel(
    private val settings: TraktSettings,
    private val repo: TraktRepository,
) : ViewModel() {

    val config: StateFlow<TraktConfig> = settings.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TraktConfig())

    val status: StateFlow<TraktRepository.Status> = repo.status

    private val _login = MutableStateFlow<TraktLogin?>(null)

    /** The login in progress (or how the last one ended), until Connect is pressed again or it succeeds. */
    val login: StateFlow<TraktLogin?> = _login.asStateFlow()

    private var loginJob: Job? = null

    /** Starts the device-code login; the row then shows the code to type at trakt.tv/activate. */
    fun connect() {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            val code = repo.beginLogin()
            if (code == null) {
                _login.value = TraktLogin("", TraktApi.ACTIVATE_URL, TraktLogin.State.FAILED)
                return@launch
            }
            _login.value = TraktLogin(code.userCode, code.verificationUrl, TraktLogin.State.WAITING)
            val result = repo.finishLogin(code)
            _login.value = when (result) {
                is TraktLoginResult.Connected -> null
                is TraktLoginResult.Declined -> _login.value?.copy(
                    state = when (result.code) {
                        TraktRepository.CODE_EXPIRED -> TraktLogin.State.EXPIRED
                        TraktRepository.CODE_DENIED -> TraktLogin.State.DENIED
                        else -> TraktLogin.State.FAILED
                    },
                    code = result.code,
                )
            }
        }
    }

    fun cancelConnect() {
        loginJob?.cancel()
        loginJob = null
        _login.value = null
    }

    fun disconnect() {
        cancelConnect()
        viewModelScope.launch { repo.disconnect() }
    }

    fun setScrobble(on: Boolean) {
        viewModelScope.launch { settings.setScrobble(on) }
    }

    fun setWatchlistRow(on: Boolean) {
        viewModelScope.launch { settings.setWatchlistRow(on) }
    }

    /** A new client id or secret forgets the login: tokens the old pair minted are not valid for it. */
    fun setClientId(id: String) {
        viewModelScope.launch {
            if (id.trim() == config.value.clientId) return@launch
            cancelConnect()
            settings.setClientId(id)
        }
    }

    fun setClientSecret(secret: String) {
        viewModelScope.launch {
            if (secret.trim() == config.value.clientSecret) return@launch
            cancelConnect()
            settings.setClientSecret(secret)
        }
    }
}
