package tv.own.owntv.mobile.health

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.PlaybackEngine
import tv.own.owntv.player.PlaybackFailure

/**
 * Turns one engine's state into [ChannelHealthStore] entries for the channel it is playing.
 *
 * A [session] lasts as long as the caller keeps it collected — the shell keys it on the channel on
 * screen and the engine holding it, so a zap ends one session and starts the next. Inside it:
 *
 *  - the first picture is a start; an error before any picture is a failed start;
 *  - an error after the picture, or re-buffering that lasts [STALL_AFTER_MS], is a stall (at most one
 *    per [STALL_GAP_MS], so a bad minute is one stall, not sixty);
 *  - an error that [accountRefusal] attributes to the account (every connection in use elsewhere) is
 *    nobody's fault here and is not recorded — the same answer the player's own panel gives.
 *
 * Two things the engine's flows do not say on their own are handled by looking: the error and the
 * playing flag the engine still carries from the *previous* channel when a session begins are ignored
 * until they change, and a picture is only credited once the engine has been seen not playing — or
 * [GRACE_MS] has passed, for an engine that swapped streams without a gap.
 */
class ChannelHealthRecorder(
    private val store: ChannelHealthStore,
    private val accountRefusal: suspend (ChannelEntity, PlaybackFailure) -> Boolean,
) {
    suspend fun session(channel: ChannelEntity, engine: PlaybackEngine) {
        val id = channel.id
        val staleError = engine.error.value
        val startedAt = SystemClock.elapsedRealtime()
        var armed = !engine.isPlaying.value
        var started = false
        var errorSeen: PlaybackFailure? = staleError
        var lastStallAt = 0L
        combine(engine.isPlaying, engine.buffering, engine.error) { playing, buffering, error -> Triple(playing, buffering, error) }
            .collectLatest { (playing, buffering, error) ->
                if (!playing) armed = true
                if (error != null) {
                    if (error !== errorSeen) {
                        errorSeen = error
                        if (!accountRefusal(channel, error)) {
                            if (started) store.recordStall(id) else store.recordFailedStart(id)
                        }
                    }
                    return@collectLatest
                }
                if (!started && playing && (armed || SystemClock.elapsedRealtime() - startedAt > GRACE_MS)) {
                    started = true
                    store.recordStart(id)
                }
                if (started && buffering) {
                    // collectLatest cancels this wait the moment the engine reports anything new, so a
                    // short hiccup never reaches the store.
                    delay(STALL_AFTER_MS)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStallAt > STALL_GAP_MS) {
                        lastStallAt = now
                        store.recordStall(id)
                    }
                }
            }
    }

    private companion object {
        const val GRACE_MS = 2_500L
        const val STALL_AFTER_MS = 2_500L
        const val STALL_GAP_MS = 30_000L
    }
}
