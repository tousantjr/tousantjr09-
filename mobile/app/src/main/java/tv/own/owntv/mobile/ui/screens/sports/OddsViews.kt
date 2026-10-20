package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.screens.sports.odds.OddsLine
import tv.own.owntv.mobile.ui.screens.sports.odds.formatPoint
import tv.own.owntv.mobile.ui.screens.sports.odds.sideLabel
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The match page's Odds box, under the score box (Settings → Sports → Betting odds, off by default):
 * who set the line, the spread and the total side by side, the moneylines and the implied win
 * chance under them, and the note that none of it can be acted on here. The television's
 * `OddsBlock`, at phone sizes.
 */
@Composable
internal fun OddsBlock(line: OddsLine, m: Match, modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    Column(
        modifier
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.ink.copy(alpha = 0.05f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sports_odds_heading), style = stageText(14f, 800), color = palette.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.sports_odds_by, line.bookmaker), style = stageText(11.5f, 600), color = palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (line.details != null || line.overUnder != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.details?.let { OddsCell(stringResource(R.string.sports_odds_spread), it, Modifier.weight(1f)) }
                line.overUnder?.let { OddsCell(stringResource(R.string.sports_odds_total), formatPoint(it), Modifier.weight(1f)) }
            }
        }
        moneyLineText(line, m)?.let {
            Spacer(Modifier.height(10.dp))
            OddsCell(stringResource(R.string.sports_odds_moneyline), it)
        }
        probabilityText(line, m)?.let {
            Spacer(Modifier.height(10.dp))
            OddsCell(stringResource(R.string.sports_odds_win_chance), it)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.sports_odds_disclaimer), style = stageText(10.5f, 500), color = palette.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OddsCell(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    Column(modifier) {
        Text(label, style = stageText(10.5f, 600), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(value, style = stageText(14f, 800), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "PHI -280 · DAL +230" (a draw between them where the sport has one); null when no side has a moneyline. */
@Composable
internal fun moneyLineText(line: OddsLine, m: Match): String? {
    val sep = stringResource(R.string.content_epg_bits_separator)
    val home = m.home?.team
    val away = m.away?.team
    val parts = listOfNotNull(
        line.homeMoneyLine?.let { ml -> home?.let { stringResource(R.string.sports_odds_pair, sideLabel(it), signedText(ml)) } },
        line.drawMoneyLine?.let { stringResource(R.string.sports_odds_pair, stringResource(R.string.sports_odds_draw), signedText(it)) },
        line.awayMoneyLine?.let { ml -> away?.let { stringResource(R.string.sports_odds_pair, sideLabel(it), signedText(ml)) } },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(sep)
}

/** "PHI 61% · DAL 39%" (a draw between them where the sport has one); null without both sides' chances. */
@Composable
internal fun probabilityText(line: OddsLine, m: Match): String? {
    val h = line.homeProbability ?: return null
    val a = line.awayProbability ?: return null
    val home = m.home?.team ?: return null
    val away = m.away?.team ?: return null
    val sep = stringResource(R.string.content_epg_bits_separator)
    return listOfNotNull(
        stringResource(R.string.sports_odds_side_percent, sideLabel(home), h),
        line.drawProbability?.let { stringResource(R.string.sports_odds_side_percent, stringResource(R.string.sports_odds_draw), it) },
        stringResource(R.string.sports_odds_side_percent, sideLabel(away), a),
    ).joinToString(sep)
}

/** The hero's one line: "DraftKings · PHI -6.5 · O/U 47.5 · 61% PHI". */
@Composable
internal fun oddsSummary(line: OddsLine, m: Match): String {
    val sep = stringResource(R.string.content_epg_bits_separator)
    return listOfNotNull(
        line.bookmaker,
        line.details,
        line.overUnder?.let { stringResource(R.string.sports_odds_total_short, formatPoint(it)) },
        favourite(line, m)?.let { (team, p) -> stringResource(R.string.sports_odds_percent_side, p, sideLabel(team)) },
    ).joinToString(sep)
}

/** The side the line favours and its chance, for the hero's "61% PHI". */
private fun favourite(line: OddsLine, m: Match): Pair<Team, Int>? {
    val h = line.homeProbability ?: return null
    val a = line.awayProbability ?: return null
    val home = m.home?.team ?: return null
    val away = m.away?.team ?: return null
    return if (h >= a) home to h else away to a
}

/** "+230" / "-280": a moneyline with its sign, as a book prints it. */
@Composable
private fun signedText(moneyLine: Int): String =
    if (moneyLine > 0) stringResource(R.string.sports_odds_positive, moneyLine.toString()) else moneyLine.toString()
