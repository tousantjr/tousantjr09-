package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.core.live.LiveArchiveUrls
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.LiveViewModel

/**
 * Live TV's view models, and the tuner they share.
 *
 * The tuner is a `single` on purpose: it is what is playing, and what is playing outlives the screen
 * that started it — Live TV's preview, the full screen player and the mini player all read this one
 * object. The two guide helpers are plain core classes with no module of their own, so they are
 * assembled here rather than reached for by type.
 */
val liveModule = module {
    single { LiveEpgReader(get(), get(), get(), get(), get()) }
    single { LiveArchiveUrls(get(), get(), get(), get()) }
    single {
        LiveTuner(
            context = androidContext(),
            channelDao = get(),
            categoryDao = get(),
            customCategoryDao = get(),
            historyDao = get(),
            profileDao = get(),
            favoriteDao = get(),
            sourceDao = get(),
            settings = get(),
            customize = get(),
            streamUrlResolver = get(),
            epgReader = get(),
            archiveUrls = get(),
            session = get(),
            dataSaver = get(),
            audioOnlyStore = get(),
            userDataWriter = get(),
            cast = get(),
            // Live's second engine, its own instance for the whole session. See PlayerModule
            // for why it is not one of Multiview's pooled tiles.
            exo = get(),
            forceMpvStore = get(),
            player = get(),
            watchSession = get(),
            downloadWatchGuard = get(),
        )
    }
    viewModelOf(::LiveViewModel)
    // Channel reliability: what this phone has learnt about each channel (ChannelHealthStore), the
    // engine watcher (ChannelHealthRecorder — an account refusal is kept out through the same check the
    // player's connection-limit panel makes) and the monitor the Application starts, which feeds the
    // recorder from the tuner without the tuner knowing.
    single { tv.own.owntv.mobile.health.ChannelHealthStore(androidContext()) }
    single {
        val notices = get<tv.own.owntv.mobile.ui.player.ConnectionLimitNotices>()
        tv.own.owntv.mobile.health.ChannelHealthRecorder(get()) { channel, failure ->
            runCatching { notices.explain(channel, null, failure) }.getOrNull() != null
        }
    }
    single { tv.own.owntv.mobile.health.ChannelHealthMonitor(tuner = get(), cast = get(), recorder = get()) }
}
