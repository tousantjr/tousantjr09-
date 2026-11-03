package tv.own.owntv.mobile.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrandMark
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText

/** The screen's rule, kept out of Compose so it can be unit tested. */
object QuickLogin {
    fun ready(username: String, password: String): Boolean = username.isNotBlank() && password.isNotBlank()
}

/**
 * The first screen on a fresh install: the MPTV logo, Username, Password and Connect — nothing else.
 * Xtream only; the server is the built-in one (same as Add a source). Other playlist types can still
 * be added later from Settings › Add a source.
 */
@Composable
fun QuickLoginScreen(onXtream: (username: String, password: String) -> Unit) {
    val palette = LocalStagePalette.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = QuickLogin.ready(username, password)
    val connect = { if (ready) onXtream(username.trim(), password) }

    Column(
        Modifier
            .fillMaxSize()
            .stageBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(AppIcon.DEFAULT, 52.dp)
            Text(stringResource(R.string.app_name), style = stageText(36f, 800), color = palette.text)
        }
        Column(
            Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .stageGlass(RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 22.dp),
        ) {
            StageField(username, { username = it }, stringResource(R.string.setup_username), imeAction = ImeAction.Next)
            StageField(password, { password = it }, stringResource(R.string.setup_password), password = true, onImeDone = connect)
            StageButton(
                onClick = connect,
                text = stringResource(R.string.quick_login_connect),
                style = StageButtonStyle.PRIMARY,
                enabled = ready,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}
