package tv.own.owntv.features.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.update.UpdateManager
import java.util.concurrent.TimeUnit

/**
 * "MPTV vX is ready — tap to install": the background half of the update check. Twice a day, while
 * the device is online, core's [UpdateManager] asks GitHub Releases for a newer build — the very same
 * check the startup card and Settings → Check for updates run — and when one is out, one notification
 * says so. A tap opens the app, which shows the usual [UpdateDialog] and starts the download straight
 * away, so the only other step is Android's own "Install?" confirmation. A silent install is not
 * possible for a normal app, and Android 10+ refuses to start an activity from the background, hence
 * the notification in between.
 *
 * Follows the existing "Check for updates on startup" setting: with it off, nothing is checked here
 * either. Each version is announced once ([UpdateNoticeStore]); the in-app card still comes back on
 * the next launch, as before. Built by WorkManager's reflective fallback like [GameDayWorker][tv.own.owntv.features.sports.GameDayWorker],
 * so the dependencies come out of Koin directly.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        if (!koin.get<SettingsRepository>().updateCheckOnStart.first()) return Result.success()
        val manager = koin.get<UpdateManager>()
        // The app is on screen and already checking or downloading: that run speaks for itself.
        val before = manager.state.value
        if (before is UpdateManager.State.Checking || before is UpdateManager.State.Downloading) return Result.success()
        manager.check()
        val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) { manager.state.first { BackgroundUpdate.isSettled(it) } }
        val available = result as? UpdateManager.State.Available
        if (available == null) {
            // Leave nothing behind for the UI: a background "up to date" or failure means nothing on screen.
            if (before is UpdateManager.State.Idle && result != null) manager.reset()
            return if (result is UpdateManager.State.Failed && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        val store = UpdateNoticeStore(applicationContext)
        val version = available.info.version
        if (!BackgroundUpdate.shouldNotify(version, store.lastNotified())) return Result.success()
        if (UpdateNotifier.post(applicationContext, koin.get<LocaleStore>(), version)) store.markNotified(version)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "mptv_update_check"
        private const val MAX_ATTEMPTS = 3
        private const val INTERVAL_HOURS = 12L

        /** A slow GitHub answer must not hold the worker forever; the next run tries again. */
        private const val CHECK_TIMEOUT_MS = 60_000L

        /** Queue the twice-daily check once; KEEP leaves an already queued one (every app start) alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The pure part of the background check: when a run is finished, and whether a version is news. */
internal object BackgroundUpdate {
    /** A check has ended once it lands on one of these; Idle/Checking/Downloading are still on the way. */
    fun isSettled(state: UpdateManager.State): Boolean =
        state is UpdateManager.State.Available || state is UpdateManager.State.UpToDate || state is UpdateManager.State.Failed

    /** "v1.0.18", "V1.0.18 " and "1.0.18" are the same release. */
    fun normalize(version: String?): String = version.orEmpty().trim().trimStart('v', 'V')

    /** True when [version] is a real version that has not been announced yet ([lastNotified] is the last one that was). */
    fun shouldNotify(version: String?, lastNotified: String?): Boolean {
        val v = normalize(version)
        return v.isNotEmpty() && v != normalize(lastNotified)
    }
}

/** Which version the last notification was about, so a release is announced once and not every 12 hours. */
internal class UpdateNoticeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastNotified(): String? = prefs.getString(KEY_LAST_NOTIFIED, null)

    fun markNotified(version: String) {
        prefs.edit().putString(KEY_LAST_NOTIFIED, BackgroundUpdate.normalize(version)).apply()
    }

    private companion object {
        const val PREFS_NAME = "mptv_update_notice"
        const val KEY_LAST_NOTIFIED = "last_notified_version"
    }
}

/**
 * The notification tap's hand-over to the running UI: MainActivity raises it, the shell takes it
 * (shows [UpdateDialog], starts the download) and lowers it again. Process-wide, like the sports
 * page request, because the activity may be created before the shell is composed.
 */
object UpdateInstallRequest {
    val pending = MutableStateFlow(false)
}

/** The "ready to install" notification; the builder and permission dance mirror the game-day heads-up. */
object UpdateNotifier {
    private const val CHANNEL_ID = "mptv_update"
    private const val NOTIFICATION_ID = 0x0D7E

    /** Intent extra that marks a launch from this notification. */
    const val EXTRA_INSTALL_UPDATE = "tv.own.owntv.extra.INSTALL_UPDATE"

    /** True when a notification was actually posted (so the version can be marked as announced). */
    fun post(context: Context, localeStore: LocaleStore, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val l = AppLocale.wrap(context, localeStore.currentTag.value)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, l.getString(R.string.update_ready_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Into the app through the enabled launcher alias, never MainActivity itself.
        val open = Intent(Intent.ACTION_MAIN)
            .setComponent(AppIconSwitcher.launchComponent(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_INSTALL_UPDATE, true)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(l.getString(R.string.update_ready_title, l.getString(R.string.app_name), BackgroundUpdate.normalize(version)))
            .setContentText(l.getString(R.string.update_ready_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        compat.notify(NOTIFICATION_ID, notification)
        return true
    }
}
