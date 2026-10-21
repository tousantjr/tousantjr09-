package tv.own.owntv.features.health

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.PlaybackEngine

/**
 * Feeds [ChannelHealthRecorder] from the shell: the live channel on screen (the preview pane's or the
 * full-screen player's) and the engine holding it. Keyed on both, so a zap or an engine swap ends the
 * session and starts the next; a null [channel] (a film, a catch-up replay, nothing playing) records
 * nothing.
 */
@Composable
fun ChannelHealthBinder(channel: ChannelEntity?, engine: PlaybackEngine?) {
    val recorder: ChannelHealthRecorder = koinInject()
    LaunchedEffect(channel?.id, engine) {
        if (channel != null && engine != null) recorder.session(channel, engine)
    }
}
