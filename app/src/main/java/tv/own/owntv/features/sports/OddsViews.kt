package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.features.sports.odds.Bookmaker
import tv.own.owntv.features.sports.odds.OddsLine
import tv.own.owntv.features.sports.odds.formatPoint
import tv.own.owntv.features.sports.odds.sideLabel
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * The match page's Odds box, under the score header (Settings → Sports → Betting odds, off by
 * default): who set the line, the spread, the total and both moneylines as cells — or, with Compare
 * bookmakers on and more than one book pricing the game, the books side by side — the implied win
 * chance under them, and the note that none of it can be acted on here. Not focusable: there is
 * nothing to do with a line but read it.
 */
@Composable
internal fun OddsBlock(line: OddsLine, m: Match, modifier: Modifier = Modifier) {
    val r = 24.mpx
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 21.mpx)
            .clip(RoundedCornerShape(r))
            .background(StageColors.Ink.copy(alpha = 0.04f))
            .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.06f), 1.mpx.toPx(), r.toPx()) }
            .padding(horizontal = 33.mpx, vertical = 24.mpx),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sports_odds_heading), style = stageText(22.5f, 800), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.sports_odds_by, line.bookmaker), style = stageText(17, 600), color = StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(18.mpx))
        if (line.others.isEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.mpx)) {
                line.details?.let { OddsCell(stringResource(R.string.sports_odds_spread), it, Modifier.weight(1f)) }
                line.overUnder?.let { OddsCell(stringResource(R.string.sports_odds_total), formatPoint(it), Modifier.weight(1f)) }
                moneyLineText(line, m)?.let { OddsCell(stringResource(R.string.sports_odds_moneyline), it, Modifier.weight(1f)) }
            }
        } else {
            OddsComparison(line, m)
        }
        probabilityText(line, m)?.let {
            Spacer(Modifier.height(18.mpx))
            OddsCell(stringResource(R.string.sports_odds_win_chance), it)
        }
        Spacer(Modifier.height(16.mpx))
        Text(
            stringResource(R.string.sports_odds_disclaimer), style = stageText(15, 500), color = StageColors.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OddsCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.mpx))
        Text(value, style = stageText(24, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Compare bookmakers (Settings → Sports → Betting odds): the user's book first and tinted, the others
 * beside it in Settings' order, each column its short name over its spread, total and moneylines.
 * Only the rows some book prices are drawn; a book without that market shows a dash. Row heights are
 * fixed so the columns line up without a table layout.
 */
@Composable
private fun OddsComparison(line: OddsLine, m: Match) {
    val books = listOf(line) + line.others
    val spreads = books.map { it.details }
    val totals = books.map { b -> b.overUnder?.let { formatPoint(it) } }
    val moneys = books.map { moneyLineText(it, m) }
    val rows: List<Pair<Int, List<String?>>> = listOfNotNull(
        spreads.takeIf { s -> s.any { it != null } }?.let { R.string.sports_odds_spread to it },
        totals.takeIf { s -> s.any { it != null } }?.let { R.string.sports_odds_total to it },
        moneys.takeIf { s -> s.any { it != null } }?.let { R.string.sports_odds_moneyline to it },
    )
    val headH = 30.mpx
    val rowH = 42.mpx
    val none = stringResource(R.string.sports_odds_no_line_cell)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
        Column(Modifier.width(168.mpx)) {
            Box(Modifier.height(headH))
            rows.forEach { (label, _) ->
                Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(label), style = stageText(16, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        books.forEachIndexed { i, b ->
            val mine = i == 0
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.mpx))
                    .background(if (mine) StageColors.Ink.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 12.mpx),
            ) {
                Box(Modifier.height(headH), contentAlignment = Alignment.CenterStart) {
                    Text(
                        bookShort(b), style = stageText(16, 800), color = if (mine) StageColors.Text else StageColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                rows.forEach { (_, values) ->
                    val v = values[i]
                    Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                        Text(
                            v ?: none, style = stageText(21, if (mine) 800 else 700), color = if (v != null) StageColors.Text else StageColors.Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** "DK", "FD", "MGM", "CZR" — or the book's own name for one the app does not list. */
@Composable
private fun bookShort(line: OddsLine): String = when (Bookmaker.find(line.bookmakerKey)) {
    Bookmaker.DRAFTKINGS -> stringResource(R.string.sports_odds_book_short_draftkings)
    Bookmaker.FANDUEL -> stringResource(R.string.sports_odds_book_short_fanduel)
    Bookmaker.BETMGM -> stringResource(R.string.sports_odds_book_short_betmgm)
    Bookmaker.CAESARS -> stringResource(R.string.sports_odds_book_short_caesars)
    null -> line.bookmaker
}

/** "PHI -280 · DAL +230" (a draw between them where the sport has one); null when no side has a moneyline. */
@Composable
internal fun moneyLineText(line: OddsLine, m: Match): String? =
    moneyLineParts(line, m)?.joinToString(stringResource(R.string.content_epg_bits_separator))

/** The moneyline's sides as separate parts — "PHI -280", "Draw +300", "DAL +230"; null when no side has one. */
@Composable
internal fun moneyLineParts(line: OddsLine, m: Match): List<String>? {
    val home = m.home?.team
    val away = m.away?.team
    val parts = listOfNotNull(
        line.homeMoneyLine?.let { ml -> home?.let { stringResource(R.string.sports_odds_pair, sideLabel(it), signedText(ml)) } },
        line.drawMoneyLine?.let { stringResource(R.string.sports_odds_pair, stringResource(R.string.sports_odds_draw), signedText(it)) },
        line.awayMoneyLine?.let { ml -> away?.let { stringResource(R.string.sports_odds_pair, sideLabel(it), signedText(ml)) } },
    )
    return parts.takeIf { it.isNotEmpty() }
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
