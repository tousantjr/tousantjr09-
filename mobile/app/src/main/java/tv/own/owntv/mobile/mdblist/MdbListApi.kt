package tv.own.owntv.mobile.mdblist

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
import tv.own.owntv.core.metadata.MetadataType
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A non-2xx answer from MDBList; 401/403 is a bad key, 429 the daily quota. No message: one could quote the URL. */
class MdbListHttpException(val code: Int) : IOException()

/**
 * One title's ratings as MDBList aggregates them. Every field is optional: a source MDBList has no
 * number from is simply left out of the line.
 */
data class MdbRatings(
    val imdb: Double?,
    val imdbVotes: Int?,
    /** Rotten Tomatoes' critics score, 0–100. */
    val tomatoes: Int?,
    /** Rotten Tomatoes' audience score, 0–100. */
    val audience: Int?,
    val metacritic: Int?,
    /** Letterboxd's 0–5 average. */
    val letterboxd: Double?,
    val tmdb: Double?,
    val trakt: Int?,
    /** MDBList's own blended 0–100 score. */
    val score: Int?,
) {
    val hasAnything: Boolean get() = imdb != null || tomatoes != null || metacritic != null || letterboxd != null
}

/** One of MDBList's public lists, as `/lists/top` describes it. */
data class MdbListInfo(
    val id: Long,
    val name: String,
    val slug: String,
    val user: String,
    val items: Int,
    val mediaType: String?,
) {
    /** How Settings stores a pick: the numeric id, which is stable where a slug can be renamed. */
    val ref: String get() = id.toString()
}

/** One entry of a list: what TMDB calls it, so it can be matched against the playlists' own titles. */
data class MdbListItem(
    val tmdbId: Int,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val rank: Int,
    val type: MetadataType,
)

/** Settings' "Test key": the HTTP status `/lists/top` answered with (0 = unreachable) and how many lists it carried. */
data class MdbKeyTest(val httpCode: Int, val listsParsed: Int) {
    val ok: Boolean get() = httpCode in 200..299
}

/**
 * MDBList's REST API (api.mdblist.com). Ratings per TMDB id, the public top lists and a list's items;
 * one call each, the key in the query string. The free tier is 1000 requests a day, so
 * [MdbListRepository] caches everything it gets and asks only for what is on screen.
 *
 * The key travels only in the request URL. Nothing here logs a URL, an exception message (OkHttp's
 * can quote the URL) or the key itself. Unverified against a live response — this sandbox cannot
 * reach the service — so the JSON is read defensively and a surprise degrades to "nothing shown".
 */
class MdbListApi(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /**
     * [tmdbId]'s ratings as raw JSON (see [MdbListParser.ratings]), or null for a title MDBList does
     * not know. Asks the newer per-id path first and falls back to the legacy query form, which
     * answers in the same shape.
     */
    suspend fun ratings(tmdbId: Int, type: MetadataType, apiKey: String): String? {
        val kind = if (type == MetadataType.TV) KIND_SHOW else KIND_MOVIE
        val key = encode(apiKey)
        return try {
            get("$API/tmdb/$kind/$tmdbId?apikey=$key")
        } catch (e: MdbListHttpException) {
            if (e.code == 401 || e.code == 403 || e.code == 429) throw e
            try {
                get("$LEGACY?apikey=$key&tm=$tmdbId&m=$kind")
            } catch (legacy: MdbListHttpException) {
                if (legacy.code == 404) null else throw legacy
            }
        }
    }

    /** The public top lists' raw JSON; see [MdbListParser.lists]. */
    suspend fun topLists(apiKey: String): String = get("$API/lists/top?apikey=${encode(apiKey)}")

    /** A list's items' raw JSON (the first [LIMIT]), by numeric id or `user/slug`; null for a reference that is neither. */
    suspend fun listItems(ref: String, apiKey: String): String? {
        val path = ref.trim().toLongOrNull()?.let { "lists/$it/items" } ?: run {
            val parts = ref.split('/').map(String::trim).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            "lists/${encode(parts[0])}/${encode(parts[1])}/items"
        }
        return get("$API/$path?apikey=${encode(apiKey)}&limit=$LIMIT")
    }

    /** Settings' "Test key": whether [apiKey] is accepted, and how many top lists came back with it. */
    suspend fun testKey(apiKey: String): MdbKeyTest = try {
        val body = topLists(apiKey)
        MdbKeyTest(200, runCatching { MdbListParser.lists(body) }.getOrDefault(emptyList()).size)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MdbListHttpException) {
        MdbKeyTest(e.code, 0)
    } catch (e: Exception) {
        Log.w(TAG, "MDBList unreachable: ${e.javaClass.simpleName}")
        MdbKeyTest(0, 0)
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw MdbListHttpException(response.code)
                response.body.string()
            }
        } finally {
            hook?.dispose()
        }
    }

    private companion object {
        const val API = "https://api.mdblist.com"
        const val LEGACY = "https://mdblist.com/api/"
        const val KIND_MOVIE = "movie"
        const val KIND_SHOW = "show"
        const val LIMIT = 100
        const val TIMEOUT_SECONDS = 10L
        const val TAG = "OwnTV-MDBList"
    }
}

