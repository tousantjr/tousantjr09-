package tv.own.owntv.mobile.trakt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.mobile.R
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.ProgressDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.SeriesRepository
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher
import tv.own.owntv.mobile.mdblist.CuratedItem
import tv.own.owntv.mobile.mdblist.CuratedRail
import tv.own.owntv.mobile.mdblist.MetadataIdLookup
import java.io.File

/** How a device-code login ended: connected, or declined with Trakt's status (0 = never reached). */
sealed interface TraktLoginResult {
    data class Connected(val username: String) : TraktLoginResult
    data class Declined(val code: Int) : TraktLoginResult
}

/**
 * What Trakt adds to the app, behind Settings → Layout → Home → Trakt: scrobbling (what is playing,
 * paused and finished goes to the user's Trakt profile), the watchlist and the Up next episodes as
 * Home rails, cut down to the titles the active playlists actually hold, and the paused positions
 * Trakt holds from other devices ([playbackPositions], which [TraktResume] folds into this device's).
 *
 * The login is Trakt's device code: the app shows a short code, the user types it at trakt.tv/activate
 * on any device, and the app polls until Trakt says yes. Tokens live in [TraktSettings] and are
 * refreshed here before they run out. The watchlist is kept twelve hours on disk, raw, like MDBList's
 * lists; the *matching* against the playlists is local and re-run on every Home load.
 *
 * Matching reuses core's own [TrendingMatcher] against each active playlist, exactly as the MDBList
 * rails do. A kids profile's hard block always applies, "Hide adult categories from Home" when on, and
 * a title the profile hid in Customize never comes back through here. A rail is shown only with
 * [MIN_MATCHES] titles or more.
 *
 * No token, client secret, URL or exception message that could quote one is ever logged.
 */
