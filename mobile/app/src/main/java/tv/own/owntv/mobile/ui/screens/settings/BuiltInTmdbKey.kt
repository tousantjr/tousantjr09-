package tv.own.owntv.mobile.ui.screens.settings

import android.content.Context
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Puts the TMDB key this build shipped with (`BuildConfig.TMDB_API_KEY`, from CI's secret) into the
 * TMDB key setting, once per install and only when that setting is empty — the phone twin of the
 * television's BuiltInTmdbKey. A user who later clears or replaces the key keeps their choice.
 */
object BuiltInTmdbKey {
    private const val PREFS = "mptv_builtin_keys"
    private const val SEEDED = "tmdb_seeded"

    suspend fun seedOnce(context: Context, settings: SettingsRepository, builtInKey: String) {
        if (builtInKey.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEDED, false)) return
        if (settings.currentTmdbApiKey().isBlank()) settings.setTmdbApiKey(builtInKey.trim())
        prefs.edit().putBoolean(SEEDED, true).apply()
    }
}
