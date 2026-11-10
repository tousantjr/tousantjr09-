package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import tv.own.owntv.R
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** How often the followed games are checked for a kick-off. */
private const val KICKOFF_POLL_MS = 15_000L

/**
 * Settings → Sports → Auto-switch at kick-off (idea from Nuvio + IPTV, GPL-3.0): a minute before a
 * followed team's game, a small card counts down [KickoffWindow.COUNTDOWN_SEC] seconds with Cancel,
 * then [onSwitch] plays the game. Each game is offered once per app run; a game whose channel is
 * already playing is skipped by [isPlaying].
 */
@Composable
fun KickoffAutoSwitch(
    enabled: Boolean,
    games: () -> Flow<List<Match>>,
    isPlaying: (Match) -> Boolean,
    onSwitch: (Match) -> Unit,
) {
    val list by remember(enabled) { if (enabled) games() else emptyFlow() }.collectAsState(initial = emptyList())
    val handled = remember { mutableSetOf<String>() }
    var due by remember { mutableStateOf<Match?>(null) }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    LaunchedEffect(enabled, list) {
        if (!enabled) { due = null; return@LaunchedEffect }
        while (true) {
            if (due == null) {
                val now = System.currentTimeMillis()
                list.firstOrNull { it.key !in handled && KickoffWindow.due(it, now) }?.let { m ->
                    handled += m.key
                    if (!currentIsPlaying(m)) due = m
                }
            }
            delay(KICKOFF_POLL_MS)
        }
    }
    val m = due ?: return
    KickoffCountdown(m, onCancel = { due = null }, onDone = { due = null; onSwitch(m) })
}

@Composable
private fun KickoffCountdown(m: Match, onCancel: () -> Unit, onDone: () -> Unit) {
    var left by remember(m.key) { mutableIntStateOf(KickoffWindow.COUNTDOWN_SEC) }
    LaunchedEffect(m.key) {
        while (left > 0) { delay(1_000); left-- }
        onDone()
    }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { cancelFocus.requestFocus() } }
    val context = LocalContext.current
    OwnTVPopup(onDismissRequest = onCancel, stageLayout = true) {
        Box(Modifier.fillMaxSize().padding(end = 60.mpx, bottom = 60.mpx), contentAlignment = Alignment.BottomEnd) {
            Row(
                Modifier.width(640.mpx)
                    .background(Color(0xE606090B), RoundedCornerShape(21.mpx))
                    .padding(horizontal = 24.mpx, vertical = 18.mpx)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(18.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.sports_kickoff_switching, MatchNotifications.pair(context, m), left),
                        style = stageText(20, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(leagueName(m), style = stageText(17, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StageButton(stringResource(R.string.sports_kickoff_cancel), onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus), height = 54.mpx, textSize = 19)
            }
        }
    }
}
