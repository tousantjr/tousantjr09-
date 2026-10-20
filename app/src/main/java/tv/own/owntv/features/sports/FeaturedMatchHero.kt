package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.features.home.tracked
import tv.own.owntv.features.sports.odds.OddsLine
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Home's featured game ("Tonight's game"), the first item of the Sports row: a wide card with both
 * sides' crests and names, the score and minute while live or a countdown to kick-off, and two
 * buttons — Find channels (the match page, where its channels are found) and, until kick-off,
 * Remind me. The row's first-item focus lands on Find channels; Remind me is the next stop, then
 * the row's cards. No artwork exists for a game, so the card is the accent's gradient and the crests.
 */
@Composable
internal fun FeaturedMatchHero(
    m: Match,
    config: SportsConfig,
    now: Long,
    reminded: Boolean,
    onOpenMatch: () -> Unit,
    onRemind: () -> Unit,
    onFocus: () -> Unit,
    /** The game's betting line (Settings → Sports → Betting odds); null while off or when there is none. */
    odds: OddsLine? = null,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = 24.mpx
    val accent = stageAccent.accent
    val ink = StageColors.Ink
    val locale = LocalConfiguration.current.locales[0]
    val sep = stringResource(R.string.content_epg_bits_separator)
    val live = m.state == MatchState.IN
    val upcoming = m.state == MatchState.PRE && m.startMs > now
    Column(
        modifier
            .width(980.mpx)
            .clip(RoundedCornerShape(r))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.2f), ink.copy(alpha = 0.05f))))
            .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.06f), 1.mpx.toPx(), r.toPx()) }
            .padding(horizontal = 30.mpx, vertical = 24.mpx),
    ) {
        // The eyebrow ("Next game" once it is no longer today's), the league, the day and the venue;
        // the live mark with the minute on the right.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            StageTag(stringResource(if (isToday(m.startMs)) R.string.sports_featured_eyebrow else R.string.sports_featured_eyebrow_next), tint = accent)
            val line = listOfNotNull(leagueName(m), dayOf(m.startMs, locale), m.venue?.takeIf { it.isNotBlank() }).joinToString(sep)
            Text(line, style = stageText(18, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (live) LiveMark(stringResource(R.string.sports_live_now) + sep + clockText(m), 19f)
        }
        Spacer(Modifier.height(20.mpx))
        Matchup(m, config.spoilerFree)
        Spacer(Modifier.height(18.mpx))
        // The line, in one row — "DraftKings · PHI -6.5 · O/U 47.5 · 61% PHI" — only when asked for in Settings.
        if (odds != null) {
            Text(oddsSummary(odds, m), style = stageText(18, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.mpx))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
            val status = when {
                upcoming -> stringResource(R.string.sports_featured_kickoff_in, countdown(m.startMs, now), timeText(m.startMs))
                m.state == MatchState.PRE -> stringResource(R.string.sports_featured_starting_now, timeText(m.startMs))
                // Live: who is showing it, by ESPN — the match page finds it in the user's own channels.
                live && m.broadcasts.isNotEmpty() -> m.broadcasts.joinToString(sep) { it.name }
                else -> m.statusText
            }
            Text(
                status, style = stageText(21, 700), color = if (upcoming) accent else StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            StageButton(
                stringResource(R.string.sports_find_channels), onClick = onOpenMatch, icon = OwnTVIcon.SEARCH, tinted = true, height = 58.mpx, textSize = 20,
                modifier = Modifier
                    .then(if (firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                    .tracked(track = track)
                    .onFocusChanged { if (it.hasFocus) onFocus() },
            )
            if (upcoming) StageButton(
                stringResource(if (reminded) R.string.sports_reminder_set else R.string.content_remind_me), onClick = onRemind,
                icon = OwnTVIcon.BELL, iconFilled = reminded, height = 58.mpx, textSize = 20,
                modifier = Modifier.tracked(track = track).onFocusChanged { if (it.hasFocus) onFocus() },
            )
        }
    }
}

/** Both sides, crest and name, with the kick-off time (still to come), the score (live or over) or a spoiler-free blank between. */
@Composable
private fun Matchup(m: Match, spoiler: Boolean) {
    val home = m.home
    val away = m.away
    if (home == null || away == null || m.competitors.size < 2) {
        // Racing, a card: the session and its subtitle.
        Text(m.title, style = stageText(34, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.subtitle?.let { Text(it, style = stageText(21, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(home.team, 84)
            Text(teamLabel(home.team), style = stageText(30, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val middle = when {
            m.state == MatchState.PRE || m.off -> timeText(m.startMs)
            spoiler -> "– : –"
            else -> "${scoreText(m.sport, home.score)} – ${scoreText(m.sport, away.score)}"
        }
        Text(
            middle, style = stageText(if (m.state == MatchState.PRE || m.off) 34 else 51, 800), color = StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 22.mpx),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.mpx, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(teamLabel(away.team), style = stageText(30, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Crest(away.team, 84)
        }
    }
}

/** "1h 20m" / "45m" until [startMs], from the ticking [now]. */
@Composable
private fun countdown(startMs: Long, now: Long): String {
    val mins = ((startMs - now) / 60_000L).toInt().coerceAtLeast(0)
    return if (mins < 60) stringResource(R.string.sports_featured_minutes, mins)
    else stringResource(R.string.sports_featured_hours_minutes, mins / 60, mins % 60)
}
