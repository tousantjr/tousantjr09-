package tv.own.owntv.mobile.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.AutoRefresh
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import java.util.concurrent.TimeUnit

/**
 * MPTV: on top of "Refresh on startup", every guide that auto-refreshes at all is also re-synced in
 * the background every [INTERVAL_HOURS] hours while the device is online, even if the app is never
 * restarted. A guide set to Off is left alone.
 *
 * Jobs carry [AutoRefresh.REASON], so the EPG worker holds them back while something is playing,
 * and use KEEP, so a guide already syncing is not started twice. Built by WorkManager's reflective
 * fallback (like UpdateCheckWorker), so dependencies come straight out of Koin.
 */
class EpgBackgroundRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val settings = koin.get<SettingsRepository>()
        val store = koin.get<EpgSourceStore>()
        val scheduler = koin.get<EpgSyncScheduler>()
        val epgDao = koin.get<EpgDao>()
        val modes = settings.epgAutoRefresh.first()
        val now = System.currentTimeMillis()
        store.getAll().forEach { src ->
            if (!EpgBackgroundRefresh.isDue(modes[src.id], src.lastSyncAt, now)) return@forEach
            Log.i(TAG, "background refresh guide ${src.id}")
            scheduler.enqueueSync(
                src.id,
                reason = AutoRefresh.REASON,
                baseProgrammes = epgDao.countForSources(listOf(src.id)),
                policy = ExistingWorkPolicy.KEEP,
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MptvEpgBackground"
        private const val WORK_NAME = "mptv_epg_background_refresh"
        const val INTERVAL_HOURS = 3L

        /** Queue the periodic refresh once; KEEP leaves an already queued one (every app start) alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpgBackgroundRefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The pure part: whether a guide is due for a background refresh. */
object EpgBackgroundRefresh {
    /** A little under the interval, so WorkManager's timing slack never skips a whole round. */
    private const val MIN_AGE_MS = (EpgBackgroundRefreshWorker.INTERVAL_HOURS * 60 - 30) * 60_000L

    fun isDue(refresh: EpgRefresh?, lastSyncAt: Long?, now: Long): Boolean {
        if (refresh == null || refresh.mode == EpgAutoRefresh.OFF) return false
        return lastSyncAt == null || now - lastSyncAt >= MIN_AGE_MS
    }
}
