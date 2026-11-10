package tv.own.owntv.features.sports

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.matchName
import tv.own.owntv.core.sports.ChannelFacets
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.RememberedChannel
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsMemory
import tv.own.owntv.core.sports.model.Match

/**
 * Fix channel per team and per network ("Always for Arsenal", "Always for NBA TV"), on this device.
 * Stored by the channel's portable name and country, like "Always use for <competition>", so a re-sync
 * or a provider change keeps it. The map is held in memory: the channel search asks for it synchronously.
 */
class SportsPins(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<Map<String, RememberedChannel>> = _pins.asStateFlow()

    /** The remembered-channel rule every channel search uses: team, then network, then competition. */
    fun rememberedFor(config: SportsConfig): (Match) -> RememberedChannel? {
        val snapshot = _pins.value
        return { m -> SportsPinRules.resolve(snapshot, m) ?: SportsMemory.rememberedFor(config, m) }
    }

    fun pinTeam(team: FollowedTeam, channel: ChannelEntity) = put(SportsPinRules.teamKey(team), channel)

    fun pinNetwork(key: String, channel: ChannelEntity) = put(key, channel)

    fun forget(key: String) = save(_pins.value - key)

    private fun put(key: String, channel: ChannelEntity) {
        val f = ChannelFacets.of(channel.matchName)
        save(_pins.value + (key to RememberedChannel(f.name, f.country)))
    }

    private fun save(map: Map<String, RememberedChannel>) {
        _pins.value = map
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONObject().put("n", v.name).put("c", v.country ?: "")) }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun load(): Map<String, RememberedChannel> = runCatching {
        val o = JSONObject(prefs.getString(KEY, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            RememberedChannel(v.getString("n"), v.optString("c").takeIf { it.isNotEmpty() })
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val PREFS = "sports_pins"
        const val KEY = "pins"
    }
}

/** The stored places behind the tables' ▲▼ ([RankMoves]); one small SharedPreferences file. */
class SportsRankMoves(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_rank_moves", Context.MODE_PRIVATE)

    /** Each row's move for [leagueKey]'s groups, keyed [RankMoves.key]; records the places seen now. */
    @Synchronized
    fun movesFor(leagueKey: String, groups: List<Pair<String, List<tv.own.owntv.core.sports.model.StandingsRow>>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        val edit = prefs.edit()
        var changed = false
        for ((group, rows) in groups) for (r in rows) {
            val key = RankMoves.key(leagueKey, group, r.team.id)
            val stored = RankMoves.Entry.decode(prefs.getString(key, null))
            val (entry, move) = RankMoves.observe(stored, r.rank)
            if (entry != stored) { edit.putString(key, entry.encode()); changed = true }
            out[key] = move
        }
        if (changed) edit.apply()
        return out
    }
}
