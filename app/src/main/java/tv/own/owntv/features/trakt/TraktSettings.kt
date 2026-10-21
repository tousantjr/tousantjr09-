package tv.own.owntv.features.trakt

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.BuildConfig

private val Context.traktDataStore by preferencesDataStore(name = "trakt")

/** The tokens Trakt handed out at login, and when the access token runs out (epoch ms). */
data class TraktAuth(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

/**
 * Settings → Layout → Home → Trakt, as stored. Both switches default on, but nothing happens until the
 * account is connected: [scrobbleOn] and [watchlistOn] are what the app actually reads.
 */
data class TraktConfig(
    val scrobble: Boolean = true,
    val watchlistRow: Boolean = true,
    /** The API app's client id and secret the user typed. Kept here only; never logged, never put in a report. */
    val clientId: String = "",
    val clientSecret: String = "",
    /** The pair the app was built with (`BuildConfig.TRAKT_CLIENT_ID` / `_SECRET`, from CI's secrets); "" in a fork or local build. */
    val builtInClientId: String = "",
    val builtInClientSecret: String = "",
    val auth: TraktAuth? = null,
    /** The Trakt username, once `/users/me` answered. */
    val username: String = "",
) {
    /** The credentials a request goes out with: the user's own when typed, else the ones built in. */
    val effectiveClientId: String get() = clientId.ifBlank { builtInClientId }
    val effectiveClientSecret: String get() = clientSecret.ifBlank { builtInClientSecret }

    /**
     * A client id is all the feature needs. Trakt's newer API apps sign users in with PKCE and are
     * issued no secret at all, so an empty secret is a complete, working configuration; one is sent
     * only when there is one.
     */
    val hasCredentials: Boolean get() = effectiveClientId.isNotBlank()

    val hasSecret: Boolean get() = effectiveClientSecret.isNotBlank()

    /** Nothing typed and a built-in client id exists: the row says "Built in" and never shows it. */
    val usesBuiltInId: Boolean get() = clientId.isBlank() && builtInClientId.isNotBlank()

    val usesBuiltInSecret: Boolean get() = clientSecret.isBlank() && builtInClientSecret.isNotBlank()

    val connected: Boolean get() = hasCredentials && auth != null

    val scrobbleOn: Boolean get() = scrobble && connected

    val watchlistOn: Boolean get() = watchlistRow && connected
}

/**
 * App-level (this device, every profile) store for the Trakt features. Its own tiny DataStore, the
 * same shape as `MdbListSettings`: `:core`'s `SettingsRepository` ships compiled in the `:core` AAR and
 * cannot gain a preference from app code. The user's credentials and tokens live here and nowhere
 * else; the built-in client pair is the build's, and the user's own always wins over it.
 */
class TraktSettings(
    private val context: Context,
    private val builtInClientId: String = BuildConfig.TRAKT_CLIENT_ID,
    private val builtInClientSecret: String = BuildConfig.TRAKT_CLIENT_SECRET,
) {
    private object Keys {
        val SCROBBLE = booleanPreferencesKey("trakt_scrobble")
        val WATCHLIST = booleanPreferencesKey("trakt_watchlist_row")
        val CLIENT_ID = stringPreferencesKey("trakt_client_id")
        val CLIENT_SECRET = stringPreferencesKey("trakt_client_secret")
        val ACCESS_TOKEN = stringPreferencesKey("trakt_access_token")
        val REFRESH_TOKEN = stringPreferencesKey("trakt_refresh_token")
        val EXPIRES_AT = longPreferencesKey("trakt_expires_at")
        val USERNAME = stringPreferencesKey("trakt_username")
    }

    val config: Flow<TraktConfig> = context.traktDataStore.data.map { p ->
        val access = p[Keys.ACCESS_TOKEN].orEmpty()
        val refresh = p[Keys.REFRESH_TOKEN].orEmpty()
        TraktConfig(
            scrobble = p[Keys.SCROBBLE] ?: true,
            watchlistRow = p[Keys.WATCHLIST] ?: true,
            clientId = p[Keys.CLIENT_ID].orEmpty(),
            clientSecret = p[Keys.CLIENT_SECRET].orEmpty(),
            builtInClientId = builtInClientId,
            builtInClientSecret = builtInClientSecret,
            auth = if (access.isBlank()) null else TraktAuth(access, refresh, p[Keys.EXPIRES_AT] ?: 0L),
            username = p[Keys.USERNAME].orEmpty(),
        )
    }.distinctUntilChanged()

    suspend fun setScrobble(on: Boolean) = edit { it[Keys.SCROBBLE] = on }

    suspend fun setWatchlistRow(on: Boolean) = edit { it[Keys.WATCHLIST] = on }

    /** A new client pair starts afresh: tokens the old one minted are not valid for it. */
    suspend fun setClientId(id: String) = edit {
        it[Keys.CLIENT_ID] = id.trim()
        clearAuth(it)
    }

    suspend fun setClientSecret(secret: String) = edit {
        it[Keys.CLIENT_SECRET] = secret.trim()
        clearAuth(it)
    }

    suspend fun setAuth(auth: TraktAuth, username: String?) = edit {
        it[Keys.ACCESS_TOKEN] = auth.accessToken
        it[Keys.REFRESH_TOKEN] = auth.refreshToken
        it[Keys.EXPIRES_AT] = auth.expiresAtMs
        if (username != null) it[Keys.USERNAME] = username
    }

    suspend fun setUsername(username: String) = edit { it[Keys.USERNAME] = username }

    suspend fun clearAuth() = edit { clearAuth(it) }

    private fun clearAuth(p: MutablePreferences) {
        p.remove(Keys.ACCESS_TOKEN)
        p.remove(Keys.REFRESH_TOKEN)
        p.remove(Keys.EXPIRES_AT)
        p.remove(Keys.USERNAME)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.traktDataStore.edit(block)
    }
}