class TraktRepository(
    private val context: Context,
    private val settings: TraktSettings,
    private val api: TraktApi,
    private val appSettings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val profileDao: ProfileDao,
    private val categoryDao: CategoryDao,
    private val customize: CustomizationStore,
    private val progressDao: ProgressDao,
    private val seriesRepository: SeriesRepository,
    database: OwnTVDatabase,
) {

    /** What the last call came to, for Settings' status line. */
    sealed interface Status {
        data object Idle : Status
        /** The device login ended with this HTTP status (0 = Trakt could not be reached). */
        data class Declined(val code: Int) : Status
        data object TokenRejected : Status
        data class HttpError(val code: Int) : Status
        data object Unreachable : Status
        data object NoMatches : Status
        data class Shown(val titles: Int) : Status
        data class Scrobbled(val title: String) : Status
    }

    val config: Flow<TraktConfig> get() = settings.config

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val cacheMutex = Mutex()
    private val railMutex = Mutex()
    private val cache = DiskCache(File(context.cacheDir, CACHE_FILE))
    private val idLookup = MetadataIdLookup(database)

    // --- Login ---------------------------------------------------------------------------------------

    /** Starts a device-code login; null (and a status) when Trakt could not be asked. */
    suspend fun beginLogin(): TraktDeviceCode? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.hasCredentials) return@withContext null
        fetch { api.deviceCode(c.effectiveClientId) }
    }

    /**
     * Polls for [code]'s outcome until Trakt says yes or no, or the code expires — at the interval it
     * asked for, a second longer each time it asks to slow down. 400 is "not yet"; anything but 200,
     * 400 and 429 ends the login (404 invalid, 409 already used, 410 expired, 418 denied). Stores the
     * tokens and the username on success. Cancel the calling coroutine to abandon the login.
     */
    suspend fun finishLogin(code: TraktDeviceCode): TraktLoginResult = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.hasCredentials) return@withContext TraktLoginResult.Declined(0)
        var interval = code.intervalMs
        var result: TraktLoginResult? = null
        while (result == null) {
            delay(interval)
            if (System.currentTimeMillis() > code.expiresAtMs) {
                result = TraktLoginResult.Declined(CODE_EXPIRED)
                break
            }
            val poll = try {
                api.deviceToken(c.effectiveClientId, c.effectiveClientSecret, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Trakt unreachable: ${e.javaClass.simpleName}")
                _status.value = Status.Unreachable
                result = TraktLoginResult.Declined(0)
                break
            }
            when (poll) {
                is TraktTokenPoll.Granted -> {
                    val username = runCatching { api.me(c.effectiveClientId, poll.auth.accessToken) }.getOrDefault("")
                    settings.setAuth(poll.auth, username)
                    cacheMutex.withLock { cache.clearAll() }
                    _status.value = Status.Idle
                    result = TraktLoginResult.Connected(username)
                }
                TraktTokenPoll.Pending -> Unit
                TraktTokenPoll.SlowDown -> interval += SLOW_DOWN_MS
                is TraktTokenPoll.Declined -> {
                    _status.value = Status.Declined(poll.code)
                    result = TraktLoginResult.Declined(poll.code)
                }
            }
        }
        result ?: TraktLoginResult.Declined(0)
    }

    /** Forgets the tokens here and, best effort, at Trakt. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        val auth = c.auth
        if (auth != null && c.hasCredentials) api.revoke(c.effectiveClientId, c.effectiveClientSecret, auth.accessToken)
        settings.clearAuth()
        cacheMutex.withLock { cache.clearAll() }
        _status.value = Status.Idle
    }

    /**
     * A usable access token: the stored one, refreshed first when it is within a day of running out.
     * Null when not connected, or when the refresh failed (the status says why).
     */
    private suspend fun accessToken(c: TraktConfig): String? {
        val auth = c.auth ?: return null
        if (auth.expiresAtMs - System.currentTimeMillis() > REFRESH_AHEAD_MS || auth.refreshToken.isBlank()) return auth.accessToken
        val fresh = fetch { api.refresh(c.effectiveClientId, c.effectiveClientSecret, auth.refreshToken) } ?: return null
        settings.setAuth(fresh, null)
        return fresh.accessToken
    }

    // --- Scrobbling ----------------------------------------------------------------------------------

    /**
     * `/scrobble/[action]` for [item] at [progress] percent. [title] is only for the status line.
     * Silent on failure: the next event tries again. A 401 drops nothing — the token may just need
     * its refresh, which the next call makes.
     */
    suspend fun scrobble(action: String, item: JSONObject, progress: Double, title: String) = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.scrobbleOn) return@withContext
        val token = accessToken(c) ?: return@withContext
        fetch { api.scrobble(c.effectiveClientId, token, action, item, progress.coerceIn(0.0, 100.0)) } ?: return@withContext
        _status.value = Status.Scrobbled(title)
    }

    // --- Paused positions (Resume from Trakt) --------------------------------------------------------

    /**
     * Every position Trakt holds from a pause on any device — films and episodes — kept five minutes
     * so the two or three starts of an evening cost one round trip. Null while the setting is off or
     * not connected, and when a call failed (the status says why); [TraktResume] does the comparing.
     */
    suspend fun playbackPositions(): List<TraktPlaybackItem>? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.resumeOn) return@withContext null
        val now = System.currentTimeMillis()
        val out = ArrayList<TraktPlaybackItem>()
        for ((kind, type) in listOf(KIND_MOVIES to MetadataType.MOVIE, KIND_EPISODES to MetadataType.TV)) {
            val body = playbackBody(c, kind, now) ?: return@withContext null
            out += runCatching { TraktParser.playback(body, type) }
                .getOrElse { Log.w(TAG, "playback did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
        }
        out
    }

    /** One kind's raw playback answer, from the cache or Trakt; null when the call failed. */
    private suspend fun playbackBody(c: TraktConfig, kind: String, now: Long): String? {
        cacheMutex.withLock { cache.body(SECTION_PLAYBACK, kind, PLAYBACK_TTL_MS, now) }?.let { return it }
        val token = accessToken(c) ?: return null
        val fresh = fetch { api.playback(c.effectiveClientId, token, kind) } ?: return null
        cacheMutex.withLock { cache.put(SECTION_PLAYBACK, kind, fresh, now) }
        return fresh
    }

    // --- Home's rails --------------------------------------------------------------------------------

    /**
     * Home's Up next rail for [profileId]: for each show the user watched most recently on Trakt (the
     * twelve newest), the episode Trakt says comes next, when the profile's playlists hold the show and
     * that episode — one card per show, newest show first, finished episodes (watched to the end here)
     * left out. Shown with a single match: this is the user's own progress, not a public list. Null
     * while the row is off, and null (with a status) when a call failed.
     */
    suspend fun upNextRail(profileId: Long, hideAdult: Boolean): CuratedRail? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.upNextOn || profileId < 0) return@withContext null
        railMutex.withLock {
            val shows = watchedShows(c)?.take(MAX_UP_NEXT_SHOWS) ?: return@withLock null
            if (shows.isEmpty()) return@withLock null
            val seriesSources = activeSourceIds(appSettings, sourceDao, profileId, MediaType.SERIES)
            if (seriesSources.isEmpty()) return@withLock null
            val language = runCatching { appSettings.metadataConfig().resolvedLanguage }.getOrDefault(DEFAULT_LANGUAGE)
            val matched = matchShows(shows, seriesSources, language)
            if (matched.isEmpty()) return@withLock null
            val hiddenSeries = runCatching { customize.observe(profileId, MediaType.SERIES).first().hiddenItems.keys }.getOrDefault(emptySet())
            val categoryNames = HashMap<Long, String?>()
            val out = ArrayList<CuratedItem>()
            for (show in shows) {
                val series = matched[show.traktId] ?: continue
                if (CustomizeKeys.series(series) in hiddenSeries) continue
                if (!allowed(profileId, series.categoryId, hideAdult, categoryNames)) continue
                // One request per show, sequential; the first failure ends the pass (the status says why).
                val next = nextEpisode(c, show.traktId) ?: break
                val wanted = next.episode ?: continue
                seriesRepository.loadEpisodes(series)
                val episode = seriesDao.findEpisodeByNumber(series.id, wanted.season, wanted.number) ?: continue
                val local = progressDao.get(profileId, MediaType.EPISODE, episode.id)
                if (local != null && local.durationMs > 0 && local.positionMs >= (local.durationMs * FINISHED_FRACTION).toLong()) continue
                val title = wanted.title ?: episode.name.takeIf { it.isNotBlank() }
                val subtitle = if (title.isNullOrBlank()) context.getString(R.string.content_season_episode, wanted.season, wanted.number)
                else context.getString(R.string.content_season_episode_title, wanted.season, wanted.number, title)
                out += CuratedItem.Episode(series, episode, subtitle, local?.positionMs ?: 0L)
                if (out.size >= MAX_UP_NEXT) break
            }
            if (out.isEmpty()) null else CuratedRail(UP_NEXT_REF, context.getString(R.string.trakt_up_next_rail), out)
        }
    }

    /** The shows the user has watched, newest first (an hour's cache); null when the call failed. */
    private suspend fun watchedShows(c: TraktConfig): List<TraktWatchedShow>? {
        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock { cache.body(SECTION_WATCHED, KIND_SHOWS, WATCHED_TTL_MS, now) }
        val body = cached ?: run {
            val token = accessToken(c) ?: return null
            val fresh = fetch { api.watchedShows(c.effectiveClientId, token) } ?: return null
            cacheMutex.withLock { cache.put(SECTION_WATCHED, KIND_SHOWS, fresh, now) }
            fresh
        }
        return runCatching { TraktParser.watchedShows(body) }
            .getOrElse { Log.w(TAG, "watched shows did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
    }

    /** A show's next episode per Trakt (an hour's cache per show); null when the call failed, [NextEpisode] with null inside when the show is done. */
    private class NextEpisode(val episode: TraktNextEpisode?)

    private suspend fun nextEpisode(c: TraktConfig, traktId: Long): NextEpisode? {
        val now = System.currentTimeMillis()
        val key = traktId.toString()
        val cached = cacheMutex.withLock { cache.body(SECTION_PROGRESS, key, PROGRESS_TTL_MS, now) }
        val body = cached ?: run {
            val token = accessToken(c) ?: return null
            val fresh = fetch { api.showProgress(c.effectiveClientId, token, traktId) } ?: return null
            cacheMutex.withLock { cache.put(SECTION_PROGRESS, key, fresh, now) }
            fresh
        }
        return NextEpisode(runCatching { TraktParser.nextEpisode(body) }.getOrNull())
    }

    /**
     * Trakt id → the playlist's show, for [shows]: first by TMDB/IMDb id through the metadata layer's
     * match table (the MDBList rails' id pass), then by title through core's [TrendingMatcher] for the
     * rest; the first of [seriesSources] to hold a show keeps it.
     */
    private suspend fun matchShows(shows: List<TraktWatchedShow>, seriesSources: List<Long>, language: String): Map<Long, SeriesEntity> {
        val out = HashMap<Long, SeriesEntity>()
        val byIds = shows.filter { it.tmdbId > 0 || it.imdbId != null }
        if (byIds.isNotEmpty()) {
            val viaImdb = idLookup.tmdbIdsByImdb(byIds.mapNotNull { it.imdbId }.distinct())
            val rows = idLookup.matchRows((byIds.map { it.tmdbId }.filter { it > 0 } + viaImdb.values.flatten().map { it.tmdbId }).distinct())
            for (show in byIds) {
                val row = idLookup.bestRow(show.tmdbId, show.imdbId, MetadataIdLookup.TYPE_TV, seriesSources, rows, viaImdb) ?: continue
                val series = seriesDao.findSeriesByRemote(row.sourceId, row.tail) ?: seriesDao.findSeriesByName(row.sourceId, row.tail) ?: continue
                out[show.traktId] = series
            }
        }
        val open = shows.filter { it.traktId !in out && it.tmdbId > 0 }
        if (open.isNotEmpty()) {
            val candidates = open.mapIndexed { i, s ->
                TrendingCandidate(
                    tmdbId = s.tmdbId, type = MetadataType.TV, localizedTitle = s.title, originalTitle = null, year = s.year,
                    overview = null, posterPath = null, backdropPath = null, rating = null, popularity = 0.0, trendingRank = i + 1,
                )
            }
            val ids = matchIds(
                candidates, MetadataType.TV, MediaType.SERIES, seriesSources, language,
                exact = { sid, signatures -> seriesDao.trendingExact(sid, signatures) },
                fts = { sid, query, limit -> seriesDao.trendingFts(sid, query, limit) },
            )
            if (ids.isNotEmpty()) {
                val series = seriesDao.getSeriesByIds(ids.values.toList()).associateBy { it.id }
                for (show in open) ids[show.tmdbId]?.let(series::get)?.let { out[show.traktId] = it }
            }
        }
        return out
    }

    /**
     * Home's watchlist rail for [profileId]: the user's Trakt watchlist cut down to the titles the
     * profile's active playlists hold, in Trakt's order; null with fewer than [MIN_MATCHES]. Null
     * while the row is off.
     */
    suspend fun watchlistRail(profileId: Long, hideAdult: Boolean): CuratedRail? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.watchlistOn || profileId < 0) return@withContext null
        railMutex.withLock {
            val items = watchlist(c) ?: return@withLock null
            val movieSources = activeSourceIds(appSettings, sourceDao, profileId, MediaType.MOVIE)
            val seriesSources = activeSourceIds(appSettings, sourceDao, profileId, MediaType.SERIES)
            if (movieSources.isEmpty() && seriesSources.isEmpty()) return@withLock null
            val language = runCatching { appSettings.metadataConfig().resolvedLanguage }.getOrDefault(DEFAULT_LANGUAGE)
            val matched = match(items, movieSources, seriesSources, language, profileId, hideAdult)
            if (matched.size < MIN_MATCHES) {
                _status.value = Status.NoMatches
                null
            } else {
                _status.value = Status.Shown(matched.size)
                CuratedRail(RAIL_REF, context.getString(R.string.trakt_watchlist_rail), matched)
            }
        }
    }

    /** The watchlist, films then shows (twelve hours' cache); null when a call failed (the status says why). */
    private suspend fun watchlist(c: TraktConfig): List<TraktWatchlistItem>? {
        val now = System.currentTimeMillis()
        val out = ArrayList<TraktWatchlistItem>()
        for ((kind, type) in listOf(KIND_MOVIES to MetadataType.MOVIE, KIND_SHOWS to MetadataType.TV)) {
            val cached = cacheMutex.withLock { cache.body(SECTION_WATCHLIST, kind, WATCHLIST_TTL_MS, now) }
            val body = cached ?: run {
                val token = accessToken(c) ?: return null
                val fresh = fetch { api.watchlist(c.effectiveClientId, token, kind) } ?: return null
                cacheMutex.withLock { cache.put(SECTION_WATCHLIST, kind, fresh, now) }
                fresh
            }
            out += runCatching { TraktParser.watchlist(body, type) }
                .getOrElse { Log.w(TAG, "watchlist did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
        }
        return out
    }

    /** After a Home load that may follow a change at Trakt: the next rail build asks again. */
    suspend fun forgetWatchlist() = withContext(Dispatchers.IO) {
        cacheMutex.withLock { cache.clear(SECTION_WATCHLIST) }
    }

    private suspend fun match(
        items: List<TraktWatchlistItem>,
        movieSources: List<Long>,
        seriesSources: List<Long>,
        language: String,
        profileId: Long,
        hideAdult: Boolean,
    ): List<CuratedItem> {
        val candidates = items.take(MAX_CANDIDATES).map { it.toCandidate() }
        val movieIds = matchIds(
            candidates, MetadataType.MOVIE, MediaType.MOVIE, movieSources, language,
            exact = { sid, signatures -> movieDao.trendingExact(sid, signatures) },
            fts = { sid, query, limit -> movieDao.trendingFts(sid, query, limit) },
        )
        val seriesIds = matchIds(
            candidates, MetadataType.TV, MediaType.SERIES, seriesSources, language,
            exact = { sid, signatures -> seriesDao.trendingExact(sid, signatures) },
            fts = { sid, query, limit -> seriesDao.trendingFts(sid, query, limit) },
        )
        val movies = if (movieIds.isEmpty()) emptyMap() else movieDao.getByIds(movieIds.values.toList()).associateBy { it.id }
        val series = if (seriesIds.isEmpty()) emptyMap() else seriesDao.getSeriesByIds(seriesIds.values.toList()).associateBy { it.id }
        val hiddenMovies = runCatching { customize.observe(profileId, MediaType.MOVIE).first().hiddenItems.keys }.getOrDefault(emptySet())
        val hiddenSeries = runCatching { customize.observe(profileId, MediaType.SERIES).first().hiddenItems.keys }.getOrDefault(emptySet())
        val categoryNames = HashMap<Long, String?>()
        val out = ArrayList<CuratedItem>()
        for (candidate in candidates) {
            val item: CuratedItem = when (candidate.type) {
                MetadataType.MOVIE -> movieIds[candidate.tmdbId]?.let(movies::get)?.takeIf { CustomizeKeys.movie(it) !in hiddenMovies }?.let(CuratedItem::Movie)
                MetadataType.TV -> seriesIds[candidate.tmdbId]?.let(series::get)?.takeIf { CustomizeKeys.series(it) !in hiddenSeries }?.let(CuratedItem::Series)
                MetadataType.EPISODE -> null
            } ?: continue
            if (!allowed(profileId, item.categoryId, hideAdult, categoryNames)) continue
            out += item
            if (out.size >= MAX_PER_RAIL) break
        }
        return out
    }

    /** TMDB id → the playlist row's id, across [sources] in order: the first playlist to hold a title keeps it. */
    private suspend fun matchIds(
        candidates: List<TrendingCandidate>,
        type: MetadataType,
        media: MediaType,
        sources: List<Long>,
        language: String,
        exact: suspend (Long, List<String>) -> List<TrendingCatalogRow>,
        fts: suspend (Long, String, Int) -> List<TrendingCatalogRow>,
    ): Map<Int, Long> {
        val found = LinkedHashMap<Int, Long>()
        val mine = candidates.filter { it.type == type }
        if (mine.isEmpty()) return found
        for (sid in sources) {
            val open = mine.filter { it.tmdbId !in found }
            if (open.isEmpty()) break
            val result = TrendingMatcher.matchMedia(
                candidates = open,
                mediaType = media,
                preferredLanguage = language,
                limit = MAX_PER_RAIL,
                exactLookup = { signatures -> exact(sid, signatures) },
                ftsLookup = { query, limit -> fts(sid, query, limit) },
            )
            for (selection in result.selections) found.putIfAbsent(selection.candidate.tmdbId, selection.variant.item.id)
        }
        return found
    }

    private suspend fun allowed(profileId: Long, categoryId: Long?, hideAdult: Boolean, names: MutableMap<Long, String?>): Boolean {
        if (!AdultCategoryClassifier.allows(profileId, categoryId, profileDao, categoryDao)) return false
        if (!hideAdult || categoryId == null) return true
        val name = names.getOrPut(categoryId) { categoryDao.getById(categoryId)?.name }
        return !AdultCategoryClassifier.isAdult(name)
    }

    private fun TraktWatchlistItem.toCandidate() = TrendingCandidate(
        tmdbId = tmdbId,
        type = type,
        localizedTitle = title,
        originalTitle = null,
        year = year,
        overview = null,
        posterPath = null,
        backdropPath = null,
        rating = null,
        popularity = 0.0,
        trendingRank = rank,
    )

    // --- Plumbing ------------------------------------------------------------------------------------

    /** Runs one call, turning a failure into a status and null — never an exception past this point. */
    private suspend fun <T : Any> fetch(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TraktHttpException) {
        noteHttp(e.code)
        null
    } catch (e: Exception) {
        Log.w(TAG, "Trakt unreachable: ${e.javaClass.simpleName}")
        _status.value = Status.Unreachable
        null
    }

    private fun noteHttp(code: Int) {
        _status.value = when (code) {
            401, 403 -> Status.TokenRejected
            else -> Status.HttpError(code)
        }
    }

    /** The raw watchlist answers, each with the time it arrived, in one JSON file in the app's cache directory. */
    private class DiskCache(private val file: File) {
        private var root: JSONObject? = null

        private fun root(): JSONObject = root ?: runCatching { if (file.isFile) JSONObject(file.readText()) else null }
            .getOrNull().let { it ?: JSONObject() }.also { root = it }

        fun body(section: String, key: String, maxAgeMs: Long, now: Long): String? {
            val entry = root().optJSONObject(section)?.optJSONObject(key) ?: return null
            if (now - entry.optLong(FIELD_AT) > maxAgeMs) return null
            return entry.optString(FIELD_BODY)
        }

        fun put(section: String, key: String, body: String, now: Long) {
            val r = root()
            val sec = r.optJSONObject(section) ?: JSONObject().also { r.put(section, it) }
            sec.put(key, JSONObject().put(FIELD_AT, now).put(FIELD_BODY, body))
            save(r)
        }

        fun clear(section: String) {
            val r = root()
            if (r.has(section)) {
                r.remove(section)
                save(r)
            }
        }

        /** Another account, or none: nothing cached belongs to it. */
        fun clearAll() {
            val r = JSONObject()
            root = r
            save(r)
        }

        private fun save(r: JSONObject) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(r.toString())
            }.onFailure { Log.w(TAG, "cache not written: ${it.javaClass.simpleName}") }
        }

        private companion object {
            const val FIELD_AT = "at"
            const val FIELD_BODY = "body"
        }
    }

    companion object {
        /** The rails' keys on Home, beside the MDBList rails' list references. */
        const val RAIL_REF = "trakt/watchlist"
        const val UP_NEXT_REF = "trakt/up-next"
        private const val TAG = "OwnTV-Trakt"
        private const val CACHE_FILE = "trakt_cache.json"
        private const val SECTION_WATCHLIST = "watchlist"
        private const val SECTION_PLAYBACK = "playback"
        private const val SECTION_WATCHED = "watched"
        private const val SECTION_PROGRESS = "progress"
        private const val KIND_MOVIES = "movies"
        private const val KIND_SHOWS = "shows"
        private const val KIND_EPISODES = "episodes"
        private const val DEFAULT_LANGUAGE = "en"
        private const val WATCHLIST_TTL_MS = 12 * 60 * 60 * 1000L
        private const val PLAYBACK_TTL_MS = 5 * 60 * 1000L
        private const val WATCHED_TTL_MS = 60 * 60 * 1000L
        private const val PROGRESS_TTL_MS = 60 * 60 * 1000L
        /** The shows the Up next rail asks about: the ones watched most recently. */
        private const val MAX_UP_NEXT_SHOWS = 12
        /** The most cards the Up next rail shows. */
        private const val MAX_UP_NEXT = 12
        /** An episode watched this far here is finished, as the Series screen counts it, and leaves Up next. */
        private const val FINISHED_FRACTION = 0.95
        private const val REFRESH_AHEAD_MS = 24 * 60 * 60 * 1000L
        /** Trakt's 429 on the device poll: wait this much longer between polls, each time. */
        private const val SLOW_DOWN_MS = 1_000L
        /** Trakt's status for a device code that ran out, used when the clock says so before Trakt does. */
        const val CODE_EXPIRED = 410
        const val CODE_DENIED = 418
        /** How many watchlist entries are tried against the playlists (the FTS fallback is per item). */
        private const val MAX_CANDIDATES = 60
        /** The most titles the rail shows. */
        private const val MAX_PER_RAIL = 20
        /** Fewer matches than this and the rail is not drawn at all. */
        private const val MIN_MATCHES = 4
    }
}
