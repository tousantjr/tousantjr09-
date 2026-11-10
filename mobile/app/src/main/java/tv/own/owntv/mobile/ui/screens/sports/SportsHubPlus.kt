package tv.own.owntv.mobile.ui.screens.sports

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.epg.EpgMatcher
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.RememberedChannel
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.StandingsRow

/*
 * Sports hub extras: the pure rules behind My Sports, League Networks, the table zones, Score delay,
 * the per-team / per-network Fix channel, Switch when a channel fails and Auto-switch at kick-off.
 * The ideas come from Nuvio + IPTV by homelessbrian (GPL-3.0); written again here for MPTV's own
 * sports model. Nothing in this file touches Android, so every rule is unit-tested.
 */

/** Score delay: the choices of Settings → Sports → Score delay, in seconds (0 = off). */
object ScoreDelay {
    val CHOICES = listOf(0, 30, 60, 120, 180, 300)

    fun sanitize(sec: Int): Int = if (sec in CHOICES) sec else 0
}

/**
 * Every value of this flow arrives [delayMs] later (read on each value, so a change in Settings counts
 * from the next score). Order is kept: the delay is the same for each value, and each starts in turn.
 */
fun <T> Flow<T>.delayedBy(delayMs: () -> Long): Flow<T> = channelFlow {
    collect { v ->
        val d = delayMs()
        if (d <= 0) send(v) else launch { delay(d); send(v) }
    }
}.buffer()

/** A coloured place band of a league table, read from ESPN's note on the row. */
enum class TableZone { CHAMPIONS, EUROPA, CONFERENCE, PROMOTION, PLAYOFF, PLAY_IN, RELEGATION_PLAYOFF, RELEGATION, NONE;

    companion object {
        fun of(note: String?): TableZone {
            val n = note?.lowercase()?.trim().orEmpty()
            if (n.isEmpty()) return NONE
            return when {
                "relegation" in n && ("play" in n || "qualif" in n) -> RELEGATION_PLAYOFF
                "relegation" in n || "relegated" in n || "eliminated" in n -> RELEGATION
                "champions league" in n || "libertadores" in n || "afc champions" in n -> CHAMPIONS
                "europa league" in n || "sudamericana" in n -> EUROPA
                "conference league" in n -> CONFERENCE
                "promotion" in n || "promoted" in n -> PROMOTION
                "play-in" in n || "play in" in n || "wild card" in n || "wildcard" in n -> PLAY_IN
                "playoff" in n || "play-off" in n || "clinched" in n || "qualif" in n || "advance" in n || "knockout" in n || "round of" in n || "championship" in n -> PLAYOFF
                else -> NONE
            }
        }

        /** A line above row [index] where the band changes (a playoff line, the drop zone's edge). */
        fun lineAbove(rows: List<StandingsRow>, index: Int): Boolean {
            if (index <= 0 || index >= rows.size) return false
            val a = of(rows[index - 1].note)
            val b = of(rows[index].note)
            return a != b && (a != NONE || b != NONE)
        }
    }
}

/**
 * ▲▼ place changes: the last two places seen per team, so a move stays shown until the next one.
 * [Entry] is what is stored; [observe] gives the new entry and the move (positive = up).
 */
object RankMoves {
    data class Entry(val current: Int, val previous: Int) {
        fun encode() = "$current,$previous"

        companion object {
            fun decode(s: String?): Entry? = s?.split(',')?.takeIf { it.size == 2 }?.let { p ->
                val c = p[0].toIntOrNull() ?: return null
                val pr = p[1].toIntOrNull() ?: return null
                Entry(c, pr)
            }
        }
    }

    fun observe(stored: Entry?, rank: Int): Pair<Entry, Int> {
        val next = when {
            stored == null -> Entry(rank, rank)
            stored.current == rank -> stored
            else -> Entry(rank, stored.current)
        }
        return next to (next.previous - next.current)
    }

    fun key(leagueKey: String, group: String, teamId: String) = "$leagueKey|$group|$teamId"
}

/** Auto-switch at kick-off: due from [LEAD_MS] before the start until [LATE_MS] after it. */
object KickoffWindow {
    const val LEAD_MS = 60_000L
    const val LATE_MS = 2 * 60_000L
    const val COUNTDOWN_SEC = 10

    fun due(match: Match, now: Long): Boolean = dueAt(match.startMs, match.state, now)

    fun dueAt(startMs: Long, state: MatchState, now: Long): Boolean =
        (state == MatchState.PRE || state == MatchState.IN) && startMs - now <= LEAD_MS && now - startMs <= LATE_MS
}

