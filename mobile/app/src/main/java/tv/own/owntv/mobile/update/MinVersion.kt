package tv.own.owntv.mobile.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.network.HttpClient

/**
 * Minimum version: the oldest build the owner still supports. The owner edits `min_version.json` at
 * the root of the release repository on GitHub; a device running anything older is told, every time
 * the app starts, that it has to update, and the one button on that notice starts the update.
 *
 * The file:
 * ```
 * { "tv": "1.0.19", "phone": "1.1.11", "message": "The old version stops working on Friday." }
 * ```
 * Each app reads only its own key; a blank or missing version means "no minimum". `message` is
 * optional (the app has its own wording). A missing file, no network or a broken file all mean no
 * notice — an outage on GitHub must never be what stops someone watching.
 */
data class MinVersionNotice(val required: String, val message: String?)

/** The pure part: what a file demands of [current] for [app]. */
object MinVersionParser {
    const val APP_TV = "tv"
    const val APP_PHONE = "phone"

    fun parse(json: String?, app: String, current: String): MinVersionNotice? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val required = o.optString(app).trim().removePrefix("v")
        if (required.isEmpty() || compareVersions(current.removePrefix("v"), required) >= 0) return null
        return MinVersionNotice(required = required, message = o.optString(KEY_MESSAGE).trim().ifEmpty { null })
    }

    /** "1.0.9" < "1.0.19": numeric part by part, a missing part counts as 0, a "-beta" tail is ignored. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private const val KEY_MESSAGE = "message"
}

/** Fetches the file once per launch and says whether this build is below the minimum. */
class MinVersionCheck(private val httpClient: HttpClient) {
    /** Set after the first look, so a rebuilt shell does not fetch it again this launch. */
    @Volatile var lookedThisLaunch = false

    suspend fun notice(current: String): MinVersionNotice? = withContext(Dispatchers.IO) {
        lookedThisLaunch = true
        val body = try {
            // The time stamp keeps a stale cached copy from hiding a new minimum for long.
            httpClient.getText("${fileUrl()}?t=${System.currentTimeMillis() / CACHE_BUCKET_MS}", userAgent = USER_AGENT)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext null
        }
        MinVersionParser.parse(body, MinVersionParser.APP_PHONE, current)
    }

    companion object {
        /** The notice waits this long after launch, so it is not lost under the start-up screens. */
        const val START_DELAY_MS = 3_000L
        private const val USER_AGENT = "MPTV"
        private const val CACHE_BUCKET_MS = 5 * 60_000L
        private fun fileUrl() = "https://raw.githubusercontent.com/${CoreBuildInfo.releaseRepo}/main/min_version.json"
    }
}
