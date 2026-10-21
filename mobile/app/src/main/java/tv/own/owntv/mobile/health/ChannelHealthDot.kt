package tv.own.owntv.mobile.health

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
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.stage.LocalStagePalette

/**
 * The small green / amber / red dot beside a channel's name: how it has behaved on this phone
 * ([ChannelHealthStore]). Draws nothing for a channel with no history, so a fresh playlist looks as
 * it always did. Carries a content description, because a colour alone says nothing to a screen reader.
 */
@Composable
fun ChannelHealthDot(channelId: Long, modifier: Modifier = Modifier) {
    val store: ChannelHealthStore = koinInject()
    val snapshot by store.snapshot.collectAsState()
    val level = remember(snapshot, channelId) { snapshot[channelId]?.level(System.currentTimeMillis()) } ?: return
    val palette = LocalStagePalette.current
    val color = when (level) {
        HealthLevel.GOOD -> palette.ok
        HealthLevel.FAIR -> palette.warn
        HealthLevel.POOR -> palette.danger
    }
    val label = stringResource(
        when (level) {
            HealthLevel.GOOD -> R.string.channel_health_good
            HealthLevel.FAIR -> R.string.channel_health_fair
            HealthLevel.POOR -> R.string.channel_health_poor
        },
    )
    Box(modifier.size(8.dp).semantics { contentDescription = label }.background(color, CircleShape))
}
