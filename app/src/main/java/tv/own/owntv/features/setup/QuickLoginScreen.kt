package tv.own.owntv.features.setup

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The first screen on a fresh install: the MPTV logo, Username, Password and Connect — nothing else.
 * Xtream only; the server is the built-in one (same as Add a source). Other playlist types can still
 * be added later from Settings › Add a source.
 */
@Composable
internal fun QuickLoginScreen(onXtream: (username: String, password: String) -> Unit) {
    val a = stageAccent
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = QuickLogin.ready(username, password)
    val connect = { if (ready) onXtream(username.trim(), password) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().stageBackground(a.accent)) {
        Column(
            Modifier.align(Alignment.Center).width(760.mpx.coerceAtMost(maxWidth - 128.mpx)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.mpx), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(AppIcon.DEFAULT, 72.mpx)
                Text(stringResource(R.string.app_name), style = stageText(56, 800), color = StageColors.Text)
            }
            Column(
                Modifier
                    .padding(top = 32.mpx)
                    .fillMaxWidth()
                    .stageGlass(28.mpx)
                    .focusGroup()
                    .padding(horizontal = 44.mpx, vertical = 36.mpx),
                verticalArrangement = Arrangement.spacedBy(6.mpx),
            ) {
                StageFieldRow(
                    OwnTVIcon.PERSON, stringResource(R.string.setup_username), username, { username = it },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                StageFieldRow(OwnTVIcon.PENCIL, stringResource(R.string.setup_password), password, { password = it }, password = true, onDone = connect)
                Box(Modifier.fillMaxWidth().padding(top = 20.mpx), contentAlignment = Alignment.CenterEnd) {
                    StagePill(
                        stringResource(R.string.quick_login_connect), onClick = connect,
                        trailingIcon = OwnTVIcon.CHEVRON, enabled = ready, highlighted = ready,
                    )
                }
            }
        }
    }
}

/** The screen's rule, kept out of Compose so it can be unit tested. */
internal object QuickLogin {
    fun ready(username: String, password: String): Boolean = username.isNotBlank() && password.isNotBlank()
}
