package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.SheetScroll
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The line under the channel name while the controls show: "● LIVE **Frankfurt 2–1 Köln** · 63'".
 * It asks for the score only while it is drawn. Spoiler-free shows the two teams without the score.
 */
@Composable
fun MatchScoreLine(playback: SportsPlayback, match: Match, spoilerFree: Boolean) {
    val m by remember(match.key) { playback.score(match) }.collectAsStateWithLifecycle(match)
    val context = LocalContext.current
    val sep = stringResource(R.string.content_epg_bits_separator)
    val pre = m.state == MatchState.PRE
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (m.isLive) LiveMark("")
        val status = when {
            m.isLive -> clockText(m)
            pre -> kickOffText(m.startMs)
            else -> m.statusText
        }
        Text(
            (if (spoilerFree || pre) MatchNotifications.pair(context, m) else MatchNotifications.scoreLine(context, m)) + sep + status,
            style = stageText(12f, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The score ticker's switch sheet (a tap on the pill): the game — crests, names, the score and clock,
 * or the pair with Spoiler-free — then Play on its best channel and every channel Find channels already
 * found, ranked as the match page ranks them; a tap on a row plays it. With none found yet, the note and
 * "Find channels", which opens the match page. [options] null = still being read.
 */
@Composable
fun TickerSwitchSheet(
    playback: SportsPlayback,
    match: Match,
    options: List<WatchOption>?,
    spoilerFree: Boolean,
    onPlay: (WatchOption) -> Unit,
    onFind: () -> Unit,
    onDismiss: () -> Unit,
) {
    val m by remember(match.key) { playback.score(match) }.collectAsStateWithLifecycle(match)
    val context = LocalContext.current
    val palette = LocalStagePalette.current
    val sep = stringResource(R.string.content_epg_bits_separator)
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(MatchNotifications.pair(context, m), eyebrow = stringResource(R.string.sports_ticker_switch_title))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetSide(m.home?.team, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val home = m.home
                val away = m.away
                val score = when {
                    m.competitors.size < 2 -> m.title
                    spoilerFree -> HIDDEN_SCORE
                    else -> "${scoreText(m.sport, home?.score)}–${scoreText(m.sport, away?.score)}"
                }
                Text(score, style = stageText(if (m.competitors.size < 2) 18f else if (score.length > 7) 24f else 40f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (m.isLive) LiveMark(stringResource(R.string.player_live) + sep + clockText(m))
                else Text(m.statusText, style = stageText(11.5f, 700), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SheetSide(m.away?.team, Modifier.weight(1f))
        }
        when {
            options == null -> Unit
            options.isEmpty() -> {
                Text(
                    stringResource(R.string.sports_featured_no_channel),
                    style = stageText(12.5f, 500), color = palette.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                StageButton(onClick = onFind, text = stringResource(R.string.sports_find_channels), icon = MobileIcons.Search, style = StageButtonStyle.PRIMARY, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            else -> {
                StageButton(
                    onClick = { onPlay(options.first()) },
                    text = stringResource(R.string.content_play) + sep + options.first().facets.name,
                    icon = MobileIcons.PlayArrow,
                    style = StageButtonStyle.PRIMARY,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SheetScroll {
                    options.forEach { o -> ChannelRow(o, m, onClick = { onPlay(o) }, onHold = null) }
                }
            }
        }
    }
}

/** A side of the sheet's score: the crest over the team's name. */
@Composable
private fun SheetSide(team: Team?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (team != null) {
            Crest(team, 48)
            Text(teamLabel(team), style = stageText(12.5f, 700), color = LocalStagePalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The score's place with Spoiler-free on: dashes, as the match page draws it. */
private const val HIDDEN_SCORE = "– : –"

/** The other channels showing the match, as a sheet: the backup when a stream stalls. */
@Composable
fun OtherChannelsSheet(session: SportsSession, playingId: Long?, onSwitch: (ChannelEntity) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(MatchNotifications.pair(context, session.match), eyebrow = stringResource(R.string.sports_other_channels_title))
        SheetScroll {
            session.options.forEach { o ->
                val playing = o.channel.id == playingId
                ChannelRow(o, session.match, onClick = { if (playing) onDismiss() else onSwitch(o.channel) }, onHold = null, playing = playing)
            }
        }
    }
}
