package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx

/**
 * Hold OK on a match page channel: Fix channel — always use [channel] for this competition, for either
 * side, or for one of the networks showing the game (idea from Nuvio + IPTV, GPL-3.0).
 */
@Composable
internal fun FixChannelMenu(match: Match, channel: ChannelEntity, vm: SportsViewModel, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(600.mpx).trapAllFocusExit().focusGroup()) {
                StageMenuHeader(stringResource(R.string.sports_fix_channel), channel.name)
                StageMenuItem(
                    text = stringResource(R.string.sports_always_use, leagueName(match)),
                    icon = OwnTVIcon.TREND,
                    onClick = { vm.rememberChannel(match, channel); onDismiss() },
                    modifier = Modifier.focusRequester(first),
                )
                match.competitors.forEach { c ->
                    val team = FollowedTeam(match.sport, c.team.id, c.team.name, c.team.abbreviation)
                    StageMenuItem(
                        text = stringResource(R.string.sports_always_for, c.team.name),
                        icon = OwnTVIcon.STAR,
                        onClick = { vm.pinTeam(team, channel); onDismiss() },
                    )
                }
                match.broadcasts.map { it.name }.filter { it.isNotBlank() }.distinct().forEach { b ->
                    StageMenuItem(
                        text = stringResource(R.string.sports_always_for, b),
                        icon = OwnTVIcon.LIVE_TV,
                        onClick = { vm.pinNetwork(SportsPinRules.networkKey(b), channel); onDismiss() },
                    )
                }
            }
        }
    }
}
