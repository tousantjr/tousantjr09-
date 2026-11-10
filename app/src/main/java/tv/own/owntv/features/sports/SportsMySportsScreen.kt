package tv.own.owntv.features.sports

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sports hub → My Sports: a big card for the next game of a team you follow, in the teams' colours,
 * then one line per followed team — its place ("2nd in Premier League") and next game. OK on the card
 * opens the match page; OK on a team opens its page. Idea from Nuvio + IPTV (GPL-3.0).
 */
@Composable
fun SportsMySportsScreen(
    vm: SportsViewModel,
    games: Flow<List<Match>>,
    onOpenMatch: (Match) -> Unit,
    onOpenTeam: (TeamRef) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    androidx.activity.compose.BackHandler(enabled = active) { onBack() }
    val hub by vm.hub.collectAsStateWithLifecycle()
    val config = hub.config
    val places by vm.myTeamPlaces.collectAsStateWithLifecycle()
    val all by remember(games) { games }.collectAsState(initial = emptyList())
    val upcoming = all.filter { it.state != MatchState.POST && !it.off }
    val hero = upcoming.firstOrNull { it.isLive } ?: upcoming.firstOrNull()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    val locale = LocalConfiguration.current.locales[0]

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = contentStart, end = 60.mpx, top = 39.mpx, bottom = 90.mpx),
            verticalArrangement = Arrangement.spacedBy(6.mpx),
        ) {
            item(key = "head") {
                Text(stringResource(R.string.sports_my_sports_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(9.mpx))
                Text(stringResource(R.string.sports_my_sports_subtitle), style = stageText(18, 400), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.mpx))
            }
            if (config.teams.isEmpty()) {
                item(key = "none") { NothingFollowedNote(Modifier.padding(top = 40.mpx)) }
                return@LazyColumn
            }
            hero?.let { m ->
                item(key = "hero") {
                    MyHero(m, spoilerFree = config.spoilerFree, locale = locale, onClick = { onOpenMatch(m) }, modifier = Modifier.focusRequester(first))
                    Spacer(Modifier.height(18.mpx))
                }
            }
            items(config.teams, key = { it.key }) { team ->
                val next = upcoming.firstOrNull { m -> m.competitors.any { it.team.id == team.id && m.sport == team.sport } }
                val crest = next?.competitors?.firstOrNull { it.team.id == team.id }?.team ?: Team(team.id, team.name, abbreviation = team.abbreviation)
                TeamLine(
                    team, crest, places[team.key], next, locale,
                    onClick = {
                        scope.launch {
                            val ref = vm.teamRefFor(team)
                            if (ref != null) onOpenTeam(ref) else next?.let(onOpenMatch)
                        }
                    },
                    modifier = if (hero == null && team == config.teams.first()) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        StageKeyHints(
            listOf(stringResource(R.string.common_ok) to stringResource(R.string.sports_team_page)),
            Modifier.align(Alignment.BottomEnd).padding(end = 60.mpx, bottom = 27.mpx),
            textSize = 18,
        )
    }
}

@Composable
private fun MyHero(m: Match, spoilerFree: Boolean, locale: Locale, onClick: () -> Unit, modifier: Modifier) {
    val home = m.competitors.firstOrNull { it.home } ?: m.competitors.firstOrNull()
    val away = m.competitors.firstOrNull { it !== home }
    val c1 = Color(TeamColors.argb(m.sport.path, home?.team?.id.orEmpty()))
    val c2 = Color(TeamColors.argb(m.sport.path, away?.team?.id.orEmpty()))
    StageSurface(onClick = onClick, radius = 24.mpx, focusStyle = StageFocus.FX, modifier = modifier.fillMaxWidth()) { _ ->
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(c1.copy(alpha = 0.55f), c2.copy(alpha = 0.55f))), RoundedCornerShape(24.mpx))
                .padding(horizontal = 36.mpx, vertical = 26.mpx),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                Text(stringResource(R.string.sports_my_sports_next), style = stageText(17, 800), color = StageColors.Text, maxLines = 1)
                Text(leagueName(m), style = stageText(17, 500), color = StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.mpx))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
                Crest(home?.team, 72)
                Text(home?.team?.let(::teamLabel).orEmpty(), style = stageText(30, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m.isLive) {
                        LiveMark(clockText(m), 17f)
                        if (!spoilerFree) Text(
                            "${scoreText(m.sport, home?.score)} – ${scoreText(m.sport, away?.score)}",
                            style = stageText(34, 800), color = StageColors.Text, maxLines = 1,
                        )
                    } else {
                        Text(whenText(m.startMs, locale), style = stageText(24, 800), color = StageColors.Text, maxLines = 1)
                    }
                }
                Text(away?.team?.let(::teamLabel).orEmpty(), style = stageText(30, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Crest(away?.team, 72)
            }
        }
    }
}

@Composable
private fun TeamLine(team: FollowedTeam, crest: Team, place: TeamPlace?, next: Match?, locale: Locale, onClick: () -> Unit, modifier: Modifier) {
    val sep = stringResource(R.string.content_epg_bits_separator)
    val colour = Color(TeamColors.argb(team.sport.path, team.id))
    StageRow(onClick = onClick, height = 92.mpx, modifier = modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        Box(Modifier.padding(end = 14.mpx).height(56.mpx).background(colour, RoundedCornerShape(3.mpx)).padding(horizontal = 3.mpx))
        Crest(crest, 52)
        Spacer(Modifier.padding(start = 16.mpx))
        Column(Modifier.weight(1f)) {
            Text(team.name, style = stageText(21, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val placeText = place?.let {
                stringResource(R.string.sports_my_sports_place, Places.ordinal(it.rank, locale.language == "en"), it.group?.takeIf { g -> g.isNotBlank() && g != it.league }?.let { g -> "${it.league} $g" } ?: it.league) +
                    sep + stringResource(R.string.sports_my_sports_points, it.points)
            }
            placeText?.let { Text(it, style = stageText(17, 600), color = StageColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val nextText = next?.let { m ->
            val other = m.competitors.firstOrNull { it.team.id != team.id }?.team
            listOfNotNull(other?.let(::teamLabel), if (m.isLive) null else whenText(m.startMs, locale)).joinToString(sep)
        } ?: stringResource(R.string.sports_my_sports_no_game)
        if (next?.isLive == true) LiveMark(clockText(next), 15f)
        Text(nextText, style = stageText(18, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.mpx))
    }
}

/** "Sat 14 Nov, 15:00" in the user's language. */
private fun whenText(ms: Long, locale: Locale): String =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMMHHmm"), locale)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
