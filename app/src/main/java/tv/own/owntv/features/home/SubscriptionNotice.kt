package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText
import java.text.DateFormat
import java.util.Date

/**
 * Home's subscription notice: a strip on the warning tint, never focusable. The rows' focus
 * restoration maps by [tv.own.owntv.core.model.HomeRow], and a focusable extra would have to join
 * that map; a strip the D-pad passes over says the same thing without it. [start] is the rows' own
 * left edge, so it lines up with their headings.
 */
@Composable
internal fun SubscriptionNotice(warning: ExpiryWarning, start: Dp, modifier: Modifier = Modifier) {
    val date = remember(warning.expiresAtMs) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(warning.expiresAtMs))
    }
    val title = if (warning.expired) {
        stringResource(R.string.subscription_expired_title, warning.sourceName)
    } else {
        pluralStringResource(R.plurals.subscription_expires_title, warning.daysLeft, warning.sourceName, warning.daysLeft)
    }
    val body = stringResource(if (warning.expired) R.string.subscription_expired_body else R.string.subscription_expires_body, date)
    Row(
        modifier
            .padding(start = start, end = 64.mpx)
            .fillMaxWidth()
            .background(StageColors.Warn.copy(alpha = 0.16f), RoundedCornerShape(18.mpx))
            .padding(horizontal = 22.mpx, vertical = 16.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.mpx),
    ) {
        OwnTVIcon(OwnTVIcon.WARNING, tint = StageColors.Warn, modifier = Modifier.size(30.mpx))
        Column {
            Text(title, style = stageText(21, 700), color = StageColors.Text)
            Text(body, style = stageText(17, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 4.mpx))
        }
    }
}
