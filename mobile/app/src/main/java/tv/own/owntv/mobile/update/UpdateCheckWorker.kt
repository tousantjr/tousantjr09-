package tv.own.owntv.mobile.update

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import org.koin.core.context.GlobalContext
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.settings.MobileUpdateCheck
import java.util.concurrent.TimeUnit

/**
 * "MPTV vX is ready — tap to install", on the phone: twice a day, while online, the same check
 * Settings → Diagnostics → Check for updates makes ([MobileUpdateCheck], the mobile-v* releases), and
 * one notification when a newer build is out. A tap opens the app, which hands the mobile-latest APK
 * to the browser at once — the same Download the Diagnostics page offers — so what is left is
 * Android's own download and "Install?" confirmation. A silent install is not possible for a normal
 * app, and Android 10+ refuses to start an activity from the background, hence the notification.
 *
 * The phone has no "check on startup" switch (its update check lives in Diagnostics only), so this
 * runs for everyone; Android's per-channel notification setting ("App updates") turns it off. Each
 * version is announced once ([UpdateNoticeStore]). Built by WorkManager's reflective fallback, like
 * the subscription check, so the dependencies come out of Koin directly.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val available = when (val result = koin.get<MobileUpdateCheck>().check()) {
            is MobileUpdateCheck.Result.Available -> result
            MobileUpdateCheck.Result.UpToDate -> return Result.success()
            MobileUpdateCheck.Result.Failed -> return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        val store = UpdateNoticeStore(applicationContext)
        if (!BackgroundUpdate.shouldNotify(available.version, store.lastNotified())) return Result.success()
        if (UpdateNotifier.post(applicationContext, available.version)) store.markNotified(available.version)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "mptv_update_check"
        private const val MAX_ATTEMPTS = 3
        private const val INTERVAL_HOURS = 12L

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

/** The pure part of the background check: whether a version is news. */
internal object BackgroundUpdate {
    /** "v1.1.10", "V1.1.10 " and "1.1.10" are the same release. */
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

/** The "ready to install" notification; the builder and permission dance mirror the subscription notice. */
object UpdateNotifier {
    private const val CHANNEL_ID = "mptv_update"
    private const val NOTIFICATION_ID = 0x0D7E

    /** Intent extra that marks a launch from this notification. */
    const val EXTRA_INSTALL_UPDATE = "tv.own.owntv.mobile.extra.INSTALL_UPDATE"

    /** True when a notification was actually posted (so the version can be marked as announced). */
    fun post(context: Context, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_ready_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.putExtra(EXTRA_INSTALL_UPDATE, true)
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(context.getString(R.string.update_ready_title, context.getString(R.string.app_name), BackgroundUpdate.normalize(version)))
            .setContentText(context.getString(R.string.update_ready_text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        compat.notify(NOTIFICATION_ID, notification)
        return true
    }

    /**
     * The app was opened from the notification: hand the latest phone APK to the browser, as the
     * Diagnostics page's Download does. True when the intent was this notification's (handled or not).
     */
    fun handleTap(activity: Activity, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) != true) return false
        intent.removeExtra(EXTRA_INSTALL_UPDATE)
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MobileUpdateCheck.latestApkUrl())))
        }
        return true
    }
}
