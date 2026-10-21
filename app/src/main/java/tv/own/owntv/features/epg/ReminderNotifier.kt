package tv.own.owntv.features.epg

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.reminder.ReminderManager
import tv.own.owntv.core.settings.SettingsRepository
import java.text.DateFormat
import java.util.Date

/**
 * Programme reminders reaching a television that is not showing the app.
 *
 * Core wakes the app when a reminder is due ([ReminderManager.due]); the shell draws its prompt over
 * whatever is on screen while a screen of the app is open. It never was open when a stick sat on the
 * launcher or in another app, so a reminder set from the guide or from Home's featured game simply
 * passed by. Now, with no screen of the app open, the reminder becomes a notification (Fire TV and
 * Android TV show them as a heads-up); a tap opens the app, where the shell asks or switches as the
 * Programme reminders setting says. The phone app has had this since its first release.
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "mptv_reminders"
    private var started = 0

    /** Whether a screen of the app is open; the shell's prompt covers that case. */
    val inForeground: Boolean get() = started > 0

    private val notified = HashSet<Long>()

    fun start(app: Application, scope: CoroutineScope, reminders: ReminderManager, settings: SettingsRepository) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // Left with a prompt unanswered: what is still due goes to the notification shade.
                if (started == 0) reminders.due.value.filter { it.id !in notified }.forEach { r ->
                    post(app, r)
                    notified += r.id
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        scope.launch {
            reminders.due.collect { due ->
                val mode = settings.reminderMode.first()
                // With a screen open the shell handles every mode itself (its prompt, its switch, its line).
                if (inForeground) return@collect
                due.filter { it.id !in notified }.forEach { r ->
                    post(app, r)
                    notified += r.id
                    // Notify only: the notification is the whole answer, so the reminder is spent.
                    if (mode == SettingsRepository.ReminderMode.NOTIFY) reminders.dismiss(r.id)
                }
            }
        }
    }

    /** The prompt was answered in the app: its notification goes too. */
    fun cancel(context: Context, id: Long) = NotificationManagerCompat.from(context).cancel(id.toInt())

    private fun post(context: Context, r: ReminderEntity) {
        // Refused notification permission (Android 13+): the reminder still comes up in the app when it is opened.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.settings_reminders), NotificationManager.IMPORTANCE_HIGH),
        )
        val times = DateFormat.getTimeInstance(DateFormat.SHORT)
        val text = listOf(
            context.getString(R.string.content_live_time_range_plain, times.format(Date(r.startMs)), times.format(Date(r.stopMs))),
            r.channelName,
        ).joinToString(context.getString(R.string.content_epg_bits_separator))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, r.id.toInt(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(r.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(r.id.toInt(), notification)
    }
}
