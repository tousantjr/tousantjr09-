package tv.own.owntv.mobile.health

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.SourceOverrides
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.StreamUrlResolver
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A channel the sweep could not get an answer from, with what "Hide them" needs: its Customize key and a label. */
data class DeadChannel(val id: Long, val sourceId: Long, val name: String, val key: String)

/** Diagnostics → Channel sweep, as the row reads it. */
sealed interface SweepUi {
    /** Nothing running. [total] > 0 means a sweep was stopped part-way and can resume from [checked]. */
    data class Idle(val checked: Int = 0, val total: Int = 0, val dead: Int = 0) : SweepUi {
        val resumable: Boolean get() = total > 0 && checked < total
    }
    data class Running(val checked: Int, val total: Int, val dead: Int, val retrying: Boolean) : SweepUi
    data class Done(val total: Int, val dead: List<DeadChannel>, val hidden: Int) : SweepUi
    data object NoChannels : SweepUi
}

/**
 * Diagnostics → Quick fixes → Channel sweep: tries every live channel of the active playlists, one
 * at a time, and lists the ones that never answer, so they can be hidden in one go.
 *
 * **One connection, one probe in flight.** Each channel's stream URL — resolved the way the player
 * resolves it, so a Stalker portal mints its link — gets a `GET` with `Range: bytes=0-0` (a `HEAD`
 * when the server refuses ranges) under a six-second clock, and the response is closed the moment
 * its status is in. That is one of the account's connections for a few seconds per channel, which is
 * why the row warns against running it while something plays. Channels that fail are tried once more
 * after the full pass; only a second failure makes a channel "not responding". Anything that is not
 * an `http` URL cannot be asked and is left alone.
 *
 * **Resumable.** The place reached is written to a small file every few channels and when the run is
 * cancelled (leaving the Diagnostics screen cancels the view model's job), so a long playlist can be
 * swept across several sittings. The cursor only resumes for the same profile, the same playlists
 * and the same channel count; anything else starts over. Results also feed [ChannelHealthStore], so
 * the rows' dots and the Sports ranking learn from a sweep.
 */
