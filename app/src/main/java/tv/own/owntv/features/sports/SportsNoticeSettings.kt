package tv.own.owntv.features.sports

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sportsNoticeDataStore by preferencesDataStore(name = "sports_notices")

/** Settings → Sports: the game-day heads-up and the score ticker, as stored. Both off until asked for. */
data class SportsNoticeConfig(
    val gameDay: Boolean = false,
    /** The local hour (0–23) the daily heads-up arrives at. */
    val gameDayHour: Int = DEFAULT_GAME_DAY_HOUR,
    val scoreTicker: Boolean = false,
    /** The local day (ISO date) the heads-up was last posted, so a re-run of the worker never posts twice. */
    val gameDayNotifiedDay: String? = null,
) {
    companion object {
        const val DEFAULT_GAME_DAY_HOUR = 9

        /** The Heads-up time row's choices, as hours of the local day. */
        val GAME_DAY_HOURS = listOf(7, 8, 9, 10, 12, 15, 18)
    }
}

/**
 * App-level (this device, every profile) store for the two sports notices that are not in the
 * profile's `SportsConfig`: that class ships compiled in the `:core` AAR and cannot gain a field from
 * app code, so these live in their own tiny DataStore, the same shape as `OddsSettings`. Changing the
 * game-day rows also (re)schedules [GameDayWorker]: the setting and the work it drives are one thing,
 * so no screen can forget the second half.
 */
class SportsNoticeSettings(private val context: Context) {
    private object Keys {
        val GAME_DAY = booleanPreferencesKey("game_day")
        val GAME_DAY_HOUR = intPreferencesKey("game_day_hour")
        val SCORE_TICKER = booleanPreferencesKey("score_ticker")
        val GAME_DAY_NOTIFIED = stringPreferencesKey("game_day_notified_day")
    }

    val config: Flow<SportsNoticeConfig> = context.sportsNoticeDataStore.data.map { p ->
        SportsNoticeConfig(
            gameDay = p[Keys.GAME_DAY] ?: false,
            gameDayHour = p[Keys.GAME_DAY_HOUR]?.takeIf { it in 0..23 } ?: SportsNoticeConfig.DEFAULT_GAME_DAY_HOUR,
            scoreTicker = p[Keys.SCORE_TICKER] ?: false,
            gameDayNotifiedDay = p[Keys.GAME_DAY_NOTIFIED]?.takeIf { it.isNotEmpty() },
        )
    }.distinctUntilChanged()

    suspend fun current(): SportsNoticeConfig = config.first()

    suspend fun setGameDay(on: Boolean) {
        edit { it[Keys.GAME_DAY] = on }
        GameDayWorker.sync(context, current(), reschedule = true)
    }

    suspend fun setGameDayHour(hour: Int) {
        edit { it[Keys.GAME_DAY_HOUR] = hour.coerceIn(0, 23) }
        GameDayWorker.sync(context, current(), reschedule = true)
    }

    suspend fun setScoreTicker(on: Boolean) = edit { it[Keys.SCORE_TICKER] = on }

    suspend fun markGameDayNotified(day: String) = edit { it[Keys.GAME_DAY_NOTIFIED] = day }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.sportsNoticeDataStore.edit(block)
    }
}
