package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Home's featured game ("Tonight's game"), above the Sports row's cards: a full-width glass card
 * with both sides' crests and names, the score and minute while live or a countdown to kick-off,
 * and two buttons — Find channels (the match page, where its channels are found) and, until
 * kick-off, Remind me. The card itself opens the match too. No artwork exists for a game, so it is
 * the accent's gradient and the crests, as on the television.
 */
@Composable
internal fun FeaturedMatchHero(
    m: Match,
    config: SportsConfig,
    now: Long,
    reminded: Boolean,
    onOpenMatch: () -> Unit,
    onRemind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val haptics = LocalHapticFeedback.current
    val locale = LocalConfiguration.current.locales[0]
    val sep = stringResource(R.string.content_epg_bits_separator)
    val shape = RoundedCornerShape(20.dp)
    val live = m.state == MatchState.IN
    val upcoming = m.state == MatchState.PRE && m.startMs > now
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .stageGlass(shape)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent)))
            .clickable(onClick = onOpenMatch)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageTag(stringResource(if (isToday(m.startMs)) R.string.sports_featured_eyebrow else R.string.sports_featured_eyebrow_next))
            Text(
                listOfNotNull(leagueName(m), dayOf(m.startMs, locale), m.venue?.takeIf { it.isNotBlank() }).joinToString(sep),
                style = stageText(11.5f, 600), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (live) LiveMark(stringResource(R.string.sports_live_now) + sep + clockText(m))
        }
        Spacer(Modifier.height(14.dp))
        Matchup(m, config.spoilerFree)
        Spacer(Modifier.height(12.dp))
        val status = when {
            upcoming -> stringResource(R.string.sports_featured_kickoff_in, countdown(m.startMs, now), timeText(m.startMs))
            m.state == MatchState.PRE -> stringResource(R.string.sports_featured_starting_now, timeText(m.startMs))
            // Live: who is showing it, by ESPN — the match page finds it in the user's own channels.
            live && m.broadcasts.isNotEmpty() -> m.broadcasts.joinToString(sep) { it.name }
            else -> m.statusText
        }
        Text(status, style = stageText(13f, 700), color = if (upcoming) accent else palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageButton(onClick = onOpenMatch, text = stringResource(R.string.sports_find_channels), icon = MobileIcons.Search, style = StageButtonStyle.PRIMARY, small = true)
            if (upcoming) StageButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onRemind() },
                text = stringResource(if (reminded) R.string.sports_reminder_set else R.string.content_remind_me),
                icon = MobileIcons.Bell, small = true, enabled = !reminded,
            )
        }
    }
}

/** Both sides, crest over name, with the kick-off time (still to come), the score (live or over) or a spoiler-free blank between. */
@Composable
private fun Matchup(m: Match, spoiler: Boolean) {
    val palette = LocalStagePalette.current
    val home = m.home
    val away = m.away
    if (home == null || away == null || m.competitors.size < 2) {
        // Racing, a card: the session and its subtitle.
        Text(m.title, style = stageText(20f, 800), color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        m.subtitle?.let { Text(it, style = stageText(13f, 600), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        return
    }
    val pre = m.state == MatchState.PRE || m.off
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Crest(home.team, 56)
            Spacer(Modifier.height(6.dp))
            Text(teamLabel(home.team), style = stageText(14f, 800), color = palette.text, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        }
        val middle = when {
            pre -> timeText(m.startMs)
            spoiler -> "– : –"
            else -> "${scoreText(m.sport, home.score)} – ${scoreText(m.sport, away.score)}"
        }
        Text(
            middle, style = stageText(if (pre) 20f else 28f, 800), color = palette.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp),
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Crest(away.team, 56)
            Spacer(Modifier.height(6.dp))
            Text(teamLabel(away.team), style = stageText(14f, 800), color = palette.text, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
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
