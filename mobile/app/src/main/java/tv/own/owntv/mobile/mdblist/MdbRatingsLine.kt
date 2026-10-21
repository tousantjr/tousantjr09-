package tv.own.owntv.mobile.mdblist

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.stageText
import java.util.Locale

/**
 * "IMDb 7.8 · RT 91% · MC 74 · LB 3.9" under a film's or show's meta line, for the title [meta]
 * describes. Nothing is drawn while Settings → Layout → Home → MDBList's ratings are off, the title is
 * unknown to MDBList, or the answer has not arrived. Read from the week-long cache first, so a title
 * seen before costs nothing; one call otherwise.
 */
@Composable
fun MdbRatingsLine(meta: MetadataCacheEntity?, modifier: Modifier = Modifier) {
    if (meta == null) return
    val tmdbId = meta.tmdbId.takeIf { it > 0 } ?: return
    val type = MetadataType.entries.firstOrNull { it.name.equals(meta.type, ignoreCase = true) } ?: return
    if (type == MetadataType.EPISODE) return
    val repo = koinInject<MdbListRepository>()
    val ratings by produceState<MdbRatings?>(initialValue = null, tmdbId, type) {
        value = repo.ratings(tmdbId, type)
    }
    val line = ratings?.let { mdbRatingsText(it) } ?: return
    Text(
        line,
        style = stageText(12.5f, 600),
        color = LocalStagePalette.current.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The line itself, in the order people quote them; a source with no number is left out. */
@Composable
fun mdbRatingsText(r: MdbRatings): String? {
    val parts = listOfNotNull(
        r.imdb?.let { stringResource(R.string.mdblist_rating_imdb, oneDecimal(it)) },
        r.tomatoes?.let { stringResource(R.string.mdblist_rating_rt, it) },
        r.metacritic?.let { stringResource(R.string.mdblist_rating_mc, it) },
        r.letterboxd?.let { stringResource(R.string.mdblist_rating_lb, oneDecimal(it)) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.content_epg_bits_separator))
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
