package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.setup.headline
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.localizedDecimal
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageText

/** This screen's rows as Settings search finds them. */
internal val DIAGNOSTICS_SEARCH_ROWS: List<Int> = listOf(
    R.string.diagnostics_run_checks, R.string.diagnostics_connection_title,
    R.string.diagnostics_streaming_heading, R.string.diagnostics_epg_title,
    R.string.diagnostics_storage_title, R.string.diagnostics_speed_test_title,
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

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: DiagnosticsViewModel = koinViewModel()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()

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

    val explanation = stringResource(R.string.diagnostics_run_checks_help)
    val report = (diagnostics as? DiagnosticsUi.Done)?.report
    val running = diagnostics is DiagnosticsUi.Running
    val rows = 2 + (report?.let { 3 + it.sources.size } ?: 0)

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
            StatusRow(
                icon = OwnTVIcon.NETWORK,
                title = stringResource(R.string.diagnostics_connection_title),
                desc = stringResource(
                    if (report.connection.status == DiagnosticStatus.OK) R.string.diagnostics_connection_ok
                    else R.string.diagnostics_connection_problem,
                ),
                status = report.connection.status,
            )

            if (report.sources.isEmpty()) {
                StatusRow(
                    icon = OwnTVIcon.PLAYLIST,
                    title = stringResource(R.string.diagnostics_streaming_heading),
                    desc = stringResource(R.string.diagnostics_no_sources),
                    status = DiagnosticStatus.WARNING,
                )
            } else {
                report.sources.forEach { source ->
                    StatusRow(
                        icon = OwnTVIcon.PLAYLIST,
                        title = source.sourceName,
                        desc = source.result.headline(androidx.compose.ui.platform.LocalContext.current.resources),
                        status = source.status,
                    )
                }
            }

            StatusRow(
                icon = OwnTVIcon.EPG,
                title = stringResource(R.string.diagnostics_epg_title),
                desc = epgDescription(report.epg),
                status = report.epg.status,
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
    }
}

@Composable
private fun epgDescription(epg: EpgCheck): String = when {
    !epg.configured -> stringResource(R.string.diagnostics_epg_no_source)
    !epg.hasData -> stringResource(R.string.diagnostics_epg_no_data)
    epg.freshestSyncAt == null -> stringResource(R.string.diagnostics_epg_unknown_age)
    else -> {
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
            epg.freshestSyncAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )
        stringResource(
            when (epg.status) {
                DiagnosticStatus.OK -> R.string.diagnostics_epg_fresh
                DiagnosticStatus.WARNING -> R.string.diagnostics_epg_stale
                DiagnosticStatus.PROBLEM -> R.string.diagnostics_epg_very_stale
            },
            ago,
        )
    }
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
