package tv.own.owntv.mobile.mdblist

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.mobile.BuildConfig

private val Context.mdbListDataStore by preferencesDataStore(name = "mdblist")

/**
 * Settings → Layout → Home → MDBList, as stored. Both halves default on, but nothing happens without a
 * key: [rowsOn] and [ratingsOn] are what the app actually reads, and both are false until one exists.
 */
data class MdbListConfig(
    val curatedRows: Boolean = true,
    val ratings: Boolean = true,
    /** The MDBList key the user typed. Kept here only; never logged, never put in a report. */
    val apiKey: String = "",
    /** The key the app was built with (`BuildConfig.MDBLIST_API_KEY`, from CI's secret); "" in a fork or local build. */
    val builtInKey: String = "",
    /** The chosen lists, each a numeric MDBList id or a `user/slug` pair; empty means the top lists. */
    val lists: List<String> = emptyList(),
) {
    /** The key a request goes out with: the user's own when typed, else the one built in. */
    val effectiveKey: String get() = apiKey.ifBlank { builtInKey }

    val hasKey: Boolean get() = effectiveKey.isNotBlank()

    /** Nothing typed and a built-in key exists: the key row says "Built-in key" and never shows it. */
    val usesBuiltInKey: Boolean get() = apiKey.isBlank() && builtInKey.isNotBlank()

    val rowsOn: Boolean get() = curatedRows && hasKey

    val ratingsOn: Boolean get() = ratings && hasKey
}

/**
 * App-level (this device, every profile) store for the MDBList features. Its own tiny DataStore, the
 * same shape as `HomeContentFilterSettings`: `:core`'s `SettingsRepository` ships compiled in the `:core`
 * AAR and cannot gain a preference from app code. The user's API key lives here and nowhere else;
 * [builtInKey] is the build's (so a household need not type one on every device) and the user's own
 * always wins over it.
 */
class MdbListSettings(private val context: Context, private val builtInKey: String = BuildConfig.MDBLIST_API_KEY) {
    private object Keys {
        val ROWS = booleanPreferencesKey("mdblist_curated_rows")
        val RATINGS = booleanPreferencesKey("mdblist_ratings")
        val API_KEY = stringPreferencesKey("mdblist_api_key")
        val LISTS = stringPreferencesKey("mdblist_lists")
    }

    val config: Flow<MdbListConfig> = context.mdbListDataStore.data.map { p ->
        MdbListConfig(
            curatedRows = p[Keys.ROWS] ?: true,
            ratings = p[Keys.RATINGS] ?: true,
            apiKey = p[Keys.API_KEY].orEmpty(),
            builtInKey = builtInKey,
            lists = p[Keys.LISTS].orEmpty().split(LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() },
        )
    }.distinctUntilChanged()

    suspend fun setCuratedRows(on: Boolean) = edit { it[Keys.ROWS] = on }

    suspend fun setRatings(on: Boolean) = edit { it[Keys.RATINGS] = on }

    suspend fun setApiKey(key: String) = edit { it[Keys.API_KEY] = key.trim() }

    /** The chosen lists, at most [MAX_LISTS], in the order they were picked. */
    suspend fun setLists(lists: List<String>) = edit {
        it[Keys.LISTS] = lists.map(String::trim).filter { ref -> ref.isNotEmpty() }.distinct().take(MAX_LISTS)
            .joinToString(LIST_SEPARATOR.toString())
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.mdbListDataStore.edit(block)
    }

    companion object {
        const val MAX_LISTS = 5
        private const val LIST_SEPARATOR = '\n'
    }
}
