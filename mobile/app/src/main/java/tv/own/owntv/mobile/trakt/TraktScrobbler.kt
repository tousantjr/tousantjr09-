package tv.own.owntv.mobile.trakt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.player.enginePinKey
import tv.own.owntv.player.MediaMeta
import tv.own.owntv.player.OwnTVPlayer

/** What is being scrobbled: a film, or one episode of a show. Live TV never is. */
sealed interface ScrobbleTarget {
    val key: String
    val title: String

    data class Movie(val movie: MovieEntity) : ScrobbleTarget {
        override val key: String get() = "movie:" + movie.id
        override val title: String get() = movie.name
    }

    data class Episode(val show: SeriesEntity, val season: Int, val number: Int) : ScrobbleTarget {
        override val key: String get() = "episode:" + show.id + ":" + season + ":" + number
        override val title: String get() = show.name
    }
}

/**
 * Turns the player's state into Trakt scrobbles: `start` when the target plays, `pause` when it is
 * paused, `stop` when it ends, is left, or another target takes its place. Whoever starts a film or
 * an episode says so with [playing]; [stop] is for leaving the player, before the engine forgets its
 * position. The television's shell feeds [televisionPlayer] from what its view models hand the engine.
 *
 * Transitions settle for [SETTLE_MS] before anything is sent, so a scrub or a quick pause-and-resume
 * is one event, not three. The title is identified by the metadata cache's TMDB and IMDb ids, or by
 * title and year when the cache has no match. Nothing here is on the hot path: every call is a
 * launched coroutine, every failure is swallowed by [TraktRepository].
 */
class TraktScrobbler(
    private val player: OwnTVPlayer,
    private val repo: TraktRepository,
    private val metadata: MetadataRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val target = MutableStateFlow<ScrobbleTarget?>(null)

    /** The target last sent to Trakt, with the item object the calls carry and the progress last seen. */
    private class Session(val target: ScrobbleTarget, val item: JSONObject) {
        var playing = false
        var lastProgress = 0.0
    }

    private var session: Session? = null

    init {
        scope.launch {
            combine(target, player.isPlaying) { t, playing -> t to playing }.collectLatest { (t, playing) ->
                delay(SETTLE_MS)
                transition(t, playing)
            }
        }
        // While something plays, remember where it got to: an episode the player advanced past has
        // already reset the position by the time anyone hears of it.
        scope.launch {
            while (isActive) {
                delay(SAMPLE_MS)
                val s = session ?: continue
                if (s.playing) progressNow()?.let { s.lastProgress = it }
            }
        }
    }

    /** A film or an episode started (or the player moved to the next episode); null = nothing of ours. */
    fun playing(next: ScrobbleTarget?) {
        if (next?.key != target.value?.key) session?.let { s -> progressNow()?.let { s.lastProgress = it } }
        target.value = next
    }

    /** Leaving the player: the position is read now, before the engine drops it. */
    fun stop() {
        session?.let { s -> progressNow()?.let { s.lastProgress = it } }
        target.value = null
    }

    /**
     * The television's shell: the film and the show its view models last handed the engine, and what
     * the engine says it is on. An episode is the show plus the engine's season and episode numbers; a
     * film counts only while the engine's item is that film (its pin key, or its URL when it has none).
     */
    fun televisionPlayer(movie: MovieEntity?, show: SeriesEntity?, meta: MediaMeta) {
        val url = player.currentMediaUrl
        val season = meta.seasonNumber
        val number = meta.episodeNumber
        val trackKey = meta.trackKey
        val next: ScrobbleTarget? = when {
            url == null || player.isLiveContent -> null
            season != null && number != null && show != null -> {
                val seriesKey = enginePinKey(show.sourceId, "SERIES", show.remoteId)
                if (seriesKey != null && trackKey != null && trackKey != seriesKey) null
                else ScrobbleTarget.Episode(show, season, number)
            }
            season == null && movie != null -> {
                val movieKey = enginePinKey(movie.sourceId, "MOVIE", movie.remoteId)
                val on = if (movieKey != null) player.currentMediaContentKey == movieKey else url == movie.streamUrl
                if (on) ScrobbleTarget.Movie(movie) else null
            }
            else -> null
        }
        playing(next)
    }

    private suspend fun transition(next: ScrobbleTarget?, isPlaying: Boolean) {
        val current = session
        if (current != null && (next == null || next.key != current.target.key)) {
            send(ACTION_STOP, current, current.lastProgress)
            session = null
        }
        if (next == null) return
        val s = session ?: Session(next, item(next) ?: return).also { session = it }
        if (isPlaying && !s.playing) {
            s.playing = true
            send(ACTION_START, s, progressNow() ?: s.lastProgress)
        } else if (!isPlaying && s.playing) {
            s.playing = false
            send(ACTION_PAUSE, s, progressNow() ?: s.lastProgress)
        }
    }

    private suspend fun send(action: String, s: Session, progress: Double) {
        try {
            repo.scrobble(action, s.item, progress, s.target.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The repository already turned it into a status; nothing else to do here.
        }
    }

    /** Where the engine is, as a percentage, or null when it has nothing to say. */
    private fun progressNow(): Double? {
        val duration = player.duration.value
        val position = player.position.value
        if (duration <= 0L || position < 0L) return null
        return (position * 100.0 / duration).coerceIn(0.0, 100.0)
    }

    /** The `movie` or `show` + `episode` object a scrobble carries: ids from the metadata cache, else the name and year. */
    private suspend fun item(t: ScrobbleTarget): JSONObject? = try {
        when (t) {
            is ScrobbleTarget.Movie -> {
                val cached = runCatching { metadata.resolveMovie(t.movie) }.getOrNull()
                val movie = JSONObject()
                if (cached != null) movie.put("ids", ids(cached.tmdbId, cached.imdbId))
                else movie.put("title", t.movie.name).also { o -> t.movie.year?.let { o.put("year", it) } }
                JSONObject().put("movie", movie)
            }
            is ScrobbleTarget.Episode -> {
                val cached = runCatching { metadata.resolveSeries(t.show) }.getOrNull()
                val show = JSONObject()
                if (cached != null) show.put("ids", ids(cached.tmdbId, cached.imdbId))
                else show.put("title", t.show.name).also { o -> t.show.year?.let { o.put("year", it) } }
                JSONObject().put("show", show).put("episode", JSONObject().put("season", t.season).put("number", t.number))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun ids(tmdbId: Int, imdbId: String?): JSONObject {
        val ids = JSONObject().put("tmdb", tmdbId)
        if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
        return ids
    }

    private companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
        const val SETTLE_MS = 1_500L
        const val SAMPLE_MS = 10_000L
    }
}
