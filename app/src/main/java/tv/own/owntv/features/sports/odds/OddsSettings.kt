package tv.own.owntv.features.sports.odds

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.BuildConfig

private val Context.oddsDataStore by preferencesDataStore(name = "sports_odds")

/** Settings → Sports → Betting odds, as stored. Off by default: nobody sees a line who did not ask. */
data class OddsConfig(
    val enabled: Boolean = false,
    val source: OddsSource = OddsSource.ESPN,
    /** The Odds API key the user typed. Kept here only; never logged, never put in a report. */
    val apiKey: String = "",
    /** The key the app was built with (`BuildConfig.ODDS_API_KEY`, from CI's secret); "" in a fork or local build. */
    val builtInKey: String = "",
    val bookmaker: Bookmaker = Bookmaker.DRAFTKINGS,
    /** The match page's comparison table: every listed bookmaker beside [bookmaker]'s line (The Odds API only). */
    val compareBookmakers: Boolean = true,
    /** The Odds API's `x-requests-remaining` after the last call; null until one was made. */
    val requestsRemaining: Int? = null,
    /** The local day (ISO date) the key's quota ran out, if it did: no call is made again that day. */
    val exhaustedDay: String? = null,
) {
    /** The key a request goes out with: the user's own when typed, else the one built in. */
    val effectiveKey: String get() = apiKey.ifBlank { builtInKey }

    val hasKey: Boolean get() = effectiveKey.isNotBlank()

    /** Nothing typed and a built-in key exists: the key row says "Built-in key" and never shows it. */
    val usesBuiltInKey: Boolean get() = apiKey.isBlank() && builtInKey.isNotBlank()

    /** The part a fetch depends on — the quota fields change *because* of a fetch, so they must not retrigger one. */
    val fetchKey: OddsConfig get() = copy(requestsRemaining = null, exhaustedDay = null)
}

/**
 * App-level (this device, every profile) store for the betting-odds display. Its own tiny DataStore,
 * the same shape as `HomeContentFilterSettings`: `:core`'s `SettingsRepository` ships compiled in the
 * `:core` AAR and cannot gain a preference from app code. The user's API key lives here and nowhere
 * else; [builtInKey] is the build's (so a household need not type one on every device) and the
 * user's own always wins over it. With only a built-in key, the source defaults to The Odds API —
 * the display itself still defaults off.
 */
class OddsSettings(private val context: Context, private val builtInKey: String = BuildConfig.ODDS_API_KEY) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("odds_enabled")
        val SOURCE = stringPreferencesKey("odds_source")
        val API_KEY = stringPreferencesKey("odds_api_key")
        val BOOKMAKER = stringPreferencesKey("odds_bookmaker")
        val COMPARE = booleanPreferencesKey("odds_compare")
        val REMAINING = intPreferencesKey("odds_requests_remaining")
        val EXHAUSTED_DAY = stringPreferencesKey("odds_exhausted_day")
    }

    val config: Flow<OddsConfig> = context.oddsDataStore.data.map { p ->
        val defaultSource = if (builtInKey.isNotBlank()) OddsSource.ODDS_API else OddsSource.ESPN
        OddsConfig(
            enabled = p[Keys.ENABLED] ?: false,
            source = p[Keys.SOURCE]?.let { s -> OddsSource.entries.firstOrNull { it.name == s } } ?: defaultSource,
            apiKey = p[Keys.API_KEY].orEmpty(),
            builtInKey = builtInKey,
            bookmaker = Bookmaker.of(p[Keys.BOOKMAKER]),
            compareBookmakers = p[Keys.COMPARE] ?: true,
            requestsRemaining = p[Keys.REMAINING]?.takeIf { it >= 0 },
            exhaustedDay = p[Keys.EXHAUSTED_DAY]?.takeIf { it.isNotEmpty() },
        )
    }.distinctUntilChanged()

    suspend fun setEnabled(on: Boolean) = edit { it[Keys.ENABLED] = on }

    suspend fun setSource(source: OddsSource) = edit { it[Keys.SOURCE] = source.name }

    /** A new key starts afresh: what the old one had left, or whether it ran out, says nothing about this one. */
    suspend fun setApiKey(key: String) = edit {
        it[Keys.API_KEY] = key.trim()
        it.remove(Keys.REMAINING)
        it.remove(Keys.EXHAUSTED_DAY)
    }

    suspend fun setBookmaker(bookmaker: Bookmaker) = edit { it[Keys.BOOKMAKER] = bookmaker.key }

    suspend fun setCompareBookmakers(on: Boolean) = edit { it[Keys.COMPARE] = on }

    /** After a call: the requests left (null = the header was missing) and the day the quota ran out, or null to clear it. */
    suspend fun recordQuota(remaining: Int?, exhaustedDay: String?) = edit {
        if (remaining != null) it[Keys.REMAINING] = remaining
        if (exhaustedDay != null) it[Keys.EXHAUSTED_DAY] = exhaustedDay else it.remove(Keys.EXHAUSTED_DAY)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.oddsDataStore.edit(block)
    }
}
