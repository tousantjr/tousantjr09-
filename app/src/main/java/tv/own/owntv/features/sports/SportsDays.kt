package tv.own.owntv.features.sports

import tv.own.owntv.core.sports.model.Match
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The Sports hub's day strip: Yesterday, Today, Tomorrow and the four days after, as offsets from today.
 * Today (0) is the hub as it always was (core's live board); every other day is fetched on its own
 * through [SportsUpcomingViewModel.loadDay].
 */
object SportsDays {
    const val FIRST = -1
    const val LAST = 6
    val offsets: List<Int> = (FIRST..LAST).toList()

    fun dateOf(offset: Int, today: LocalDate = LocalDate.now()): LocalDate = today.plusDays(offset.toLong())

    /**
     * The matches a day's ESPN scoreboard returned that belong on [date]. ESPN files a game under its
     * own (US) calendar day, so a kick-off up to a day either side in local time is kept; a tennis or
     * golf tournament listed from its first day (weeks back) is not.
     */
    fun onDay(matches: List<Match>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Match> =
        matches.filter { m ->
            val local = Instant.ofEpochMilli(m.startMs).atZone(zone).toLocalDate()
            kotlin.math.abs(ChronoUnit.DAYS.between(date, local)) <= 1
        }
}
