package tv.own.owntv.mobile.ui.screens.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.brand.AppIconSwitcher
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.setup.headline
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSectionHead
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.stageText
import tv.own.owntv.mobile.ui.theme.MobileDimens

/**
 * Settings → App → Network → Diagnostics, the Diagnostic Center: "Run checks" at the top, then each
 * check as a row with its OK/Warning/Problem word and — where there is something to do about it —
 * the fix right under it (Re-sync this playlist, Re-sync TV guide). Below the checks, what is true
 * without running anything: the Movies & Series engine with a one-tap switch, the app version with
 * the phone's own update check ([MobileUpdateCheck]), the device, the quick fixes, and the report
 * to show, copy or share — mobile's own page over [DiagnosticsViewModel], which carries the same
 * logic as the TV app's own Diagnostics screen.
 *
 * The checks run only on "Run checks" ([DiagnosticsViewModel.runChecks]), never automatically: a
 * [tv.own.owntv.core.repository.SourceTester] run briefly costs a live connection slot on an Xtream
 * account, the same reason this app's own Proxy/DNS "Test" rows are button-triggered. The speed test
 * runs on this screen's own [rememberCoroutineScope] (not the view model's) so leaving the page
 * cancels an in-flight multi-megabyte download outright, through [HttpClient]'s own cancellation hook.
 */
private sealed interface SpeedTestUi {
    data object Idle : SpeedTestUi
    data object Running : SpeedTestUi
    data class Done(val mbps: Double) : SpeedTestUi
    data object Failed : SpeedTestUi
}