/**
 * MDBList's JSON → the models above. Only the fields shown are read; an entry that doesn't parse is
 * skipped rather than failing the call. Both the `{movies:[…], shows:[…]}` and the flat-array shapes of
 * a list's items are accepted, and an id is taken from whichever of `tmdbid`, `tmdb_id` or `id` is there.
 */
internal object MdbListParser {

    fun ratings(body: String): MdbRatings? {
        val o = JSONObject(body)
        // The legacy form answers {"response": false} for a title it does not know.
        if (o.has("response") && !o.optBoolean("response", true)) return null
        val arr = o.optJSONArray("ratings") ?: return null
        var imdb: Double? = null
        var imdbVotes: Int? = null
        var tomatoes: Int? = null
        var audience: Int? = null
        var metacritic: Int? = null
        var letterboxd: Double? = null
        var tmdb: Double? = null
        var trakt: Int? = null
        for (r in objects(arr)) {
            val value = number(r, "value")?.takeIf { it > 0 } ?: continue
            when (r.optString("source").lowercase()) {
                "imdb" -> {
                    imdb = value
                    imdbVotes = number(r, "votes")?.toInt()
                }
                "tomatoes" -> tomatoes = value.toInt()
                "tomatoesaudience" -> audience = value.toInt()
                "metacritic" -> metacritic = value.toInt()
                "letterboxd" -> letterboxd = value
                "tmdb" -> tmdb = value
                "trakt" -> trakt = value.toInt()
            }
        }
        val score = number(o, "score")?.takeIf { it > 0 }?.toInt()
        return MdbRatings(imdb, imdbVotes, tomatoes, audience, metacritic, letterboxd, tmdb, trakt, score)
    }

    fun lists(body: String): List<MdbListInfo> {
        val trimmed = body.trimStart()
        val arr = if (trimmed.startsWith('[')) JSONArray(body) else JSONObject(body).optJSONArray("lists")
        return objects(arr).mapNotNull { l ->
            val id = l.optLong("id", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            val name = l.optString("name").trim().ifEmpty { return@mapNotNull null }
            MdbListInfo(
                id = id,
                name = name,
                slug = l.optString("slug"),
                user = l.optString("user_name"),
                items = l.optInt("items", 0),
                mediaType = l.optString("mediatype").takeIf { it.isNotBlank() },
            )
        }
    }

    fun items(body: String): List<MdbListItem> {
        val trimmed = body.trimStart()
        if (trimmed.startsWith('[')) {
            return objects(JSONArray(body)).mapIndexedNotNull { i, o -> item(o, i, null) }.sortedBy { it.rank }
        }
        val o = JSONObject(body)
        val out = ArrayList<MdbListItem>()
        objects(o.optJSONArray("movies")).forEachIndexed { i, m -> item(m, i, MetadataType.MOVIE)?.let(out::add) }
        objects(o.optJSONArray("shows")).forEachIndexed { i, s -> item(s, i, MetadataType.TV)?.let(out::add) }
        return out.sortedBy { it.rank }
    }

    private fun item(o: JSONObject, index: Int, fixedType: MetadataType?): MdbListItem? {
        val tmdbId = firstInt(o, "tmdbid", "tmdb_id", "id") ?: return null
        val title = o.optString("title").trim().ifEmpty { return null }
        val type = fixedType ?: when (o.optString("mediatype").lowercase()) {
            "movie" -> MetadataType.MOVIE
            "show", "tv" -> MetadataType.TV
            else -> return null
        }
        val rank = o.optInt("rank", 0).takeIf { it > 0 } ?: (index + 1)
        val imdb = o.optString("imdb_id").ifEmpty { o.optString("imdbid") }.takeIf { it.isNotBlank() }
        return MdbListItem(tmdbId, imdb, title, firstInt(o, "release_year", "year"), rank, type)
    }

    private fun firstInt(o: JSONObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { k -> number(o, k)?.toInt()?.takeIf { it > 0 } }

    private fun number(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { !it.isNaN() } else null

    private fun objects(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}