class ChannelSweep(
    context: Context,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val customize: CustomizationStore,
    private val resolver: StreamUrlResolver,
    http: OkHttpClient,
    private val store: ChannelHealthStore,
) {
    private val cursorFile = File(context.filesDir, CURSOR_FILE)
    private val client = http.newBuilder()
        .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val running = AtomicBoolean(false)

    private val _state = MutableStateFlow<SweepUi>(readCursor()?.let { SweepUi.Idle(it.next, it.total, it.dead.size) } ?: SweepUi.Idle())
    val state: StateFlow<SweepUi> = _state.asStateFlow()

    private class Cursor(
        val pid: Long,
        val sources: List<Long>,
        val total: Int,
        var next: Int = 0,
        var retrying: Boolean = false,
        val pending: MutableList<Long> = mutableListOf(),
        val dead: MutableList<DeadChannel> = mutableListOf(),
    )

    /**
     * The sweep itself; returns when it is done or throws when the caller cancels it. Only one runs
     * at a time — a second call while one is under way returns at once.
     */
    suspend fun run() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ctx = activeProfileSources(settings, sourceDao).first()
            val pid = ctx.profileId
            if (pid < 0) {
                _state.value = SweepUi.NoChannels
                return
            }
            val sourceIds = ctx.liveSourceIds
            val sources = ctx.sources.associateBy { it.id }
            val hidden = customize.observe(pid, MediaType.LIVE).first().hiddenItems.keys
            val channels = withContext(Dispatchers.IO) { channelDao.allForSources(sourceIds, Int.MAX_VALUE) }
                .filter { CustomizeKeys.channel(it) !in hidden }
            if (channels.isEmpty()) {
                clearCursor()
                _state.value = SweepUi.NoChannels
                return
            }
            val cursor = readCursor()?.takeIf { it.pid == pid && it.sources == sourceIds && it.total == channels.size }
                ?: Cursor(pid, sourceIds, channels.size)
            sweep(channels, sources, cursor)
        } finally {
            running.set(false)
        }
    }

    private suspend fun sweep(channels: List<ChannelEntity>, sources: Map<Long, SourceEntity>, cursor: Cursor) {
        fun publish() {
            _state.value = SweepUi.Running(
                checked = if (cursor.retrying) channels.size else cursor.next,
                total = channels.size,
                dead = if (cursor.retrying) cursor.dead.size else cursor.pending.size + cursor.dead.size,
                retrying = cursor.retrying,
            )
        }
        publish()
        try {
            if (!cursor.retrying) {
                while (cursor.next < channels.size) {
                    val channel = channels[cursor.next]
                    if (!probe(channel, sources[channel.sourceId])) cursor.pending += channel.id
                    cursor.next++
                    if (cursor.next % SAVE_EVERY == 0) saveCursor(cursor)
                    publish()
                    delay(PROBE_GAP_MS)
                }
                cursor.retrying = true
                saveCursor(cursor)
                publish()
            }
            // The second chance: a channel that was busy, or a server that dropped one request, answers now.
            val byId = channels.associateBy { it.id }
            var since = 0
            while (cursor.pending.isNotEmpty()) {
                val id = cursor.pending.first()
                val channel = byId[id]
                if (channel != null && !probe(channel, sources[channel.sourceId])) {
                    cursor.dead += DeadChannel(channel.id, channel.sourceId, channel.name, CustomizeKeys.channel(channel))
                }
                cursor.pending.removeAt(0)
                if (++since % SAVE_EVERY == 0) saveCursor(cursor)
                publish()
                delay(PROBE_GAP_MS)
            }
            val deadIds = cursor.dead.mapTo(HashSet()) { it.id }
            store.recordProbeResults(alive = channels.map { it.id }.filterNot { it in deadIds }, dead = deadIds)
            clearCursor()
            _state.value = SweepUi.Done(channels.size, cursor.dead.toList(), hidden = 0)
        } catch (c: CancellationException) {
            withContext(NonCancellable) {
                saveCursor(cursor)
                _state.value = SweepUi.Idle(
                    checked = if (cursor.retrying) channels.size else cursor.next,
                    total = channels.size,
                    dead = cursor.pending.size + cursor.dead.size,
                )
            }
            throw c
        }
    }

    /** Hides every channel the last sweep found dead for the active profile; how many it hid. */
    suspend fun hideDead(): Int {
        val done = _state.value as? SweepUi.Done ?: return 0
        if (done.dead.isEmpty() || done.hidden > 0) return 0
        val pid = settings.activeProfileId.first()
        if (pid < 0) return 0
        customize.setItemsHidden(pid, MediaType.LIVE, done.dead.associate { it.key to it.name }, true)
        _state.value = done.copy(hidden = done.dead.size)
        return done.dead.size
    }

    /** Forget a stopped sweep's place (and a finished one's list), back to the plain row. */
    fun discard() {
        if (running.get()) return
        clearCursor()
        _state.value = SweepUi.Idle()
    }

    /** True when the stream's server answered the probe. Never throws except for cancellation. */
    private suspend fun probe(channel: ChannelEntity, source: SourceEntity?): Boolean = withContext(Dispatchers.IO) {
        val url = try {
            if (resolver.needsResolve(source)) resolver.resolve(source!!, channel.streamUrl) else channel.streamUrl
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext false
        }
        // rtmp://, udp:// and friends cannot be asked with HTTP; they are not called dead for it.
        if (!url.startsWith(HTTP, ignoreCase = true)) return@withContext true
        val userAgent = source?.userAgent?.takeIf { it.isNotBlank() }
            ?: if (source?.type == SourceType.STALKER) StalkerClient.DEFAULT_MAG_USER_AGENT else HttpClient.DEFAULT_USER_AGENT
        val headers = StreamHeaders.decode(SourceOverrides.headersWithReferer(channel.httpHeaders, source))
        fun request(head: Boolean): Request? = runCatching {
            Request.Builder().url(url)
                .header(USER_AGENT, userAgent)
                .apply {
                    headers.forEach { (name, value) -> if (value.isNotBlank() && !name.equals(USER_AGENT, ignoreCase = true)) header(name, value) }
                    if (head) head() else header(RANGE, FIRST_BYTE).get()
                }
                .build()
        }.getOrNull()
        val first = request(head = false)?.let { status(it) } ?: return@withContext false
        when (first) {
            in 200..299 -> true
            // No ranges, no GET: ask the cheaper way before giving up on the server.
            405, 416, 501 -> request(head = true)?.let { status(it) }?.let { it in 200..299 } ?: false
            else -> false
        }
    }

    /** The response's status, with the body never read; null when the connection failed or timed out. */
    private suspend fun status(request: Request): Int? {
        val call = client.newCall(request)
        val hook = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it is CancellationException) call.cancel() }
        return try {
            call.execute().use { it.code }
        } catch (e: IOException) {
            null
        } finally {
            hook?.dispose()
        }
    }

    private fun readCursor(): Cursor? = runCatching {
        if (!cursorFile.exists()) return null
        val o = JSONObject(cursorFile.readText())
        val sources = o.getJSONArray(KEY_SOURCES).let { a -> List(a.length()) { a.getLong(it) } }
        val pending = o.optJSONArray(KEY_PENDING)?.let { a -> MutableList(a.length()) { a.getLong(it) } } ?: mutableListOf()
        val dead = o.optJSONArray(KEY_DEAD)?.let { a ->
            MutableList(a.length()) { i ->
                val d = a.getJSONObject(i)
                DeadChannel(d.getLong(KEY_ID), d.getLong(KEY_SOURCE), d.getString(KEY_NAME), d.getString(KEY_KEY))
            }
        } ?: mutableListOf()
        Cursor(
            pid = o.getLong(KEY_PID),
            sources = sources,
            total = o.getInt(KEY_TOTAL),
            next = o.getInt(KEY_NEXT),
            retrying = o.optBoolean(KEY_RETRYING, false),
            pending = pending,
            dead = dead,
        )
    }.getOrNull()

    private fun saveCursor(cursor: Cursor) {
        runCatching {
            val o = JSONObject()
                .put(KEY_PID, cursor.pid)
                .put(KEY_SOURCES, JSONArray(cursor.sources))
                .put(KEY_TOTAL, cursor.total)
                .put(KEY_NEXT, cursor.next)
                .put(KEY_RETRYING, cursor.retrying)
                .put(KEY_PENDING, JSONArray(cursor.pending))
                .put(
                    KEY_DEAD,
                    JSONArray().apply {
                        cursor.dead.forEach {
                            put(JSONObject().put(KEY_ID, it.id).put(KEY_SOURCE, it.sourceId).put(KEY_NAME, it.name).put(KEY_KEY, it.key))
                        }
                    },
                )
            cursorFile.writeText(o.toString())
        }
    }

    private fun clearCursor() {
        runCatching { cursorFile.delete() }
    }

    private companion object {
        const val CURSOR_FILE = "channel_sweep.json"
        const val PROBE_TIMEOUT_S = 6L
        const val PROBE_GAP_MS = 120L
        const val SAVE_EVERY = 25
        const val HTTP = "http"
        const val USER_AGENT = "User-Agent"
        const val RANGE = "Range"
        const val FIRST_BYTE = "bytes=0-0"
        const val KEY_PID = "pid"
        const val KEY_SOURCES = "sources"
        const val KEY_TOTAL = "total"
        const val KEY_NEXT = "next"
        const val KEY_RETRYING = "retrying"
        const val KEY_PENDING = "pending"
        const val KEY_DEAD = "dead"
        const val KEY_ID = "id"
        const val KEY_SOURCE = "src"
        const val KEY_NAME = "name"
        const val KEY_KEY = "key"
    }
}
