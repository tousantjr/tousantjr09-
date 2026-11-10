package tv.own.owntv.mobile.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.RememberedChannel
import tv.own.owntv.core.sports.model.Broadcast
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.StandingsRow
import tv.own.owntv.core.sports.model.Team

/** The pure rules behind the Sports hub extras (ideas from Nuvio + IPTV, GPL-3.0). */
class SportsHubPlusTest {

    private fun row(rank: Int, note: String?) = StandingsRow(rank, Team("$rank", "T$rank"), 10, 5, 3, 2, 15, 10, "+5", 18, note)

    private fun match(state: MatchState = MatchState.PRE, start: Long = 0L, broadcasts: List<String> = emptyList()) = Match(
        id = "1", sport = Sport.SOCCER, feed = "soccer/eng.1", leagueSlug = "eng.1", leagueName = "Premier League",
        startMs = start, state = state, statusText = "", title = "A v B",
        competitors = listOf(
            Competitor(Team("10", "Arsenal"), home = true, winner = null, score = null),
            Competitor(Team("20", "Chelsea"), home = false, winner = null, score = null),
        ),
        broadcasts = broadcasts.map { Broadcast(it) },
    )

    @Test fun zonesFromNotes() {
        assertEquals(TableZone.CHAMPIONS, TableZone.of("Champions League"))
        assertEquals(TableZone.EUROPA, TableZone.of("UEFA Europa League"))
        assertEquals(TableZone.RELEGATION, TableZone.of("Relegation"))
        assertEquals(TableZone.RELEGATION_PLAYOFF, TableZone.of("Relegation Playoff"))
        assertEquals(TableZone.PLAY_IN, TableZone.of("Play-In Tournament"))
        assertEquals(TableZone.PROMOTION, TableZone.of("Promotion"))
        assertEquals(TableZone.NONE, TableZone.of(null))
        assertEquals(TableZone.NONE, TableZone.of(""))
    }

    @Test fun lineWhereZoneChanges() {
        val rows = listOf(row(1, "Champions League"), row(2, "Champions League"), row(3, null), row(4, "Relegation"))
        assertFalse(TableZone.lineAbove(rows, 0))
        assertFalse(TableZone.lineAbove(rows, 1))
        assertTrue(TableZone.lineAbove(rows, 2))
        assertTrue(TableZone.lineAbove(rows, 3))
        assertFalse(TableZone.lineAbove(rows, 9))
    }

    @Test fun rankMovesStayUntilNextChange() {
        val (first, m0) = RankMoves.observe(null, 5)
        assertEquals(0, m0)
        val (second, m1) = RankMoves.observe(first, 3)
        assertEquals(2, m1)
        val (third, m2) = RankMoves.observe(second, 3)
        assertEquals(2, m2)
        val (_, m3) = RankMoves.observe(third, 6)
        assertEquals(-3, m3)
        assertEquals(second, RankMoves.Entry.decode(second.encode()))
        assertNull(RankMoves.Entry.decode("x"))
    }

    @Test fun kickoffWindow() {
        val start = 1_000_000L
        assertFalse(KickoffWindow.dueAt(start, MatchState.PRE, start - 61_000))
        assertTrue(KickoffWindow.dueAt(start, MatchState.PRE, start - 60_000))
        assertTrue(KickoffWindow.dueAt(start, MatchState.IN, start + 60_000))
        assertFalse(KickoffWindow.dueAt(start, MatchState.IN, start + 3 * 60_000))
        assertFalse(KickoffWindow.dueAt(start, MatchState.POST, start))
    }

    @Test fun failoverTriesEachOnce() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(2L, Failover.next(ids, 1L, setOf(1L)))
        assertEquals(3L, Failover.next(ids, 2L, setOf(1L, 2L)))
        assertNull(Failover.next(ids, 3L, setOf(1L, 2L, 3L)))
        assertEquals(1L, Failover.next(ids, 3L, setOf(3L)))
        assertNull(Failover.next(emptyList(), null, emptySet()))
    }

    @Test fun pinsHomeThenAwayThenNetwork() {
        val home = RememberedChannel("Home ch", null)
        val away = RememberedChannel("Away ch", null)
        val net = RememberedChannel("Net ch", null)
        val m = match(broadcasts = listOf("Sky Sports Main Event"))
        val homeKey = "team:soccer/10"
        val awayKey = "team:soccer/20"
        val netKey = SportsPinRules.networkKey("Sky Sports Main Event")
        assertEquals(home, SportsPinRules.resolve(mapOf(homeKey to home, awayKey to away, netKey to net), m))
        assertEquals(away, SportsPinRules.resolve(mapOf(awayKey to away, netKey to net), m))
        assertEquals(net, SportsPinRules.resolve(mapOf(netKey to net), m))
        assertNull(SportsPinRules.resolve(emptyMap(), m))
    }

    @Test fun networkNamesNeverMatchAnotherNumber() {
        val espn = SportsNetworks.ALL.first { it.name == "ESPN" }
        val nbaTv = SportsNetworks.ALL.first { it.name == "NBA TV" }
        assertTrue(SportsNetworks.matches(espn, "espn"))
        assertFalse(SportsNetworks.matches(espn, "espn 2"))
        assertTrue(SportsNetworks.matches(nbaTv, "nba tv canada"))
        assertFalse(SportsNetworks.matches(nbaTv, "nba"))
    }

    @Test fun ordinals() {
        assertEquals("1st", Places.ordinal(1, true))
        assertEquals("2nd", Places.ordinal(2, true))
        assertEquals("3rd", Places.ordinal(3, true))
        assertEquals("11th", Places.ordinal(11, true))
        assertEquals("22nd", Places.ordinal(22, true))
        assertEquals("#4", Places.ordinal(4, false))
    }

    @Test fun scoreDelayChoices() {
        assertEquals(60, ScoreDelay.sanitize(60))
        assertEquals(0, ScoreDelay.sanitize(45))
    }
}
