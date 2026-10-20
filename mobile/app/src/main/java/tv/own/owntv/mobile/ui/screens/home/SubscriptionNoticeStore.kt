package tv.own.owntv.mobile.ui.screens.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.subscriptionNoticeDataStore by preferencesDataStore(name = "subscription_notice")

/**
 * When the subscription notice was last waved away on Home, and when its notification last went
 * out — both as a day number, so each happens at most once a day and comes back the next morning
 * while the warning still stands. Same tiny-DataStore shape as [HomeContentFilterSettings], for the
 * same reason: core's SettingsRepository is compiled and cannot gain a preference from here.
 */
class SubscriptionNoticeStore(private val context: Context) {
    private object Keys {
        val DISMISSED_DAY = longPreferencesKey("dismissed_day")
        val NOTIFIED_DAY = longPreferencesKey("notified_day")
    }

    /** The epoch day the notice was last dismissed on; -1 when never. */
    val dismissedDay: Flow<Long> = context.subscriptionNoticeDataStore.data.map { prefs -> prefs[Keys.DISMISSED_DAY] ?: -1L }

    suspend fun dismissForToday() {
        context.subscriptionNoticeDataStore.edit { prefs -> prefs[Keys.DISMISSED_DAY] = today() }
    }

    suspend fun notifiedDay(): Long = context.subscriptionNoticeDataStore.data.first()[Keys.NOTIFIED_DAY] ?: -1L

    suspend fun markNotifiedToday() {
        context.subscriptionNoticeDataStore.edit { prefs -> prefs[Keys.NOTIFIED_DAY] = today() }
    }

    companion object {
        fun today(): Long = LocalDate.now().toEpochDay()
    }
}
