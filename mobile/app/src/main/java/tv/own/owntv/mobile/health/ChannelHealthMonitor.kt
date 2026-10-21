package tv.own.owntv.mobile.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.ui.screens.live.LiveTuner

/**
 * Feeds [ChannelHealthRecorder] from the phone's tuner, for the life of the process: the channel on
 * screen (Live TV's preview pane or the full-screen player) and the engine holding it. A new channel
 * or a swapped engine ends the session and starts the next. A catch-up replay is a recording's
 * stream and a cast is the television's, so neither says anything about the channel and neither is
 * recorded. Started once from the Application; the tuner itself is untouched.
 */
class ChannelHealthMonitor(
    private val tuner: LiveTuner,
    private val cast: CastController,
    private val recorder: ChannelHealthRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(tuner.channel, tuner.activeEngine, tuner.replaying, cast.deviceName) { channel, engine, replaying, device ->
                if (channel == null || replaying || device != null) null else channel to engine
            }
                .distinctUntilChanged { a, b -> a?.first?.id == b?.first?.id && a?.second === b?.second }
                .collectLatest { session -> if (session != null) recorder.session(session.first, session.second) }
        }
    }
}
