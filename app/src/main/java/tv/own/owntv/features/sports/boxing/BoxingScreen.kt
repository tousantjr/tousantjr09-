package tv.own.owntv.features.sports.boxing

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.features.sports.LiveMark
import tv.own.owntv.features.sports.SportsChip
import tv.own.owntv.features.sports.SportsLive
import tv.own.owntv.features.sports.SportsViewModel
import tv.own.owntv.features.sports.timeText
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sports hub → Boxing: the next 7 days of ESPN's (guessed) boxing scoreboard, grouped by day exactly
 * like [tv.own.owntv.features.sports.SportsUpcomingScreen] — "Today", "Tomorrow", then day-of-week.
 *
 * Tapping a card does **not** open a match page: boxing never runs through
 * [tv.own.owntv.features.sports.SportsMatchScreen]'s live-refresh flow (see [BoxingUpcomingViewModel]'s
 * doc for why), so the card itself opens a small menu with "Find channels" and "Remind me" — the two
 * things that menu would otherwise be for — and nothing else. There is no live score to tick, so this
 * screen fetches once per day per 12-minute cache window and never polls while open.
 */
@Composable
fun BoxingScreen(
    vm: BoxingUpcomingViewModel,
    onPlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    androidx.activity.compose.BackHandler(enabled = active) { onBack() }
    val state by vm.state.collectAsStateWithLifecycle()
    val channelSearches by vm.channels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val locale = LocalConfiguration.current.locales[0]
    var selected by remember { mutableStateOf<BoxingBout?>(null) }
    val now = System.currentTimeMillis()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = contentStart, end = 60.mpx, top = 39.mpx, bottom = 60.mpx),
        ) {
            item(key = "head") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.boxing_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(9.mpx))
                            Text(stringResource(R.string.boxing_schedule_credit), style = stageText(18, 400), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SportsChip("", false, icon = OwnTVIcon.REFRESH) { vm.retry() }
                    }
                    when {
                        state.paused -> Text(
                            stringResource(R.string.boxing_paused), style = stageText(18, 600), color = StageColors.Warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.mpx),
                        )
                        state.hasFailures -> Row(
                            Modifier.padding(top = 9.mpx), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.mpx),
                        ) {
                            Text(
                                stringResource(R.string.boxing_partial_failure), style = stageText(18, 600),
                                color = StageColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            SportsChip(stringResource(R.string.common_retry), false) { vm.retry() }
                        }
                    }
                }
            }

            val allEmpty = state.days.all { it.bouts.isEmpty() }
            if (state.loading && allEmpty) {
                item(key = "loading") { OwnTVSpinner(sizeDp = 18, modifier = Modifier.padding(top = 60.mpx)) }
            } else if (allEmpty) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.boxing_empty), style = stageText(22, 600), color = StageColors.Muted,
                        modifier = Modifier.padding(top = 60.mpx),
                    )
                }
            } else {
                state.days.forEach { day ->
                    if (day.bouts.isEmpty()) return@forEach
                    item(key = "day-${day.date}") {
                        Text(
                            boxingDayLabel(day.date, locale), style = stageText(22, 800), color = StageColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 21.mpx, bottom = 9.mpx),
                        )
                    }
                    items(day.bouts, key = { it.key }) { b ->
                        val reminded = b.startMs > 0 && SportsViewModel.reminderKey(b.startMs, matchupText(b)) in state.reminders
                        BoutRow(b, reminded) { selected = b }
                    }
                }
            }
        }
    }

    selected?.let { bout ->
        BoutMenu(
            bout = bout,
            search = channelSearches[bout.id] ?: BoxingChannelSearch.NotSearched,
            now = now,
            reminded = SportsViewModel.reminderKey(bout.startMs, matchupText(bout)) in state.reminders,
            onFind = { vm.findChannels(bout) },
            onPlay = { ch, zap -> onPlayChannel(ch, zap); selected = null },
            onRemind = { options -> vm.remind(bout, options) },
            onDismiss = { selected = null },
        )
    }
}

