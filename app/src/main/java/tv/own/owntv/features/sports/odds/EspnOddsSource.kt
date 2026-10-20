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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * ESPN's own line for one game, from the same no-key "site API" core's `EspnSportsSource` reads
 * scores from — reimplemented here (as `BoxingSource` is) because core's source is compiled into the
 * `:core` AAR and reads none of the odds fields. The summary's `pickcenter[]` carries the fullest line
 * (spread, total, both moneylines, who is favoured); when a game has none there, the day's scoreboard
 * (`competitions[0].odds[]`) usually still has the spread and total.
 *
 * **Unverified against a live response**: this sandbox cannot reach ESPN, so the field names are
 * those of ESPN's public API as its users document it, read defensively — a missing or differently
 * shaped field is skipped, never fabricated ([EspnOddsParser]). An HTTP error surfaces as
 * [SportsHttpException], exactly as core's own source does.
 */
class EspnOddsSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /** [match]'s line, or null when ESPN has none for it. */
    suspend fun odds(match: Match): OddsLine? {
        val base = "$SITE/apis/site/v2/sports/${match.sport.path}/${match.leagueSlug}"
        val summary = get("$base/summary?event=${match.id}")
        val fromSummary = runCatching { EspnOddsParser.summary(summary, match) }
            .getOrElse { Log.w(TAG, "summary odds did not parse as expected: ${it.javaClass.simpleName}"); null }
        if (fromSummary != null) return fromSummary
        // ESPN's scoreboard days are US Eastern.
        val day = Instant.ofEpochMilli(match.startMs).atZone(ZoneId.of(ESPN_ZONE)).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        val board = get("$base/scoreboard?dates=$day")
        return runCatching { EspnOddsParser.scoreboard(board, match) }
            .getOrElse { Log.w(TAG, "scoreboard odds did not parse as expected: ${it.javaClass.simpleName}"); null }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SportsHttpException(response.code)
                response.body.string()
            }
        } finally {
            hook?.dispose()
        }
    }

    private companion object {
        const val SITE = "https://site.api.espn.com"
        const val ESPN_ZONE = "America/New_York"
        const val TIMEOUT_SECONDS = 10L
        const val TAG = "OwnTV-Odds"
    }
}

/**
 * ESPN's odds JSON → [OddsLine], field by field, in `EspnParser`'s manner: only the keys shown are
 * read, every read tolerates an absent or differently typed value, and an entry that says nothing
 * we show is skipped for the next.
 */
internal object EspnOddsParser {

    /** The summary: `pickcenter[]` first (the full line), then the header competition's `odds[]`. */
    fun summary(body: String, match: Match): OddsLine? {
        val root = JSONObject(body)
        objects(root.optJSONArray("pickcenter")).firstNotNullOfOrNull { line(it, match) }?.let { return it }
        val header = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        return objects(header?.optJSONArray("odds")).firstNotNullOfOrNull { line(it, match) }
    }

    /** The day's scoreboard: this event's `competitions[0].odds[]`. */
    fun scoreboard(body: String, match: Match): OddsLine? {
        val event = objects(JSONObject(body).optJSONArray("events")).firstOrNull { it.optString("id") == match.id } ?: return null
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        return objects(competition.optJSONArray("odds")).firstNotNullOfOrNull { line(it, match) }
    }

    private fun line(o: JSONObject, match: Match): OddsLine? {
        val provider = o.optJSONObject("provider")?.optString("name")?.takeIf { it.isNotEmpty() } ?: ESPN
        val homeMl = int(o.optJSONObject("homeTeamOdds"), "moneyLine")
        val awayMl = int(o.optJSONObject("awayTeamOdds"), "moneyLine")
        val drawMl = int(o.optJSONObject("drawOdds"), "moneyLine")
        val spread = double(o, "spread")
        val overUnder = double(o, "overUnder")
        val details = o.optString("details").takeIf { it.isNotEmpty() } ?: spread?.let { spreadDetails(match, it) }
        val p = ImpliedProbability.normalised(homeMl, awayMl, drawMl)
        return OddsLine(
            bookmaker = provider,
            details = details,
            spread = spread,
            overUnder = overUnder,
            homeMoneyLine = homeMl,
            awayMoneyLine = awayMl,
            drawMoneyLine = drawMl,
            homeProbability = p?.first,
            awayProbability = p?.second,
            drawProbability = p?.third,
            fetchedAt = System.currentTimeMillis(),
        ).takeIf { it.hasAnything }
    }

    /** ESPN writes moneylines as numbers, now and then as strings ("-280", or "EVEN", which is no number). */
    private fun int(o: JSONObject?, key: String): Int? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        return o.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: o.optString(key).toIntOrNull()
    }

    private fun double(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key)
        return if (d.isNaN()) o.optString(key).toDoubleOrNull() else d
    }

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private const val ESPN = "ESPN"
}
