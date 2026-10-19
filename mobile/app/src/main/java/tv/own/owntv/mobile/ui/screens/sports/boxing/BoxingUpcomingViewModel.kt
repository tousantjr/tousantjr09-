package tv.own.owntv.mobile.ui.screens.sports.boxing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.reminder.ReminderManager
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.SportsHttpException
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchTier
import tv.own.owntv.mobile.ui.screens.sports.SportsViewModel
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One day of the boxing schedule: its calendar date and that day's bouts, start-time order. */
data class BoxingDay(val date: LocalDate, val bouts: List<BoxingBout>)

/** What [BoxingUpcomingViewModel] draws: the 7-day window, and whether anything failed to load. */
data class BoxingUpcomingState(
    val days: List<BoxingDay> = emptyList(),
    val loading: Boolean = true,
    /** At least one day's fetch failed for a reason other than ESPN's own rate limit — including a
     *  wrong URL/schema guess (a non-2xx answer surfaces exactly like a rate limit would not). */
    val hasFailures: Boolean = false,
    /** ESPN answered 403/429 on the last attempt: every fetch stands down until this time. */
    val pausedUntil: Long = 0L,
    /** Start times of the profile's boxing reminders, keyed the same way the rest of Sports keys
     *  them ([SportsViewModel.reminderKey]), so a card can show "Reminder". */
    val reminders: Set<String> = emptySet(),
) {
    val paused: Boolean get() = pausedUntil > System.currentTimeMillis()
}

/** Where a bout's "Find channels" stands — searched only when asked, one bout at a time. */
sealed interface BoxingChannelSearch {
    data object NotSearched : BoxingChannelSearch
    data object Searching : BoxingChannelSearch
    data class Found(val options: List<WatchOption>) : BoxingChannelSearch
}

