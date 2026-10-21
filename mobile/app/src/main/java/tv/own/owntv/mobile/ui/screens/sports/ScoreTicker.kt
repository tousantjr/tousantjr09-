package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.stageText

/** How long each game stays up before the ticker moves on to the next one. */
private const val TICK_MS = 8_000L

/**
 * The ticker's games and which of them the pill shows, kept by the player rather than the pill so a
 * tap can act on that game (the switch sheet) and a long press can move along. [paused] holds the cycle
 * while the sheet is open. The list keeps its game across a refresh of the board when the game is still
 * on; otherwise it starts over at the first. The television's.
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
 * It draws nothing at all with no such game on. A tap ([onClick]) opens the game's switch sheet, a
 * long press ([onLongClick]) moves the pill along at once; the chevron at its end says so.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScoreTickerPill(
    state: ScoreTickerState,
    spoilerFree: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xD906090B))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveMark("")
        Text(text, style = stageText(12f, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        StageIcon(MobileIcons.KeyboardArrowUp, 14.dp, Color.White.copy(alpha = 0.7f))
    }
}
