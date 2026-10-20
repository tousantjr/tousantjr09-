package tv.own.owntv.features.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.homeTrailerDataStore by preferencesDataStore(name = "home_trailers")

/**
 * App-level toggle for Home's Trending hero: whether the focused title's trailer plays, muted, inside
 * the hero's backdrop after a short dwell (see [TrendingTrailerPreview]).
 *
 * Its own tiny DataStore for the same reason as [HomeContentFilterSettings]: `:core`'s
 * `SettingsRepository` ships compiled and cannot gain a preference from app code. The default follows
 * core's own hero-preview default (off on a low-RAM device): a muted WebView video costs about what the
 * live hero preview does, so the two should be switched on for the same boxes.
 */
class HomeTrailerSettings(private val context: Context) {
    private object Keys {
        val PLAY_TRAILERS = booleanPreferencesKey("play_trailers_on_home")
    }

    fun playTrailers(default: Boolean): Flow<Boolean> = context.homeTrailerDataStore.data
        .map { prefs -> prefs[Keys.PLAY_TRAILERS] ?: default }

    suspend fun setPlayTrailers(enabled: Boolean) {
        context.homeTrailerDataStore.edit { prefs -> prefs[Keys.PLAY_TRAILERS] = enabled }
    }
}
