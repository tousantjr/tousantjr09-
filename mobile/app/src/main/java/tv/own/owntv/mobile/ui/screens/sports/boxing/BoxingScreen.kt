package tv.own.owntv.mobile.ui.screens.sports.boxing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.sports.LiveMark
import tv.own.owntv.mobile.ui.screens.sports.SportsLive
import tv.own.owntv.mobile.ui.screens.sports.SportsViewModel
import tv.own.owntv.mobile.ui.screens.sports.timeText
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sports hub → Boxing: the next 7 days of ESPN's (guessed) boxing scoreboard, grouped by day exactly
 * like [tv.own.owntv.mobile.ui.screens.sports.SportsUpcomingScreen] — "Today", "Tomorrow", then
 * day-of-week.
 *
 * Tapping a card does **not** open a match page: boxing never runs through
 * [tv.own.owntv.mobile.ui.screens.sports.SportsMatchScreen]'s live-refresh flow (see
 * [BoxingUpcomingViewModel]'s doc for why), so the card opens a bottom sheet with "Find channels" and
 * "Remind me" — the two things that sheet would otherwise be for — and nothing else. There is no live
 * score to tick, so this screen fetches once per day per 12-minute cache window and never polls while open.
 */
@Composable
fun BoxingScreen(
    onPlayChannel: (Long) -> Unit,
    vm: BoxingUpcomingViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val channelSearches by vm.channels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val locale = LocalConfiguration.current.locales[0]
    val palette = LocalStagePalette.current
    var selected by remember { mutableStateOf<BoxingBout?>(null) }
    val now = System.currentTimeMillis()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.boxing_title), style = stageText(22f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.boxing_schedule_credit), style = stageText(12f, 500), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StageTool(onClick = vm::retry, icon = MobileIcons.Refresh, contentDescription = stringResource(R.string.sports_refresh_data_now))
                    }
                    when {
                        state.paused -> Text(
                            stringResource(R.string.boxing_paused), style = stageText(12.5f, 600), color = palette.warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                        state.hasFailures -> Row(
                            Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.boxing_partial_failure), style = stageText(12.5f, 600),
                                color = palette.warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            StageTool(onClick = vm::retry, label = stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            val allEmpty = state.days.all { it.bouts.isEmpty() }
            if (state.loading && allEmpty) {
                item(key = "loading") {
                    CircularProgressIndicator(
                        color = palette.muted, strokeWidth = 2.dp,
                        modifier = Modifier.padding(top = 60.dp).size(16.dp),
                    )
                }
            } else if (allEmpty) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.boxing_empty), style = stageText(13.5f, 600), color = palette.muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 40.dp),
                    )
                }
            } else {
                state.days.forEach { day ->
                    if (day.bouts.isEmpty()) return@forEach
                    item(key = "day-${day.date}") {
                        Text(
                            boxingDayLabel(day.date, locale), style = stageText(15f, 800), color = palette.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
        BoutSheet(
            bout = bout,
            search = channelSearches[bout.id] ?: BoxingChannelSearch.NotSearched,
            now = now,
            reminded = SportsViewModel.reminderKey(bout.startMs, matchupText(bout)) in state.reminders,
            onFind = { vm.findChannels(bout) },
            onPlay = { channelId -> onPlayChannel(channelId); selected = null },
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
    val palette = LocalStagePalette.current
    StageRow(onClick = onClick, height = 64.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Box(Modifier.width(64.dp)) {
            if (bout.isLive) LiveMark("") else Text(timeText(bout.startMs), style = stageText(13f, 700), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val matchup = matchupText(bout)
            Text(matchup, style = stageText(13.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(
                bout.eventTitle.takeIf { it.isNotEmpty() && it != matchup },
                bout.weightClass,
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = stageText(11.5f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(right, style = stageText(11.5f, 700), color = if (bout.isLive) SportsLive else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tapping a card: Find channels (once) then its results, and Remind me once a channel turns up. No
 *  Follow (boxing follows nothing) and no match page — see the screen doc for why. */
@Composable
private fun BoutSheet(
    bout: BoxingBout,
    search: BoxingChannelSearch,
    now: Long,
    reminded: Boolean,
    onFind: () -> Unit,
    onPlay: (Long) -> Unit,
    onRemind: (List<WatchOption>) -> Unit,
    onDismiss: () -> Unit,
) {
    MobileBottomSheet(onDismissRequest = onDismiss) {
        val matchup = matchupText(bout)
        val subtitle = listOfNotNull(
            bout.eventTitle.takeIf { it.isNotEmpty() && it != matchup },
            bout.weightClass,
        ).joinToString(" · ").ifEmpty { null }
        StageSheetHeader(matchup, subtitle = subtitle)
        when (search) {
            is BoxingChannelSearch.NotSearched -> StageMenuItem(
                label = stringResource(R.string.boxing_find_channels),
                icon = MobileIcons.Search,
                onClick = onFind,
            )
            is BoxingChannelSearch.Searching -> StageMenuItem(
                label = stringResource(R.string.boxing_searching_channels),
                icon = MobileIcons.Search,
                enabled = false,
                onClick = {},
            )
            is BoxingChannelSearch.Found -> if (search.options.isEmpty()) {
                StageMenuItem(
                    label = stringResource(R.string.boxing_no_channels_found),
                    enabled = false,
                    onClick = {},
                )
            } else {
                search.options.forEach { option ->
                    StageMenuItem(
                        label = option.facets.name,
                        icon = MobileIcons.PlayArrow,
                        onClick = { onPlay(option.channel.id) },
                    )
                }
                if (!reminded && bout.startMs > now) {
                    StageMenuItem(
                        label = stringResource(R.string.content_remind_me),
                        icon = MobileIcons.Bell,
                        onClick = { onRemind(search.options); onDismiss() },
                    )
                }
            }
        }
    }
}
