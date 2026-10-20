package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * The wall clock, as something a screen can be drawn from: `System.currentTimeMillis()` read in a
 * composable is answered once and then frozen until something else recomposes. Home's featured
 * game counts down to kick-off from this. Thirty seconds between ticks: a countdown shown in
 * minutes does not need more, and Home is a big screen to recompose.
 */
@Composable
fun rememberClockTick(periodMs: Long = 30_000L): State<Long> {
    val tick = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            tick.longValue = System.currentTimeMillis()
        }
    }
    return tick
}
