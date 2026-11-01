package tv.own.owntv.features.sports.preview

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.SportsHttpException
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import java.util.concurrent.TimeUnit

/**
 * What ESPN says about a game before it starts — the match page's **Preview** tab. Every part is
 * optional: ESPN writes an article and a win projection for the American leagues, a head-to-head for
 * football (soccer), injuries and weather where it has them. The tab shows only what is there.
 */
data class MatchPreview(
    /** The preview article's headline (ESPN's `article`, type Preview). */
    val headline: String? = null,
    /** The article's opening paragraphs, plain text. */
    val paragraphs: List<String> = emptyList(),
    val home: PreviewSide? = null,
    val away: PreviewSide? = null,
    /** "ARS leads series 4-0-1". */
    val headToHead: String? = null,
    /** The last meetings, most recent first. */
    val meetings: List<PreviewMeeting> = emptyList(),
    /** "Empower Field at Mile High, Denver". */
    val venue: String? = null,
    val temperatureF: Int? = null,
    /** Chance of rain, percent. */
    val rainChance: Int? = null,
) {
    /** Whether there is anything to show beyond the venue (which the score header already shows). */
    val hasAnything: Boolean get() =
        headline != null || paragraphs.isNotEmpty() || headToHead != null || meetings.isNotEmpty() ||
            temperatureF != null || listOfNotNull(home, away).any { it.record != null || it.winChance != null || it.injuries.isNotEmpty() }
}

/** One side's numbers: its season record, the home or road record, ESPN's win chance, its injury list. */
data class PreviewSide(
    val teamId: String,
    val abbreviation: String,
    /** "5-2" (or "8-0-1" in football: won-drawn-lost as ESPN writes it). */
    val record: String? = null,
    /** The home record for the home side, the road record for the away side. */
    val venueRecord: String? = null,
    /** ESPN's matchup predictor, percent (NFL, college football, NBA). */
    val winChance: Double? = null,
    val injuries: List<PreviewInjury> = emptyList(),
)

data class PreviewInjury(val name: String, val position: String?, val status: String, val detail: String?)

/** A past meeting: when, and the score as "ARS 2 – 0 HUL" (home first). */
data class PreviewMeeting(val date: String, val home: String, val homeScore: String, val away: String, val awayScore: String)

/**
 * ESPN's game summary — the same no-key "site API" [tv.own.owntv.features.sports.odds.EspnOddsSource]
 * reads the line from — fetched once per game for the preview. An HTTP error surfaces as
 * [SportsHttpException], exactly as core's own source does.
 */
class MatchPreviewSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    suspend fun preview(match: Match): MatchPreview? {
        val body = get("$SITE/apis/site/v2/sports/${match.sport.path}/${match.leagueSlug}/summary?event=${match.id}")
        return runCatching { MatchPreviewParser.parse(body) }
            .getOrElse { Log.w(TAG, "preview did not parse as expected: ${it.javaClass.simpleName}"); null }
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
        const val TIMEOUT_SECONDS = 10L
        const val TAG = "OwnTV-Preview"
    }
}

/**
 * ESPN's summary JSON → [MatchPreview], in `EspnOddsParser`'s manner: only the keys named are read,
 * every read tolerates an absent or differently typed value, and nothing is made up. Checked against
 * live NFL and Premier League summaries (November 2026).
 */
internal object MatchPreviewParser {

    /** Paragraphs of the article kept: the tab is a preview, not the whole story. */
    const val MAX_PARAGRAPHS = 6
    /** Injuries listed per side; the rest are counted, not listed. */
    const val MAX_INJURIES = 6

