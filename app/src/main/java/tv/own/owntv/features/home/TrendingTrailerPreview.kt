package tv.own.owntv.features.home

import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import tv.own.owntv.core.metadata.TrailerKeys
import tv.own.owntv.ui.stage.backdropLeftWash
import tv.own.owntv.ui.theme.ownTvTween

/**
 * The focused Trending title's trailer, muted, in the hero's backdrop: the same android-youtube-player
 * IFrame view `TrailerPlayerScreen` uses full screen, here inline, silent and without a HUD — the
 * WebView is a pure video surface that never takes D-pad focus. [videoKey] may hold several keys, best
 * first (core [TrailerKeys]): a key YouTube refuses to embed moves on to the next, and when none plays,
 * the video ends, or the box has no usable WebView, [onStop] asks Home to drop the preview and the
 * still artwork shows through again.
 *
 * Invisible (alpha 0) until the first PLAYING state, so the IFrame's black page and start overlay never
 * flash over the artwork, then a short fade in. A video at a fraction of the screen is composited by
 * the GPU rather than shown on a hardware overlay (TrailerPlayerScreen's own note), which is why this
 * is a setting — Settings › Layout › Home › Play trailers on Home — and defaults off on low-RAM boxes.
 */
@Composable
internal fun TrendingTrailerPreview(videoKey: String, modifier: Modifier = Modifier, onStop: () -> Unit) {
    val context = LocalContext.current
    val keys = remember(videoKey) { TrailerKeys.split(videoKey).ifEmpty { listOf(videoKey) } }
    var playing by remember(videoKey) { mutableStateOf(false) }
    val stop by rememberUpdatedState(onStop)

    // Some boxes ship without a usable System WebView — constructing the view itself can throw there.
    val playerView = remember(videoKey) {
        runCatching {
            YouTubePlayerView(context).apply {
                enableAutomaticInitialization = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        }.getOrNull()
    }
    if (playerView == null) {
        LaunchedEffect(videoKey) { stop() }
        return
    }

    val options = remember(context) {
        IFramePlayerOptions.Builder(context)
            .controls(0)
            .fullscreen(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(0)
            .build()
    }

    DisposableEffect(playerView) {
        var keyIndex = 0
        val listener = object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                // Muted before the first frame: a Home screen must never start talking by itself.
                youTubePlayer.mute()
                youTubePlayer.loadVideo(keys[keyIndex], 0f)
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                if (keyIndex < keys.lastIndex) {
                    keyIndex++
                    youTubePlayer.loadVideo(keys[keyIndex], 0f)
                } else {
                    stop()
                }
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> playing = true
                    PlayerConstants.PlayerState.ENDED -> stop()
                    else -> Unit
                }
            }
        }
        runCatching { playerView.initialize(listener, options) }.onFailure { stop() }
        onDispose { runCatching { playerView.release() } }
    }

    val alpha by animateFloatAsState(if (playing) 1f else 0f, ownTvTween(420), label = "trendingTrailer")
    Box(modifier) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
        // The same left wash the still backdrop carries, so the title and its buttons keep reading over video.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .backdropLeftWash(0f to 0.70f, 0.30f to 0.35f, 0.60f to 0f, 1f to 0f),
        )
    }
}
