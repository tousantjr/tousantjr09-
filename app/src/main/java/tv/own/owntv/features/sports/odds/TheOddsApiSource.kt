package tv.own.owntv.features.sports.odds

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.SportsHttpException
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Team
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The Odds API (the-odds-api.com), v4: one call per league returns its upcoming games with the asked
 * bookmaker's moneyline (`h2h`), spread and total in American format. Needs a key; the free tier is
 * 500 requests a month, so [OddsRepository] keeps a league for 30 minutes and asks only for the league
 * of a game actually on screen. The response's `x-requests-remaining` header is passed back so
 * Settings can show it and the app can stop for the day at zero.
 *
 * The key travels only in the request URL. Nothing here logs a URL, an exception message (OkHttp's
 * can quote the URL) or the key itself. Unverified against a live response — this sandbox cannot
 * reach the service — so the JSON is read defensively and a surprise degrades to "no line".
 */
class TheOddsApiSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /** [sportKey]'s games with [bookmaker]'s lines. HTTP errors (401 bad key, 429 quota) surface as [SportsHttpException]. */
    suspend fun league(sportKey: String, apiKey: String, bookmaker: Bookmaker): LeagueOdds {
        val key = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        val url = "$API/v4/sports/$sportKey/odds?apiKey=$key&regions=us&markets=h2h,spreads,totals&oddsFormat=american&bookmakers=${bookmaker.key}"
        val (body, remaining) = get(url)
        val events = runCatching { OddsApiParser.events(body) }
            .getOrElse { Log.w(TAG, "odds did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
        return LeagueOdds(events, remaining)
    }

    private suspend fun get(url: String): Pair<String, Int?> = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SportsHttpException(response.code)
                val remaining = response.header(REMAINING_HEADER)?.trim()?.toDoubleOrNull()?.toInt()
                response.body.string() to remaining
            }
        } finally {
            hook?.dispose()
        }
    }

    private companion object {
        const val API = "https://api.the-odds-api.com"
        const val REMAINING_HEADER = "x-requests-remaining"
        const val TIMEOUT_SECONDS = 10L
        const val TAG = "OwnTV-Odds"
    }
}

/** One league's answer: its games (each with the one bookmaker asked for) and the key's requests left. */
data class LeagueOdds(val events: List<OddsEvent>, val requestsRemaining: Int?)

/** One game as The Odds API lists it. Outcome names are the API's own team names. */
data class OddsEvent(
    val id: String,
    val commenceMs: Long,
    val homeTeam: String,
    val awayTeam: String,
    val bookmaker: String,
    /** Moneyline by outcome name (a team, or "Draw"), American. */
    val moneyLines: Map<String, Int>,
    /** Spread point by team name: the side's own handicap, −6.5 for the favourite. */
    val spreads: Map<String, Double>,
    /** The total's point (the Over/Under line). */
    val total: Double?,
)

/**
 * The Odds API's league JSON → [OddsEvent]s. Only the fields shown are read; a game or a market that
 * doesn't parse is skipped rather than failing the league.
 */
internal object OddsApiParser {

    fun events(body: String): List<OddsEvent> = objects(JSONArray(body)).mapNotNull { e -> runCatching { event(e) }.getOrNull() }

    private fun event(e: JSONObject): OddsEvent? {
        val id = e.optString("id").ifEmpty { return null }
        val commence = time(e.optString("commence_time")) ?: return null
        val home = e.optString("home_team").ifEmpty { return null }
        val away = e.optString("away_team").ifEmpty { return null }
        // One bookmaker was asked for; the first listed is it.
        val book = objects(e.optJSONArray("bookmakers")).firstOrNull() ?: return null
        val title = book.optString("title").ifEmpty { book.optString("key") }
        val markets = objects(book.optJSONArray("markets")).associateBy { it.optString("key") }
        val moneyLines = objects(markets["h2h"]?.optJSONArray("outcomes")).mapNotNull { o ->
            val name = o.optString("name")
            val price = price(o)
            if (name.isEmpty() || price == null) null else name to price
        }.toMap()
        val spreads = objects(markets["spreads"]?.optJSONArray("outcomes")).mapNotNull { o ->
            val name = o.optString("name")
            val point = point(o)
            if (name.isEmpty() || point == null) null else name to point
        }.toMap()
        val total = objects(markets["totals"]?.optJSONArray("outcomes")).firstNotNullOfOrNull { point(it) }
        return OddsEvent(id, commence, home, away, title, moneyLines, spreads, total)
    }