    fun parse(body: String): MatchPreview? {
        val root = JSONObject(body)
        val competition = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
        val competitors = objects(competition?.optJSONArray("competitors"))
        val predictor = root.optJSONObject("predictor")
        val injuries = objects(root.optJSONArray("injuries"))

        fun side(homeAway: String): PreviewSide? {
            val c = competitors.firstOrNull { it.optString("homeAway") == homeAway } ?: return null
            val team = c.optJSONObject("team") ?: return null
            val id = team.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val records = objects(c.optJSONArray("record"))
            fun record(type: String) = records.firstOrNull { it.optString("type") == type }?.let { text(it, "summary") }
            val projection = listOfNotNull(predictor?.optJSONObject("homeTeam"), predictor?.optJSONObject("awayTeam"))
                .firstOrNull { it.optString("id") == id }?.let { text(it, "gameProjection")?.toDoubleOrNull() }
            val hurt = objects(injuries.firstOrNull { it.optJSONObject("team")?.optString("id") == id }?.optJSONArray("injuries"))
                .mapNotNull { injury(it) }
            return PreviewSide(
                teamId = id,
                abbreviation = text(team, "abbreviation") ?: text(team, "shortDisplayName") ?: id,
                record = record("total"),
                venueRecord = record(if (homeAway == HOME) "home" else "road"),
                winChance = projection?.takeIf { it in 0.0..100.0 },
                injuries = hurt,
            )
        }

        val article = root.optJSONObject("article")?.takeIf { it.optString("type").equals("Preview", ignoreCase = true) }
        val series = objects(root.optJSONArray("seasonseries")).firstOrNull { it.optString("type") == "head-to-head" }
            ?: objects(root.optJSONArray("seasonseries")).firstOrNull()
        val info = root.optJSONObject("gameInfo")
        val venue = info?.optJSONObject("venue")
        val weather = info?.optJSONObject("weather")

        val preview = MatchPreview(
            headline = article?.let { text(it, "headline") },
            paragraphs = article?.let { paragraphs(text(it, "story") ?: text(it, "description")) }.orEmpty(),
            home = side(HOME),
            away = side(AWAY),
            headToHead = series?.let { text(it, "summary") },
            meetings = objects(series?.optJSONArray("events")).mapNotNull { meeting(it) }.sortedByDescending { it.date },
            venue = venue?.let { v -> listOfNotNull(text(v, "fullName"), v.optJSONObject("address")?.let { text(it, "city") }).distinct().joinToString(", ").takeIf { it.isNotEmpty() } },
            temperatureF = weather?.let { int(it, "temperature") },
            rainChance = weather?.let { int(it, "precipitation") }?.takeIf { it in 0..100 },
        )
        return preview.takeIf { it.hasAnything }
    }

    private fun injury(o: JSONObject): PreviewInjury? {
        val athlete = o.optJSONObject("athlete") ?: return null
        val name = text(athlete, "displayName") ?: return null
        val status = text(o, "status") ?: o.optJSONObject("type")?.let { text(it, "description") } ?: return null
        return PreviewInjury(
            name = name,
            position = athlete.optJSONObject("position")?.let { text(it, "abbreviation") },
            status = status,
            detail = o.optJSONObject("details")?.let { text(it, "type") },
        )
    }

    private fun meeting(e: JSONObject): PreviewMeeting? {
        // A meeting yet to be played (this one) is no result.
        if (e.optJSONObject("statusType")?.optBoolean("completed", true) == false) return null
        val cs = objects(e.optJSONArray("competitors"))
        val h = cs.firstOrNull { it.optString("homeAway") == HOME } ?: return null
        val a = cs.firstOrNull { it.optString("homeAway") == AWAY } ?: return null
        fun abbr(c: JSONObject) = c.optJSONObject("team")?.let { text(it, "abbreviation") ?: text(it, "displayName") }
        return PreviewMeeting(
            date = text(e, "date")?.take(10) ?: return null,
            home = abbr(h) ?: return null,
            homeScore = text(h, "score") ?: return null,
            away = abbr(a) ?: return null,
            awayScore = text(a, "score") ?: return null,
        )
    }

    /** ESPN's story is HTML-ish text with blank lines between paragraphs: tags dropped, entities read. */
    internal fun paragraphs(story: String?): List<String> {
        if (story.isNullOrBlank()) return emptyList()
        return story.split(Regex("\\n\\s*\\n|</p>|<br\\s*/?>", RegexOption.IGNORE_CASE))
            .map { unescape(it.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > 1 }
            .take(MAX_PARAGRAPHS)
    }

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun text(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }

    private fun int(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key)
        return if (d.isNaN()) o.optString(key).toIntOrNull() else Math.round(d).toInt()
    }

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private const val HOME = "home"
    private const val AWAY = "away"
}

/**
 * The open match's preview, before kick-off only, kept [CACHE_MS] per game so re-opening a match
 * (or Back from the player) reads the cache rather than ESPN. Failures are quiet: no tab, no toast.
 */
class MatchPreviewRepository(private val source: MatchPreviewSource) {

    private val mutex = Mutex()
    private class Cached(val at: Long, val value: MatchPreview?)
    private val cache = HashMap<String, Cached>()

    fun observe(match: Match?): Flow<MatchPreview?> {
        if (match == null || match.state != MatchState.PRE) return flowOf(null)
        return flow { emit(previewFor(match)) }
    }

    private suspend fun previewFor(match: Match): MatchPreview? = mutex.withLock {
        val now = System.currentTimeMillis()
        cache[match.key]?.takeIf { now - it.at < CACHE_MS }?.let { return@withLock it.value }
        val value = try {
            source.preview(match)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "preview unavailable: ${e.javaClass.simpleName}")
            return@withLock null
        }
        cache[match.key] = Cached(now, value)
        value
    }

    private companion object {
        const val CACHE_MS = 30 * 60_000L
        const val TAG = "OwnTV-Preview"
    }
}
