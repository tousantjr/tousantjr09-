package tv.own.owntv.mobile.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The hub's day strip: which days it offers and which of a scoreboard's matches belong on a day. */
class SportsDaysTest {

    private val zone = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 11, 4)

    private fun match(id: String, at: LocalDateTime) = Match(
        id = id, sport = Sport.SOCCER, feed = "soccer/eng.1", leagueSlug = "eng.1", leagueName = "Premier League",
        startMs = at.atZone(zone).toInstant().toEpochMilli(), state = MatchState.PRE, statusText = "", title = id, subtitle = "",
    )

    @Test
    fun stripRunsFromYesterdayToSixDaysAhead() {
        assertEquals(listOf(-1, 0, 1, 2, 3, 4, 5, 6), SportsDays.offsets)
        assertEquals(LocalDate.of(2026, 11, 3), SportsDays.dateOf(-1, day))
        assertEquals(LocalDate.of(2026, 11, 10), SportsDays.dateOf(6, day))
    }

    @Test
    fun keepsTheDayAndANeighbourButNotATournamentStartedWeeksAgo() {
        val kept = SportsDays.onDay(
            listOf(
                match("same", day.atTime(15, 0)),
                match("lateEt", day.plusDays(1).atTime(1, 0)), // an evening US kick-off is past midnight here
                match("tournament", day.minusDays(10).atTime(10, 0)),
                match("nextWeek", day.plusDays(3).atTime(10, 0)),
            ),
            day, zone,
        )
        assertEquals(listOf("same", "lateEt"), kept.map { it.id })
    }
}
