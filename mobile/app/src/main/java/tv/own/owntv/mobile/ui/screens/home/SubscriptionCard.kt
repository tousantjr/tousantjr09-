package tv.own.owntv.mobile.ui.screens.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageRadii
import tv.own.owntv.mobile.ui.stage.stageText
import java.text.DateFormat
import java.util.Date

/** The notice's headline: how long is left, or that it has already ended. Shared with the notification. */
fun ExpiryWarning.title(context: Context): String =
    if (expired) context.getString(R.string.subscription_expired_title, sourceName)
    else context.resources.getQuantityString(R.plurals.subscription_expires_title, daysLeft, sourceName, daysLeft)

/** The notice's second line: the date, and what to do about it. */
fun ExpiryWarning.body(context: Context): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiresAtMs))
    return context.getString(if (expired) R.string.subscription_expired_body else R.string.subscription_expires_body, date)
}

/**
 * Home's subscription notice: a card on the warning tint with an X that puts it away until tomorrow
 * (the date does not change by being looked at, but the next day's count does).
 */
@Composable
internal fun SubscriptionCard(warning: ExpiryWarning, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalStagePalette.current
    val context = LocalContext.current
    val title = remember(warning) { warning.title(context) }
    val body = remember(warning) { warning.body(context) }
    Row(
        modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .background(palette.warn.copy(alpha = 0.16f), RoundedCornerShape(StageRadii.Row))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StageIcon(MobileIcons.Warning, 22.dp, palette.warn)
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(14.5f, 700), color = palette.text)
            Text(body, style = stageText(12.5f, 500), color = palette.muted, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDismiss) {
            Icon(MobileIcons.Close, contentDescription = stringResource(R.string.common_dismiss), tint = palette.muted)
        }
    }
}
