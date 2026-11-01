package tv.own.owntv.features.announce

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.network.HttpClient
import java.time.LocalDate
import java.time.ZoneId

/**
 * Announcements: one message from the app's owner to everyone running it. The owner edits
 * `announcement.json` at the root of the release repository on GitHub; the app reads that file
 * shortly after it starts and, when it carries a message this device has not seen yet, shows it
 * once as a pop-up. Nothing to host, nothing to sign in to — the same repository the updates come from.
 *
 * The file:
 * ```
 * { "id": "2026-11-01-maintenance", "title": "Server maintenance", "message": "…",
 *   "apps": ["tv", "phone"], "until": "2026-11-03" }
 * ```
 * A new [Announcement.id] is a new message (shown again even if the text is the same); a blank id or
 * message means "nothing to say". `apps` and `until` are optional: both apps, no end date. A missing
 * file, no network or a broken file all mean no pop-up — never an error on screen.
 */
data class Announcement(val id: String, val title: String, val message: String)

/** The pure part: what a file says for this app on [today]. */
object AnnouncementParser {
    const val APP_TV = "tv"
    const val APP_PHONE = "phone"

    fun parse(json: String?, app: String, today: LocalDate): Announcement? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val id = o.optString(KEY_ID).trim()
        val message = o.optString(KEY_MESSAGE).trim()
        if (id.isEmpty() || message.isEmpty()) return null
        val apps = o.optJSONArray(KEY_APPS)
        if (apps != null && apps.length() > 0) {
            val listed = (0 until apps.length()).map { apps.optString(it).trim().lowercase() }
            if (app !in listed) return null
        }
        val until = o.optString(KEY_UNTIL).trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (until != null && today.isAfter(until)) return null
        return Announcement(id = id, title = o.optString(KEY_TITLE).trim(), message = message)
    }

    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_MESSAGE = "message"
    private const val KEY_APPS = "apps"
    private const val KEY_UNTIL = "until"
}

/** Fetches the file and decides whether there is something new to show; one look per app launch. */
class AnnouncementCheck(private val httpClient: HttpClient, context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Set after the first look, so a rebuilt shell does not fetch (or show) it again this launch. */
    @Volatile var lookedThisLaunch = false

    /** The announcement to show now, or null: none, already seen, or the file could not be read. */
    suspend fun unseen(): Announcement? = withContext(Dispatchers.IO) {
        lookedThisLaunch = true
        val body = try {
            // The time stamp keeps a stale cached copy from hiding a fresh message.
            httpClient.getText("${fileUrl()}?t=${System.currentTimeMillis() / CACHE_BUCKET_MS}", userAgent = USER_AGENT)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext null
        }
        AnnouncementParser.parse(body, AnnouncementParser.APP_TV, LocalDate.now(ZoneId.systemDefault()))
            ?.takeIf { it.id != prefs.getString(KEY_SEEN, null) }
    }

    fun markSeen(id: String) {
        prefs.edit().putString(KEY_SEEN, id).apply()
    }

    private companion object {
        const val PREFS_NAME = "mptv_announcements"
        const val KEY_SEEN = "seen_id"
        const val USER_AGENT = "MPTV"
        const val CACHE_BUCKET_MS = 5 * 60_000L
        fun fileUrl() = "https://raw.githubusercontent.com/${CoreBuildInfo.releaseRepo}/main/announcement.json"
    }
}
