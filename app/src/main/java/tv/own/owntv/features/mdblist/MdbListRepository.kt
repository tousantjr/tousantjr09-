package tv.own.owntv.features.mdblist

import android.content.Context
import android.util.Log
import androidx.room.useReaderConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeSourceIds
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher
import java.io.File

/** One curated rail on Home: a list's name and the titles of it the playlists hold, in the list's order. */
data class CuratedRail(val ref: String, val title: String, val items: List<CuratedItem>)

/** A title on a curated rail: the playlist's own entry, so a click opens the usual page. */
sealed interface CuratedItem {
    val key: String
    val name: String
    val posterUrl: String?
    val rating: Double?
    val categoryId: Long?

    data class Movie(val movie: MovieEntity) : CuratedItem {
        override val key: String get() = KEY_MOVIE + movie.id
        override val name: String get() = movie.name
        override val posterUrl: String? get() = movie.posterUrl
        override val rating: Double? get() = movie.rating
        override val categoryId: Long? get() = movie.categoryId
    }

    data class Series(val series: SeriesEntity) : CuratedItem {
        override val key: String get() = KEY_SERIES + series.id
        override val name: String get() = series.name
        override val posterUrl: String? get() = series.posterUrl
        override val rating: Double? get() = series.rating
        override val categoryId: Long? get() = series.categoryId
    }

    companion object {
        private const val KEY_MOVIE = "movie:"
        private const val KEY_SERIES = "series:"
    }
}

/**
 * The titles this profile hid through Customize, as [CustomizeKeys] (which survive a re-sync), so a
 * curated rail never shows what the Movies and Series screens no longer do.
 */
data class HiddenTitles(val movies: Set<String> = emptySet(), val series: Set<String> = emptySet())

/**
 * What MDBList adds to the app, behind Settings → Layout → Home → MDBList: curated rails on Home (a
 * public list, cut down to the titles the active playlists actually hold) and a title's ratings from
 * IMDb, Rotten Tomatoes, Metacritic and Letterboxd on its details page.
 *
 * Frugal, because the free tier is 1000 requests a day: a list's items are kept for twelve hours, the
 * top lists for a day and a title's ratings for a week, on disk ([DiskCache]) so a restart costs
 * nothing. The *matching* of a list against the playlists is local and re-run on every Home load, so a
 * sync that brings a title in shows it without another call.
 *
 * Matching is two passes against each active playlist. First by id: a title the metadata layer has
 * already resolved to the item's TMDB id (or to the TMDB id the cache holds for its IMDb id) for one
 * of these playlists is taken as is — exact, and indifferent to how the playlist spells the title.
 * The rest go through core's own [TrendingMatcher] — the exact title-signature lookup and the FTS
 * fallback that core's Trending uses for TMDB's titles — so a list item is shown only when the
 * playlist holds it under a title that passes the same test. A kids profile's hard block
 * ([AdultCategoryClassifier.allows]) always applies; "Hide adult categories from Home" applies when on;
 * a title hidden through Customize ([HiddenTitles]) never shows. A rail is shown only with
 * [MIN_MATCHES] titles or more.
 *
 * The key is never logged — nor is any message that could quote a URL.
 */