/** Switch when a channel fails: the next option after [currentId] not tried yet, in the list's order. */
object Failover {
    /** A stream still buffering this long during a sports session counts as failed. */
    const val STALL_MS = 15_000L

    fun next(optionIds: List<Long>, currentId: Long?, tried: Set<Long>): Long? {
        if (optionIds.isEmpty()) return null
        val start = optionIds.indexOf(currentId).let { if (it < 0) -1 else it }
        for (step in 1..optionIds.size) {
            val id = optionIds[(start + step).mod(optionIds.size)]
            if (id != currentId && id !in tried) return id
        }
        return null
    }
}

/**
 * Fix channel per team or per network. Keys: `team:<sport/id>` and `net:<normalised network>`.
 * A match's pick: its home side's team pin, the away side's, then a pin for a network ESPN lists for it.
 */
object SportsPinRules {
    fun teamKey(team: FollowedTeam) = "team:${team.key}"
    fun networkKey(name: String) = "net:${EpgMatcher.normalizeForEpg(name)}"

    fun resolve(pins: Map<String, RememberedChannel>, match: Match): RememberedChannel? {
        if (pins.isEmpty()) return null
        val sides = match.competitors.sortedBy { if (it.home) 0 else 1 }
        for (c in sides) pins[teamKey(FollowedTeam(match.sport, c.team.id, c.team.name))]?.let { return it }
        for (b in match.broadcasts) pins[networkKey(b.name)]?.let { return it }
        return null
    }
}

/** One network of League Networks: its display name and the names a playlist calls it by. */
data class SportsNetwork(val name: String, val aliases: List<String> = emptyList()) {
    val keys: List<String> get() = (listOf(name) + aliases).map { EpgMatcher.normalizeForEpg(it) }.distinct()
    val pinKey: String get() = SportsPinRules.networkKey(name)
}

object SportsNetworks {
    val ALL = listOf(
        SportsNetwork("NFL RedZone", listOf("RedZone", "NFL Red Zone", "Red Zone")),
        SportsNetwork("NFL Network"),
        SportsNetwork("NBA TV"),
        SportsNetwork("MLB Network"),
        SportsNetwork("NHL Network"),
        SportsNetwork("Golf Channel", listOf("Golf TV")),
        SportsNetwork("Tennis Channel"),
        SportsNetwork("LaLiga TV", listOf("La Liga TV")),
        SportsNetwork("Premier Sports 1"),
        SportsNetwork("Sky Sports Premier League"),
        SportsNetwork("Sky Sports Football"),
        SportsNetwork("Sky Sports F1"),
        SportsNetwork("Sky Sports Golf"),
        SportsNetwork("TNT Sports 1"),
        SportsNetwork("ESPN"),
        SportsNetwork("ESPN2", listOf("ESPN 2")),
        SportsNetwork("ESPNU"),
        SportsNetwork("Fox Sports 1", listOf("FS1")),
        SportsNetwork("CBS Sports Network", listOf("CBSSN")),
        SportsNetwork("Big Ten Network", listOf("BTN")),
        SportsNetwork("SEC Network"),
        SportsNetwork("ACC Network"),
        SportsNetwork("beIN Sports", listOf("beIN Sports 1")),
        SportsNetwork("DAZN 1"),
        SportsNetwork("Eurosport 1"),
        SportsNetwork("MUTV"),
        SportsNetwork("LFC TV"),
        SportsNetwork("Real Madrid TV"),
    )

    /**
     * Whether a channel's normalised name ([portableKey]) is this network: the same name, or the name
     * and more words ("nba tv canada") — never a different number ("espn 2" is not "espn").
     */
    fun matches(network: SportsNetwork, portableKey: String): Boolean = network.keys.any { k ->
        portableKey == k || portableKey.startsWith("$k ") && portableKey.removePrefix("$k ").firstOrNull()?.isDigit() == false
    }
}

/** A colour per team, steady from its id: ESPN's colours are not in the board, so My Sports picks one. */
object TeamColors {
    private val PALETTE = longArrayOf(
        0xFFC8102E, 0xFF003DA5, 0xFF00843D, 0xFFFFB81C, 0xFF6F263D, 0xFF241773,
        0xFFFB4F14, 0xFF0085CA, 0xFF97233F, 0xFF006778, 0xFF4F2683, 0xFF203731,
    )

    fun argb(sport: String, teamId: String): Long = PALETTE[(("$sport/$teamId").hashCode() and 0x7fffffff) % PALETTE.size]
}

/** "1st", "2nd", "3rd", "11th" for a place in a table (English ordinals; other languages show "#n"). */
object Places {
    fun ordinal(n: Int, english: Boolean): String {
        if (!english) return "#$n"
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
