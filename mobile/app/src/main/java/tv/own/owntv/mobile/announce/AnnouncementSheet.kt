package tv.own.owntv.mobile.announce

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/** The announcement as a Stage sheet, like the reminder prompt: an accent eyebrow, the message, OK. */
@Composable
internal fun AnnouncementSheet(announcement: Announcement, onDismiss: () -> Unit) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    MobileBottomSheet(onDismissRequest = onDismiss, title = announcement.title.ifBlank { stringResource(R.string.announcement_title) }) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StageIcon(MobileIcons.Bell, 14.dp, accent)
                Text(stringResource(R.string.announcement_eyebrow), style = stageText(11.5f, 700), color = accent)
            }
            Text(
                announcement.message,
                style = stageText(16f, 500, lineHeight = 1.45f),
                color = palette.text,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            StageButton(
                onClick = onDismiss,
                text = stringResource(R.string.common_ok),
                style = StageButtonStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
