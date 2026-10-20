package tv.own.owntv.features.home

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient
import kotlin.math.ceil

/**
 * The playlist whose subscription ends soonest, once that is close enough to say so: [daysLeft] is
 * rounded up (1 = ends within the next 24 hours), and 0 or less means it has already ended.
 */
@Immutable
data class ExpiryWarning(
    val sourceId: Long,
    val sourceName: String,
    val expiresAtMs: Long,
    val daysLeft: Int,
) {
    val expired: Boolean get() = daysLeft <= 0
}

/**
 * Asks each Xtream playlist for its expiry date and remembers the answers for a while, so Home can
 * show a warning without re-asking the panel on every visit.
 *
 * Xtream only: `player_api` reports `exp_date` as a timestamp. A Stalker portal's expiry is the
 * portal's own free text (see core's `stalkerExpiryOf`), which cannot be turned into "days left"
 * reliably, and an M3U playlist has no account at all — the Settings › Playlists row already shows
 * whatever text those give. One instance for the process (a Koin single): the cache is the point.
 */
class SubscriptionExpiry(private val xtreamClient: XtreamClient) {

    private val mutex = Mutex()
    private var checkedAtMs = 0L
    /** Source id → reported expiry; null when the panel reported none (or could not be reached). */
    private var expiries: Map<Long, Long?> = emptyMap()

    suspend fun warningFor(sources: List<SourceEntity>, nowMs: Long = System.currentTimeMillis()): ExpiryWarning? {
        val xtream = sources.filter { it.type == SourceType.XTREAM }
        if (xtream.isEmpty()) return null
        val known = mutex.withLock {
            val fresh = nowMs - checkedAtMs < CHECK_EVERY_MS && xtream.all { it.id in expiries }
            if (!fresh) {
                expiries = xtream.associate { s -> s.id to runCatching { xtreamClient.fetchAccountDetails(s)?.expiryMs }.getOrNull() }
                checkedAtMs = nowMs
            }
            expiries
        }
        val (source, expiresAt) = xtream
            .mapNotNull { s -> known[s.id]?.let { s to it } }
            .minByOrNull { it.second } ?: return null
        if (expiresAt - nowMs > WARN_WITHIN_MS) return null
        val daysLeft = ceil((expiresAt - nowMs).toDouble() / DAY_MS).toInt()
        return ExpiryWarning(source.id, source.name, expiresAt, daysLeft)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val WARN_WITHIN_MS = 7 * DAY_MS
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}
