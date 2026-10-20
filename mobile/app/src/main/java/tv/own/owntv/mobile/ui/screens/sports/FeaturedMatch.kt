package tv.own.owntv.mobile.ui.screens.sports

import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsBoard
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState

/**
 * Home's featured game: the one followed match worth the hero card above the Sports row. A live
 * one wins — the user's own teams first, then their league order, then kick-off — else the next
 * one kicking off within [AHEAD_MS]. Core's `SportsHomeRow` only fills the row within the hour;
 * this looks further ahead on purpose, so the evening's game is on Home in the afternoon. Followed
 * means the profile's chosen leagues or a followed team, exactly as the row counts it, never
 * "All sports"' every league. Same rule as the television's.
 */
internal object FeaturedMatch {
    const val AHEAD_MS = 12 * 60 * 60_000L

    /** A kick-off that passed while ESPN still says "pre" counts as under way for this long, as the row does. */
    private const val STALE_MS = 3 * 60 * 60_000L

    fun pick(board: SportsBoard, config: SportsConfig, now: Long): Match? {
        val leagues = config.leagues.toSet()
        val teamKeys = config.teams.map { it.key }.toSet()
        fun mine(m: Match) = m.competitors.any { FollowedTeam(m.sport, it.team.id, "").key in teamKeys }
        val followed = board.matches.filter { m -> mine(m) || m.feed in leagues }
        val underWay = followed.filter { m ->
            m.state == MatchState.IN || (m.state == MatchState.PRE && m.startMs <= now && m.startMs > now - STALE_MS)
        }
        underWay.minWithOrNull(
            compareBy({ !mine(it) }, { config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }, { it.startMs }),
        )?.let { return it }
        return followed
            .filter { it.state == MatchState.PRE && it.startMs > now && it.startMs - now <= AHEAD_MS }
            .minWithOrNull(compareBy({ it.startMs }, { !mine(it) }))
    }
}