/**
 * "Fighter A – Fighter B", core's own `sports_pair` format — the same "%1$s – %2$s" every other
 * combat/team sport's title already uses (see `MatchNotifications.pair`) — rather than a separately
 * translated "A vs B" string. Falls back to the event/card's own name when a bout didn't parse two
 * fighters, same as `MatchNotifications.pair` falls back to `match.title`.
 */
@Composable
@ReadOnlyComposable
private fun matchupText(bout: BoxingBout): String {
    val names = matchupNames(bout)
    return if (names != null) stringResource(R.string.sports_pair, names.first, names.second) else bout.eventTitle
}

/** "Today" / "Tomorrow" (shared with the rest of the guide), else the day-of-week and date, locale-formatted. */
@Composable
@ReadOnlyComposable
private fun boxingDayLabel(date: LocalDate, locale: Locale): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.content_epg_today)
        today.plusDays(1) -> stringResource(R.string.content_epg_tomorrow)
        else -> {
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEd")
            DateTimeFormatter.ofPattern(pattern, locale).format(date)
        }
    }
}

/** One row of a day's group: start time (or LIVE) on the left, the matchup and card/weight class, a
 *  Reminder tag, and Final/live status on the right — no score, boxing has none to show here. */
@Composable
private fun BoutRow(bout: BoxingBout, reminded: Boolean, onClick: () -> Unit) {
    StageRow(onClick = onClick, height = 84.mpx, modifier = Modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        Box(Modifier.width(96.mpx)) {
            if (bout.isLive) LiveMark("") else Text(timeText(bout.startMs), style = stageText(19, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val matchup = matchupText(bout)
            Text(matchup, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                bout.eventTitle.takeIf { it.isNotEmpty() && it != matchup },
                bout.weightClass,
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = stageText(17, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (reminded) StageTag(stringResource(R.string.sports_reminder_set))
        val right = when {
            bout.isLive -> bout.statusText
            bout.state == BoxingBoutState.FINAL -> bout.statusText
            bout.state == BoxingBoutState.CANCELLED || bout.state == BoxingBoutState.POSTPONED -> bout.statusText
            else -> ""
        }
        if (right.isNotEmpty()) {
            Text(right, style = stageText(17, 700), color = if (bout.isLive) SportsLive else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tapping a card: Find channels (once) then its results, and Remind me once a channel turns up. No
 *  Follow (boxing follows nothing) and no match page — see the screen doc for why. */
@Composable
private fun BoutMenu(
    bout: BoxingBout,
    search: BoxingChannelSearch,
    now: Long,
    reminded: Boolean,
    onFind: () -> Unit,
    onPlay: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onRemind: (List<WatchOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(560.mpx).trapAllFocusExit().focusGroup()) {
                val matchup = matchupText(bout)
                val subtitle = listOfNotNull(
                    bout.eventTitle.takeIf { it.isNotEmpty() && it != matchup },
                    bout.weightClass,
                ).joinToString(" · ").ifEmpty { null }
                StageMenuHeader(matchup, subtitle)
                when (search) {
                    is BoxingChannelSearch.NotSearched -> StageMenuItem(
                        text = stringResource(R.string.boxing_find_channels),
                        icon = OwnTVIcon.SEARCH,
                        onClick = onFind,
                        modifier = Modifier.focusRequester(first),
                    )
                    is BoxingChannelSearch.Searching -> StageMenuItem(
                        text = stringResource(R.string.boxing_searching_channels),
                        icon = OwnTVIcon.SEARCH,
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.focusRequester(first),
                    )
                    is BoxingChannelSearch.Found -> if (search.options.isEmpty()) {
                        StageMenuItem(
                            text = stringResource(R.string.boxing_no_channels_found),
                            enabled = false,
                            onClick = {},
                            modifier = Modifier.focusRequester(first),
                        )
                    } else {
                        search.options.forEachIndexed { i, option ->
                            StageMenuItem(
                                text = option.facets.name,
                                icon = OwnTVIcon.PLAY,
                                onClick = { onPlay(option.channel, search.options.map { it.channel }) },
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                        if (!reminded && bout.startMs > now) {
                            StageMenuItem(
                                text = stringResource(R.string.content_remind_me),
                                icon = OwnTVIcon.BELL,
                                onClick = { onRemind(search.options); onDismiss() },
                            )
                        }
                    }
                }
            }
        }
    }
}
