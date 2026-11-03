package tv.own.owntv.ui.stage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import tv.own.owntv.ui.theme.mpx

/** MPTV's glowing VOD buttons: how strong the halo is idle and focused, and how fast it breathes. */
internal object VodGlow {
    const val PULSE_MS = 1600
    const val IDLE_MIN = 0.22f
    const val IDLE_MAX = 0.42f
    const val FOCUSED_MIN = 0.55f
    const val FOCUSED_MAX = 0.85f
    const val IDLE_BLUR = 26
    const val FOCUSED_BLUR = 56

    /** The halo's alpha for a [pulse] between 0 and 1. */
    fun alpha(focused: Boolean, pulse: Float): Float {
        val p = pulse.coerceIn(0f, 1f)
        return if (focused) FOCUSED_MIN + (FOCUSED_MAX - FOCUSED_MIN) * p else IDLE_MIN + (IDLE_MAX - IDLE_MIN) * p
    }
}

/**
 * A soft accent halo that slowly breathes around a VOD button, with a thin accent edge while idle;
 * focused, the halo grows and brightens. Place it before the button's focus target so it sees focus.
 * The pulse is read in the draw phase only, so it redraws without recomposing.
 */
@Composable
fun Modifier.vodGlow(color: Color, radius: Dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "vodGlow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(VodGlow.PULSE_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "vodGlowPulse",
    )
    val idleBlur = VodGlow.IDLE_BLUR.mpx
    val focusedBlur = VodGlow.FOCUSED_BLUR.mpx
    val edge = 1.5f.mpx
    return this
        .onFocusChanged { focused = it.hasFocus }
        .drawBehind {
            val r = radius.toPx()
            val a = VodGlow.alpha(focused, pulse)
            drawBoxShadow(color.copy(alpha = a), (if (focused) focusedBlur else idleBlur).toPx(), r)
            if (!focused) {
                val w = edge.toPx()
                drawRoundRect(
                    color.copy(alpha = (a + 0.25f).coerceAtMost(1f)),
                    topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r), style = Stroke(w),
                )
            }
        }
}
