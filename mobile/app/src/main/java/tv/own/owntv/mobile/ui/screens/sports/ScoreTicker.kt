package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.stage.stageText

/** How long each game stays up before the ticker moves on to the next one. */
private const val TICK_MS = 8_000L

/**
 * The score ticker (Settings → Sports → Score ticker): a small pill at the top of the player that
 * cycles through the *other* followed games under way — the user's teams first, then their leagues —
 * reading "PHI 14–7 DAL · Q2 7:41", or only the pair and "Live now" with Spoiler-free on. The scores are
 * the Home row's ([SportsPlayback.otherLiveFollowed]), refreshed on its cadence; nothing here polls.
 * Informational only: it draws nothing at all with no such game on. The television's, in the phone's clothes.
 */
@Composable
fun ScoreTickerPill(playback: SportsPlayback, excludeKey: String?, spoilerFree: Boolean, modifier: Modifier = Modifier) {
    val matches by remember(excludeKey) { playback.otherLiveFollowed(excludeKey) }.collectAsStateWithLifecycle(emptyList())
    if (matches.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(matches.size) {
        val n = matches.size
        index = 0
        while (n > 1) {
            delay(TICK_MS)
            index = (index + 1) % n
        }
    }
    val m = matches[index.coerceIn(0, matches.lastIndex)]
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
            .background(Color(0xD906090B), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveMark("")
        Text(text, style = stageText(12f, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
