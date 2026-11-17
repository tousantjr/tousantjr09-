package tv.own.owntv.mobile.ui.screens.multiview

import android.content.Context
import androidx.core.content.edit

/**
 * The grid shape the user last chose from the tile menu, kept on this phone only. It is a matter of
 * taste for this screen, not a setting worth syncing, so it lives outside core's DataStore.
 */
object MultiviewLayoutStore {
    private const val PREFS = "mptv_multiview"
    private const val KEY_LAYOUT = "layout_mode"

    fun read(context: Context): MultiviewLayoutMode =
        MultiviewLayoutMode.from(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null))

    fun write(context: Context, mode: MultiviewLayoutMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LAYOUT, mode.key) }
    }
}
