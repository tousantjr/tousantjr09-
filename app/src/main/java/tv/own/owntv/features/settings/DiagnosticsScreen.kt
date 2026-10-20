package tv.own.owntv.features.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.setup.headline
import tv.own.owntv.features.update.UpdateDialog
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.format.localizedDecimal
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** This screen's rows as Settings search finds them. */
internal val DIAGNOSTICS_SEARCH_ROWS: List<Int> = listOf(
    R.string.diagnostics_run_checks, R.string.diagnostics_connection_title,
    R.string.diagnostics_streaming_heading, R.string.diagnostics_resync_playlist,
    R.string.diagnostics_epg_title, R.string.diagnostics_resync_epg,
    R.string.diagnostics_sports_title, R.string.diagnostics_storage_title,
    R.string.diagnostics_speed_test_title, R.string.diagnostics_player_title,
    R.string.diagnostics_app_title, R.string.diagnostics_device_title,
    R.string.diagnostics_clear_image_cache, R.string.diagnostics_open_app_settings,
    R.string.diagnostics_restart_app, R.string.diagnostics_show_report,
    R.string.diagnostics_copy_report, R.string.diagnostics_share_report,
)

/**
 * The internet speed test's UI state, local to this screen: it is a direct, cancellable download
 * (see [runSpeedTest] below), not a stored setting — nothing here is persisted.
 */
private sealed interface SpeedTestUi {
    data object Idle : SpeedTestUi
    data object Running : SpeedTestUi
    data class Done(val mbps: Double) : SpeedTestUi
    data object Failed : SpeedTestUi
}

