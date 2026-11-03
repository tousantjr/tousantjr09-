package tv.own.owntv.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The three ways in, as the tabs on the simple login card. */
internal enum class QuickLoginKind { XTREAM, M3U, PORTAL }

/**
 * The first screen on a fresh install: the MPTV logo and one sign-in card, nothing else.
 * Tabs pick Xtream (username + password; the server is the built-in one, as on Add a source),
 * M3U (playlist URL) or Portal (portal URL + MAC). Connect hands the details up; "More setup
 * options" opens the full wizard (language, display size, backup restore, sync, profiles).
 */
@Composable
internal fun QuickLoginScreen(
    onXtream: (username: String, password: String) -> Unit,
    onM3u: (url: String) -> Unit,
    onPortal: (portalUrl: String, mac: String) -> Unit,
    onMoreOptions: () -> Unit,
) {
    val a = stageAccent
    var kind by rememberSaveable { mutableStateOf(QuickLoginKind.XTREAM) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var portalUrl by rememberSaveable { mutableStateOf("") }
    var mac by rememberSaveable { mutableStateOf("") }
    val macValid = tv.own.owntv.core.stalker.StalkerClient.canonicalizeMac(mac) != null
    val ready = QuickLogin.ready(kind, username, password, m3uUrl, portalUrl, macValid)
    val connect = {
        if (ready) when (kind) {
            QuickLoginKind.XTREAM -> onXtream(username.trim(), password)
            QuickLoginKind.M3U -> onM3u(m3uUrl.trim())
            QuickLoginKind.PORTAL -> onPortal(portalUrl.trim(), mac.trim())
        }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize().stageBackground(a.accent)) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.mpx, bottom = 100.mpx)
                .width(880.mpx.coerceAtMost(maxWidth - 128.mpx))
                .heightIn(max = maxHeight - 148.mpx)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The logo: the app mark and the name, nothing else above the card.
            Row(horizontalArrangement = Arrangement.spacedBy(18.mpx), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(AppIcon.DEFAULT, 72.mpx)
                Text(stringResource(R.string.app_name), style = stageText(56, 800), color = StageColors.Text)
            }
            Column(
                Modifier
                    .padding(top = 28.mpx)
                    .fillMaxWidth()
                    .stageGlass(28.mpx)
                    .focusGroup()
                    .padding(horizontal = 48.mpx, vertical = 40.mpx),
            ) {
                Text(stringResource(R.string.quick_login_title), style = stageText(38, 800), color = StageColors.Text)
                Text(stringResource(R.string.quick_login_sub), style = stageText(20, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 6.mpx))

                // Login type tabs.
                Row(
                    Modifier
                        .padding(top = 26.mpx)
                        .fillMaxWidth()
                        .background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(18.mpx))
                        .padding(8.mpx),
                    horizontalArrangement = Arrangement.spacedBy(8.mpx),
                ) {
                    QuickLoginKind.entries.forEachIndexed { i, k ->
                        val on = k == kind
                        StageSurface(
                            onClick = { kind = k },
                            radius = 12.mpx,
                            focusStyle = StageFocus.FX,
                            idle = if (on) Modifier.background(a.accent.copy(alpha = 0.18f), RoundedCornerShape(12.mpx)) else Modifier,
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(60.mpx)
                                .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        ) { focused ->
                            Text(
                                stringResource(k.labelRes),
                                style = stageText(21, if (on) 800 else 600),
                                color = if (on && !focused) a.accent else StageColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Column(Modifier.padding(top = 20.mpx), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                    when (kind) {
                        QuickLoginKind.XTREAM -> {
                            StageFieldRow(OwnTVIcon.PERSON, stringResource(R.string.setup_username), username, { username = it })
                            StageFieldRow(OwnTVIcon.PENCIL, stringResource(R.string.setup_password), password, { password = it }, password = true, onDone = connect)
                        }
                        QuickLoginKind.M3U -> StageFieldRow(
                            OwnTVIcon.PLAYLIST, stringResource(R.string.quick_login_playlist_url), m3uUrl, { m3uUrl = it },
                            placeholder = stringResource(R.string.setup_playlist_example), keyboardType = KeyboardType.Uri, onDone = connect,
                        )
                        QuickLoginKind.PORTAL -> {
                            StageFieldRow(
                                OwnTVIcon.LIST, stringResource(R.string.setup_portal_url), portalUrl, { portalUrl = it },
                                placeholder = stringResource(R.string.setup_portal_example), keyboardType = KeyboardType.Uri,
                            )
                            StageFieldRow(
                                OwnTVIcon.PENCIL, stringResource(R.string.setup_mac_address), mac, { mac = it },
                                placeholder = stringResource(R.string.setup_mac_example), onDone = connect,
                            )
                            if (mac.isNotBlank() && !macValid) {
                                Text(stringResource(R.string.setup_mac_invalid), style = stageText(17, 600), color = StageColors.Muted, modifier = Modifier.padding(start = 8.mpx))
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 26.mpx), verticalAlignment = Alignment.CenterVertically) {
                    StagePill(stringResource(R.string.quick_login_more_options), onClick = onMoreOptions)
                    Spacer(Modifier.weight(1f))
                    StagePill(
                        stringResource(R.string.quick_login_connect), onClick = connect,
                        trailingIcon = OwnTVIcon.CHEVRON, enabled = ready, highlighted = ready,
                    )
                }
                Text(
                    stringResource(R.string.quick_login_privacy) + "\n" + stringResource(R.string.setup_disclaimer),
                    style = stageText(16, 500),
                    color = StageColors.Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.mpx),
                )
            }
        }
        StageKeyHints(
            listOf(
                stringResource(R.string.common_ok) to stringResource(R.string.content_key_select),
            ),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.mpx),
            textSize = 15,
        )
    }
}

private val QuickLoginKind.labelRes: Int get() = when (this) {
    QuickLoginKind.XTREAM -> R.string.setup_xtream
    QuickLoginKind.M3U -> R.string.setup_m3u
    QuickLoginKind.PORTAL -> R.string.quick_login_portal
}

/** The card's rules, kept out of Compose so they can be unit tested. */
internal object QuickLogin {
    fun ready(kind: QuickLoginKind, username: String, password: String, m3uUrl: String, portalUrl: String, macValid: Boolean): Boolean =
        when (kind) {
            QuickLoginKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
            QuickLoginKind.M3U -> m3uUrl.isNotBlank()
            QuickLoginKind.PORTAL -> portalUrl.isNotBlank() && macValid
        }
}
