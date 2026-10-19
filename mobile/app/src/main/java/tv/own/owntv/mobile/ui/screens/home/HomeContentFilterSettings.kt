package tv.own.owntv.mobile.ui.screens.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.homeContentFilterDataStore by preferencesDataStore(name = "home_content_filter")

/**
 * App-level (not per-profile) toggle for Home's Trending rail: whether an item whose category
 * [tv.own.owntv.core.content.AdultCategoryClassifier.isAdult] flags is left out, for EVERY profile —
 * not just kids ones.
 *
 * This is additive to, and never a substitute for, the existing kids-profile enforcement already in
 * `:core` ([tv.own.owntv.core.content.AdultCategoryClassifier.hiddenCategoryIds] /
 * [tv.own.owntv.core.content.AdultCategoryClassifier.allows]), which stays a hard block for a kids
 * profile regardless of this setting — a kids profile gets both: that hard block, and this toggle on
 * top of it, which is harmless.
 *
 * Lives in its own tiny DataStore rather than `:core`'s `SettingsRepository`: that class ships compiled
 * inside the `:core` AAR this app depends on (source isn't in this repo), so it cannot gain a new
 * preference from app code — this is the smallest thing that can hold one persisted flag. Ported from
 * the TV app's identically-named class (same key, same default), kept as its own copy here because
 * each app's DataStore is its own process-local file.
 *
 * Defaults ON, matching the kids-profile mechanism already treating these categories as something
 * that should be hidden unless a profile is deliberately shown them.
 */
class HomeContentFilterSettings(private val context: Context) {
    private object Keys {
        val HIDE_ADULT = booleanPreferencesKey("hide_adult_categories_home")
    }

    val hideAdultCategories: Flow<Boolean> = context.homeContentFilterDataStore.data
        .map { prefs -> prefs[Keys.HIDE_ADULT] ?: true }

    suspend fun setHideAdultCategories(hidden: Boolean) {
        context.homeContentFilterDataStore.edit { prefs -> prefs[Keys.HIDE_ADULT] = hidden }
    }
}
