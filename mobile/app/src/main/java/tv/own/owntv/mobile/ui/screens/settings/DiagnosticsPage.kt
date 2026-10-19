package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.setup.headline
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * Settings → App → Network → Diagnostics: the connection/playlist/guide/storage health checks, and a
 * download speed test — mobile's own screen over [DiagnosticsViewModel], which carries the same logic
 * as the TV app's own Diagnostics screen.
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

    val report = (diagnostics as? DiagnosticsUi.Done)?.report
    val running = diagnostics is DiagnosticsUi.Running

    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(stringResource(R.string.diagnostics_run_checks_help)) }
        item(key = "run") {
            val lastChecked = report?.let {
                stringResource(
                    R.string.diagnostics_last_checked,
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        it.finishedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
            }
            StageSettingRow(
                icon = MobileIcons.Refresh,
                title = stringResource(if (running) R.string.diagnostics_running else R.string.diagnostics_run_checks),
                subtitle = lastChecked,
                onClick = { if (!running) vm.runChecks() },
            ) {}
        }

        if (report != null) {
            item(key = "connection") {
                StatusRow(
                    icon = MobileIcons.Translate,
                    title = stringResource(R.string.diagnostics_connection_title),
                    desc = stringResource(
                        if (report.connection.status == DiagnosticStatus.OK) R.string.diagnostics_connection_ok
                        else R.string.diagnostics_connection_problem,
                    ),
                    status = report.connection.status,
                )
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
                    StatusRow(
                        icon = MobileIcons.Layers,
                        title = source.sourceName,
                        desc = source.result.headline(LocalContext.current.resources),
                        status = source.status,
                    )
                }
            }

            item(key = "epg") {
                StatusRow(
                    icon = MobileIcons.Guide,
                    title = stringResource(R.string.diagnostics_epg_title),
                    desc = epgDescription(report.epg),
                    status = report.epg.status,
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

/** One decimal place, the device's own locale — the same formatter Downloads' own storage line uses. */
private fun decimal(value: Double): String = NumberFormat.getNumberInstance().apply {
    minimumFractionDigits = 1
    maximumFractionDigits = 1
}.format(value)
