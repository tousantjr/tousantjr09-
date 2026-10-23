package tv.own.owntv.features.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.homeTrailerDataStore by preferencesDataStore(name = "home_trailers")

/**
 * App-level toggle for Home's Trending hero: whether the shown title's trailer plays, muted, inside
 * the hero's backdrop as soon as the hero has settled (see [TrendingTrailerPreview]).
 *
 * Its own tiny DataStore for the same reason as [HomeContentFilterSettings]: `:core`'s
 * `SettingsRepository` ships compiled and cannot gain a preference from app code. On by default on
 * every box ([DEFAULT]): the trailer is the hero's point, and a muted video at a fraction of the
 * screen has been fine on the sticks tried; the switch stays for anyone who finds Home less smooth.
 */
class HomeTrailerSettings(private val context: Context) {
    private object Keys {
        val PLAY_TRAILERS = booleanPreferencesKey("play_trailers_on_home")
    }

    companion object {
        /** Trailers play unless the user switches them off. */
        const val DEFAULT = true
    }

    fun playTrailers(default: Boolean): Flow<Boolean> = context.homeTrailerDataStore.data
        .map { prefs -> prefs[Keys.PLAY_TRAILERS] ?: default }

    suspend fun setPlayTrailers(enabled: Boolean) {
        context.homeTrailerDataStore.edit { prefs -> prefs[Keys.PLAY_TRAILERS] = enabled }
    }
}
