package tv.own.owntv.features.sports.odds

import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Team
import kotlin.math.abs
import kotlin.math.roundToInt

/** "PHI -6.5": the favoured side's abbreviation and its handicap, from the home side's [homeSpread]. Null at zero, a pick 'em. */
fun spreadDetails(match: Match, homeSpread: Double): String? {
    if (homeSpread == 0.0 || homeSpread.isNaN()) return null
    val home = match.home?.team ?: return null
    val away = match.away?.team ?: return null
    val favourite = if (homeSpread < 0) home else away
    return sideLabel(favourite) + " " + formatPoint(-abs(homeSpread))
}

/** 47.5 → "47.5", 48.0 → "48", −6.5 → "-6.5". */
fun formatPoint(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** A side as a line names it: the abbreviation ("PHI"), or the short name where ESPN gives none. */
fun sideLabel(team: Team): String = team.abbreviation.ifBlank { team.shortName }

/** Where betting lines are read from. Both are read-only: nothing can be bet from this app. */
enum class OddsSource { ESPN, ODDS_API }

/**
 * The bookmakers The Odds API is asked for, by its own `bookmakers=` key. DraftKings is the default;
 * the rest are the other large US books it lists in the `us` region.
 */
enum class Bookmaker(val key: String) {
    DRAFTKINGS("draftkings"),
    FANDUEL("fanduel"),
    BETMGM("betmgm"),
    CAESARS("williamhill_us");

    companion object {
        fun of(key: String?): Bookmaker = entries.firstOrNull { it.key == key } ?: DRAFTKINGS
    }
}

/**
 * One game's line, as the match page and Home's featured game show it. Every field is optional
 * because every source leaves some out: ESPN's scoreboard has no moneylines, a soccer line has a
 * draw, a total is missing for some sports. [details] is the spread as a bookmaker prints it
 * ("PHI -6.5"); [spread] is the home side's number (negative = home favoured).
 */
data class OddsLine(
    val bookmaker: String,
    val details: String?,
    val spread: Double?,
    val overUnder: Double?,
    val homeMoneyLine: Int?,
    val awayMoneyLine: Int?,
    val drawMoneyLine: Int? = null,
    /** Implied win chances, whole percentages that sum to 100 across the sides present. */
    val homeProbability: Int?,
    val awayProbability: Int?,
    val drawProbability: Int? = null,
    val fetchedAt: Long,
) {
    val hasAnything: Boolean get() = details != null || spread != null || overUnder != null || homeMoneyLine != null || awayMoneyLine != null
}

/**
 * American moneyline → implied probability, the bookmaker's vig removed by normalising the sides
 * to 100%. −280 → 280/380 = 73.7%; +230 → 100/330 = 30.3%; together 104% → 71% · 29%.
 */
object ImpliedProbability {

    fun of(moneyLine: Int): Double =
        if (moneyLine < 0) -moneyLine / (-moneyLine + 100.0) else 100.0 / (moneyLine + 100.0)

    /**
     * Whole percentages for the sides given (null = not offered), normalised across those present
     * and nudged so they sum to exactly 100. Null when fewer than two sides carry a line.
     */
    fun normalised(home: Int?, away: Int?, draw: Int? = null): Triple<Int?, Int?, Int?>? {
        val raw = listOf(home, away, draw).map { it?.let(::of) }
        val sum = raw.filterNotNull().sum()
        if (raw.count { it != null } < 2 || sum <= 0.0) return null
        val pct = raw.map { it?.let { p -> (p / sum * 100).roundToInt() } }
        // Rounding can leave 99 or 101: push the difference onto the largest side.
        val off = 100 - pct.filterNotNull().sum()
        val fixed = if (off == 0) pct else {
            val big = pct.indices.filter { pct[it] != null }.maxByOrNull { pct[it]!! }!!
            pct.mapIndexed { i, v -> if (i == big) v!! + off else v }
        }
        return Triple(fixed[0], fixed[1], fixed[2])
    }
}
