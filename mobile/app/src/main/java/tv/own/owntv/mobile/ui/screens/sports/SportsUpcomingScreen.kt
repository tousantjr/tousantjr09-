package tv.own.owntv.mobile.ui.screens.sports

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.stageText
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
    onOpenMatch: (Match) -> Unit,
    onChooseSports: () -> Unit = {},
    vm: SportsUpcomingViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val locale = LocalConfiguration.current.locales[0]
    val palette = LocalStagePalette.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sports_upcoming_title), style = stageText(22f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.sports_scores_espn), style = stageText(12f, 500), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Forgets the cache and tries again now — the same action a failure banner's Retry runs.
                        StageTool(onClick = vm::retry, icon = MobileIcons.Refresh, contentDescription = stringResource(R.string.sports_refresh_data_now))
                    }
                    when {
                        state.paused -> Text(
                            stringResource(R.string.sports_paused), style = stageText(12.5f, 600), color = palette.warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                        )
                        state.hasFailures -> Row(
                            Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.sports_upcoming_partial_failure), style = stageText(12.5f, 600),
                                color = palette.warn, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            StageTool(onClick = vm::retry, label = stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            val allEmpty = state.days.all { it.matches.isEmpty() }
            if (state.config.followedLeagues().isEmpty()) {
                item(key = "none") { NothingFollowedNote(onChooseSports) }
            } else if (state.loading && allEmpty) {
                item(key = "loading") {
                    CircularProgressIndicator(
                        color = palette.muted, strokeWidth = 2.dp,
                        modifier = Modifier.padding(top = 60.dp).size(16.dp),
                    )
                }
            } else if (allEmpty) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.sports_upcoming_empty), style = stageText(13.5f, 600), color = palette.muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 40.dp),
                    )
                }
            } else {
                state.days.forEach { day ->
                    if (day.matches.isEmpty()) return@forEach
                    item(key = "day-${day.date}") {
                        Text(
                            upcomingDayLabel(day.date, locale), style = stageText(15f, 800), color = palette.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
    val palette = LocalStagePalette.current
    StageRow(onClick = onClick, height = 64.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Box(Modifier.width(64.dp)) {
            if (m.isLive) LiveMark("") else Text(timeText(m.startMs), style = stageText(13f, 700), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f)) {
            val title = if (m.state == MatchState.PRE || spoilerFree) MatchNotifications.pair(context, m) else MatchNotifications.scoreLine(context, m)
            Text(title, style = stageText(13.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(leagueName(m), style = stageText(11.5f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val right = when {
            m.off -> m.statusText
            m.isLive -> clockText(m)
            m.state == MatchState.POST -> m.statusText
            else -> ""
        }
        if (right.isNotEmpty()) {
            Text(right, style = stageText(11.5f, 700), color = if (m.isLive) SportsLive else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
