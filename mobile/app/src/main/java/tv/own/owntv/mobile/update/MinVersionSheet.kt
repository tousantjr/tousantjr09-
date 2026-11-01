package tv.own.owntv.mobile.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.screens.settings.MobileUpdateCheck
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * "Update needed" as a Stage sheet. Update now opens the latest phone APK, exactly as the update
 * notification does; closing the sheet without it only hides it until the next launch.
 */
@Composable
internal fun MinVersionSheet(notice: MinVersionNotice, onDismiss: () -> Unit) {
    val palette = LocalStagePalette.current
    val context = LocalContext.current
    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.min_version_title)) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 16.dp)) {
            Text(
                notice.message ?: stringResource(R.string.min_version_body, BuildConfig.VERSION_NAME, notice.required),
                style = stageText(16f, 500, lineHeight = 1.45f),
                color = palette.text,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            StageButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MobileUpdateCheck.latestApkUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        // No browser to hand the download to; the sheet comes back next launch.
                    }
                    onDismiss()
                },
                text = stringResource(R.string.min_version_update),
                style = StageButtonStyle.PRIMARY,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
