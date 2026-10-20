package tv.own.owntv.mobile.subscription

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.home.ExpiryWarning
import tv.own.owntv.mobile.ui.screens.home.SubscriptionExpiry
import tv.own.owntv.mobile.ui.screens.home.SubscriptionNoticeStore
import tv.own.owntv.mobile.ui.screens.home.body
import tv.own.owntv.mobile.ui.screens.home.title
import java.util.concurrent.TimeUnit

/**
 * Once a day, with the app closed: the same subscription check Home makes, as a notification when
 * a playlist's account ends within a week. A phone is in a pocket, not on Home, so a notice only on
 * Home would be seen on the day the channels stop.
 *
 * Built by WorkManager's own reflective fallback — core's `KoinWorkerFactory` answers `null` for a
 * class it does not know, and WorkManager then wants a `(Context, WorkerParameters)` constructor —
 * so the dependencies come out of Koin directly. At most one notification per day
 * ([SubscriptionNoticeStore.notifiedDay]), and none at all while notifications are off for the app.
 */
class SubscriptionExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val sources = runCatching { koin.get<SourceDao>().getAllOnce() }.getOrElse { return Result.retry() }
        val warning = koin.get<SubscriptionExpiry>().warningFor(sources) ?: return Result.success()
        val store = koin.get<SubscriptionNoticeStore>()
        if (store.notifiedDay() == SubscriptionNoticeStore.today()) return Result.success()
        if (SubscriptionNotifier.post(applicationContext, warning)) store.markNotifiedToday()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "subscription_expiry"

        /** Idempotent: KEEP leaves an already scheduled check (and its timing) alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionExpiryWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The subscription notification itself; the builder and permission dance mirror `ReminderNotifier`. */
object SubscriptionNotifier {

    private const val CHANNEL_ID = "mptv_subscription"
    private const val NOTIFICATION_ID = 0x5EB5

    /** True when a notification was actually posted (so the day can be marked). */
    fun post(context: Context, warning: ExpiryWarning): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.subscription_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(warning.title(context))
            .setContentText(warning.body(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(warning.body(context)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        compat.notify(NOTIFICATION_ID, notification)
        return true
    }
}
