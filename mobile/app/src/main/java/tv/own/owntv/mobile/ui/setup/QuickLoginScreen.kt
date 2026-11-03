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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrandMark
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.StageSegmented
import tv.own.owntv.mobile.ui.stage.stageBackground
import tv.own.owntv.mobile.ui.stage.stageGlass
import tv.own.owntv.mobile.ui.stage.stageText

/** The three ways in, as the tabs on the simple login card. */
enum class QuickLoginKind { XTREAM, M3U, PORTAL }

/** The card's rules, kept out of Compose so they can be unit tested. */
object QuickLogin {
    fun ready(kind: QuickLoginKind, username: String, password: String, m3uUrl: String, portalUrl: String, macValid: Boolean): Boolean =
        when (kind) {
            QuickLoginKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
            QuickLoginKind.M3U -> m3uUrl.isNotBlank()
            QuickLoginKind.PORTAL -> portalUrl.isNotBlank() && macValid
        }
}

/**
 * The first screen on a fresh install: the MPTV logo and one sign-in card, nothing else.
 * Xtream asks only for username + password (the server is the built-in one, as on Add a source);
 * M3U asks for the playlist URL; Portal for the portal URL and MAC. "More setup options" opens the
 * full wizard (language, display size, backup restore, sync, profiles).
 */
@Composable
fun QuickLoginScreen(
    onXtream: (username: String, password: String) -> Unit,
    onM3u: (url: String) -> Unit,
    onPortal: (portalUrl: String, mac: String) -> Unit,
    onMoreOptions: () -> Unit,
) {
    val palette = LocalStagePalette.current
    var kindIndex by rememberSaveable { mutableIntStateOf(0) }
    val kind = QuickLoginKind.entries[kindIndex]
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var portalUrl by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf("") }
    val macValid = StalkerClient.canonicalizeMac(mac) != null
    val ready = QuickLogin.ready(kind, username, password, m3uUrl, portalUrl, macValid)
    val connect = {
        if (ready) when (kind) {
            QuickLoginKind.XTREAM -> onXtream(username.trim(), password)
            QuickLoginKind.M3U -> onM3u(m3uUrl.trim())
            QuickLoginKind.PORTAL -> onPortal(portalUrl.trim(), mac.trim())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .stageBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(AppIcon.DEFAULT, 52.dp)
            Text(stringResource(R.string.app_name), style = stageText(36f, 800), color = palette.text)
        }
        Column(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .stageGlass(RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 22.dp),
        ) {
            Text(stringResource(R.string.quick_login_title), style = stageText(22f, 800), color = palette.text)
            Text(stringResource(R.string.quick_login_sub), style = stageText(14f, 500), color = palette.muted, modifier = Modifier.padding(top = 4.dp))
            StageSegmented(
                options = listOf(stringResource(R.string.setup_xtream), stringResource(R.string.setup_m3u), stringResource(R.string.quick_login_portal)),
                selected = kindIndex,
                onSelect = { kindIndex = it },
                fill = true,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
            )
            when (kind) {
                QuickLoginKind.XTREAM -> {
                    StageField(username, { username = it }, stringResource(R.string.setup_username), imeAction = ImeAction.Next)
                    StageField(password, { password = it }, stringResource(R.string.setup_password), password = true, onImeDone = connect)
                }
                QuickLoginKind.M3U -> StageField(
                    m3uUrl, { m3uUrl = it }, stringResource(R.string.quick_login_playlist_url),
                    placeholder = stringResource(R.string.setup_playlist_example), keyboardType = KeyboardType.Uri, onImeDone = connect,
                )
                QuickLoginKind.PORTAL -> {
                    StageField(
                        portalUrl, { portalUrl = it }, stringResource(R.string.setup_portal_url),
                        placeholder = stringResource(R.string.setup_portal_example), keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
                    )
                    StageField(
                        mac, { mac = it }, stringResource(R.string.setup_mac_address),
                        placeholder = stringResource(R.string.setup_mac_example),
                        isError = mac.isNotBlank() && !macValid,
                        supportingText = if (mac.isNotBlank() && !macValid) stringResource(R.string.setup_mac_invalid) else null,
                        onImeDone = connect,
                    )
                }
            }
            StageButton(
                onClick = connect,
                text = stringResource(R.string.quick_login_connect),
                style = StageButtonStyle.PRIMARY,
                enabled = ready,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            StageButton(
                onClick = onMoreOptions,
                text = stringResource(R.string.quick_login_more_options),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text(
                stringResource(R.string.quick_login_privacy) + "\n" + stringResource(R.string.setup_disclaimer),
                style = stageText(12f, 500),
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}