/**
 * Boxing's own schedule view: the next 7 days (today through +6) of ESPN's boxing scoreboard, grouped
 * by day. There is no core `Sport.BOXING`, so this is not a view onto core's Demand/Feed board the way
 * [tv.own.owntv.mobile.ui.screens.sports.SportsUpcomingScreen] is for every other sport; it owns its
 * whole pipeline — [BoxingSource] for the fetch, this class for the cache/backoff/state, [BoxingScreen]
 * for the UI. Deliberately does **not** route through [tv.own.owntv.core.sports.SportsRepository],
 * `EspnSportsSource` or `SportsMatchScreen`: all three build their ESPN calls and a match's identity
 * from core's `Sport` enum, which has no boxing case, so forcing boxing through them would either not
 * compile or silently fetch the wrong sport. See `BoxingSource`'s doc for the full reasoning.
 *
 * Mirrors the TV app's own `BoxingUpcomingViewModel` shape closely on purpose — the in-memory TTL
 * cache, the bounded [Semaphore], the 403/429 backoff — so boxing behaves exactly like the rest of
 * Sports' "beyond today" views on both apps, even though it is fetched completely separately.
 *
 * "Find channels" and "Remind me" skip core's match-aware [tv.own.owntv.core.sports.WatchReader] /
 * [tv.own.owntv.core.sports.SportsAlertsSetup] the same way: both are built around core's `Match`,
 * which a bout is not. Instead:
 *  - channels are found with the same full-text channel search `WatchReader`'s own "likely"/"event"
 *    tiers use underneath ([ChannelDao.searchListDetailedFts]), fed fighter names and ESPN's named
 *    broadcasters directly — see [search].
 *  - reminders call [ReminderManager.add] directly. It only ever asked for a start time, a title and a
 *    channel — never a `Match` — so it is safe to call for a bout exactly as the real "Remind me" calls
 *    it for a match ([tv.own.owntv.core.sports.SportsAlertsSetup.remind] is only a thin, Match-shaped
 *    wrapper around the same call).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoxingUpcomingViewModel(
    private val context: Context,
    private val localeStore: LocaleStore,
    private val settings: SettingsRepository,
    private val source: BoxingSource,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val reminders: ReminderManager,
) : ViewModel() {

    private data class CacheEntry(val fetchedAt: Long, val bouts: List<BoxingBout>)

    private val cache = ConcurrentHashMap<LocalDate, CacheEntry>()
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** Set by a 403/429 answer; every fetch stands down until this time. */
    private val pausedUntilAtomic = AtomicLong(0L)

    private val _boxing = MutableStateFlow(BoxingUpcomingState())

    private val reminderKeys = settings.activeProfileId.flatMapLatest { pid ->
        if (pid < 0) flowOf(emptySet())
        else reminders.observe(pid).map { list -> list.map { SportsViewModel.reminderKey(it.startMs, it.title) }.toSet() }
    }

    val state: StateFlow<BoxingUpcomingState> = combine(_boxing, reminderKeys) { b, r -> b.copy(reminders = r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoxingUpcomingState())

    /** One bout at a time's channel search, kept for the life of the screen (never auto-searched). */
    private val _channels = MutableStateFlow<Map<String, BoxingChannelSearch>>(emptyMap())
    val channels: StateFlow<Map<String, BoxingChannelSearch>> = _channels

    /** Loads (or reloads from cache) the window. [force] bypasses the cache and the rate-limit pause. */
    fun load(force: Boolean = false) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val pausedUntil = pausedUntilAtomic.get()
        if (!force && pausedUntil > now) {
            _boxing.value = _boxing.value.copy(loading = false, pausedUntil = pausedUntil)
            return@launch
        }
        _boxing.value = _boxing.value.copy(loading = true)

        val today = LocalDate.now()
        val dates = (0 until WINDOW_DAYS).map { today.plusDays(it.toLong()) }
        val hitRateLimit = AtomicBoolean(false)
        val anyFailure = AtomicBoolean(false)

        val byDate = coroutineScope {
            dates.map { date ->
                async(Dispatchers.IO) {
                    val cached = if (force) null else cache[date]?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }
                    val bouts = cached?.bouts ?: semaphore.withPermit {
                        try {
                            val fetched = source.scoreboard(date)
                            cache[date] = CacheEntry(now, fetched)
                            fetched
                        } catch (e: SportsHttpException) {
                            if (e.code == 403 || e.code == 429) {
                                hitRateLimit.set(true)
                                pausedUntilAtomic.set(now + PAUSE_MS)
                            } else {
                                anyFailure.set(true)
                            }
                            // A stale cached answer beats nothing on a transient failure.
                            cache[date]?.bouts.orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            anyFailure.set(true)
                            cache[date]?.bouts.orEmpty()
                        }
                    }
                    date to bouts
                }
            }.map { it.await() }
        }

        val days = byDate.map { (d, bouts) -> BoxingDay(d, bouts.distinctBy { it.id }.sortedBy { it.startMs }) }
        _boxing.value = BoxingUpcomingState(
            days = days,
            loading = false,
            hasFailures = anyFailure.get(),
            pausedUntil = pausedUntilAtomic.get(),
        )
    }

    /** The header's refresh / a failure banner's Retry: forgets the cache and tries again now. */
    fun retry() {
        cache.clear()
        pausedUntilAtomic.set(0L)
        _channels.value = emptyMap()
        load(force = true)
    }

    /** Find channels: search this bout's channels (only this bout, only now). */
    fun findChannels(bout: BoxingBout) = viewModelScope.launch {
        _channels.value = _channels.value + (bout.id to BoxingChannelSearch.Searching)
        val options = search(bout)
        _channels.value = _channels.value + (bout.id to BoxingChannelSearch.Found(options))
    }

    /** "Remind me": the best found channel's programme reminder, the same convention the rest of
     *  Sports' "Remind me" uses (`SportsAlertsSetup.remind`'s own `channels.firstOrNull()`). Does
     *  nothing without a channel — a programme reminder has nowhere to point without one, and boxing
     *  has no fallback "no channel yet" notification path (that path, `MatchAlertScheduler`, is built
     *  around core's `Match`; see the class doc). */
    fun remind(bout: BoxingBout, options: List<WatchOption>) = viewModelScope.launch {
        val best = options.firstOrNull() ?: return@launch
        val pid = settings.activeProfileIdNow()
        if (pid < 0) return@launch
        val lead = settings.reminderLeadMinutes.first()
        reminders.add(
            profileId = pid,
            channelId = best.channel.id,
            channelName = best.facets.name,
            epgChannelId = best.channel.epgChannelId,
            title = matchupTitle(bout),
            startMs = bout.startMs,
            stopMs = bout.startMs + BOUT_LENGTH_MS,
            leadMinutes = lead,
        )
    }

    /**
     * "Fighter A – Fighter B", formatted from core's own `sports_pair` resource — the exact string
     * [BoxingScreen]'s `matchupText` composable shows, so the reminder this stores as `title` and the
     * "Reminder" badge the screen looks up by [SportsViewModel.reminderKey] agree on the same text, in
     * whatever language the device is in. Falls back to the event/card's own name for a bout that
     * didn't parse two fighters, same as `MatchNotifications.pair` falls back to `match.title`.
     */
    private fun matchupTitle(bout: BoxingBout): String {
        val names = matchupNames(bout)
        if (names == null) return bout.eventTitle
        val localized = AppLocale.wrap(context, localeStore.currentTag.value)
        return localized.getString(tv.own.owntv.mobile.R.string.sports_pair, names.first, names.second)
    }

    /**
     * [bout]'s channels: a full-text search of the active profile's live channels (the same
     * `channels_fts` table and query shape `WatchReader`'s own tiers use), never the guide — boxing
     * bouts are almost never named in a provider's EPG the way a league fixture is, so there is no
     * confirmed/guide tier here, only:
     *  - EVENT: a channel naming words from *both* fighters (rare, but some providers do run a
     *    dedicated PPV channel named for the fight).
     *  - LIKELY: a channel naming one of ESPN's listed broadcasters, or a generic boxing/PPV word.
     * Either query failing (a malformed FTS query, a DB error) degrades to no result for that tier,
     * never a crash.
     */
    private suspend fun search(bout: BoxingBout): List<WatchOption> = withContext(Dispatchers.Default) {
        val pid = settings.activeProfileIdNow()
        if (pid < 0) return@withContext emptyList()
        val sourceIds = runCatching { activeSourceIds(settings, sourceDao, pid, MediaType.LIVE) }.getOrDefault(emptyList())
        if (sourceIds.isEmpty()) return@withContext emptyList()

        val fighterGroups = bout.fighters.mapNotNull { f ->
            asciiWords(f.name).takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "$it*" }?.let { "($it)" }
        }
        val event = if (fighterGroups.size >= 2) {
            runCatching { channelDao.searchListDetailedFts(fighterGroups.joinToString(" "), sourceIds, MAX_RESULTS) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val likelyWords = (bout.broadcasters.flatMap(::asciiWords) + GENERIC_WORDS).distinct()
        val likely = if (likelyWords.isNotEmpty()) {
            runCatching { channelDao.searchListDetailedFts(likelyWords.joinToString(" OR ") { "$it*" }, sourceIds, MAX_RESULTS) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val out = LinkedHashMap<Long, WatchOption>()
        event.forEach { r -> out.putIfAbsent(r.channel.id, WatchOption(r.channel, WatchTier.EVENT)) }
        likely.forEach { r -> if (r.channel.id !in out) out[r.channel.id] = WatchOption(r.channel, WatchTier.LIKELY) }
        out.values.sortedBy { it.tier.ordinal }
    }

    /** Words worth searching a channel name for: ASCII, 3+ letters, lower-cased — short enough to
     *  catch "Ali" but long enough to skip initials and "Jr"/"Sr". */
    private fun asciiWords(name: String): List<String> =
        WORD.findAll(name).map { it.value.lowercase() }.filter { it !in STOPWORDS }.toList()

    private companion object {
        const val WINDOW_DAYS = 7
        const val MAX_CONCURRENT = 4
        const val CACHE_TTL_MS = 12 * 60_000L
        const val PAUSE_MS = 15 * 60_000L
        const val MAX_RESULTS = 60

        /** A reminder's programme slot when there is no real stop time to go by — boxing cards run
         *  long and unpredictably, so this is deliberately generous (core's own MMA/match fallback is
         *  150 min; boxing PPV cards commonly run longer). */
        const val BOUT_LENGTH_MS = 240 * 60_000L

        val WORD = Regex("[A-Za-z]{3,}")
        val STOPWORDS = setOf("jnr", "snr", "the", "and")
        val GENERIC_WORDS = listOf("boxing", "ppv", "dazn", "showtime", "espn", "fight", "box")
    }
}
