package tv.own.owntv.features.live

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

private val Context.antiFreezeDataStore by preferencesDataStore(name = "anti_freeze")

/** The two output formats an Xtream panel serves every live channel in. */
enum class LiveFormat { TS, HLS }

/**
 * Settings → Video player → Live: "Anti-freeze", plus the format that worked for each channel.
 *
 * On by default ([DEFAULT]): it never opens a second stream — a switch is one ordinary re-tune that
 * replaces the frozen one — it only touches Xtream channels, and it switches at most once per tune.
 * Its own DataStore for the same reason as HomeTrailerSettings (core's SettingsRepository is compiled).
 */
class AntiFreezeSettings(private val context: Context) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("anti_freeze_enabled")
    }

    companion object {
        const val DEFAULT = true
    }

    val enabled: Flow<Boolean> = context.antiFreezeDataStore.data.map { it[Keys.ENABLED] ?: DEFAULT }

    suspend fun setEnabled(on: Boolean) {
        context.antiFreezeDataStore.edit { it[Keys.ENABLED] = on }
    }

    /** The format a channel was last switched to, or null when it never needed one. */
    suspend fun rememberedFormat(channel: ChannelEntity): LiveFormat? {
        val raw = context.antiFreezeDataStore.data.first()[formatKey(channel)] ?: return null
        return LiveFormat.entries.firstOrNull { it.name == raw }
    }

    suspend fun rememberFormat(channel: ChannelEntity, format: LiveFormat) {
        context.antiFreezeDataStore.edit { it[formatKey(channel)] = format.name }
    }

    private fun formatKey(channel: ChannelEntity) = stringPreferencesKey(AntiFreeze.channelKey(channel))
}

/** Pure rules behind Anti-freeze, kept free of Android so they can be unit-tested. */
object AntiFreeze {
    /** A stall this long (ms) counts as frozen on its own. */
    const val STALL_MS = 8_000L

    /** More than [MAX_REBUFFERS] rebuffers inside [WINDOW_MS] counts as frozen. */
    const val WINDOW_MS = 30_000L
    const val MAX_REBUFFERS = 2

    private const val TS_EXT = ".ts"
    private const val HLS_EXT = ".m3u8"

    /** Stable per-channel key: playlist + the stream's path without its format extension. */
    fun channelKey(channel: ChannelEntity): String =
        "${channel.sourceId}|${basePath(channel.streamUrl)}"

    private fun basePath(url: String): String {
        val path = url.substringBefore('?')
        return when {
            path.endsWith(TS_EXT, ignoreCase = true) -> path.dropLast(TS_EXT.length)
            path.endsWith(HLS_EXT, ignoreCase = true) -> path.dropLast(HLS_EXT.length)
            else -> path
        }
    }

    /** Only Xtream channels whose URL names one of the two formats can be switched. */
    fun switchable(channel: ChannelEntity, source: SourceEntity?): Boolean {
        if (source?.type != SourceType.XTREAM) return false
        val path = channel.streamUrl.substringBefore('?')
        return path.endsWith(TS_EXT, ignoreCase = true) || path.endsWith(HLS_EXT, ignoreCase = true)
    }

    /** The format the channel opens in today: its URL after the playlist's Prefer HLS is applied. */
    fun currentFormat(channel: ChannelEntity, source: SourceEntity?): LiveFormat {
        val path = channel.streamUrl.substringBefore('?')
        val hls = path.endsWith(HLS_EXT, ignoreCase = true) || source?.preferHls == true
        return if (hls) LiveFormat.HLS else LiveFormat.TS
    }

    fun other(format: LiveFormat) = if (format == LiveFormat.HLS) LiveFormat.TS else LiveFormat.HLS

    /**
     * The channel and playlist to hand the tuner so it opens [format]: the URL's extension swapped
     * (query kept) and, for TS, the playlist's Prefer HLS turned off for this one tune so it is not
     * rewritten back. Nothing is saved to the playlist.
     */
    fun withFormat(channel: ChannelEntity, source: SourceEntity, format: LiveFormat): Pair<ChannelEntity, SourceEntity> {
        val query = channel.streamUrl.substringAfter('?', "")
        val ext = if (format == LiveFormat.HLS) HLS_EXT else TS_EXT
        val url = basePath(channel.streamUrl) + ext + (if (query.isNotEmpty()) "?$query" else "")
        return channel.copy(streamUrl = url) to source.copy(preferHls = format == LiveFormat.HLS)
    }
}

/**
 * Counts rebuffers in a sliding window. [onRebuffer] is called each time playback starts buffering
 * again after it had been playing; it answers true once there have been more than
 * [AntiFreeze.MAX_REBUFFERS] inside [AntiFreeze.WINDOW_MS].
 */
class RebufferCounter {
    private val times = ArrayDeque<Long>()

    fun onRebuffer(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.isNotEmpty() && nowMs - times.first() > AntiFreeze.WINDOW_MS) times.removeFirst()
        return times.size > AntiFreeze.MAX_REBUFFERS
    }

    fun reset() = times.clear()
}
