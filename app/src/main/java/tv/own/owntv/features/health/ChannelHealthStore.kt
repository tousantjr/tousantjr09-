package tv.own.owntv.features.health

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.WatchList
import tv.own.owntv.core.sports.WatchOption
import java.io.File
import kotlin.math.pow

/** How a channel has behaved for this device: green, amber or red on a row, or nothing without a history. */
enum class HealthLevel { GOOD, FAIR, POOR }

/**
 * One channel's reliability memory: starts that produced a picture, starts that failed before one,
 * stalls (re-buffering after a picture) and what the sweep found. The counts decay with a two-week
 * half-life, so a channel that was broken last month and works now climbs back on its own.
 */
data class ChannelHealth(
    val starts: Double = 0.0,
    val fails: Double = 0.0,
    val stalls: Double = 0.0,
    /** Failed starts since the last successful one; the "likely dead" rule reads this. */
    val consecutiveFails: Int = 0,
    val lastSuccessAt: Long = 0L,
    /** When any of the counts last moved — the decay clock. */
    val lastAt: Long = 0L,
    /** Sweeps in a row that found the channel not responding; a successful sweep or start clears it. */
    val probeFails: Int = 0,
    val lastProbeAt: Long = 0L,
) {
    val hasHistory: Boolean get() = starts + fails > 0.0 || lastProbeAt > 0L

    /**
     * For ranking: a success rate shrunk towards [NEUTRAL] by two phantom tries (one start alone does
     * not make a channel better than one never tried by much), a stall penalty and the sweep's word.
     */
    val score: Double
        get() {
            val rate = (starts + NEUTRAL * 2.0) / (starts + fails + 2.0)
            val stallFactor = 1.0 / (1.0 + stalls / (starts + 1.0))
            val probeFactor = when (probeFails) {
                0 -> 1.0
                1 -> 0.7
                else -> 0.3
            }
            return (rate * stallFactor * probeFactor).coerceIn(0.0, 1.0)
        }

    /** Three failed starts in a row with nothing working for a week, or two sweeps that got no answer. */
    fun isLikelyDead(now: Long): Boolean =
        (consecutiveFails >= DEAD_FAILS && now - lastSuccessAt > DEAD_SILENCE_MS) || probeFails >= 2

    fun level(now: Long): HealthLevel? {
        if (!hasHistory) return null
        val tries = starts + fails
        val rate = if (tries > 0.0) starts / tries else 1.0
        return when {
            isLikelyDead(now) || (fails >= 2.0 && rate < 0.5) -> HealthLevel.POOR
            (fails >= 1.0 && rate < 0.8) || stalls >= (starts + 1.0) * 0.5 || probeFails == 1 -> HealthLevel.FAIR
            else -> HealthLevel.GOOD
        }
    }

    /** The counts shrunk for the time since they last moved: half their weight every [HALF_LIFE_MS]. */
    fun decayed(now: Long): ChannelHealth {
        if (lastAt <= 0L || now <= lastAt) return this
        val factor = 0.5.pow((now - lastAt).toDouble() / HALF_LIFE_MS)
        return copy(starts = starts * factor, fails = fails * factor, stalls = stalls * factor)
    }

    companion object {
        /** What an untried channel is worth in a ranking: below a channel that just worked, above one that just failed. */
        const val NEUTRAL = 0.6
        const val DEAD_FAILS = 3
        const val DEAD_SILENCE_MS = 7L * 24 * 60 * 60 * 1000
        const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    }
}

/**
 * The device's memory of how its channels behave, keyed by channel row id: fed by the player (a start
 * that showed a picture, one that failed first, a stall) and by the Diagnostics sweep, read by the
 * Sports match page to rank same-tier channels and by the rows' small coloured dot.
 *
 * App-level and this device's own — a channel that stutters on the phone's mobile data says nothing
 * about the television. A JSON file in `filesDir`, held in memory as an access-ordered map capped at
 * [MAX_ENTRIES], so a huge playlist cannot grow it without bound; writes are debounced.
 *
 * A refusal from the account (every connection in use) is the caller's to keep out: it is the
 * account's failure, not the channel's, and [ChannelHealthRecorder] checks before recording.
 */
class ChannelHealthStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = object : LinkedHashMap<Long, ChannelHealth>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ChannelHealth>?): Boolean = size > MAX_ENTRIES
    }
    private val _snapshot = MutableStateFlow<Map<Long, ChannelHealth>>(emptyMap())

    /** Every entry, republished after each change — what the rows' dots read. */
    val snapshot: StateFlow<Map<Long, ChannelHealth>> = _snapshot.asStateFlow()

    private var saveJob: Job? = null
    @Volatile private var loaded = false

    init {
        scope.launch { load() }
    }

    fun healthOf(channelId: Long): ChannelHealth? = synchronized(lock) { entries[channelId] }

    /** The ranking score, [ChannelHealth.NEUTRAL] for a channel with no history. */
    fun score(channelId: Long): Double = healthOf(channelId)?.takeIf { it.hasHistory }?.score ?: ChannelHealth.NEUTRAL

    fun isLikelyDead(channelId: Long, now: Long = System.currentTimeMillis()): Boolean =
        healthOf(channelId)?.isLikelyDead(now) ?: false

    fun level(channelId: Long, now: Long = System.currentTimeMillis()): HealthLevel? = healthOf(channelId)?.level(now)

    /** The engine showed a picture for this channel. */
    fun recordStart(channelId: Long) = update(channelId) { h, now ->
        h.copy(starts = h.starts + 1.0, consecutiveFails = 0, lastSuccessAt = now, lastAt = now, probeFails = 0)
    }

    /** The engine gave up before any picture, and the account was not the reason. */
    fun recordFailedStart(channelId: Long) = update(channelId) { h, now ->
        h.copy(fails = h.fails + 1.0, consecutiveFails = h.consecutiveFails + 1, lastAt = now)
    }

    /** The picture stopped to re-buffer, or the stream dropped after it had started. */
    fun recordStall(channelId: Long) = update(channelId) { h, now ->
        h.copy(stalls = h.stalls + 1.0, lastAt = now)
    }

    /** The Diagnostics sweep asked the stream's server and it answered ([ok]) or did not. */
    fun recordProbe(channelId: Long, ok: Boolean) = update(channelId) { h, now ->
        h.copy(probeFails = if (ok) 0 else h.probeFails + 1, lastProbeAt = now)
    }

    /** A whole sweep's verdicts at once — one snapshot, one save, however long the playlist. */
    fun recordProbeResults(alive: Collection<Long>, dead: Collection<Long>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // The dead first: an alive playlist would otherwise push them out of a full map.
            dead.forEach { id ->
                val h = (entries[id] ?: ChannelHealth()).decayed(now)
                entries[id] = h.copy(probeFails = h.probeFails + 1, lastProbeAt = now)
            }
            alive.forEach { id ->
                val h = (entries[id] ?: ChannelHealth()).decayed(now)
                entries[id] = h.copy(probeFails = 0, lastProbeAt = now)
            }
            _snapshot.value = LinkedHashMap(entries)
        }
        scheduleSave()
    }

    /**
     * [options] in the order core ranked them, with the channels of each tier reordered by score —
     * never across tiers, so a guide-confirmed channel still sits above a likely one however badly it
     * behaves. A stable sort: channels of equal score (every untried one, for instance) keep core's
     * own order, which is the profile's language preference.
     */
    fun rank(options: List<WatchOption>): List<WatchOption> {
        if (options.size < 2) return options
        val scores = options.associate { it.channel.id to score(it.channel.id) }
        if (scores.values.all { it == ChannelHealth.NEUTRAL }) return options
        return options.sortedWith(compareBy({ it.tier.ordinal }, { -(scores[it.channel.id] ?: ChannelHealth.NEUTRAL) }))
    }

    fun rank(list: WatchList): WatchList = list.copy(shown = rank(list.shown))

    private fun update(channelId: Long, change: (ChannelHealth, Long) -> ChannelHealth) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = (entries[channelId] ?: ChannelHealth()).decayed(now)
            entries[channelId] = change(current, now)
            _snapshot.value = LinkedHashMap(entries)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        val read = LinkedHashMap<Long, ChannelHealth>()
        if (text != null) {
            runCatching {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optLong(KEY_ID, -1L)
                    if (id < 0) continue
                    read[id] = ChannelHealth(
                        starts = o.optDouble(KEY_STARTS, 0.0),
                        fails = o.optDouble(KEY_FAILS, 0.0),
                        stalls = o.optDouble(KEY_STALLS, 0.0),
                        consecutiveFails = o.optInt(KEY_CONSECUTIVE, 0),
                        lastSuccessAt = o.optLong(KEY_LAST_SUCCESS, 0L),
                        lastAt = o.optLong(KEY_LAST_AT, 0L),
                        probeFails = o.optInt(KEY_PROBE_FAILS, 0),
                        lastProbeAt = o.optLong(KEY_LAST_PROBE, 0L),
                    )
                }
            }
        }
        synchronized(lock) {
            // Anything recorded before the file was read wins over the file's older copy of it.
            val fresh = LinkedHashMap(entries)
            entries.clear()
            read.forEach { (id, h) -> if (id !in fresh) entries[id] = h }
            fresh.forEach { (id, h) -> entries[id] = h }
            loaded = true
            _snapshot.value = LinkedHashMap(entries)
        }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        if (!loaded) return@withContext
        val copy = synchronized(lock) { LinkedHashMap(entries) }
        val array = JSONArray()
        copy.forEach { (id, h) ->
            array.put(
                JSONObject()
                    .put(KEY_ID, id)
                    .put(KEY_STARTS, h.starts)
                    .put(KEY_FAILS, h.fails)
                    .put(KEY_STALLS, h.stalls)
                    .put(KEY_CONSECUTIVE, h.consecutiveFails)
                    .put(KEY_LAST_SUCCESS, h.lastSuccessAt)
                    .put(KEY_LAST_AT, h.lastAt)
                    .put(KEY_PROBE_FAILS, h.probeFails)
                    .put(KEY_LAST_PROBE, h.lastProbeAt),
            )
        }
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        }
        Unit
    }

    private companion object {
        const val FILE_NAME = "channel_health.json"
        const val MAX_ENTRIES = 3000
        const val SAVE_DEBOUNCE_MS = 2_000L
        const val KEY_ID = "id"
        const val KEY_STARTS = "s"
        const val KEY_FAILS = "f"
        const val KEY_STALLS = "st"
        const val KEY_CONSECUTIVE = "cf"
        const val KEY_LAST_SUCCESS = "ls"
        const val KEY_LAST_AT = "la"
        const val KEY_PROBE_FAILS = "pf"
        const val KEY_LAST_PROBE = "lp"
    }
}
