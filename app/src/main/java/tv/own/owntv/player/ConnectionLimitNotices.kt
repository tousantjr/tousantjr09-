package tv.own.owntv.player

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient

/** The playlist's own numbers behind a failed stream: every one of [max] connections is in use. */
@Immutable
data class ConnectionLimitNotice(val active: Int, val max: Int)

/**
 * Explains a failed stream with the account's connection count when that is the reason.
 *
 * Core already names this failure when a panel answers HTTP 458 ([PlaybackFailure.AccountInUse]),
 * but most panels refuse a stream over the limit with a plain 403/503 or a dead stream, which core
 * can only report as the generic failure it looks like. So after a failure that *could* be that, the
 * Xtream account is asked directly (`player_api`'s `active_cons` / `max_connections`, the same call
 * Settings › Playlists › Info makes): all connections in use means the account is playing elsewhere,
 * and the overlay can say so with the numbers. One cheap call per failure, nothing for a playlist
 * that is not Xtream, and nothing when the panel does not publish the numbers.
 */
class ConnectionLimitNotices(
    private val xtreamClient: XtreamClient,
    private val sourceDao: SourceDao,
) {
    suspend fun explain(sourceId: Long, failure: PlaybackFailure): ConnectionLimitNotice? {
        if (!failure.couldBeConnectionLimit) return null
        return withContext(Dispatchers.IO) {
            val source = sourceDao.getById(sourceId)?.takeIf { it.type == SourceType.XTREAM } ?: return@withContext null
            val account = xtreamClient.fetchAccountDetails(source) ?: return@withContext null
            if (account.maxConnections <= 0 || account.activeConnections < account.maxConnections) null
            else ConnectionLimitNotice(account.activeConnections, account.maxConnections)
        }
    }
}

/**
 * The failures a panel refusing one more stream can present as. Decoder, hardware, image, cast and
 * file failures are the device's own and never the account's, so they are not worth a round trip.
 */
val PlaybackFailure.couldBeConnectionLimit: Boolean
    get() = when (this) {
        PlaybackFailure.Channel,
        PlaybackFailure.LostConnection,
        PlaybackFailure.StreamLink,
        PlaybackFailure.NotStreaming,
        PlaybackFailure.AccountInUse,
        PlaybackFailure.MpvStreamNeverStarted,
        PlaybackFailure.MpvOpenDecode,
        PlaybackFailure.BothEnginesExoFirst,
        is PlaybackFailure.StreamUnavailable,
        is PlaybackFailure.ExoPlay,
        is PlaybackFailure.BothEnginesMpvFirst,
        is PlaybackFailure.Raw -> true
        else -> false
    }
