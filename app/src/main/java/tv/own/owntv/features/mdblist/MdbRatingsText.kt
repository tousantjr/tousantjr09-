package tv.own.owntv.features.mdblist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.metadata.MetadataType
import java.util.Locale

/**
 * "IMDb 7.8 · RT 91% · MC 74 · LB 3.9" for the title [meta] describes, or null while Settings → Home →
 * MDBList's ratings are off, the title is unknown to MDBList, or the answer has not arrived. Read from
 * the week-long cache first, so a title seen before costs nothing; one call otherwise.
 */
@Composable
fun mdbRatingsLine(meta: MetadataCacheEntity?): String? =
    mdbRatingsLine(meta?.tmdbId, MetadataType.entries.firstOrNull { it.name.equals(meta?.type, ignoreCase = true) })

@Composable
fun mdbRatingsLine(tmdbId: Int?, type: MetadataType?): String? {
    if (tmdbId == null || tmdbId <= 0 || type == null || type == MetadataType.EPISODE) return null
    val repo = koinInject<MdbListRepository>()
    val ratings by produceState<MdbRatings?>(initialValue = null, tmdbId, type) {
        value = repo.ratings(tmdbId, type)
    }
    return ratings?.let { mdbRatingsText(it) }
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
    return parts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.content_metadata_separator))
}

private fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
