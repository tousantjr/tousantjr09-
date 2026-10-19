package tv.own.owntv.features.sports

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sports hub → Upcoming: the next 7 days (today through +6) for every followed league, grouped by day
 * ("Today", "Tomorrow", then day-of-week and date). Reuses [SportsMatchScreen] for the match itself
 * (via [onOpenMatch], exactly as the hub opens one) — this screen only lists what is coming, grouped by
 * day; it fetches nothing the hub's own board already carries for today, and nothing here changes what
 * is followed (see Settings → Sports).
 */
@Composable
fun SportsUpcomingScreen(
    vm: SportsUpcomingViewModel,
    onOpenMatch: (Match) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    androidx.activity.compose.BackHandler(enabled = active) { onBack() }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val locale = LocalConfiguration.current.locales[0]

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = contentStart, end = 60.mpx, top = 39.mpx, bottom = 60.mpx),
        ) {
            item(key = "head") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sports_upcoming_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(9.mpx))
                            Text(stringResource(R.string.sports_scores_espn), style = stageText(18, 400), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Forgets the cache and tries again now — the same action a failure banner's Retry runs.
                        SportsChip("", false, icon = OwnTVIcon.REFRESH) { vm.retry() }
                    }
                    when {
                        state.paused -> Text(
                            stringResource(R.string.sports_paused), style = stageText(18, 600), color = StageColors.Warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.mpx),
                        )
                        state.hasFailures -> Row(
                            Modifier.padding(top = 9.mpx), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.mpx),
                        ) {
                            Text(
                                stringResource(R.string.sports_upcoming_partial_failure), style = stageText(18, 600),
                                color = StageColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            SportsChip(stringResource(R.string.common_retry), false) { vm.retry() }
                        }
                    }
                }
            }

            val allEmpty = state.days.all { it.matches.isEmpty() }
            if (state.config.followedLeagues().isEmpty()) {
                item(key = "none") { NothingFollowedNote(Modifier.padding(top = 60.mpx, end = 300.mpx)) }
            } else if (state.loading && allEmpty) {
                item(key = "loading") {
                    tv.own.owntv.ui.components.OwnTVSpinner(sizeDp = 18, modifier = Modifier.padding(top = 60.mpx))
                }
            } else if (allEmpty) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.sports_upcoming_empty), style = stageText(22, 600), color = StageColors.Muted,
                        modifier = Modifier.padding(top = 60.mpx),
                    )
                }
            } else {
                state.days.forEach { day ->
                    if (day.matches.isEmpty()) return@forEach
                    item(key = "day-${day.date}") {
                        Text(
                            upcomingDayLabel(day.date, locale), style = stageText(22, 800), color = StageColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 21.mpx, bottom = 9.mpx),
                        )
                    }
                    items(day.matches, key = { it.key }) { m ->
                        UpcomingMatchRow(m, state.config.spoilerFree) { onOpenMatch(m) }
                    }
                }
            }
        }
    }
}

/** "Today" / "Tomorrow" (shared with the rest of the guide), else the day-of-week and date, locale-formatted. */
@Composable
@ReadOnlyComposable
private fun upcomingDayLabel(date: LocalDate, locale: Locale): String {
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

/** One row of a day's group: kick-off (or LIVE) on the left, the matchup and its league, the state on the right. */
@Composable
private fun UpcomingMatchRow(m: Match, spoilerFree: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    StageRow(onClick = onClick, height = 84.mpx, modifier = Modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        Box(Modifier.width(96.mpx)) {
            if (m.isLive) LiveMark("") else Text(timeText(m.startMs), style = stageText(19, 700), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val title = if (m.state == MatchState.PRE || spoilerFree) MatchNotifications.pair(context, m) else MatchNotifications.scoreLine(context, m)
            Text(title, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(leagueName(m), style = stageText(17, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val right = when {
            m.off -> m.statusText
            m.isLive -> clockText(m)
            m.state == MatchState.POST -> m.statusText
            else -> ""
        }
        if (right.isNotEmpty()) {
            Text(right, style = stageText(17, 700), color = if (m.isLive) SportsLive else StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
