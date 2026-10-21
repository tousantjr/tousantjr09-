package tv.own.owntv.mobile.ui.screens.sports.odds

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.sports.SportsHttpException
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import java.time.LocalDate

/**
 * The betting line for a game on screen, from whichever source Settings → Sports → Betting odds
 * names. Read-only and informational: nothing is bet, nothing is predicted — a bookmaker's own
 * numbers, and the win chance they imply, shown beside the score.
 *
 * Frugal by design, because The Odds API's free tier is 500 requests a month: a league's lines are
 * kept for [CACHE_MS] and fetched only for the league of a game actually shown (the open match
 * page, Home's featured game), and one fetch carries every bookmaker Settings lists, so switching
 * the bookmaker or the comparison on and off re-reads the cache rather than the service; ESPN's
 * per-game line is kept just as long. When the key's quota reads
 * zero (or the service answers 429) no call is made again until the next local day, and Settings
 * says so rather than a toast. The key is never logged — nor is any message that could quote a URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OddsRepository(
    private val settings: OddsSettings,
    private val espn: EspnOddsSource,
    private val api: TheOddsApiSource,
) {

    /** What the last fetch came to, for Settings' status line. */
    sealed interface Status {
        data object Idle : Status
        data class Shown(val bookmaker: String) : Status
        data object NoLine : Status
        data object NotCovered : Status
        data object NoKey : Status
        data object KeyRejected : Status
        data object Exhausted : Status
        data class HttpError(val code: Int) : Status
        data object Unreachable : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private class Cached<T>(val at: Long, val value: T)
    private val espnCache = HashMap<String, Cached<OddsLine?>>()
    private val leagueCache = HashMap<String, Cached<LeagueOdds>>()

    /** Whether [match] can carry a line at all: a game over, postponed or cancelled shows none. */
    fun showable(match: Match): Boolean = match.state == MatchState.PRE || match.state == MatchState.IN

    /**
     * [match]'s line while the display is on, refreshed as the cache expires; null while off, for a
     * game that cannot carry one, or when the source has none. Starts over when the source, key or
     * bookmaker changes — not when a fetch records the quota, which would loop.
     */
    fun observe(match: Match?): Flow<OddsLine?> {
        if (match == null || !showable(match)) return flowOf(null)
        return settings.config.map { it.fetchKey }.distinctUntilChanged().flatMapLatest<OddsConfig, OddsLine?> { c ->
            if (!c.enabled) flowOf(null)
            else flow {
                while (true) {
                    emit(lineFor(match))
                    delay(CACHE_MS)
                }
            }
        }
    }

    /** One line, now, from the cache or the source. Serialised: the hero and the match page share a league. */
    suspend fun lineFor(match: Match): OddsLine? = mutex.withLock {
        val c = settings.config.first()
        if (!c.enabled || !showable(match)) return@withLock null
        when (c.source) {
            OddsSource.ESPN -> espnLine(match)
            OddsSource.ODDS_API -> apiLine(match, c)
        }
    }

    private suspend fun espnLine(match: Match): OddsLine? {
        val now = System.currentTimeMillis()
        espnCache[match.key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.value }
        val line = try {
            espn.odds(match)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SportsHttpException) {
            _status.value = Status.HttpError(e.code)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "ESPN odds unreachable: ${e.javaClass.simpleName}")
            _status.value = Status.Unreachable
            return null
        }
        espnCache[match.key] = Cached(now, line)
        _status.value = if (line != null) Status.Shown(line.bookmaker) else Status.NoLine
        return line
    }

    private suspend fun apiLine(match: Match, c: OddsConfig): OddsLine? {
        if (!c.hasKey) {
            _status.value = Status.NoKey
            return null
        }
        val sportKey = OddsSportKeys.of(match)
        if (sportKey == null) {
            _status.value = Status.NotCovered
            return null
        }
        val today = LocalDate.now().toString()
        if (c.exhaustedDay == today) {
            _status.value = Status.Exhausted
            return null
        }
        val now = System.currentTimeMillis()
        val league = leagueCache[sportKey]?.takeIf { now - it.at < CACHE_MS }?.value ?: run {
            val fetched = try {
                api.league(sportKey, c.effectiveKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SportsHttpException) {
                _status.value = when (e.code) {
                    401, 403 -> Status.KeyRejected
                    429 -> { settings.recordQuota(0, today); Status.Exhausted }
                    else -> Status.HttpError(e.code)
                }
                return null
            } catch (e: Exception) {
                Log.w(TAG, "The Odds API unreachable: ${e.javaClass.simpleName}")
                _status.value = Status.Unreachable
                return null
            }
            leagueCache[sportKey] = Cached(now, fetched)
            settings.recordQuota(fetched.requestsRemaining, if (fetched.requestsRemaining == 0) today else null)
            fetched
        }
        val hit = OddsMatcher.find(league.events, match)
        val line = hit?.let { OddsMatcher.line(it, match, now, c.bookmaker, c.compareBookmakers) }
        _status.value = if (line != null) Status.Shown(line.bookmaker) else Status.NoLine
        return line
    }

    private companion object {
        const val CACHE_MS = 30 * 60 * 1000L
        const val TAG = "OwnTV-Odds"
    }
}

/**
 * Core's league slug → The Odds API's `sport_key`, for the leagues it covers. Anything else (tennis,
 * golf, racing, the cups, most national leagues) gets no line from that source rather than a guess.
 */
object OddsSportKeys {
    private val BY_LEAGUE = mapOf(
        "nfl" to "americanfootball_nfl",
        "college-football" to "americanfootball_ncaaf",
        "nba" to "basketball_nba",
        "mens-college-basketball" to "basketball_ncaab",
        "wnba" to "basketball_wnba",
        "mlb" to "baseball_mlb",
        "nhl" to "icehockey_nhl",
        "eng.1" to "soccer_epl",
        "esp.1" to "soccer_spain_la_liga",
        "ger.1" to "soccer_germany_bundesliga",
        "ita.1" to "soccer_italy_serie_a",
        "fra.1" to "soccer_france_ligue_one",
        "uefa.champions" to "soccer_uefa_champs_league",
        "usa.1" to "soccer_usa_mls",
        "ufc" to "mma_mixed_martial_arts",
    )

    fun of(match: Match): String? = BY_LEAGUE[match.leagueSlug]
}
