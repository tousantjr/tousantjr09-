package tv.own.owntv.mobile.ui.screens.sports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.Demand
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsMemory
import tv.own.owntv.core.sports.SportsCatalog
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
 * What the player knows about Sports: which match the playing channel was started
 * from and its score while the controls show. Nothing here touches playback; with no session, or ESPN
 * unreachable, the player is exactly the plain Live player.
 */
class SportsPlayback(
    private val repository: SportsRepository,
    private val settings: SettingsRepository,
    private val watchReader: WatchReader,
    private val sourceDao: SourceDao,
    /** Same-tier channels in [channelsFor] are reordered by how they have behaved here, so "best" means reliable. */
    private val health: tv.own.owntv.mobile.health.ChannelHealthStore,
) {
    private val _session = MutableStateFlow<SportsSession?>(null)
    val session: StateFlow<SportsSession?> = _session.asStateFlow()

    /**
     * The Sports page's open tab and its two chip rows (none chosen = All), shared by the three tabs and
     * kept while the app runs, so a match page opened over them returns to the same place; a fresh start
     * opens on Scores with everything.
     */
    val tab = MutableStateFlow(SportsTab.SCORES)
    val hubChips = MutableStateFlow<Set<HubChip>>(emptySet())
    val hubLeagues = MutableStateFlow<Set<String>>(emptySet())

    /** The open league's own tab (Table, Fixtures …), and the league it belongs to. */
    val leagueTab = MutableStateFlow(0)
    var leagueTabOf: String? = null

    /** Opens [leagueKey] in the Leagues tab, on its first tab: its sport and that league chosen, as the chips would. */
    fun chooseLeague(leagueKey: String) {
        val league = SportsCatalog.league(leagueKey) ?: return
        hubChips.value = setOf(HubChip.One(league.sport))
        hubLeagues.value = setOf(leagueKey)
        leagueTabOf = leagueKey
        leagueTab.value = 0
        tab.value = SportsTab.LEAGUES
    }

    /** The hub's day strip ([SportsDays]): 0 = today. Kept while the app runs, back to today on a fresh start. */
    var hubDay: Int = 0

    /** The channel last played from a match page, so its row is scrolled to again on return. */
    var lastPlayedChannelId: Long? = null

    /**
     * The match a page is being opened for. Its route carries only the key, and the board holds today and
     * tomorrow only, so a match from the Calendar, a league or a team page is found here.
     */
    var opening: Match? = null

    /** A match an alert's tap asked for ([tv.own.owntv.core.sports.MatchNotifications.EXTRA_MATCH_KEY]); the shell opens it. */
    val openMatchKey = MutableStateFlow<String?>(null)

    fun start(match: Match, options: List<WatchOption>) { _session.value = SportsSession(match, options) }

    /** [match] as it stands now, refreshed while collected (`Demand.PlayerScore`: that one league, 30 s live). */
    fun score(match: Match): Flow<Match> =
        repository.observe(Demand.PlayerScore(match)).map { it.match(match.key) ?: match }.distinctUntilChanged()

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
        }.distinctUntilChanged()

    /**
     * [match]'s channels as "Watch live" lists them by default (the profile's filter) — only if Find
     * channels already found them; empty otherwise, and nothing is searched here. The television's,
     * for the ticker's switch sheet.
     */
    suspend fun channelsFor(match: Match): List<WatchOption> {
        val config = settings.activeSportsConfig.first()
        val options = watchReader.found(match) { SportsMemory.rememberedFor(config, it) } ?: return emptyList()
        val liveSources = activeProfileSources(settings, sourceDao).first().liveSourceIds
        return health.rank(WatchList.of(options, WatchFilter.from(config, Locale.getDefault().language, liveSources)).shown)
    }
}
