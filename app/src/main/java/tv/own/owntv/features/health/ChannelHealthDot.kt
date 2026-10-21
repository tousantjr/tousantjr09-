package tv.own.owntv.features.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx

/**
 * The small green / amber / red dot beside a channel's name: how it has behaved on this device
 * ([ChannelHealthStore]). Draws nothing for a channel with no history, so a fresh playlist looks as
 * it always did. Carries a content description, because a colour alone says nothing to a screen reader.
 */
@Composable
fun ChannelHealthDot(channelId: Long, modifier: Modifier = Modifier) {
    val store: ChannelHealthStore = koinInject()
    val snapshot by store.snapshot.collectAsState()
    val level = remember(snapshot, channelId) { snapshot[channelId]?.level(System.currentTimeMillis()) } ?: return
    val color = when (level) {
        HealthLevel.GOOD -> StageColors.Ok
        HealthLevel.FAIR -> StageColors.Warn
        HealthLevel.POOR -> StageColors.Danger
    }
    val label = stringResource(
        when (level) {
            HealthLevel.GOOD -> R.string.channel_health_good
            HealthLevel.FAIR -> R.string.channel_health_fair
            HealthLevel.POOR -> R.string.channel_health_poor
        },
    )
    Box(modifier.size(10.mpx).semantics { contentDescription = label }.background(color, CircleShape))
}
