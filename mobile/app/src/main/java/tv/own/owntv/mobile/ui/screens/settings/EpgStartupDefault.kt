package tv.own.owntv.mobile.ui.screens.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.SettingsRepository

/**
 * MPTV: a TV guide refreshes itself every time the app starts, unless the user picks otherwise.
 *
 * Upstream leaves a new guide on "Off", so it is downloaded once and then quietly runs dry: the old
 * programmes are pruned and the Guide falls back to "No EPG added". MPTV customers never find the
 * per-guide Auto refresh setting, so MPTV makes "Refresh on startup" the default:
 *
 * - **Once per install**, every guide still on Off (or never set) is switched to Refresh on startup.
 *   Upstream's add dialog stored "Off" explicitly, so existing installs need this one pass.
 * - **After that**, only guides with no setting at all get Refresh on startup. A guide the user
 *   later sets to Off on purpose stays Off.
 */
object EpgStartupDefault {
    val DEFAULT = EpgRefresh(EpgAutoRefresh.STARTUP)

    private const val PREFS = "mptv_defaults"
    private const val KEY_DONE = "epg_refresh_on_startup_v1"

    /** Whether a guide's saved setting ([current], null = never set) should become [DEFAULT]. */
    fun shouldSwitch(current: EpgRefresh?, firstPass: Boolean): Boolean =
        current == null || (firstPass && current.mode == EpgAutoRefresh.OFF)

    suspend fun apply(context: Context, settings: SettingsRepository, store: EpgSourceStore) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val firstPass = !prefs.getBoolean(KEY_DONE, false)
            val modes = settings.epgAutoRefresh.first()
            store.getAll().forEach { src ->
                val current = modes[src.id]
                if (shouldSwitch(current, firstPass)) {
                    Log.i("MptvEpgDefault", "guide ${src.id} auto refresh ${current?.mode} -> STARTUP")
                    settings.setEpgAutoRefresh(src.id, DEFAULT)
                }
            }
            if (firstPass) prefs.edit().putBoolean(KEY_DONE, true).apply()
        }.onFailure { Log.w("MptvEpgDefault", "could not apply the guide refresh default", it) }
    }
}
