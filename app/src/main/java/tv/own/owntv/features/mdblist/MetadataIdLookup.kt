package tv.own.owntv.features.mdblist

import androidx.room.useReaderConnection
import tv.own.owntv.core.database.OwnTVDatabase

/**
 * The metadata layer's tables read by TMDB and IMDb id: which playlist rows a title was matched to, and
 * which TMDB id the cache holds for an IMDb id. Shared by the MDBList rails and the Trakt rails, which
 * both start from a list of ids and want the playlists' own entries for them.
 *
 * Raw statements through Room's reader connection, because core's DAO reads these tables by local key
 * only and core is a compiled dependency: two bound, chunked reads, nothing written.
 */
internal class MetadataIdLookup(private val database: OwnTVDatabase) {

    /** One match-table row: the playlist, and the provider's id (or the name, for an M3U row) its key names. */
    data class LocalRow(val type: String, val sourceId: Long, val tail: String) {
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
    data class CachedId(val type: String, val tmdbId: Int)

    /** The match table's rows resolved to [tmdbIds]: TMDB id → the local keys. */
    suspend fun matchRows(tmdbIds: List<Int>): Map<Int, List<LocalRow>> {
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
    suspend fun tmdbIdsByImdb(imdbIds: List<String>): Map<String, List<CachedId>> {
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

    /**
     * The playlist row's provider id or name for one title: the first of [sources] (in order) that the
     * match table says holds it, by its TMDB id or by the TMDB ids the cache knows for its IMDb id.
     * [rows] and [viaImdb] are one [matchRows] / [tmdbIdsByImdb] answer for the whole batch.
     */
    fun bestRow(
        tmdbId: Int,
        imdbId: String?,
        type: String,
        sources: List<Long>,
        rows: Map<Int, List<LocalRow>>,
        viaImdb: Map<String, List<CachedId>>,
    ): LocalRow? {
        val ids = (listOf(tmdbId) + imdbId?.let(viaImdb::get).orEmpty().filter { it.type == type }.map { it.tmdbId }).distinct()
        return ids.flatMap { rows[it].orEmpty() }
            .filter { it.type == type && it.sourceId in sources }
            .minByOrNull { sources.indexOf(it.sourceId) }
    }

    /** `?,?,?)` for [n] bound values — the tail of an `IN (` clause. */
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",") + ")"

    companion object {
        /** The match and cache tables' `type` column for a film and a show, as core writes them. */
        const val TYPE_MOVIE = "movie"
        const val TYPE_TV = "tv"
        /** What a kids profile's lookup appends to a local key — the same local row either way. */
        const val KIDS_SUFFIX = ":kids"
        const val SQL_MATCH_ROWS = "SELECT localKey, tmdbId, type FROM metadata_match WHERE tmdbId IN ("
        const val SQL_CACHE_BY_IMDB = "SELECT imdbId, tmdbId, type FROM metadata_cache WHERE imdbId IN ("
        /** Bound values per statement — far under SQLite's limit; a list tries sixty items at most anyway. */
        const val QUERY_CHUNK = 400
    }
}