@Composable
fun DiagnosticsPage(modifier: Modifier = Modifier, vm: DiagnosticsViewModel = koinViewModel()) {
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val vodEngine by vm.vodEngine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val httpClient: HttpClient = koinInject()
    val scope = rememberCoroutineScope()
    var speedTest by remember { mutableStateOf<SpeedTestUi>(SpeedTestUi.Idle) }
    fun runSpeedTest() {
        if (speedTest is SpeedTestUi.Running) return
        speedTest = SpeedTestUi.Running
        scope.launch {
            speedTest = try {
                SpeedTestUi.Done(measureDownloadMbps(httpClient))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                SpeedTestUi.Failed
            }
        }
    }

    var showReport by remember { mutableStateOf(false) }

    // The channel sweep: its state is app-wide (ChannelSweep), the probing is the view model's job.
    val sweepState by vm.channelSweep.collectAsStateWithLifecycle()
    var showSweepConfirm by remember { mutableStateOf(false) }
    var showSweepStop by remember { mutableStateOf(false) }
    var showDeadList by remember { mutableStateOf(false) }
    val sweepDone = sweepState as? tv.own.owntv.mobile.health.SweepUi.Done
    val deadFound = sweepDone?.dead?.takeIf { it.isNotEmpty() }

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

    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun copyReport() {
        val text = reportText ?: run { say(reportNone); return }
        copyToClipboard(context, reportTitle, text)
        say(copied)
    }
    fun shareReport() {
        val text = reportText ?: run { say(reportNone); return }
        if (!shareText(context, reportTitle, text)) say(noShareTarget)
    }

    val lastChecked = report?.let {
        stringResource(
            R.string.diagnostics_last_checked,
            android.text.format.DateUtils.getRelativeTimeSpanString(
                it.finishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ),
        )
    }

    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(stringResource(R.string.diagnostics_run_checks_help)) }
        item(key = "run") {
            StageSettingRow(
                icon = MobileIcons.Refresh,
                title = stringResource(if (running) R.string.diagnostics_running else R.string.diagnostics_run_checks),
                subtitle = lastChecked,
                onClick = { if (!running) vm.runChecks() },
            ) {}
        }

        if (report != null) {
            item(key = "connection") {
                val verdict = stringResource(
                    if (report.connection.status == DiagnosticStatus.OK) R.string.diagnostics_connection_ok
                    else R.string.diagnostics_connection_problem,
                )
                StatusRow(
                    icon = MobileIcons.Wifi,
                    title = stringResource(R.string.diagnostics_connection_title),
                    desc = verdict + stringResource(R.string.content_epg_bits_separator) + stringResource(report.connection.network.labelRes()),
                    status = report.connection.status,
                )
            }

            item(key = "sources-head") {
                StageSectionHead(stringResource(R.string.diagnostics_streaming_heading), count = report.sources.size.takeIf { it > 0 })
            }
            if (report.sources.isEmpty()) {
                item(key = "no-sources") {
                    StatusRow(
                        icon = MobileIcons.Layers,
                        title = stringResource(R.string.diagnostics_streaming_heading),
                        desc = stringResource(R.string.diagnostics_no_sources),
                        status = DiagnosticStatus.WARNING,
                    )
                }
            } else {
                items(report.sources, key = { "source-" + it.sourceId }) { source ->
                    val headline = source.result.headline(LocalContext.current.resources)
                    StatusRow(
                        icon = MobileIcons.Layers,
                        title = source.sourceName,
                        desc = source.host?.let { headline + stringResource(R.string.content_epg_bits_separator) + it } ?: headline,
                        status = source.status,
                    )
                    StageSettingRow(
                        icon = MobileIcons.Sync,
                        title = stringResource(R.string.diagnostics_resync_playlist),
                        subtitle = playlistLine(source),
                        onClick = { vm.resyncPlaylist(source.sourceId); say(queued) },
                    ) { StageRowValue(stringResource(R.string.settings_sources_resync)) }
                }
            }

            item(key = "epg-head") { StageSectionHead(stringResource(R.string.diagnostics_epg_title)) }
            item(key = "epg") {
                StatusRow(
                    icon = MobileIcons.Guide,
                    title = stringResource(R.string.diagnostics_epg_title),
                    desc = epgLine(LocalContext.current.resources, report.epg),
                    status = report.epg.status,
                )
            }
            item(key = "epg-resync") {
                val canResyncGuide = report.epg.standaloneSources > 0
                val desc = if (canResyncGuide) {
                    pluralStringResource(R.plurals.diagnostics_epg_feeds, report.epg.standaloneSources, report.epg.standaloneSources) +
                        stringResource(R.string.content_epg_bits_separator) +
                        pluralStringResource(R.plurals.sync_count_epg, report.epg.programmes, NumberFormat.getIntegerInstance().format(report.epg.programmes))
                } else {
                    stringResource(R.string.diagnostics_epg_resync_playlist_only)
                }
                StageSettingRow(
                    icon = MobileIcons.Sync,
                    title = stringResource(R.string.diagnostics_resync_epg),
                    subtitle = desc,
                    onClick = if (canResyncGuide) ({ vm.resyncGuide(); say(queued) }) else null,
                ) { if (canResyncGuide) StageRowValue(stringResource(R.string.settings_sources_resync)) }
            }

            item(key = "sports") {
                StatusRow(
                    icon = MobileIcons.Sports,
                    title = stringResource(R.string.diagnostics_sports_title),
                    desc = sportsLine(LocalContext.current.resources, report.sports),
                    status = report.sports.status,
                )
            }

            item(key = "storage") {
                StatusRow(
                    icon = MobileIcons.Folder,
                    title = stringResource(R.string.diagnostics_storage_title),
                    desc = stringResource(
                        R.string.content_downloads_storage_free,
                        decimal(report.storage.freeBytes.coerceAtLeast(0) / 1_073_741_824.0),
                        decimal(report.storage.totalBytes.coerceAtLeast(0) / 1_073_741_824.0),
                    ),
                    status = report.storage.status,
                )
            }
        }

        item(key = "speed") {
            val speedDesc = stringResource(R.string.diagnostics_speed_test_desc)
            val speedTitle = stringResource(if (speedTest is SpeedTestUi.Running) R.string.settings_testing else R.string.diagnostics_speed_test_title)
            val speedResult = when (val s = speedTest) {
                is SpeedTestUi.Done -> stringResource(R.string.diagnostics_speed_test_result, decimal(s.mbps))
                SpeedTestUi.Failed -> stringResource(R.string.diagnostics_speed_test_failed)
                else -> speedDesc
            }
            StageSettingRow(
                icon = MobileIcons.Schedule,
                title = speedTitle,
                subtitle = speedResult,
                onClick = { runSpeedTest() },
            ) {}
        }

        // ---- Player, app, device: true now, no check needed -----------------------------------
        item(key = "app-head") { StageSectionHead(stringResource(R.string.diagnostics_app_heading)) }
        item(key = "player") {
            val engineNow = vodEngine
            val otherEngine = engineNow?.let { stringResource(if (it.startsOnMpv) R.string.settings_player_exoplayer else R.string.settings_player_mpv) }
            StageSettingRow(
                icon = MobileIcons.PlayCircle,
                title = stringResource(R.string.diagnostics_player_title),
                subtitle = engineNow?.let { engineLabel(it) } ?: stringResource(R.string.diagnostics_player_help),
                onClick = { if (engineNow != null) vm.switchVodEngine() },
            ) { otherEngine?.let { StageRowValue(stringResource(R.string.diagnostics_player_switch_to, it)) } }
        }
        item(key = "app") {
            val current = BuildConfig.VERSION_NAME
            val versionLine = stringResource(R.string.diagnostics_app_version, current, BuildConfig.VERSION_CODE)
            val available = (update as? UpdateCheckUi.Done)?.result as? MobileUpdateCheck.Result.Available
            val subtitle = when (val u = update) {
                UpdateCheckUi.Idle -> versionLine
                UpdateCheckUi.Checking -> stringResource(R.string.update_checking)
                is UpdateCheckUi.Done -> when (val r = u.result) {
                    is MobileUpdateCheck.Result.Available -> stringResource(R.string.update_available_version, r.version, current)
                    MobileUpdateCheck.Result.UpToDate -> stringResource(R.string.update_latest, current)
                    MobileUpdateCheck.Result.Failed -> stringResource(R.string.update_failed_check)
                }
            }
            StageSettingRow(
                icon = MobileIcons.Info,
                title = stringResource(R.string.diagnostics_app_title),
                subtitle = subtitle,
                onClick = { if (available != null) openLink(context, available.apkUrl) else vm.checkForUpdate() },
            ) {
                if (available != null) {
                    StageRowValue(stringResource(R.string.diagnostics_update_download), StageChevron.FORWARD)
                } else {
                    StageRowValue(stringResource(R.string.settings_check_updates))
                }
            }
        }
        item(key = "device") {
            StageSettingRow(
                icon = MobileIcons.Phone,
                title = stringResource(R.string.diagnostics_device_title),
                subtitle = stringResource(
                    R.string.diagnostics_device_line,
                    deviceModel(),
                    android.os.Build.VERSION.RELEASE.orEmpty(),
                    android.os.Build.VERSION.SDK_INT,
                ),
                onClick = null,
            ) {}
        }

        // ---- Quick fixes ------------------------------------------------------------------------
        item(key = "fixes-head") { StageSectionHead(stringResource(R.string.diagnostics_quick_fixes)) }
        item(key = "clear-cache") {
            StageSettingRow(
                icon = MobileIcons.Image,
                title = stringResource(R.string.diagnostics_clear_image_cache),
                subtitle = stringResource(R.string.diagnostics_clear_image_cache_desc),
                onClick = { scope.launch { vm.clearImageCache(); say(cacheCleared) } },
            ) {}
        }
        // Channel sweep: one row that starts, resumes, stops or re-runs it and shows where it is; then,
        // once it has found channels that never answered, "Hide them" and the list.
        item(key = "sweep") {
            val sweepLine = sweepLine(sweepState)
            val sweepAction = stringResource(
                when (val s = sweepState) {
                    is tv.own.owntv.mobile.health.SweepUi.Running -> R.string.diagnostics_sweep_cancel
                    is tv.own.owntv.mobile.health.SweepUi.Idle -> if (s.resumable) R.string.diagnostics_sweep_resume else R.string.diagnostics_sweep_start
                    is tv.own.owntv.mobile.health.SweepUi.Done -> R.string.diagnostics_sweep_run_again
                    tv.own.owntv.mobile.health.SweepUi.NoChannels -> R.string.diagnostics_sweep_start
                },
            )
            StageSettingRow(
                icon = MobileIcons.LiveTv,
                title = stringResource(R.string.diagnostics_sweep_title),
                subtitle = sweepLine ?: stringResource(R.string.diagnostics_sweep_desc),
                onClick = { if (sweepState is tv.own.owntv.mobile.health.SweepUi.Running) showSweepStop = true else showSweepConfirm = true },
            ) { StageRowValue(sweepAction) }
        }
        if (deadFound != null && sweepDone != null) {
            item(key = "sweep-hide") {
                val hiddenLine = pluralStringResource(R.plurals.diagnostics_sweep_hidden, deadFound.size, deadFound.size)
                val alreadyHidden = sweepDone.hidden > 0
                StageSettingRow(
                    icon = MobileIcons.VisibilityOff,
                    title = stringResource(R.string.diagnostics_sweep_hide),
                    subtitle = if (alreadyHidden) hiddenLine else stringResource(R.string.diagnostics_sweep_hide_desc),
                    onClick = if (alreadyHidden) null else ({ scope.launch { if (vm.hideDeadChannels() > 0) say(hiddenLine) } }),
                ) { if (!alreadyHidden) StageRowValue(stringResource(R.string.diagnostics_sweep_hide)) }
            }
            item(key = "sweep-list") {
                StageSettingRow(
                    icon = MobileIcons.FormatListBulleted,
                    title = stringResource(R.string.diagnostics_sweep_show_list),
                    subtitle = stringResource(R.string.diagnostics_sweep_show_list_desc),
                    onClick = { showDeadList = true },
                ) { StageRowValue(buildString { }, StageChevron.FORWARD) }
            }
        }
        item(key = "app-settings") {
            StageSettingRow(
                icon = MobileIcons.Settings,
                title = stringResource(R.string.diagnostics_open_app_settings),
                subtitle = stringResource(R.string.diagnostics_open_app_settings_desc),
                onClick = { vm.openAppSettings() },
            ) { StageRowValue(buildString { }, StageChevron.FORWARD) }
        }
        item(key = "restart") {
            StageSettingRow(
                icon = MobileIcons.Restore,
                title = stringResource(R.string.diagnostics_restart_app),
                subtitle = stringResource(R.string.diagnostics_restart_app_desc),
                onClick = { context.findActivity()?.let { AppIconSwitcher.restartWith(it, AppIconSwitcher.applied(it)) } },
            ) {}
        }

        // ---- Report -----------------------------------------------------------------------------
        item(key = "report-head") { StageSectionHead(stringResource(R.string.diagnostics_report_heading)) }
        item(key = "show-report") {
            StageSettingRow(
                icon = MobileIcons.FormatListBulleted,
                title = stringResource(R.string.diagnostics_show_report),
                subtitle = lastChecked ?: reportNone,
                onClick = { if (reportText != null) showReport = true else say(reportNone) },
            ) { if (reportText != null) StageRowValue(buildString { }, StageChevron.FORWARD) }
        }
        item(key = "copy-report") {
            StageSettingRow(
                icon = MobileIcons.Layers,
                title = stringResource(R.string.diagnostics_copy_report),
                subtitle = stringResource(R.string.diagnostics_copy_report_desc),
                onClick = { copyReport() },
            ) {}
        }
        item(key = "share-report") {
            StageSettingRow(
                icon = MobileIcons.Share,
                title = stringResource(R.string.diagnostics_share_report),
                subtitle = stringResource(R.string.diagnostics_share_report_desc),
                onClick = { shareReport() },
            ) {}
        }
    }

    if (showReport && reportText != null) {
        MobileBottomSheet(onDismissRequest = { showReport = false }, title = reportTitle) {
            StageSheetHeader(reportTitle, subtitle = lastChecked)
            Column(
                Modifier
                    .padding(horizontal = MobileDimens.ScreenPaddingH)
                    .heightIn(max = REPORT_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(reportText, style = stageText(12f, 500), color = LocalStagePalette.current.text)
            }
            StageMenuItem(stringResource(R.string.diagnostics_copy_report), onClick = { copyReport() }, icon = MobileIcons.Layers)
            StageMenuItem(stringResource(R.string.common_share), onClick = { shareReport() }, icon = MobileIcons.Share)
            StageMenuItem(stringResource(R.string.common_done), onClick = { showReport = false }, icon = MobileIcons.Check)
        }
    }
    if (showSweepConfirm) {
        val confirmHeading = stringResource(R.string.diagnostics_sweep_confirm_title)
        val resumable = (sweepState as? tv.own.owntv.mobile.health.SweepUi.Idle)?.resumable == true
        MobileBottomSheet(onDismissRequest = { showSweepConfirm = false }, title = confirmHeading) {
            StageSheetHeader(confirmHeading, subtitle = stringResource(R.string.diagnostics_sweep_confirm_body))
            StageMenuItem(
                stringResource(if (resumable) R.string.diagnostics_sweep_resume else R.string.diagnostics_sweep_start),
                onClick = { showSweepConfirm = false; vm.startSweep() },
                icon = MobileIcons.LiveTv,
            )
            StageMenuItem(stringResource(R.string.diagnostics_sweep_cancel), onClick = { showSweepConfirm = false }, icon = MobileIcons.Close)
        }
    }
    if (showSweepStop) {
        val stopHeading = stringResource(R.string.diagnostics_sweep_stop_title)
        MobileBottomSheet(onDismissRequest = { showSweepStop = false }, title = stopHeading) {
            StageSheetHeader(stopHeading, subtitle = stringResource(R.string.diagnostics_sweep_stop_body))
            StageMenuItem(stringResource(R.string.diagnostics_sweep_keep), onClick = { showSweepStop = false; vm.stopSweep() }, icon = MobileIcons.Check)
            StageMenuItem(stringResource(R.string.diagnostics_sweep_discard), onClick = { showSweepStop = false; vm.discardSweep() }, icon = MobileIcons.Delete, danger = true)
            StageMenuItem(stringResource(R.string.diagnostics_sweep_back), onClick = { showSweepStop = false }, icon = MobileIcons.Close)
        }
    }
    if (showDeadList && deadFound != null) {
        val listHeading = pluralStringResource(R.plurals.diagnostics_sweep_dead, deadFound.size, deadFound.size)
        MobileBottomSheet(onDismissRequest = { showDeadList = false }, title = listHeading) {
            StageSheetHeader(listHeading, subtitle = stringResource(R.string.diagnostics_sweep_show_list_desc))
            Column(
                Modifier
                    .padding(horizontal = MobileDimens.ScreenPaddingH)
                    .heightIn(max = REPORT_MAX_HEIGHT_DP.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                deadFound.forEach { Text(it.name, style = stageText(12.5f, 500), color = LocalStagePalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            StageMenuItem(stringResource(R.string.common_done), onClick = { showDeadList = false }, icon = MobileIcons.Check)
        }
    }
}

/** The Channel sweep row's second line for the state it is in; null = the row's own description. */
@Composable
private fun sweepLine(state: tv.own.owntv.mobile.health.SweepUi): String? {
    val sep = stringResource(R.string.content_epg_bits_separator)
    val numbers = NumberFormat.getIntegerInstance()
    @Composable
    fun progress(checked: Int, total: Int, dead: Int): String =
        stringResource(R.string.diagnostics_sweep_progress, numbers.format(checked), numbers.format(total)) + sep +
            pluralStringResource(R.plurals.diagnostics_sweep_dead, dead, dead)
    return when (state) {
        is tv.own.owntv.mobile.health.SweepUi.Running ->
            progress(state.checked, state.total, state.dead) + if (state.retrying) sep + stringResource(R.string.diagnostics_sweep_retrying) else ""
        is tv.own.owntv.mobile.health.SweepUi.Idle ->
            if (state.resumable) stringResource(R.string.diagnostics_sweep_paused, progress(state.checked, state.total, state.dead)) else null
        is tv.own.owntv.mobile.health.SweepUi.Done ->
            if (state.dead.isEmpty()) stringResource(R.string.diagnostics_sweep_none_dead, numbers.format(state.total))
            else pluralStringResource(R.plurals.diagnostics_sweep_dead, state.dead.size, state.dead.size) +
                if (state.hidden > 0) sep + pluralStringResource(R.plurals.diagnostics_sweep_hidden, state.hidden, state.hidden) else ""
        tv.own.owntv.mobile.health.SweepUi.NoChannels -> stringResource(R.string.diagnostics_sweep_no_channels)
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
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String?, status: DiagnosticStatus) {
    val palette = LocalStagePalette.current
    val (label, color) = when (status) {
        DiagnosticStatus.OK -> stringResource(R.string.diagnostics_status_ok) to palette.ok
        DiagnosticStatus.WARNING -> stringResource(R.string.diagnostics_status_warning) to palette.warn
        DiagnosticStatus.PROBLEM -> stringResource(R.string.diagnostics_status_problem) to palette.danger
    }
    StageSettingRow(icon = icon, title = title, subtitle = desc, onClick = null) {
        Text(label, style = stageText(13f, 700), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }
}

/** Hands the text to the system share sheet; false only when even the system's chooser could not start. */
private fun shareText(context: Context, subject: String, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND)
        .setType(SHARE_MIME)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    return runCatching {
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
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
 * speed-test endpoint for an exact byte count, so the loop below simply reads until the stream ends;
 * no Content-Length trust or manual cut-off is needed. Download-only, as the TV app's own test is.
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
private const val REPORT_MAX_HEIGHT_DP = 360

/** One decimal place, the device's own locale — the same formatter Downloads' own storage line uses. */
private fun decimal(value: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)
