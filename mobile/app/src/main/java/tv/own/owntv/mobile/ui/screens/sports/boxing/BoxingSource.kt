package tv.own.owntv.mobile.ui.screens.sports.boxing

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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Where a bout stands. Boxing has no live score to tick, so this is read once per fetch, never refreshed
 *  while a card is open — see the module doc on [BoxingUpcomingViewModel] for why. */
enum class BoxingBoutState { UPCOMING, LIVE, FINAL, POSTPONED, CANCELLED }

/** One fighter. [record] is ESPN's own summary ("28-0-0") when it sends one; often it doesn't. */
data class BoxingFighter(val name: String, val record: String? = null)

/**
 * One scheduled bout, from ESPN's (guessed) boxing scoreboard. [eventTitle] is the card/event it
 * belongs to ("Riyadh Season"), which can differ from the bout's own two names; [isMainEvent] is true
 * for the last bout listed under its event, ESPN's own convention for "the headliner goes last"
 * elsewhere in its API (see core's `EspnParser.mmaCard`) — unconfirmed for boxing, so it is decoration
 * only and nothing here depends on it being right.
 */
data class BoxingBout(
    val id: String,
    val eventTitle: String,
    val fighters: List<BoxingFighter>,
    val weightClass: String?,
    val startMs: Long,
    val state: BoxingBoutState,
    val statusText: String,
    val venue: String?,
    val broadcasters: List<String>,
    val isMainEvent: Boolean,
) {
    val key: String get() = "boxing#$id"
    val isLive: Boolean get() = state == BoxingBoutState.LIVE
}

/**
 * [bout]'s two fighters' names, for formatting with core's own `R.string.sports_pair` ("%1$s – %2$s")
 * — the exact format every other combat/team sport's "Team A – Team B" title already uses (see
 * `MatchNotifications.pair`) — rather than a new, separately-translated "A vs B" string. Null when a
 * bout didn't parse two fighters (a card listing weigh-ins or a walkover): callers fall back to
 * [BoxingBout.eventTitle], the same fallback `MatchNotifications.pair` uses for a competitor-less match.
 */
fun matchupNames(bout: BoxingBout): Pair<String, String>? =
    if (bout.fighters.size >= 2) bout.fighters[0].name to bout.fighters[1].name else null

/**
 * ESPN's boxing scoreboard, called straight from the device — the same no-key, no-Worker approach
 * [tv.own.owntv.core.sports.EspnSportsSource] uses for every other sport, but reimplemented here
 * rather than reused through it: core's `Sport` enum has no `BOXING` case, and every one of its fetch
 * paths builds its URL from `league.sport.path`, so there is no way to make that machinery hit a
 * boxing endpoint without quietly routing to the wrong sport. See the module doc for the full reasoning.
 *
 * Ported close to verbatim from the TV app's own `BoxingSource` (same endpoint, same defensive
 * parsing) — this is app-owned, not tied to TV-specific UI, so the data layer carries over unchanged.
 *
 * **The URL and the JSON shape below are both a guess**, by analogy with ESPN's MMA scoreboard (the
 * closest sport its public "site API" already covers: individual athletes, not teams). Neither has
 * been confirmed against a live response — this sandbox has no general internet access. If the guess
 * is wrong, [scoreboard] degrades to an empty list (a 404/other HTTP error surfaces as
 * [SportsHttpException], and a JSON shape that doesn't match is read defensively and skipped field by
 * field — see [BoxingParser]) rather than crashing or showing fabricated data.
 */
class BoxingSource(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    suspend fun scoreboard(day: LocalDate): List<BoxingBout> {
        val date = day.format(DateTimeFormatter.BASIC_ISO_DATE)
        val body = get("$SITE/apis/site/v2/sports/boxing/boxing/scoreboard?dates=$date")
        return runCatching { BoxingParser.scoreboard(body) }
            .getOrElse { Log.w(TAG, "boxing scoreboard did not parse as expected: ${it.message}"); emptyList() }
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
        const val TAG = "MPTV-Boxing"
    }
}

/**
 * ESPN's (guessed) boxing JSON → [BoxingBout]. Modeled on core's `EspnParser`, field by field: only
 * the keys we show are read, every read tolerates being absent or a different shape, and one bout (or
 * one whole event) that doesn't parse is skipped rather than failing the whole day — exactly
 * `EspnParser.scoreboard`'s own contract, which this mirrors because we cannot verify boxing matches it.
 */
internal object BoxingParser {

    private const val TAG = "MPTV-Boxing"

    fun scoreboard(body: String): List<BoxingBout> {
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        return objects(events).flatMap { e ->
            runCatching { boutsOf(e) }.getOrElse { Log.w(TAG, "skipped one boxing event: ${it.message}"); emptyList() }
        }
    }

