package tv.own.owntv.mobile.trakt

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.metadata.MetadataType
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** A non-2xx answer from Trakt. No message: one could quote the URL or a token. */
class TraktHttpException(val code: Int) : IOException()

/**
 * What `/oauth/device/code` hands out: the code the user types at [verificationUrl], how to poll for
 * the result, and the PKCE verifier this login was started with (in memory only, for its duration).
 */
data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalMs: Long,
    val codeVerifier: String,
)

/** One poll of `/oauth/device/token`, Trakt's status codes by name. */
sealed interface TraktTokenPoll {
    data class Granted(val auth: TraktAuth) : TraktTokenPoll
    /** 400: the user has not typed the code yet. */
    data object Pending : TraktTokenPoll
    /** 429: polled too fast — wait a second longer. */
    data object SlowDown : TraktTokenPoll
    /** 404 invalid code, 409 already used, 410 expired, 418 denied, or anything else: the login is over. */
    data class Declined(val code: Int) : TraktTokenPoll
}

/** One entry of the user's watchlist, as Trakt names it, so it can be matched against the playlists' own titles. */
data class TraktWatchlistItem(
    val tmdbId: Int,
    val imdbId: String?,
    val title: String,
    val year: Int?,
    val rank: Int,
    val type: MetadataType,
)

/**
 * Trakt's REST API (api.trakt.tv, version 2). The device-code login, token refresh, who the token
 * belongs to, scrobbling and the watchlist — one call each, the client id in a header and the user's
 * token in another, both sent on nothing but Trakt's own host.
 *
 * The client secret is optional throughout: Trakt's newer API apps are PKCE apps with no secret, so
 * the device login sends a PKCE challenge and verifier as well, and `client_secret` only when there
 * is one. An app that does have a secret works the same way — the extra PKCE fields cost nothing.
 *
 * Nothing here logs a URL, a header, a response body, an exception message (OkHttp's can quote the
 * URL) or a token. Unverified against a live response — this sandbox cannot reach the service — so
 * the JSON is read defensively and a surprise degrades to "nothing happened".
 */
class TraktApi(client: OkHttpClient) {

    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /** Starts a device-code login for [clientId], with a fresh PKCE pair. */
    suspend fun deviceCode(clientId: String): TraktDeviceCode {
        val verifier = TraktPkce.verifier()
        val json = JSONObject()
            .put(FIELD_CLIENT_ID, clientId)
            .put("code_challenge", TraktPkce.challenge(verifier))
            .put("code_challenge_method", "S256")
        val body = post("$API/oauth/device/code", json, clientId, token = null)
        val o = JSONObject(body)
        val now = System.currentTimeMillis()
        return TraktDeviceCode(
            deviceCode = o.optString("device_code").ifEmpty { throw TraktHttpException(0) },
            userCode = o.optString("user_code").ifEmpty { throw TraktHttpException(0) },
            verificationUrl = o.optString("verification_url").ifEmpty { ACTIVATE_URL },
            expiresAtMs = now + o.optLong("expires_in", DEFAULT_EXPIRES_SECONDS) * 1000L,
            intervalMs = o.optLong("interval", DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1L) * 1000L,
            codeVerifier = verifier,
        )
    }

    /** One poll for the device-code login's outcome; Trakt's status codes map onto [TraktTokenPoll]. */
    suspend fun deviceToken(clientId: String, clientSecret: String, code: TraktDeviceCode): TraktTokenPoll {
        val json = JSONObject()
            .put("code", code.deviceCode)
            .put(FIELD_CLIENT_ID, clientId)
            .put("code_verifier", code.codeVerifier)
        if (clientSecret.isNotBlank()) json.put(FIELD_CLIENT_SECRET, clientSecret)
        return try {
            val body = post("$API/oauth/device/token", json, clientId, token = null)
            parseAuth(body)?.let { TraktTokenPoll.Granted(it) } ?: TraktTokenPoll.Declined(0)
        } catch (e: TraktHttpException) {
            when (e.code) {
                400 -> TraktTokenPoll.Pending
                429 -> TraktTokenPoll.SlowDown
                else -> TraktTokenPoll.Declined(e.code)
            }
        }
    }