    private fun price(o: JSONObject): Int? =
        if (o.has("price") && !o.isNull("price")) o.optDouble("price").takeIf { !it.isNaN() }?.toInt() else null

    private fun point(o: JSONObject): Double? =
        if (o.has("point") && !o.isNull("point")) o.optDouble("point").takeIf { !it.isNaN() } else null

    private fun time(s: String): Long? =
        runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

/**
 * Pairs a board match with the API's game for it: a kick-off within two hours and both teams' names
 * agreeing — exactly first, then ignoring case and punctuation ("St. Louis" / "St Louis"), then by
 * nickname alone (the last word: ESPN's "Philadelphia Eagles" against a book's "Phila. Eagles").
 * A neutral-site game can list the sides the other way round; [Hit.swapped] says so.
 */
internal object OddsMatcher {

    data class Hit(val event: OddsEvent, val swapped: Boolean)

    fun find(events: List<OddsEvent>, match: Match): Hit? {
        val home = match.home?.team ?: return null
        val away = match.away?.team ?: return null
        val near = events.filter { abs(it.commenceMs - match.startMs) <= WINDOW_MS }
        for (level in 0..2) {
            for (e in near) {
                if (same(home, e.homeTeam, level) && same(away, e.awayTeam, level)) return Hit(e, swapped = false)
                if (same(home, e.awayTeam, level) && same(away, e.homeTeam, level)) return Hit(e, swapped = true)
            }
        }
        return null
    }

    /** The matched game's line for [match], its sides mapped back to the match's home and away. */
    fun line(hit: Hit, match: Match, now: Long): OddsLine? {
        val e = hit.event
        val homeName = if (hit.swapped) e.awayTeam else e.homeTeam
        val awayName = if (hit.swapped) e.homeTeam else e.awayTeam
        val homeMl = e.moneyLines[homeName]
        val awayMl = e.moneyLines[awayName]
        val drawMl = e.moneyLines.entries.firstOrNull { it.key.equals(DRAW, ignoreCase = true) }?.value
        val homeSpread = e.spreads[homeName] ?: e.spreads[awayName]?.let { -it }
        val p = ImpliedProbability.normalised(homeMl, awayMl, drawMl)
        return OddsLine(
            bookmaker = e.bookmaker,
            details = homeSpread?.let { spreadDetails(match, it) },
            spread = homeSpread,
            overUnder = e.total,
            homeMoneyLine = homeMl,
            awayMoneyLine = awayMl,
            drawMoneyLine = drawMl,
            homeProbability = p?.first,
            awayProbability = p?.second,
            drawProbability = p?.third,
            fetchedAt = now,
        ).takeIf { it.hasAnything }
    }

    private fun same(team: Team, name: String, level: Int): Boolean {
        val mine = buildList {
            add(team.name)
            add(team.shortName)
            team.location?.let { add(it + " " + team.shortName); add(it + " " + team.name) }
        }
        return when (level) {
            0 -> mine.any { it == name }
            1 -> normal(name).let { n -> n.isNotEmpty() && mine.any { normal(it) == n } }
            else -> lastWord(name).let { n -> n.length >= 3 && mine.any { lastWord(it) == n } }
        }
    }

    private fun normal(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lastWord(s: String): String = normal(s.trim().substringAfterLast(' '))

    private const val WINDOW_MS = 2 * 60 * 60 * 1000L
    private const val DRAW = "Draw"
}
