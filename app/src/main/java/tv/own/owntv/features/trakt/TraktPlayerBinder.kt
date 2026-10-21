package tv.own.owntv.features.trakt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.player.OwnTVPlayer

/**
 * Feeds [TraktScrobbler] from the shell: the film and the show the view models last handed the
 * engine, and what the engine is on now. Lives in the shell's composition so it follows every way a
 * title can start — a page, a search hit, Home's rows, the player's own next-episode advance.
 */
@Composable
fun TraktPlayerBinder(movieVm: MovieViewModel, seriesVm: SeriesViewModel) {
    val scrobbler: TraktScrobbler = koinInject()
    val player: OwnTVPlayer = koinInject()
    val movie by movieVm.playingMovie.collectAsStateWithLifecycle()
    val show by seriesVm.playingSeries.collectAsStateWithLifecycle()
    val meta by player.currentMeta.collectAsStateWithLifecycle()
    // Keyed on the playing flag too: the engine's URL and item are set a moment after play() is asked.
    val playing by player.isPlaying.collectAsStateWithLifecycle()
    LaunchedEffect(movie, show, meta, playing) { scrobbler.televisionPlayer(movie, show, meta) }
}