    /** Trades the refresh token for a new pair; null when Trakt did not answer with one. */
    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): TraktAuth? {
        val json = JSONObject()
            .put("refresh_token", refreshToken)
            .put(FIELD_CLIENT_ID, clientId)
            .put("redirect_uri", REDIRECT_URI)
            .put("grant_type", "refresh_token")
        if (clientSecret.isNotBlank()) json.put(FIELD_CLIENT_SECRET, clientSecret)
        return parseAuth(post("$API/oauth/token", json, clientId, token = null))
    }

    /** Tells Trakt the token is done with. Best effort: a failure here changes nothing on this side. */
    suspend fun revoke(clientId: String, clientSecret: String, accessToken: String) {
        val json = JSONObject().put("token", accessToken).put(FIELD_CLIENT_ID, clientId)
        if (clientSecret.isNotBlank()) json.put(FIELD_CLIENT_SECRET, clientSecret)
        try {
            post("$API/oauth/revoke", json, clientId, token = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: the local tokens are dropped regardless.
        }
    }

    /** The username the token belongs to, from `/users/me`. */
    suspend fun me(clientId: String, accessToken: String): String {
        val o = JSONObject(get("$API/users/me", clientId, accessToken))
        return o.optString("username").ifEmpty { o.optJSONObject("ids")?.optString("slug").orEmpty() }
    }

    /**
     * `/scrobble/start`, `/pause` or `/stop` with [item] (a `movie` or a `show` + `episode` object) at
     * [progress] percent. 409 is Trakt saying the same thing was just scrobbled, which is fine.
     */
    suspend fun scrobble(clientId: String, accessToken: String, action: String, item: JSONObject, progress: Double) {
        val json = JSONObject()
        for (key in item.keys()) json.put(key, item.get(key))
        json.put("progress", progress)
        try {
            post("$API/scrobble/$action", json, clientId, accessToken)
        } catch (e: TraktHttpException) {
            if (e.code != 409) throw e
        }
    }

    /** The user's watchlist of films or shows (`movies` / `shows`) as raw JSON; see [TraktParser.watchlist]. */
    suspend fun watchlist(clientId: String, accessToken: String, kind: String): String =
        get("$API/sync/watchlist/$kind", clientId, accessToken)

    private fun parseAuth(body: String): TraktAuth? {
        val o = JSONObject(body)
        val access = o.optString("access_token").ifEmpty { return null }
        val refresh = o.optString("refresh_token")
        val createdAt = o.optLong("created_at", System.currentTimeMillis() / 1000L)
        val expiresIn = o.optLong("expires_in", DEFAULT_TOKEN_SECONDS)
        return TraktAuth(access, refresh, (createdAt + expiresIn) * 1000L)
    }

    private suspend fun get(url: String, clientId: String, token: String?): String =
        run(Request.Builder().url(url).headers(clientId, token).build())

    private suspend fun post(url: String, json: JSONObject, clientId: String, token: String?): String =
        run(Request.Builder().url(url).post(json.toString().toRequestBody(JSON)).headers(clientId, token).build())

    private fun Request.Builder.headers(clientId: String, token: String?): Request.Builder {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", clientId)
        if (token != null) header("Authorization", "Bearer $token")
        return this
    }

    private suspend fun run(request: Request): String = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw TraktHttpException(response.code)
                response.body?.string().orEmpty()
            }
        } finally {
            hook?.dispose()
        }
    }

    companion object {
        /**
         * The redirect URI the token refresh names. The Trakt API app (developer.trakt.tv) must list
         * exactly this URI; the device login itself never opens it — the user types a code instead.
         */
        const val REDIRECT_URI = "http://localhost/callback"
        const val ACTIVATE_URL = "https://trakt.tv/activate"
        private const val API = "https://api.trakt.tv"
        private const val FIELD_CLIENT_ID = "client_id"
        private const val FIELD_CLIENT_SECRET = "client_secret"
        private const val DEFAULT_EXPIRES_SECONDS = 600L
        private const val DEFAULT_INTERVAL_SECONDS = 5L
        private const val DEFAULT_TOKEN_SECONDS = 7_776_000L
        private const val TIMEOUT_SECONDS = 15L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** PKCE (RFC 7636): a random verifier and its S256 challenge, base64url without padding. */
internal object TraktPkce {
    private const val VERIFIER_BYTES = 64
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** 86 unreserved characters: within the 43–128 the RFC allows. */
    fun verifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, FLAGS)
    }

    fun challenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), FLAGS)
}

/**
 * Trakt's JSON → the models above. Only the fields shown are read; an entry that doesn't parse is
 * skipped rather than failing the call.
 */
internal object TraktParser {

    /** A watchlist answer: one object per entry, the title under `movie` or `show`. */
    fun watchlist(body: String, type: MetadataType): List<TraktWatchlistItem> {
        val arr = JSONArray(body)
        val out = ArrayList<TraktWatchlistItem>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val media = entry.optJSONObject(if (type == MetadataType.TV) "show" else "movie") ?: continue
            val ids = media.optJSONObject("ids") ?: continue
            val tmdbId = ids.optInt("tmdb", 0)
            val title = media.optString("title").trim()
            if (tmdbId <= 0 || title.isEmpty()) continue
            val year = media.optInt("year", 0).takeIf { it > 0 }
            val rank = entry.optInt("rank", 0).takeIf { it > 0 } ?: (i + 1)
            val imdb = ids.optString("imdb").takeIf { it.isNotBlank() }
            out += TraktWatchlistItem(tmdbId, imdb, title, year, rank, type)
        }
        return out.sortedBy { it.rank }
    }
}