class MdbListRepository(
    context: Context,
    private val settings: MdbListSettings,
    private val api: MdbListApi,
    private val appSettings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val profileDao: ProfileDao,
    private val categoryDao: CategoryDao,
    private val database: OwnTVDatabase,
) {

    /** What the last call came to, for Settings' status line. */
    sealed interface Status {
        data object Idle : Status
        data object NoKey : Status
        data object KeyRejected : Status
        data object Quota : Status
        data class HttpError(val code: Int) : Status
        data object Unreachable : Status
        data object NoMatches : Status
        data class Shown(val rails: Int, val titles: Int) : Status
    }

    val config: Flow<MdbListConfig> get() = settings.config

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val cacheMutex = Mutex()
    private val railsMutex = Mutex()
    private val cache = DiskCache(File(context.cacheDir, CACHE_FILE))

    // --- Ratings -------------------------------------------------------------------------------------

    /** One title's ratings, from the cache (a week) or MDBList; null while off, for a title it does not know, or on a failure. */
    suspend fun ratings(tmdbId: Int, type: MetadataType): MdbRatings? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.ratingsOn || tmdbId <= 0 || type == MetadataType.EPISODE) return@withContext null
        val key = type.name.lowercase() + ":" + tmdbId
        val now = System.currentTimeMillis()
        cacheMutex.withLock { cache.body(SECTION_RATINGS, key, RATINGS_TTL_MS, now) }?.let { return@withContext parseRatings(it) }
        // An unknown title is remembered as "" so it is not asked for again this week.
        val body = fetch { api.ratings(tmdbId, type, c.effectiveKey).orEmpty() } ?: return@withContext null
        cacheMutex.withLock { cache.put(SECTION_RATINGS, key, body, now, MAX_RATINGS_ENTRIES) }
        parseRatings(body)
    }

    private fun parseRatings(body: String): MdbRatings? {
        if (body.isEmpty()) return null
        return runCatching { MdbListParser.ratings(body) }
            .getOrElse { Log.w(TAG, "ratings did not parse as expected: ${it.javaClass.simpleName}"); null }
            ?.takeIf { it.hasAnything }
    }

    // --- Lists ---------------------------------------------------------------------------------------

    /** MDBList's public top lists (a day's cache), for Settings' picker and the default rails; empty on a failure. */
    suspend fun topLists(): List<MdbListInfo> = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.hasKey) {
            _status.value = Status.NoKey
            return@withContext emptyList<MdbListInfo>()
        }
        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock { cache.body(SECTION_TOP, KEY_TOP, TOP_TTL_MS, now) }
        val body = cached ?: fetch { api.topLists(c.effectiveKey) }?.also { fresh ->
            cacheMutex.withLock { cache.put(SECTION_TOP, KEY_TOP, fresh, now, 1) }
        } ?: return@withContext emptyList<MdbListInfo>()
        runCatching { MdbListParser.lists(body) }
            .getOrElse { Log.w(TAG, "top lists did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
    }

    /** A list's items (twelve hours' cache); null when the call failed (the status says why), empty for a reference that is not a list. */
    suspend fun listItems(ref: String): List<MdbListItem>? = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.hasKey) {
            _status.value = Status.NoKey
            return@withContext null
        }
        val now = System.currentTimeMillis()
        val cached = cacheMutex.withLock { cache.body(SECTION_ITEMS, ref, ITEMS_TTL_MS, now) }
        val body = cached ?: run {
            val fresh = fetch { api.listItems(ref, c.effectiveKey).orEmpty() } ?: return@withContext null
            cacheMutex.withLock { cache.put(SECTION_ITEMS, ref, fresh, now, MAX_ITEM_ENTRIES) }
            fresh
        }
        if (body.isEmpty()) return@withContext emptyList<MdbListItem>()
        runCatching { MdbListParser.items(body) }
            .getOrElse { Log.w(TAG, "list items did not parse as expected: ${it.javaClass.simpleName}"); emptyList() }
    }

    /** Settings' "Test key": one call with the current key. A pass forgets the cached top lists, which the old key fetched. */
    suspend fun testKey(): MdbKeyTest = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.hasKey) {
            _status.value = Status.NoKey
            return@withContext MdbKeyTest(0, 0)
        }
        val result = api.testKey(c.effectiveKey)
        when {
            result.ok -> {
                cacheMutex.withLock { cache.clear(SECTION_TOP) }
                _status.value = Status.Idle
            }
            result.httpCode == 0 -> _status.value = Status.Unreachable
            else -> noteHttp(result.httpCode)
        }
        result
    }

    /** After the key changes: what the old key fetched says nothing about the new one. */
    suspend fun forgetLists() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            cache.clear(SECTION_TOP)
            cache.clear(SECTION_ITEMS)
        }
        _status.value = Status.Idle
    }

    // --- Home's rails --------------------------------------------------------------------------------

    /**
     * Home's curated rails for [profileId]: the chosen lists (or the top three), each cut down to the
     * titles the profile's active playlists hold, in the list's order; a rail with fewer than
     * [MIN_MATCHES] is left out, and a title in [hidden] never shows. Empty while the rows are off or
     * no playlist has films or shows.
     */
    suspend fun rails(profileId: Long, hideAdult: Boolean, hidden: HiddenTitles = HiddenTitles()): List<CuratedRail> = withContext(Dispatchers.IO) {
        val c = settings.config.first()
        if (!c.rowsOn || profileId < 0) return@withContext emptyList<CuratedRail>()
        railsMutex.withLock {
            val chosen = chosenLists(c)
            if (chosen.isEmpty()) return@withLock emptyList<CuratedRail>()
            val movieSources = activeSourceIds(appSettings, sourceDao, profileId, MediaType.MOVIE)
            val seriesSources = activeSourceIds(appSettings, sourceDao, profileId, MediaType.SERIES)
            if (movieSources.isEmpty() && seriesSources.isEmpty()) return@withLock emptyList<CuratedRail>()
            val language = runCatching { appSettings.metadataConfig().resolvedLanguage }.getOrDefault(DEFAULT_LANGUAGE)
            val categoryNames = HashMap<Long, String?>()
            val rails = ArrayList<CuratedRail>()
            var titles = 0
            var failed = false
            for (list in chosen) {
                val items = listItems(list.ref)
                if (items == null) {
                    failed = true
                    continue
                }
                val matched = match(items, movieSources, seriesSources, language, profileId, hideAdult, hidden, categoryNames)
                if (matched.size >= MIN_MATCHES) {
                    rails += CuratedRail(list.ref, list.title, matched)
                    titles += matched.size
                }
            }
            if (!failed) _status.value = if (rails.isEmpty()) Status.NoMatches else Status.Shown(rails.size, titles)
            rails
        }
    }

    private data class ListRef(val ref: String, val title: String)

    private suspend fun chosenLists(c: MdbListConfig): List<ListRef> {
        val top = topLists()
        if (c.lists.isEmpty()) return top.take(DEFAULT_LISTS).map { ListRef(it.ref, it.name) }
        return c.lists.map { ref -> ListRef(ref, top.firstOrNull { it.ref == ref }?.name ?: titleFromRef(ref)) }
    }

    /** "user/best-horror-2024" → "Best Horror 2024": the slug, for a typed list the top lists do not name. */
    private fun titleFromRef(ref: String): String = ref.substringAfterLast('/')
        .split('-', '_').filter { it.isNotBlank() }
        .joinToString(separator = " ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { ref }

    /**
     * A list's titles the playlists hold, in the list's order. First by id ([matchByIds]) — a title the
     * metadata layer has already resolved to this TMDB or IMDb id for one of these playlists — then, for
     * the rest, by title through [TrendingMatcher]. The id pass is exact and language-proof, which the
     * title pass is not: a playlist naming a film by its local title, or with a year the list lacks,
     * still matches once its details have ever been looked up.
     */
    private suspend fun match(
        items: List<MdbListItem>,
        movieSources: List<Long>,
        seriesSources: List<Long>,
        language: String,
        profileId: Long,
        hideAdult: Boolean,
        hidden: HiddenTitles,
        categoryNames: MutableMap<Long, String?>,
    ): List<CuratedItem> {
        val wanted = items.take(MAX_CANDIDATES)
        val byId = matchByIds(wanted, movieSources, seriesSources)
        val candidates = wanted.filter { it.idKey !in byId }.map { it.toCandidate() }
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
        val out = ArrayList<CuratedItem>()
        for (entry in wanted) {
            val item: CuratedItem = byId[entry.idKey] ?: when (entry.type) {
                MetadataType.MOVIE -> movieIds[entry.tmdbId]?.let(movies::get)?.let(CuratedItem::Movie)
                MetadataType.TV -> seriesIds[entry.tmdbId]?.let(series::get)?.let(CuratedItem::Series)
                MetadataType.EPISODE -> null
            } ?: continue
            if (item.isHidden(hidden)) continue
            if (!allowed(profileId, item.categoryId, hideAdult, categoryNames)) continue
            out += item
            if (out.size >= MAX_PER_RAIL) break
        }
        return out
    }

    /**
     * The id pass: for each item, the playlist row the metadata layer has resolved to its TMDB id —
     * the match table holds one for every title whose details were ever shown, and for what the
     * trending backfill resolved — the first of [movieSources] / [seriesSources] to hold it winning,
     * as in the title pass. The item's IMDb id is tried too, through the TMDB id the cache holds for
     * it, since MDBList and TMDB occasionally disagree on a TMDB id. Keyed like [idKey].
     */
    private suspend fun matchByIds(
        items: List<MdbListItem>,
        movieSources: List<Long>,
        seriesSources: List<Long>,
    ): Map<Pair<MetadataType, Int>, CuratedItem> {
        val wanted = items.filter { it.type != MetadataType.EPISODE && it.tmdbId > 0 }
        if (wanted.isEmpty() || (movieSources.isEmpty() && seriesSources.isEmpty())) return emptyMap()
        val viaImdb = tmdbIdsByImdb(wanted.mapNotNull { it.imdbId }.distinct())
        val rows = matchRows((wanted.map { it.tmdbId } + viaImdb.values.flatten().map { it.tmdbId }).distinct())
        if (rows.isEmpty()) return emptyMap()
        val out = HashMap<Pair<MetadataType, Int>, CuratedItem>()
        for (item in wanted) {
            val (type, sources) = when (item.type) {
                MetadataType.MOVIE -> TYPE_MOVIE to movieSources
                MetadataType.TV -> TYPE_TV to seriesSources
                MetadataType.EPISODE -> continue
            }
            if (sources.isEmpty()) continue
            val ids = (listOf(item.tmdbId) + item.imdbId?.let(viaImdb::get).orEmpty().filter { it.type == type }.map { it.tmdbId }).distinct()
            val row = ids.flatMap { rows[it].orEmpty() }
                .filter { it.type == type && it.sourceId in sources }
                .minByOrNull { sources.indexOf(it.sourceId) } ?: continue
            val found: CuratedItem = when (item.type) {
                MetadataType.MOVIE ->
                    (movieDao.findByRemote(row.sourceId, row.tail) ?: movieDao.findByName(row.sourceId, row.tail))?.let(CuratedItem::Movie)
                else ->
                    (seriesDao.findSeriesByRemote(row.sourceId, row.tail) ?: seriesDao.findSeriesByName(row.sourceId, row.tail))?.let(CuratedItem::Series)
            } ?: continue
            out[item.idKey] = found
        }
        return out
    }

    /** What the id pass and the title pass are keyed by: a list item's type and TMDB id. */
    private val MdbListItem.idKey: Pair<MetadataType, Int> get() = type to tmdbId

    private fun CuratedItem.isHidden(hidden: HiddenTitles): Boolean = when (this) {
        is CuratedItem.Movie -> CustomizeKeys.movie(movie) in hidden.movies
        is CuratedItem.Series -> CustomizeKeys.series(series) in hidden.series
    }

    /** One match-table row: the playlist, and the provider's id (or the name, for an M3U row) its key names. */
    private data class LocalRow(val type: String, val sourceId: Long, val tail: String) {
        companion object {
            /** `movie:12:tt-remote` or `movie:12:Some Title`, minus the `:kids` a kids profile's lookup appends; null for anything else. */
            fun parse(localKey: String, type: String): LocalRow? {
                val afterType = localKey.removeSuffix(KIDS_SUFFIX).substringAfter(':', "").ifEmpty { return null }
                val sourceId = afterType.substringBefore(':').toLongOrNull() ?: return null
                val tail = afterType.substringAfter(':', "").ifEmpty { return null }
                return LocalRow(type, sourceId, tail)
            }
        }
    }

    /** A cache row's identity: its type and TMDB id (one row per metadata language, hence a list per IMDb id). */
    private data class CachedId(val type: String, val tmdbId: Int)

    /**
     * The match table's rows resolved to [tmdbIds]: TMDB id → the local keys. A raw statement, because
     * core's DAO reads that table by local key only, and core is a compiled dependency.
     */
    private suspend fun matchRows(tmdbIds: List<Int>): Map<Int, List<LocalRow>> {
        if (tmdbIds.isEmpty()) return emptyMap()
        val out = HashMap<Int, MutableList<LocalRow>>()
        for (chunk in tmdbIds.chunked(QUERY_CHUNK)) {
            database.useReaderConnection { connection ->
                connection.usePrepared(SQL_MATCH_ROWS + placeholders(chunk.size)) { statement ->
                    chunk.forEachIndexed { i, id -> statement.bindLong(i + 1, id.toLong()) }
                    while (statement.step()) {
                        val row = LocalRow.parse(statement.getText(0), statement.getText(2)) ?: continue
                        out.getOrPut(statement.getLong(1).toInt()) { ArrayList() } += row
                    }
                }
            }
        }
        return out
    }

    /** The cache's TMDB ids for [imdbIds]: IMDb id → the cache rows (type and TMDB id) carrying it. */
    private suspend fun tmdbIdsByImdb(imdbIds: List<String>): Map<String, List<CachedId>> {
        if (imdbIds.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<CachedId>>()
        for (chunk in imdbIds.chunked(QUERY_CHUNK)) {
            database.useReaderConnection { connection ->
                connection.usePrepared(SQL_CACHE_BY_IMDB + placeholders(chunk.size)) { statement ->
                    chunk.forEachIndexed { i, id -> statement.bindText(i + 1, id) }
                    while (statement.step()) {
                        if (statement.isNull(0)) continue
                        out.getOrPut(statement.getText(0)) { ArrayList() } += CachedId(statement.getText(2), statement.getLong(1).toInt())
                    }
                }
            }
        }
        return out
    }

    /** `?,?,?)` for [n] bound values — the tail of an `IN (` clause. */
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",") + ")"

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

    private fun MdbListItem.toCandidate() = TrendingCandidate(
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
    private suspend fun <T : Any> fetch(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MdbListHttpException) {
        noteHttp(e.code)
        null
    } catch (e: Exception) {
        Log.w(TAG, "MDBList unreachable: ${e.javaClass.simpleName}")
        _status.value = Status.Unreachable
        null
    }

    private fun noteHttp(code: Int) {
        _status.value = when (code) {
            401, 403 -> Status.KeyRejected
            429 -> Status.Quota
            else -> Status.HttpError(code)
        }
    }

    /**
     * The raw answers, by section and key, each with the time it arrived, in one JSON file in the
     * app's cache directory. Raw bodies rather than parsed models: the parser is the one source of
     * truth for the shape, and a cache entry is read through it exactly like a fresh answer.
     */
    private class DiskCache(private val file: File) {
        private var root: JSONObject? = null

        private fun root(): JSONObject = root ?: runCatching { if (file.isFile) JSONObject(file.readText()) else null }
            .getOrNull().let { it ?: JSONObject() }.also { root = it }

        /** The body stored under [key], or null when there is none or it is older than [maxAgeMs]. */
        fun body(section: String, key: String, maxAgeMs: Long, now: Long): String? {
            val entry = root().optJSONObject(section)?.optJSONObject(key) ?: return null
            if (now - entry.optLong(FIELD_AT) > maxAgeMs) return null
            return entry.optString(FIELD_BODY)
        }

        fun put(section: String, key: String, body: String, now: Long, maxEntries: Int) {
            val r = root()
            val sec = r.optJSONObject(section) ?: JSONObject().also { r.put(section, it) }
            sec.put(key, JSONObject().put(FIELD_AT, now).put(FIELD_BODY, body))
            while (sec.length() > maxEntries) {
                val oldest = sec.keys().asSequence().minByOrNull { sec.optJSONObject(it)?.optLong(FIELD_AT) ?: 0L } ?: break
                sec.remove(oldest)
            }
            save(r)
        }

        fun clear(section: String) {
            val r = root()
            if (r.has(section)) {
                r.remove(section)
                save(r)
            }
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

    private companion object {
        const val TAG = "OwnTV-MDBList"
        const val CACHE_FILE = "mdblist_cache.json"
        const val SECTION_RATINGS = "ratings"
        const val SECTION_ITEMS = "items"
        const val SECTION_TOP = "top"
        const val KEY_TOP = "top"
        const val DEFAULT_LANGUAGE = "en"
        /** The match and cache tables' `type` column for a film and a show, as core writes them. */
        const val TYPE_MOVIE = "movie"
        const val TYPE_TV = "tv"
        /** What a kids profile's lookup appends to a local key — the same local row either way. */
        const val KIDS_SUFFIX = ":kids"
        const val SQL_MATCH_ROWS = "SELECT localKey, tmdbId, type FROM metadata_match WHERE tmdbId IN ("
        const val SQL_CACHE_BY_IMDB = "SELECT imdbId, tmdbId, type FROM metadata_cache WHERE imdbId IN ("
        /** Bound values per statement — far under SQLite's limit, and a list tries at most [MAX_CANDIDATES] anyway. */
        const val QUERY_CHUNK = 400
        const val RATINGS_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val ITEMS_TTL_MS = 12 * 60 * 60 * 1000L
        const val TOP_TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_RATINGS_ENTRIES = 400
        const val MAX_ITEM_ENTRIES = 12
        /** How many of a list's items are tried against the playlists (the FTS fallback is per item). */
        const val MAX_CANDIDATES = 60
        /** The most titles one rail shows. */
        const val MAX_PER_RAIL = 20
        /** Fewer matches than this and the rail is not drawn at all. */
        const val MIN_MATCHES = 4
        /** With no list chosen, this many of the top lists. */
        const val DEFAULT_LISTS = 3
    }
}
