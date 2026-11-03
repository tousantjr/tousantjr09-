package tv.own.owntv.mobile.ui.stage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** MPTV's glowing VOD buttons: how strong the halo is and how fast it breathes. */
object VodGlow {
    const val PULSE_MS = 1600
    const val MIN = 0.25f
    const val MAX = 0.55f

    /** The halo's alpha for a [pulse] between 0 and 1. */
    fun alpha(pulse: Float): Float = MIN + (MAX - MIN) * pulse.coerceIn(0f, 1f)
}

/**
 * A soft accent halo that slowly breathes around a VOD button, with a thin accent edge. The pulse is
 * read in the draw phase only, so it redraws without recomposing.
 */
@Composable
fun Modifier.vodGlow(color: Color, radius: Dp): Modifier {
    val pulse by rememberInfiniteTransition(label = "vodGlow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(VodGlow.PULSE_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "vodGlowPulse",
    )
    return drawWithCache {
        val r = radius.toPx()
        val outline = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r)))
        }
        val w = 1.dp.toPx()
        onDrawBehind {
            val a = VodGlow.alpha(pulse)
            drawBoxShadow(outline, color.copy(alpha = a), 22.dp.toPx())
            drawRoundRect(
                color.copy(alpha = (a + 0.2f).coerceAtMost(1f)),
                topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(r), style = Stroke(w),
            )
        }
    }
}
