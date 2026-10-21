package tv.own.owntv.features.trakt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.PlaybackProgressEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/**
 * Resume from Trakt: the position a film or an episode was paused at on another device, folded into
 * this device's own resume position before the Play button reads it.
 *
 * Trakt keeps one paused position per title (the scrobbler's `pause` and `stop` send them). Before a
 * title starts, [mergeMovie] / [mergeEpisode] (or [mergeShow], for a whole show at once when its page
 * opens) compare Trakt's position with the local row through core's own merge rule — the same one
 * local sync uses between two of the user's devices: `insertIfAbsent` then `updateIfNewer`, stamped
 * with Trakt's `paused_at`, so a position this device reached later is never overwritten, and the
 * screens' Resume / Start over prompt, the progress bars and the Keep watching row all see one answer.
 * Nothing is ever seeked behind the user's back: the prompt and the Resume playback setting decide.
 *
 * A percentage needs a duration to become a position. The local row's, else the playlist's own
 * (`durationSecs`); when neither is known, the seek waits for the engine to report one — but only on
 * **Always** resume, since nobody was asked — and is armed for one title and one minute at a time.
 * Positions past [MAX_PERCENT] are a finished title (nothing to resume), under [MIN_PERCENT] a false
 * start; both are left alone.
 */
class TraktResume(
    private val repo: TraktRepository,
    private val metadata: MetadataRepository,
    private val progressDao: ProgressDao,
    private val settings: SettingsRepository,
    private val player: OwnTVPlayer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A seek waiting for the engine's duration: which stream, how far in, for whom. */
    private class Pending(val streamUrl: String, val percent: Double, val profileId: Long, val type: MediaType, val itemId: Long) {
        val armedAtMs: Long = System.currentTimeMillis()
    }

    @Volatile
    private var pending: Pending? = null

    init {
        scope.launch {
            combine(player.duration, player.isPlaying) { d, p -> d to p }.collect { (duration, playing) ->
                val p = pending ?: return@collect
                if (System.currentTimeMillis() - p.armedAtMs > PENDING_TTL_MS) {
                    pending = null
                    return@collect
                }
                if (duration <= 0L || !playing || player.currentMediaUrl != p.streamUrl) return@collect
                pending = null
                val target = (duration * p.percent / 100.0).toLong()
                val position = player.position.value
                // Already there (a local position was handed over after all): nothing to do.
                if (position >= target - SEEK_SLACK_MS) return@collect
                player.seekBy(target - position)
                runCatching { write(p.profileId, p.type, p.itemId, target, duration, System.currentTimeMillis()) }
            }
        }
    }

    /** Folds Trakt's position for [movie] into the local row; the position to resume from (ms), or null when nothing is known. */
    suspend fun mergeMovie(movie: MovieEntity): Long? = withContext(Dispatchers.IO) {
        guard {
            val profileId = settings.activeProfileIdNow()
            if (profileId < 0) return@guard null
            val items = repo.playbackPositions() ?: return@guard null
            val cached = runCatching { metadata.resolveMovie(movie) }.getOrNull() ?: return@guard null
            val hit = items.firstOrNull { it.type == MetadataType.MOVIE && it.matches(cached.tmdbId, cached.imdbId) }
            apply(profileId, MediaType.MOVIE, movie.id, movie.durationSecs, movie.streamUrl, hit)
        }
    }

    /** Folds Trakt's position for one [episode] of [show] into the local row; the position to resume from (ms), or null. */
    suspend fun mergeEpisode(show: SeriesEntity, episode: EpisodeEntity): Long? = withContext(Dispatchers.IO) {
        guard {
            val profileId = settings.activeProfileIdNow()
            if (profileId < 0) return@guard null
            val items = repo.playbackPositions() ?: return@guard null
            val cached = runCatching { metadata.resolveSeries(show) }.getOrNull() ?: return@guard null
            val hit = items.firstOrNull {
                it.type == MetadataType.TV && it.season == episode.seasonNumber && it.number == episode.episodeNumber && it.matches(cached.tmdbId, cached.imdbId)
            }
            apply(profileId, MediaType.EPISODE, episode.id, episode.durationSecs, episode.streamUrl, hit)
        }
    }

    /** Folds Trakt's positions for every episode of [show] among [episodes] into the local rows, as the show's page opens. */
    suspend fun mergeShow(show: SeriesEntity, episodes: List<EpisodeEntity>) = withContext(Dispatchers.IO) {
        guard {
            if (episodes.isEmpty()) return@guard null
            val profileId = settings.activeProfileIdNow()
            if (profileId < 0) return@guard null
            val items = repo.playbackPositions()?.filter { it.type == MetadataType.TV } ?: return@guard null
            if (items.isEmpty()) return@guard null
            val cached = runCatching { metadata.resolveSeries(show) }.getOrNull() ?: return@guard null
            val mine = items.filter { it.matches(cached.tmdbId, cached.imdbId) }
            for (hit in mine) {
                val episode = episodes.firstOrNull { it.seasonNumber == hit.season && it.episodeNumber == hit.number } ?: continue
                // Never arm a deferred seek from here: nothing is about to play.
                apply(profileId, MediaType.EPISODE, episode.id, episode.durationSecs, streamUrl = null, hit = hit)
            }
            null
        }
        Unit
    }

    /**
     * The merge itself: Trakt's [hit] against the local row, in milliseconds when a duration is known,
     * else (on Always resume) armed for the engine. Returns the position to resume from, or null.
     */
    private suspend fun apply(profileId: Long, type: MediaType, itemId: Long, durationSecs: Int?, streamUrl: String?, hit: TraktPlaybackItem?): Long? {
        val local = progressDao.get(profileId, type, itemId)
        if (hit == null) return local?.positionMs
        if (local != null && local.updatedAt >= hit.pausedAtMs) return local.positionMs
        if (hit.progress < MIN_PERCENT || hit.progress > MAX_PERCENT) return local?.positionMs
        val durationMs = local?.durationMs?.takeIf { it > 0L } ?: durationSecs?.takeIf { it > 0 }?.let { it * 1000L }
        if (durationMs == null) {
            if (streamUrl != null && settings.resumeMode.first() == SettingsRepository.ResumeMode.AUTO) {
                pending = Pending(streamUrl, hit.progress, profileId, type, itemId)
            }
            return local?.positionMs
        }
        val positionMs = (durationMs * hit.progress / 100.0).toLong()
        write(profileId, type, itemId, positionMs, durationMs, hit.pausedAtMs)
        return positionMs
    }

    /** Core's merge write: insert when absent, then overwrite only when [at] is later than what is stored. */
    private suspend fun write(profileId: Long, type: MediaType, itemId: Long, positionMs: Long, durationMs: Long, at: Long) {
        progressDao.insertIfAbsent(
            PlaybackProgressEntity(profileId = profileId, mediaType = type, itemId = itemId, positionMs = positionMs, durationMs = durationMs, updatedAt = at),
        )
        progressDao.updateIfNewer(profileId, type, itemId, positionMs, durationMs, at)
    }

    private fun TraktPlaybackItem.matches(tmdbId: Int, imdbId: String?): Boolean =
        (this.tmdbId > 0 && this.tmdbId == tmdbId) || (!this.imdbId.isNullOrBlank() && this.imdbId == imdbId)

    /** Nothing here may stop a title from starting: a failure is a position not merged, nothing more. */
    private suspend fun guard(block: suspend () -> Long?): Long? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MIN_PERCENT = 2.0
        const val MAX_PERCENT = 95.0
        /** How long an armed seek waits for the engine before it is forgotten. */
        const val PENDING_TTL_MS = 60_000L
        /** Within this of the target the engine is already there: no seek. */
        const val SEEK_SLACK_MS = 5_000L
    }
}
