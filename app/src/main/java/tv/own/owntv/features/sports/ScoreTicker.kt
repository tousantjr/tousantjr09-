package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** How long each game stays up before the ticker moves on to the next one. */
private const val TICK_MS = 8_000L

/** The glyph at the pill's end: a press of UP offers to switch to the game it shows. A shape, not a word. */
private const val UP_HINT = "▲"

/**
 * The ticker's games and which of them the pill shows, kept by the shell rather than the pill so a
 * key can act on that game: UP arms a [TickerPrompt] for it, UP/DOWN while armed move along the list.
 * [paused] holds the cycle while the prompt is up. The list keeps its game across a refresh of the
 * board when the game is still on; otherwise it starts over at the first.
 */
@Stable
class ScoreTickerState internal constructor() {
    var matches: List<Match> by mutableStateOf(emptyList())
        internal set
    var index: Int by mutableIntStateOf(0)
        internal set
    var paused: Boolean by mutableStateOf(false)

    /** The game on the pill, or null with nothing on. */
    val current: Match? get() = matches.getOrNull(index)

    fun next() { if (matches.isNotEmpty()) index = (index + 1) % matches.size }
    fun previous() { if (matches.isNotEmpty()) index = (index - 1 + matches.size) % matches.size }
}

/** The ticker's state for this player: the *other* followed games under way ([SportsPlayback.otherLiveFollowed]), cycling every eight seconds unless paused. */
@Composable
fun rememberScoreTicker(playback: SportsPlayback, excludeKey: String?): ScoreTickerState {
    val state = remember { ScoreTickerState() }
    val matches by remember(excludeKey) { playback.otherLiveFollowed(excludeKey) }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(matches) {
        val key = state.current?.key
        state.matches = matches
        state.index = matches.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: 0
    }
    val paused = state.paused
    LaunchedEffect(matches.size, paused) {
        if (paused) return@LaunchedEffect
        while (matches.size > 1) {
            delay(TICK_MS)
            state.next()
        }
    }
    return state
}

/**
 * The score ticker (Settings → Sports → Score ticker): a small pill at the top of the player that
 * cycles through the *other* followed games under way — the user's teams first, then their leagues —
 * reading "PHI 14–7 DAL · Q2 7:41", or only the pair and "Live now" with Spoiler-free on. The scores are
 * the Home row's ([SportsPlayback.otherLiveFollowed]), refreshed on its cadence; nothing here polls.
 * It never takes focus and draws nothing at all with no such game on. The glyph at its end says UP
 * does something; [armed] (the prompt is up) brightens the pill so the two read as one thing.
 */
@Composable
fun ScoreTickerPill(state: ScoreTickerState, spoilerFree: Boolean, armed: Boolean = false, modifier: Modifier = Modifier) {
    val m = state.current ?: return
    val context = LocalContext.current
    val sep = stringResource(R.string.content_epg_bits_separator)
    val text = if (spoilerFree) {
        MatchNotifications.pair(context, m) + sep + stringResource(R.string.sports_live_now)
    } else {
        MatchNotifications.scoreLine(context, m) + sep + clockText(m)
    }
    Row(
        modifier
            .widthIn(max = 720.mpx)
            .background(if (armed) Color(0xF00A0F12) else Color(0xD906090B), RoundedCornerShape(21.mpx))
            .padding(horizontal = 21.mpx, vertical = 12.mpx),
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveMark("")
        Text(text, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(UP_HINT, style = stageText(12, 700), color = if (armed) StageColors.Text else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
