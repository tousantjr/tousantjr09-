package tv.own.owntv.mobile.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsHttpException
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.Match
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One day of the Upcoming view: its calendar date and that day's matches across every followed league, kick-off order. */
data class UpcomingDay(val date: LocalDate, val matches: List<Match>)

/** What [SportsUpcomingViewModel] draws: the 7-day window, and whether anything failed to load. */
data class SportsUpcomingState(
    val config: SportsConfig = SportsConfig(),
    val days: List<UpcomingDay> = emptyList(),
    val loading: Boolean = true,
    /** At least one (league, day) fetch failed for a reason other than ESPN's own rate limit. */
    val hasFailures: Boolean = false,
    /** ESPN answered 403/429 on the last attempt: every fetch stands down until this time. */
    val pausedUntil: Long = 0L,
) {
    val paused: Boolean get() = pausedUntil > System.currentTimeMillis()
}

/**
 * Sports hub → Upcoming: the next 7 days (today through +6) for every followed league, grouped by day.
 *
 * Core's `Demand`/`Feed` model (`SportsRepository`/`SportsRefreshPolicy`) has no notion of "day N" —
 * it only ever asks ESPN for today and tomorrow — so this view bypasses that system entirely and calls
 * the already-injected [SportsSource] directly (ESPN's own parsing, `EspnSportsSource` under the hood;
 * bound in :core's own module, the same instance [tv.own.owntv.mobile.di.SportsModule]'s
 * `SportsViewModel` binding resolves), once per followed league per day. A small in-memory cache
 * ([CACHE_TTL_MS]) and a bounded [Semaphore] keep a large follow list from firing dozens of requests at
 * once, or refetching every time the screen reopens.
 *
 * A 403/429 from ESPN (rate limit) pauses every fetch for [PAUSE_MS], the same length core's own
 * `SportsRefreshPolicy.pauseAfter` uses, without pulling that class in (it is tied to `Demand`/`Feed`).
 * Any other failure marks [SportsUpcomingState.hasFailures] but never clears matches that already
 * loaded: one league's trouble never blanks the whole screen.
 *
 * Mirrors the TV app's own `SportsUpcomingViewModel` logic exactly; only the screen over it
 * ([SportsUpcomingScreen]) is mobile's own.
 */
class SportsUpcomingViewModel(
    private val settings: SettingsRepository,
    private val source: SportsSource,
) : ViewModel() {

    private data class CacheKey(val leagueKey: String, val date: LocalDate)
    private data class CacheEntry(val fetchedAt: Long, val matches: List<Match>)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** Set by a 403/429 answer; every fetch stands down until this time. */
    private val pausedUntilAtomic = AtomicLong(0L)

    private val _state = MutableStateFlow(SportsUpcomingState())
    val state: StateFlow<SportsUpcomingState> = _state

    /** Loads (or reloads from cache) the window. [force] bypasses the cache and the rate-limit pause. */
    fun load(force: Boolean = false) = viewModelScope.launch {
        val c = settings.activeSportsConfig.first()
        val followed = c.followedLeagues()
        if (followed.isEmpty()) {
            _state.value = SportsUpcomingState(config = c, loading = false)
            return@launch
        }
        val now = System.currentTimeMillis()
        val pausedUntil = pausedUntilAtomic.get()
        if (!force && pausedUntil > now) {
            _state.value = _state.value.copy(config = c, loading = false, pausedUntil = pausedUntil)
            return@launch
        }
        _state.value = _state.value.copy(config = c, loading = true)

        val leagues = followed.mapNotNull(SportsCatalog::league)
        val today = LocalDate.now()
        val dates = (0 until WINDOW_DAYS).map { today.plusDays(it.toLong()) }
        val hitRateLimit = AtomicBoolean(false)
        val anyFailure = AtomicBoolean(false)

        val byDate = coroutineScope {
            leagues.flatMap { league -> dates.map { date -> league to date } }
                .map { (league, date) ->
                    async(Dispatchers.IO) {
                        val key = CacheKey(league.key, date)
                        val cached = if (force) null else cache[key]?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }
                        val matches = cached?.matches ?: semaphore.withPermit {
                            try {
                                val fetched = source.scoreboard(league, date)
                                cache[key] = CacheEntry(now, fetched)
                                fetched
                            } catch (e: SportsHttpException) {
                                if (e.code == 403 || e.code == 429) {
                                    hitRateLimit.set(true)
                                    pausedUntilAtomic.set(now + PAUSE_MS)
                                } else {
                                    anyFailure.set(true)
                                }
                                // A stale cached answer beats nothing on a transient failure.
                                cache[key]?.matches.orEmpty()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                anyFailure.set(true)
                                cache[key]?.matches.orEmpty()
                            }
                        }
                        date to matches
                    }
                }
                .map { it.await() }
        }

        val matchesByDate = HashMap<LocalDate, MutableList<Match>>()
        byDate.forEach { (date, matches) -> matchesByDate.getOrPut(date) { mutableListOf() }.addAll(matches) }
        val days = dates.map { d ->
            UpcomingDay(
                d,
                matchesByDate[d].orEmpty().distinctBy { it.key }
                    .sortedWith(compareBy({ c.rankOf(it.sport, it.feed).first }, { c.rankOf(it.sport, it.feed).second }, { it.startMs })),
            )
        }
        _state.value = SportsUpcomingState(
            config = c,
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
        load(force = true)
    }

    private companion object {
        const val WINDOW_DAYS = 7
        const val MAX_CONCURRENT = 4
        const val CACHE_TTL_MS = 12 * 60_000L
        const val PAUSE_MS = 15 * 60_000L
    }
}
