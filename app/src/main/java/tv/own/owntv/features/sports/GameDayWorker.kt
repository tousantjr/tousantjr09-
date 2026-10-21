package tv.own.owntv.features.sports

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * "Your teams play today": once a day, at the hour the user chose (Settings → Sports → Goal alerts →
 * Game day), one notification listing the followed teams' games of the day with their kick-off times
 * — never a score, so Spoiler-free has nothing to hide. A tap opens the first game's page.
 *
 * Built by WorkManager's own reflective fallback, like the phone's subscription check: core's
 * `KoinWorkerFactory` answers null for a class it does not know, so the dependencies come out of
 * Koin directly. The followed leagues' boards are fetched afresh (a few small requests on the shared
 * client), because the stored board is only as fresh as the last time the app was opened. At most
 * one notification per day ([SportsNoticeSettings.markGameDayNotified]); none on a kids profile, none
 * while notifications are off for the app.
 */
class GameDayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.retry()
        val notices = koin.get<SportsNoticeSettings>()
        val config = notices.current()
        if (!config.gameDay) return Result.success()
        val today = LocalDate.now()
        if (config.gameDayNotifiedDay == today.toString()) return Result.success()
        val settings = koin.get<SettingsRepository>()
        val profileId = settings.activeProfileIdNow()
        val kids = runCatching { koin.get<ProfileDao>().getById(profileId)?.isKids }.getOrNull() == true
        if (kids) return Result.success()
        val sports = settings.activeSportsConfig.first()
        if (sports.teams.isEmpty()) return Result.success()
        val games = try {
            GameDay.todaysGames(koin.get<SportsSource>(), sports, today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
        if (games.isEmpty()) {
            notices.markGameDayNotified(today.toString())
            return Result.success()
        }
        if (GameDayNotifier.post(applicationContext, koin.get<LocaleStore>(), games)) notices.markGameDayNotified(today.toString())
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sports_game_day"
        private const val MAX_ATTEMPTS = 3

        /**
         * Keep the schedule in step with the setting: on → a daily run from the next chosen hour, off →
         * nothing queued. KEEP leaves a queued run alone (app start); [reschedule] (a changed hour or a
         * fresh switch-on) drops it first, so the next run lands on the new hour.
         */
        fun sync(context: Context, config: SportsNoticeConfig, reschedule: Boolean = false) {
            val manager = WorkManager.getInstance(context)
            if (!config.gameDay) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            if (reschedule) manager.cancelUniqueWork(WORK_NAME)
            val request = PeriodicWorkRequestBuilder<GameDayWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(GameDay.untilNext(config.gameDayHour, LocalDateTime.now()).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The pure part of the game-day heads-up: which leagues to ask, which of their matches count, and when the next run is. */
internal object GameDay {
    /** With "All sports" on there is no league list to ask; the followed teams' sports' leagues stand in, capped. */
    private const val MAX_LEAGUES = 20

    /** Time until the next [hour] o'clock, local: later today, else tomorrow. Never zero, so a run is never queued for the past. */
    fun untilNext(hour: Int, now: LocalDateTime): Duration {
        var at = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return Duration.between(now, at).let { if (it.isNegative || it.isZero) Duration.ofMinutes(1) else it }
    }

    /** The league keys whose boards are worth fetching for [config]'s followed teams. */
    fun leagueKeys(config: SportsConfig): List<String> {
        val keys = if (!config.allSports) config.leagues else {
            val sports = config.teams.map { it.sport }.toSet()
            SportsCatalog.leagues.filter { it.sport in sports }.map { it.key }
        }
        return keys.distinct().take(MAX_LEAGUES)
    }

    /** [matches] that one of [config]'s teams plays on [today] (local), still to come or under way, soonest first. */
    fun pick(matches: List<Match>, config: SportsConfig, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Match> {
        val teamKeys = config.teams.map { it.key }.toSet()
        if (teamKeys.isEmpty()) return emptyList()
        return matches
            .filter { m -> m.state == MatchState.PRE || m.state == MatchState.IN }
            .filter { m -> Instant.ofEpochMilli(m.startMs).atZone(zone).toLocalDate() == today }
            .filter { m -> m.competitors.any { FollowedTeam(m.sport, it.team.id, "").key in teamKeys } }
            .distinctBy { it.key }
            .sortedBy { it.startMs }
    }

    /** Today's games of the followed teams, fetched league by league; a league that fails to load is skipped. */
    suspend fun todaysGames(source: SportsSource, config: SportsConfig, today: LocalDate): List<Match> {
        val fresh = mutableListOf<Match>()
        var failures = 0
        var asked = 0
        for (key in leagueKeys(config)) {
            val league = SportsCatalog.league(key) ?: continue
            asked++
            try {
                fresh += source.scoreboard(league, today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
            }
        }
        // Nothing loaded at all: that is the network, not an empty day — let the worker retry.
        if (asked > 0 && failures == asked) throw IllegalStateException()
        return pick(fresh, config, today)
    }
}

/** The game-day notification itself; the builder and permission dance mirror the phone's `SubscriptionNotifier`. */
object GameDayNotifier {
    private const val CHANNEL_ID = "mptv_game_day"
    private const val NOTIFICATION_ID = 0x6A4D

    /** True when a notification was actually posted (so the day can be marked). */
    fun post(context: Context, localeStore: LocaleStore, games: List<Match>): Boolean {
        if (games.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val l = AppLocale.wrap(context, localeStore.currentTag.value)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, l.getString(R.string.sports_game_day_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val times = DateFormat.getTimeInstance(DateFormat.SHORT)
        val sep = l.getString(R.string.content_epg_bits_separator)
        val lines = games.map { m ->
            val at = if (m.state == MatchState.IN) l.getString(R.string.sports_game_day_now) else times.format(Date(m.startMs))
            listOf(at, MatchNotifications.pair(l, m), m.leagueName).joinToString(sep)
        }
        val title = l.resources.getQuantityString(R.plurals.sports_game_day_title, games.size, games.size)
        // Into the app at the first game — through the enabled launcher alias, never MainActivity itself.
        val open = Intent(Intent.ACTION_MAIN)
            .setComponent(AppIconSwitcher.launchComponent(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MatchNotifications.EXTRA_MATCH_KEY, games.first().key)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        compat.notify(NOTIFICATION_ID, notification)
        return true
    }
}