/**
 * Settings → App → Network → Diagnostics, the Diagnostic Center: "Run checks" at the top, then each
 * check as a row with its OK/Warning/Problem word and — where there is something to do about it —
 * the fix right under it (Re-sync this playlist, Re-sync the guide). Below the checks, what is true
 * without running anything: the Movies & Series engine with a one-press switch, the app version
 * with the update check, the device, the quick fixes, and the report to show, copy or share.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DiagnosticsViewModel = koinViewModel()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val vodEngine by vm.vodEngine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toast = rememberInAppToast()

    // The speed test runs on this screen's own coroutine scope (not the ViewModel's) so leaving the
    // screen cancels an in-flight multi-megabyte download outright, the same guarantee
    // AddSourceScreen's "Test connection" / "Test HLS support" probes rely on for their own calls.
    val httpClient: HttpClient = koinInject()
    val scope = rememberCoroutineScope()
    var speedTest by remember { mutableStateOf<SpeedTestUi>(SpeedTestUi.Idle) }
    fun runSpeedTest() {
        if (speedTest is SpeedTestUi.Running) return
        speedTest = SpeedTestUi.Running
        scope.launch {
            val result = try {
                SpeedTestUi.Done(measureDownloadMbps(httpClient))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                SpeedTestUi.Failed
            }
            speedTest = result
        }
    }

    val rowsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rowsFocus.requestFocus() } }

    var showUpdate by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }

    val explanation = stringResource(R.string.diagnostics_run_checks_help)
    val report = (diagnostics as? DiagnosticsUi.Done)?.report
    val running = diagnostics is DiagnosticsUi.Running
    val appName = stringResource(R.string.app_name)
    val reportTitle = stringResource(R.string.diagnostics_report_title)
    val reportText = remember(report, appName) { report?.toText(context.resources, appName) }
    val queued = stringResource(R.string.diagnostics_resync_queued)
    val copied = stringResource(R.string.diagnostics_report_copied)
    val cacheCleared = stringResource(R.string.diagnostics_cache_cleared)
    val reportNone = stringResource(R.string.diagnostics_report_none)
    val noShareTarget = stringResource(R.string.diagnostics_share_unavailable)

    fun copyReport() {
        val text = reportText ?: run { toast.show(reportNone); return }
        copyToClipboard(context, reportTitle, text)
        toast.show(copied)
    }
    fun shareReport() {
        val text = reportText ?: run { toast.show(reportNone); return }
        if (!shareText(context, reportTitle, text)) toast.show(noShareTarget)
    }

    // Run checks, the check rows (two per playlist: its verdict and its Re-sync), the speed test,
    // then the three always-on sections: player/app/device, quick fixes, report.
    val rows = 1 + (report?.let { 1 + (if (it.sources.isEmpty()) 1 else it.sources.size * 2) + 2 + 1 + 1 } ?: 0) + 1 + 3 + 3 + 3

    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_app), stringResource(R.string.settings_group_network)),
        title = stringResource(R.string.diagnostics_title),
        count = pluralStringResource(R.plurals.settings_setting_count, rows, rows),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
    ) {
        val runTitle = stringResource(if (running) R.string.diagnostics_running else R.string.diagnostics_run_checks)
        val lastChecked = report?.let {
            stringResource(
                R.string.diagnostics_last_checked,
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    it.finishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                ),
            )
        }
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = runTitle,
            desc = lastChecked,
            value = null,
            onClick = { if (!running) vm.runChecks() },
            help = SettingHelp(runTitle, explanation, hints = settingHints(null, pinnable = false)),
        )

        if (report != null) {
            val connectionVerdict = stringResource(
                if (report.connection.status == DiagnosticStatus.OK) R.string.diagnostics_connection_ok
                else R.string.diagnostics_connection_problem,
            )
            StatusRow(
                icon = OwnTVIcon.NETWORK,
                title = stringResource(R.string.diagnostics_connection_title),
                desc = connectionVerdict + stringResource(R.string.content_epg_bits_separator) + stringResource(report.connection.network.labelRes()),
                status = report.connection.status,
            )

            StageSettingsHeading(stringResource(R.string.diagnostics_streaming_heading), count = report.sources.size.takeIf { it > 0 })
            if (report.sources.isEmpty()) {
                StatusRow(
                    icon = OwnTVIcon.PLAYLIST,
                    title = stringResource(R.string.diagnostics_streaming_heading),
                    desc = stringResource(R.string.diagnostics_no_sources),
                    status = DiagnosticStatus.WARNING,
                )
            } else {
                report.sources.forEach { source ->
                    val headline = source.result.headline(context.resources)
                    StatusRow(
                        icon = OwnTVIcon.PLAYLIST,
                        title = source.sourceName,
                        desc = source.host?.let { headline + stringResource(R.string.content_epg_bits_separator) + it } ?: headline,
                        status = source.status,
                    )
                    val resyncTitle = stringResource(R.string.diagnostics_resync_playlist)
                    val resyncHelp = stringResource(R.string.diagnostics_resync_playlist_help, source.sourceName)
                    StageSettingRow(
                        icon = OwnTVIcon.REFRESH,
                        title = stringResource(R.string.diagnostics_resync_playlist),
                        desc = playlistLine(source),
                        value = SettingValue.Action(stringResource(R.string.settings_sources_resync)),
                        onClick = { vm.resyncPlaylist(source.sourceId); toast.show(queued) },
                        help = SettingHelp(resyncTitle, resyncHelp, hints = settingHints(null, pinnable = false)),
                    )
                }
            }

            StageSettingsHeading(stringResource(R.string.diagnostics_epg_title), count = null)
            StatusRow(
                icon = OwnTVIcon.EPG,
                title = stringResource(R.string.diagnostics_epg_title),
                desc = epgLine(context.resources, report.epg),
                status = report.epg.status,
            )
            val canResyncGuide = report.epg.standaloneSources > 0
            val resyncEpgTitle = stringResource(R.string.diagnostics_resync_epg)
            val resyncEpgDesc = if (canResyncGuide) {
                pluralStringResource(R.plurals.diagnostics_epg_feeds, report.epg.standaloneSources, report.epg.standaloneSources) +
                    stringResource(R.string.content_epg_bits_separator) +
                    pluralStringResource(R.plurals.sync_count_epg, report.epg.programmes, java.text.NumberFormat.getIntegerInstance().format(report.epg.programmes))
            } else {
                stringResource(R.string.diagnostics_epg_resync_playlist_only)
            }
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = stringResource(R.string.diagnostics_resync_epg),
                desc = resyncEpgDesc,
                value = if (canResyncGuide) SettingValue.Action(stringResource(R.string.settings_sources_resync)) else null,
                onClick = { if (canResyncGuide) { vm.resyncGuide(); toast.show(queued) } },
                help = SettingHelp(resyncEpgTitle, resyncEpgDesc, hints = settingHints(null, pinnable = false)),
                enabled = canResyncGuide,
            )

            StatusRow(
                icon = OwnTVIcon.SPORTS,
                title = stringResource(R.string.diagnostics_sports_title),
                desc = sportsLine(context.resources, report.sports),
                status = report.sports.status,
            )

            StatusRow(
                icon = OwnTVIcon.FOLDER,
                title = stringResource(R.string.diagnostics_storage_title),
                desc = stringResource(
                    R.string.content_downloads_storage_free,
                    localizedDecimal(report.storage.freeBytes.coerceAtLeast(0) / 1_073_741_824.0, 1, 1),
                    localizedDecimal(report.storage.totalBytes.coerceAtLeast(0) / 1_073_741_824.0, 1, 1),
                ),
                status = report.storage.status,
            )
        }

        val speedDesc = stringResource(R.string.diagnostics_speed_test_desc)
        val speedTitle = stringResource(if (speedTest is SpeedTestUi.Running) R.string.settings_testing else R.string.diagnostics_speed_test_title)
        val speedResult = when (val s = speedTest) {
            is SpeedTestUi.Done -> stringResource(R.string.diagnostics_speed_test_result, localizedDecimal(s.mbps, 1, 1))
            SpeedTestUi.Failed -> stringResource(R.string.diagnostics_speed_test_failed)
            else -> speedDesc
        }
        StageSettingRow(
            icon = OwnTVIcon.TREND,
            title = speedTitle,
            desc = speedResult,
            value = null,
            onClick = { runSpeedTest() },
            help = SettingHelp(speedTitle, speedDesc, hints = settingHints(null, pinnable = false)),
        )

        // ---- Player, app, device: true now, no check needed -----------------------------------
        StageSettingsHeading(stringResource(R.string.diagnostics_app_heading), count = null)

        val engineNow = vodEngine
        val playerTitle = stringResource(R.string.diagnostics_player_title)
        val playerHelp = stringResource(R.string.diagnostics_player_help)
        val otherEngine = engineNow?.let { stringResource(if (it.startsOnMpv) R.string.settings_player_exoplayer else R.string.settings_player_mpv) }
        StageSettingRow(
            icon = OwnTVIcon.PLAY_CIRCLE,
            title = stringResource(R.string.diagnostics_player_title),
            desc = engineNow?.let { engineLabel(it) },
            value = otherEngine?.let { SettingValue.Action(stringResource(R.string.diagnostics_player_switch_to, it)) },
            onClick = { if (engineNow != null) vm.switchVodEngine() },
            help = SettingHelp(playerTitle, playerHelp, hints = settingHints(null, pinnable = false)),
        )

        val appTitle = stringResource(R.string.diagnostics_app_title)
        val appVersion = stringResource(R.string.diagnostics_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        StageSettingRow(
            icon = OwnTVIcon.INFO,
            title = stringResource(R.string.diagnostics_app_title),
            desc = appVersion,
            value = SettingValue.Action(stringResource(R.string.settings_check_updates)),
            onClick = { showUpdate = true },
            help = SettingHelp(appTitle, stringResource(R.string.diagnostics_app_help), hints = settingHints(null, pinnable = false)),
        )

        val deviceTitle = stringResource(R.string.diagnostics_device_title)
        val deviceLine = stringResource(
            R.string.diagnostics_device_line,
            deviceModel(),
            android.os.Build.VERSION.RELEASE.orEmpty(),
            android.os.Build.VERSION.SDK_INT,
        )
        StageSettingRow(
            icon = OwnTVIcon.PHONE,
            title = stringResource(R.string.diagnostics_device_title),
            desc = deviceLine,
            value = null,
            onClick = {},
            help = SettingHelp(deviceTitle, deviceLine, hints = settingHints(null, pinnable = false)),
        )

        // ---- Quick fixes ------------------------------------------------------------------------
        StageSettingsHeading(stringResource(R.string.diagnostics_quick_fixes), count = null)

        val clearCacheTitle = stringResource(R.string.diagnostics_clear_image_cache)
        val clearCacheDesc = stringResource(R.string.diagnostics_clear_image_cache_desc)
        StageSettingRow(
            icon = OwnTVIcon.IMAGE,
            title = stringResource(R.string.diagnostics_clear_image_cache),
            desc = clearCacheDesc,
            value = null,
            onClick = { scope.launch { vm.clearImageCache(); toast.show(cacheCleared) } },
            help = SettingHelp(clearCacheTitle, clearCacheDesc, hints = settingHints(null, pinnable = false)),
        )

        val appSettingsTitle = stringResource(R.string.diagnostics_open_app_settings)
        val appSettingsDesc = stringResource(R.string.diagnostics_open_app_settings_desc)
        StageSettingRow(
            icon = OwnTVIcon.GEAR,
            title = stringResource(R.string.diagnostics_open_app_settings),
            desc = appSettingsDesc,
            value = SettingValue.Opens(null),
            onClick = { vm.openAppSettings() },
            help = SettingHelp(appSettingsTitle, appSettingsDesc, hints = settingHints(null, pinnable = false)),
        )

        val restartTitle = stringResource(R.string.diagnostics_restart_app)
        val restartDesc = stringResource(R.string.diagnostics_restart_app_desc)
        StageSettingRow(
            icon = OwnTVIcon.POWER,
            title = stringResource(R.string.diagnostics_restart_app),
            desc = restartDesc,
            value = null,
            onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, AppIconSwitcher.applied(it)) } },
            help = SettingHelp(restartTitle, restartDesc, hints = settingHints(null, pinnable = false)),
        )

        // ---- Report -----------------------------------------------------------------------------
        StageSettingsHeading(stringResource(R.string.diagnostics_report_heading), count = null)

        val showReportTitle = stringResource(R.string.diagnostics_show_report)
        val showReportDesc = lastChecked ?: reportNone
        StageSettingRow(
            icon = OwnTVIcon.LIST,
            title = stringResource(R.string.diagnostics_show_report),
            desc = showReportDesc,
            value = if (reportText != null) SettingValue.Opens(null) else null,
            onClick = { if (reportText != null) showReport = true else toast.show(reportNone) },
            help = SettingHelp(showReportTitle, stringResource(R.string.diagnostics_show_report_desc), hints = settingHints(null, pinnable = false)),
            enabled = reportText != null,
        )

        val copyTitle = stringResource(R.string.diagnostics_copy_report)
        val copyDesc = stringResource(R.string.diagnostics_copy_report_desc)
        StageSettingRow(
            icon = OwnTVIcon.LAYERS,
            title = stringResource(R.string.diagnostics_copy_report),
            desc = copyDesc,
            value = null,
            onClick = { copyReport() },
            help = SettingHelp(copyTitle, copyDesc, hints = settingHints(null, pinnable = false)),
            enabled = reportText != null,
        )

        val shareTitle = stringResource(R.string.diagnostics_share_report)
        val shareDesc = stringResource(R.string.diagnostics_share_report_desc)
        StageSettingRow(
            icon = OwnTVIcon.SHARE,
            title = stringResource(R.string.diagnostics_share_report),
            desc = shareDesc,
            value = null,
            onClick = { shareReport() },
            help = SettingHelp(shareTitle, shareDesc, hints = settingHints(null, pinnable = false)),
            enabled = reportText != null,
        )
    }

    InAppToast(toast)

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false }, checkOnOpen = true)
    }
    if (showReport && reportText != null) {
        ReportPopup(
            text = reportText,
            onCopy = { copyReport() },
            onShare = { shareReport() },
            onDismiss = { showReport = false },
        )
    }
}

/** "<n> channels · <n> movies · <n> series", then when the playlist last synced (or that it never has). */
@Composable
private fun playlistLine(source: SourceCheck): String {
    val counts = playlistCounts(LocalContext.current.resources, source)
    val synced = source.lastSyncAt?.let {
        stringResource(
            R.string.settings_synced_at,
            android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
        )
    } ?: stringResource(R.string.diagnostics_playlist_never_synced)
    return counts + stringResource(R.string.content_epg_bits_separator) + synced
}

