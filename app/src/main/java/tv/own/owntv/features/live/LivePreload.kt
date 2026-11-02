package tv.own.owntv.features.live

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.LivePreviewEngine

private val Context.livePreloadDataStore by preferencesDataStore(name = "live_preload")

/**
 * Settings → Video player → Live: "Preload next channel".
 *
 * Its own tiny DataStore for the same reason as HomeTrailerSettings: `:core`'s SettingsRepository
 * ships compiled and cannot gain a preference from app code. Off by default ([DEFAULT]): a preloaded
 * neighbour is a second open stream, and many providers allow exactly one.
 */
class LivePreloadSettings(private val context: Context) {
    private object Keys {
        val PRELOAD_NEXT = booleanPreferencesKey("preload_next_channel")
    }

    companion object {
        const val DEFAULT = false
    }

    val preloadNext: Flow<Boolean> = context.livePreloadDataStore.data.map { it[Keys.PRELOAD_NEXT] ?: DEFAULT }

    suspend fun setPreloadNext(enabled: Boolean) {
        context.livePreloadDataStore.edit { it[Keys.PRELOAD_NEXT] = enabled }
    }
}

/**
 * How long the playing channel has to stay put before its neighbour is preloaded. Well past
 * [ZAP_ISOLATED_PRESS_MS], so a preload never joins a burst of CH+/- opens — the burst rule in
 * ZapTuneDelay.kt is what keeps providers from reading zapping as abuse, and a preload must not add
 * to it. At most one preload open follows each channel the user actually stops on.
 */
internal const val PRELOAD_SETTLE_MS = 4_000L

/** The longest the preloaded picture stands in for the real tune before it is dropped regardless. */
internal const val PRELOAD_HANDOFF_MAX_MS = 8_000L

/**
 * Whether a neighbour may be preloaded from this playlist. Never on a one-connection playlist (or a
 * host already flagged one-session): the second stream would knock out the one being watched.
 */
internal fun preloadAllowed(maxConnections: Int?, singleSession: Boolean): Boolean =
    !singleSession && maxConnections != 1

/** Which neighbour to warm: the way the user last zapped (+1 down / −1 up), next channel by default. */
internal fun preloadDirection(lastZapDelta: Int?): Int = if (lastZapDelta != null && lastZapDelta < 0) -1 else 1

/**
 * Holds the one extra engine "Preload next channel" uses: a muted, surface-less copy of the Live
 * preview engine playing the neighbour the user is most likely to zap to. Exactly one, so the
 * feature costs one extra connection and one extra decoder at most.
 *
 * The engine is built on first use and fully released by [release] — leaving Live, the app going to
 * the background and memory pressure all call it, and the next settled channel builds it again.
 */
class LiveChannelPreloader(private val newEngine: () -> LivePreviewEngine) {
    private var engine: LivePreviewEngine? = null

    /** The channel the engine is (or was last asked to be) playing; null when nothing is preloaded. */
    var channel: ChannelEntity? = null
        private set

    /** The engine to play [target] on, built if needed. The caller starts the actual stream. */
    fun engineFor(target: ChannelEntity): LivePreviewEngine {
        channel = target
        return engine ?: newEngine().also { engine = it }
    }

    /** The preloaded engine when it already shows [channelId]'s picture, else null. */
    fun readyFor(channelId: Long): LivePreviewEngine? {
        val e = engine ?: return null
        if (channel?.id != channelId) return null
        return e.takeIf { it.state.value == LivePreviewEngine.State.PLAYING }
    }

    /** Stop the neighbour's stream (closing its connection) but keep the engine for the next one. */
    fun stop() {
        channel = null
        engine?.let { runCatching { it.stop() } }
    }

    /** Told when [release] runs (from the Activity or the Application), so a stand-in picture still
     *  on screen is taken down before its engine goes away. */
    var onReleased: (() -> Unit)? = null

    /** Close the stream and free the decoder and buffers entirely. */
    fun release() {
        channel = null
        val e = engine ?: return
        engine = null
        onReleased?.invoke()
        runCatching { e.stop() }
        runCatching { e.release() }
    }
}
