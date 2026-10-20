package tv.own.owntv.features.sports

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.features.home.tracked
import tv.own.owntv.ui.components.rememberClockTick
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Home's "Live sports" row: the [featured] game's hero first ([FeaturedMatchHero] — the live
 * followed match, else the next within twelve hours), then the hub's match cards for followed
 * matches that are live or kick off within the hour. OK (and the Play key) opens the match page,
 * where its channels are found. [reminded] is whether the featured game has a reminder; [onRemind]
 * asks for one.
 */
@Composable
fun LiveSportsRow(
    cards: List<Match>,
    featured: Match?,
    reminded: Boolean,
    config: SportsConfig,
    start: Dp,
    onOpenMatch: (Match) -> Unit,
    onRemind: (Match) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ticking, for the hero's countdown; the cards read it too.
    val now by rememberClockTick()
    // The featured game is the hero, not a card as well.
    val rest = if (featured == null) cards else cards.filter { it.key != featured.key }
    Column(modifier) {
        Row(
            Modifier.padding(start = start, bottom = 10.mpx),
            horizontalArrangement = Arrangement.spacedBy(15.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sports_title), style = stageText(26, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyRow(
            Modifier.focusRestorer().focusGroup(),
            contentPadding = PaddingValues(start = start, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
        ) {
            if (featured != null) {
                item(key = "featured:" + featured.key) {
                    FeaturedMatchHero(
                        featured, config = config, now = now, reminded = reminded,
                        onOpenMatch = { onOpenMatch(featured) },
                        onRemind = { onRemind(featured) },
                        onFocus = onFocus,
                        firstItemFocusRequester = firstItemFocusRequester,
                        track = track,
                    )
                }
            }
            itemsIndexed(rest, key = { _, m -> m.key }) { i, m ->
                MatchCard(
                    m, featured = false, config = config, now = now, reminded = false,
                    onClick = { onOpenMatch(m) },
                    onPlay = { onOpenMatch(m) },
                    onHold = { onOpenMatch(m) },
                    modifier = Modifier
                        .then(if (featured == null && i == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                )
            }
        }
    }
}