/** A read-only Diagnostics row: an explanation on the left, the OK/Warning/Problem word on the right. */
@Composable
private fun StatusRow(icon: OwnTVIcon, title: String, desc: String?, status: DiagnosticStatus) {
    val (label, color) = when (status) {
        DiagnosticStatus.OK -> stringResource(R.string.diagnostics_status_ok) to StageColors.Ok
        DiagnosticStatus.WARNING -> stringResource(R.string.diagnostics_status_warning) to StageColors.Warn
        DiagnosticStatus.PROBLEM -> stringResource(R.string.diagnostics_status_problem) to StageColors.Danger
    }
    val value = SettingValue.Custom {
        Text(label, style = stageText(18, 700), color = color, maxLines = 1)
    }
    StageSettingRow(
        icon = icon,
        title = title,
        desc = desc,
        value = value,
        onClick = {},
        help = SettingHelp(title, desc.orEmpty()),
    )
}

/**
 * The whole report as text in a popup, scrolled with Up/Down from whichever button has focus (the
 * text itself takes no focus), with Copy and Share under it so the report can go somewhere from here.
 */
@Composable
private fun ReportPopup(text: String, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { REPORT_SCROLL_STEP.mpx.toPx() }
    val scrollKeys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-stepPx) }; true }
            Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(stepPx) }; true }
            else -> false
        }
    }
    val popupTitle = stringResource(R.string.diagnostics_report_title)
    StagePopup(
        onDismiss = onDismiss,
        title = popupTitle,
        width = 1100.mpx,
        scroll = false,
        buttons = {
            StageButton(stringResource(R.string.diagnostics_copy_report), onClick = onCopy, height = 56.mpx, textSize = 19, modifier = scrollKeys)
            StageButton(stringResource(R.string.common_share), onClick = onShare, height = 56.mpx, textSize = 19, modifier = scrollKeys)
            StageButton(stringResource(R.string.common_done), onClick = onDismiss, height = 56.mpx, textSize = 19, tinted = true, modifier = Modifier.focusRequester(first).then(scrollKeys))
        },
    ) {
        Column(Modifier.heightIn(max = REPORT_MAX_HEIGHT.mpx).verticalScroll(scroll)) {
            Text(text, style = stageText(17, 500), color = StageColors.Text)
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}

/**
 * Hands the text to the system share sheet (the chooser is the system's own, so it always opens —
 * on a TV with nothing to send to it says so itself); false only when even that could not start.
 */
private fun shareText(context: Context, subject: String, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType(SHARE_MIME)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    return runCatching {
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * A timed download of a public test file, measured as Mbps. [SPEED_TEST_URL] asks Cloudflare's own
 * speed-test endpoint for an exact byte count (a few MB — enough for a steady reading, small enough
 * not to matter on a metered connection), so the loop below simply reads until the stream ends; no
 * Content-Length trust or manual cut-off is needed. Download-only, as the task calls for.
 */
private suspend fun measureDownloadMbps(httpClient: HttpClient): Double = withContext(Dispatchers.IO) {
    var totalBytes = 0L
    val startedAt = android.os.SystemClock.elapsedRealtime()
    httpClient.get(SPEED_TEST_URL, maxAttempts = 1) { input ->
        val buffer = ByteArray(SPEED_TEST_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            totalBytes += read
        }
    }
    val elapsedMs = (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
    (totalBytes * 8.0 / 1_000_000.0) / (elapsedMs / 1000.0)
}

private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=5000000"
private const val SPEED_TEST_BUFFER_BYTES = 64 * 1024
private const val SHARE_MIME = "text/plain"
private const val REPORT_MAX_HEIGHT = 620
private const val REPORT_SCROLL_STEP = 220
