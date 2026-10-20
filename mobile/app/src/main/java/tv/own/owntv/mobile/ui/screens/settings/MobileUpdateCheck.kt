package tv.own.owntv.mobile.ui.screens.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.mobile.BuildConfig

/**
 * The phone's update check. Core's [tv.own.owntv.core.update.UpdateManager] asks GitHub for the
 * repository's *latest* release, which is always the TV app's: phone releases are tagged `mobile-v*`
 * and published as pre-releases precisely so the TV updater and the TV download link never see them
 * (see .github/workflows/android-mobile.yml). So the phone lists recent releases itself, keeps the
 * `mobile-v*` ones, and compares the newest with this build. Installing stays the browser's job: the
 * rolling `mobile-latest` pre-release carries the newest phone APK under a fixed name, the same link
 * the Downloader code points at, so [Result.Available.apkUrl] is that link rather than a versioned one.
 */
class MobileUpdateCheck(private val httpClient: HttpClient) {

    sealed interface Result {
        data class Available(val version: String, val apkUrl: String) : Result
        data object UpToDate : Result
        data object Failed : Result
    }

    suspend fun check(current: String = BuildConfig.VERSION_NAME): Result = withContext(Dispatchers.IO) {
        try {
            val body = httpClient.getText(
                releasesUrl(),
                userAgent = USER_AGENT,
                headers = mapOf(ACCEPT_HEADER to ACCEPT_GITHUB_JSON),
            )
            val releases = JSONArray(body)
            val versions = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .mapNotNull { release ->
                    release.optString(TAG_NAME_KEY).takeIf { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX)
                }
            // No phone release at all is "nothing newer is known", not a failed check.
            val newest = versions.maxWithOrNull(::compareVersions) ?: return@withContext Result.UpToDate
            if (compareVersions(newest, current) > 0) Result.Available(newest, latestApkUrl()) else Result.UpToDate
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.Failed
        }
    }

    companion object {
        /** The newest twenty releases: TV and phone releases interleave, and a phone one is always among them. */
        private fun releasesUrl() = "https://api.github.com/repos/${CoreBuildInfo.releaseRepo}/releases?per_page=20"

        /** The rolling pre-release's fixed-name APK — the link that never changes (android-mobile.yml). */
        fun latestApkUrl() = "https://github.com/${CoreBuildInfo.releaseRepo}/releases/download/mobile-latest/MPTV-Mobile.apk"

        private const val TAG_PREFIX = "mobile-v"
        private const val TAG_NAME_KEY = "tag_name"
        private const val USER_AGENT = "MPTV"
        private const val ACCEPT_HEADER = "Accept"
        private const val ACCEPT_GITHUB_JSON = "application/vnd.github+json"

        /** Dotted numbers compared part by part; a missing part is 0, a non-numeric suffix is ignored. */
        internal fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