    /** One event ("card") can list several bouts (`competitions`); each becomes its own schedulable row
     *  rather than keeping only the last one, unlike MMA's single-card convention — a fight-night
     *  schedule is exactly the undercard too, and guessing which one is "the" main event is not worth
     *  silently hiding the others if that guess is wrong. */
    private fun boutsOf(e: JSONObject): List<BoxingBout> {
        val eventId = e.optString("id").ifEmpty { return emptyList() }
        val eventTitle = e.optString("name")
        val competitions = e.optJSONArray("competitions") ?: return emptyList()
        val list = objects(competitions)
        return list.mapIndexedNotNull { i, c ->
            runCatching { bout(eventId, eventTitle, i, list.size, e, c) }.getOrNull()
        }
    }

    private fun bout(eventId: String, eventTitle: String, index: Int, total: Int, e: JSONObject, c: JSONObject): BoxingBout {
        val status = c.optJSONObject("status")
        val dateStr = c.optString("date").ifEmpty { e.optString("date") }
        val startMs = time(dateStr) ?: throw IllegalStateException("no usable date")
        val fighters = objects(c.optJSONArray("competitors")).mapNotNull(::fighter)
        return BoxingBout(
            id = "$eventId-${c.optString("id").ifEmpty { index.toString() }}",
            eventTitle = eventTitle,
            fighters = fighters,
            weightClass = c.optJSONObject("type")?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
            startMs = startMs,
            state = state(status),
            statusText = statusText(status),
            venue = venueOf(c.optJSONObject("venue") ?: e.optJSONObject("venue")),
            broadcasters = broadcasters(c),
            isMainEvent = index == total - 1,
        )
    }

    private fun fighter(c: JSONObject): BoxingFighter? {
        val athlete = c.optJSONObject("athlete")
        val team = c.optJSONObject("team")
        val name = athlete?.optString("displayName")?.takeIf { it.isNotEmpty() }
            ?: team?.optString("displayName")?.takeIf { it.isNotEmpty() }
            ?: c.optString("displayName").takeIf { it.isNotEmpty() }
            ?: return null
        val record = athlete?.optJSONArray("records")?.let(::objects)
            ?.firstOrNull { it.optString("type") == "total" || it.optString("name") == "overall" }
            ?.optString("summary")?.takeIf { it.isNotEmpty() }
            ?: c.optString("record").takeIf { it.isNotEmpty() }
        return BoxingFighter(name = name, record = record)
    }

    private fun state(status: JSONObject?): BoxingBoutState {
        val type = status?.optJSONObject("type")
        val name = type?.optString("name").orEmpty()
        return when {
            "POSTPONED" in name -> BoxingBoutState.POSTPONED
            "CANCEL" in name || "ABANDON" in name -> BoxingBoutState.CANCELLED
            type?.optString("state") == "in" -> BoxingBoutState.LIVE
            type?.optString("state") == "post" -> BoxingBoutState.FINAL
            else -> BoxingBoutState.UPCOMING
        }
    }

    private fun statusText(status: JSONObject?): String =
        status?.optJSONObject("type")?.let { it.optString("shortDetail").ifEmpty { it.optString("description") } }.orEmpty()

    private fun venueOf(v: JSONObject?): String? {
        val name = v?.optString("fullName")?.takeIf { it.isNotEmpty() } ?: return null
        val a = v.optJSONObject("address")
        return listOfNotNull(name, a?.optString("city")?.takeIf { it.isNotEmpty() }, a?.optString("country")?.takeIf { it.isNotEmpty() })
            .distinct().joinToString(", ")
    }

    /** Mirrors `EspnParser`'s own `geoBroadcasts` → `broadcasts` fallback, kept to names only. */
    private fun broadcasters(c: JSONObject): List<String> {
        val geo = objects(c.optJSONArray("geoBroadcasts")).mapNotNull { b ->
            b.optJSONObject("media")?.optString("shortName")?.takeIf { it.isNotEmpty() }
        }
        if (geo.isNotEmpty()) return geo.distinct()
        return objects(c.optJSONArray("broadcasts")).flatMap { b ->
            val names = b.optJSONArray("names") ?: JSONArray()
            (0 until names.length()).mapNotNull { i -> names.optString(i).takeIf { it.isNotEmpty() } }
        }.distinct()
    }

    /** Same tolerance as `EspnParser.time`: ESPN writes both "…T18:30Z" and "…T03:30:00Z". Null (not a
     *  thrown exception) for anything else, so one bad date skips just this bout. */
    private fun time(s: String): Long? =
        runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
