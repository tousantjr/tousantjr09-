package tv.own.owntv.features.sports

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.AlertKind
import tv.own.owntv.core.sports.AlertSnapshot
import tv.own.owntv.core.sports.Demand
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.MatchAlert
import tv.own.owntv.core.sports.MatchAlertDiff
import tv.own.owntv.core.sports.SportsMemory
import tv.own.owntv.core.sports.SportsRepository
import tv.own.owntv.core.sports.WatchFilter
import tv.own.owntv.core.sports.WatchList
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchReader
import tv.own.owntv.core.sports.model.Match
import java.util.Locale

/** A channel started from a match: the match, and every channel showing it, best first (after the filter). */
data class SportsSession(val match: Match, val options: List<WatchOption>)

/**
 * What the player knows about Sports: which match the playing channel was started from, its
 * score while the info bar is open, and goals in *other* followed matches for the toast. Nothing here
 * touches playback; with no session, or ESPN unreachable, the player is exactly the plain Live player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsPlayback(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val repository: SportsRepository,
    private val watchReader: WatchReader,
    /** Same-tier channels in [channelsFor] are reordered by how they have behaved here, so "best" means reliable. */
    private val health: tv.own.owntv.features.health.ChannelHealthStore,
    /** Score delay (Settings → Sports): read on each score, so a change counts from the next one. */
    private val notices: SportsNoticeSettings,
    /** Fix channel per team / network, before the competition's remembered channel. */
    private val pins: SportsPins,
) {
    @Volatile private var delayMs = 0L

    /** This flow, every value [ScoreDelay]-late; the setting is followed while it is collected. */
    private fun <T> Flow<T>.scoreDelayed(): Flow<T> = kotlinx.coroutines.flow.channelFlow {
        delayMs = notices.current().scoreDelaySec * 1000L
        val follow = launch { notices.config.collect { delayMs = it.scoreDelaySec * 1000L } }
        this@scoreDelayed.delayedBy { delayMs }.collect { send(it) }
        follow.cancel()
    }

    private val _session = MutableStateFlow<SportsSession?>(null)
    val session: StateFlow<SportsSession?> = _session.asStateFlow()

    /** The match page the player's *Match* button asked for, and the channel Back from it returns to. */
    val pendingMatch = MutableStateFlow<Pair<Match, ChannelEntity?>?>(null)

    /** A match an alert's tap asked for ([tv.own.owntv.core.sports.MatchNotifications.EXTRA_MATCH_KEY]); the shell opens it. */
    val openMatchKey = MutableStateFlow<String?>(null)

    /** The team page Settings → Sports → My teams asked for: the section opens on it. */
    val pendingTeam = MutableStateFlow<tv.own.owntv.core.sports.FollowedTeam?>(null)

    /** The chosen sport and league chips: kept while the app runs, back to All on a fresh start. */
    internal var hubChip: Set<HubChip> = emptySet()
    var hubLeague: Set<String> = emptySet()

    /** The hub's day strip ([SportsDays]): 0 = today. Kept while the app runs, back to today on a fresh start. */
    var hubDay: Int = 0

    /** The match page a channel was played full screen from: Back from the player lands on it again. */
    var returnToMatch: Match? = null

    /** The channel last played from a match page, so its row takes focus again on return. */
    var lastPlayedChannelId: Long? = null

    fun start(match: Match, options: List<WatchOption>) { _session.value = SportsSession(match, options) }

    /** [match] as it stands now, refreshed while collected (`Demand.PlayerScore`: that one league, 30 s live). */
    fun score(match: Match): Flow<Match> =
        repository.observe(Demand.PlayerScore(match)).map { it.match(match.key) ?: match }.distinctUntilChanged().scoreDelayed()

    /**
     * Goals in other followed matches, while collected and only with Goal alerts on. The followed leagues
     * are watched as the Home row watches them (live ones every 60 s). A match's first sighting only
     * records it, so opening the player never replays goals scored before.
     */
    fun goals(): Flow<MatchAlert> = settings.activeSportsConfig.map { it.alerts.enabled }.distinctUntilChanged().flatMapLatest { on ->
        if (!on) emptyFlow() else flow {
            val seen = HashMap<String, AlertSnapshot>()
            repository.observe(Demand.HomeRow).collect { board ->
                val config = settings.activeSportsConfig.first()
                val followed = config.leagues.toSet()
                for (m in board.today.values.flatten()) {
                    if (!MatchAlertDiff.wants(config, followed, m)) continue
                    val before = seen.put(m.key, AlertSnapshot.of(m)) ?: continue
                    if (m.key == _session.value?.match?.key) continue
                    MatchAlertDiff.diff(before, m, setOf(AlertKind.GOALS))
                        .filter { it.kind == MatchAlert.Kind.GOAL }
                        .forEach { emit(it) }
                }
            }
        }.scoreDelayed()
    }

    /**
     * The *other* followed games under way, for the score ticker: the profile's teams first, then its
     * leagues in its order, then kick-off — the Home row's board (live ones every 60 s), minus
     * [excludeKey], the match on screen. Empty with nothing on, so the ticker draws nothing.
     */
    fun otherLiveFollowed(excludeKey: String?): Flow<List<Match>> =
        combine(repository.observe(Demand.HomeRow), settings.activeSportsConfig) { board, config ->
            val teamKeys = config.teams.map { it.key }.toSet()
            val leagues = config.leagues.toSet()
            fun mine(m: Match) = m.competitors.any { FollowedTeam(m.sport, it.team.id, "").key in teamKeys }
            board.matches
                .filter { m -> m.isLive && m.key != excludeKey && (mine(m) || m.feed in leagues) }
                .distinctBy { it.key }
                .sortedWith(
                    compareBy({ !mine(it) }, { config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }, { it.startMs }),
                )
        }.distinctUntilChanged().scoreDelayed()

    /**
     * [match]'s channels as "Watch live" lists them by default (the profile's filter) — only if Find
     * channels already found them; empty otherwise, and nothing is searched here.
     */
    suspend fun channelsFor(match: Match): List<WatchOption> {
        val config = settings.activeSportsConfig.first()
        val options = watchReader.found(match, pins.rememberedFor(config)) ?: return emptyList()
        val liveSources = activeProfileSources(settings, sourceDao).first().liveSourceIds
        return health.rank(WatchList.of(options, WatchFilter.from(config, Locale.getDefault().language, liveSources)).shown)
    }

    /** [channelsFor], searching this one match when nothing was found for it yet (Auto-switch at kick-off). */
    suspend fun channelsSearched(match: Match): List<WatchOption> {
        channelsFor(match).takeIf { it.isNotEmpty() }?.let { return it }
        val config = settings.activeSportsConfig.first()
        val options = watchReader.watchFor(listOf(match), pins.rememberedFor(config))[match.key].orEmpty()
        val liveSources = activeProfileSources(settings, sourceDao).first().liveSourceIds
        return health.rank(WatchList.of(options, WatchFilter.from(config, Locale.getDefault().language, liveSources)).shown)
    }

    /** Followed teams' games not over yet, from the Home row's board, soonest first (kick-off switch, My Sports). */
    fun followedTeamGames(): Flow<List<Match>> =
        combine(repository.observe(Demand.HomeRow), settings.activeSportsConfig) { board, config ->
            val teamKeys = config.teams.map { it.key }.toSet()
            board.matches.filter { m -> m.competitors.any { FollowedTeam(m.sport, it.team.id, "").key in teamKeys } }
                .distinctBy { it.key }.sortedBy { it.startMs }
        }.distinctUntilChanged()
}
