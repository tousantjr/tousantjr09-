package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.core.home.HomeFeedReader
import tv.own.owntv.mobile.mdblist.MdbListApi
import tv.own.owntv.mobile.mdblist.MdbListRepository
import tv.own.owntv.mobile.mdblist.MdbListSettings
import tv.own.owntv.mobile.mdblist.MdbListSettingsViewModel
import tv.own.owntv.mobile.trakt.TraktApi
import tv.own.owntv.mobile.trakt.TraktRepository
import tv.own.owntv.mobile.trakt.TraktScrobbler
import tv.own.owntv.mobile.trakt.TraktSettings
import tv.own.owntv.mobile.trakt.TraktSettingsViewModel
import tv.own.owntv.mobile.ui.screens.home.HomeContentFilterSettings
import tv.own.owntv.mobile.ui.screens.home.HomeViewModel
import tv.own.owntv.mobile.ui.screens.home.SubscriptionExpiry
import tv.own.owntv.mobile.ui.screens.home.SubscriptionNoticeStore

/**
 * Home's rails come out of core, the same reader the television's Home uses, so the two apps agree
 * item for item on what is on it. [guideModule] provides the guide reader it takes.
 */
val homeModule = module {
    single {
        HomeFeedReader(
            planner = get(),
            movieDao = get(),
            seriesDao = get(),
            channelDao = get(),
            categoryDao = get(),
            customize = get(),
            sourceDao = get(),
            settings = get(),
            profileDao = get(),
            trendingDao = get(),
            guide = get(),
        )
    }
    // Home's "Hide adult categories" toggle (Settings → Layout → Home): app-level, not per-profile,
    // and additive to :core's own kids-profile category hiding — see HomeContentFilterSettings.
    single { HomeContentFilterSettings(androidContext()) }
    // Home's subscription notice (and the daily check behind its notification): asks each Xtream
    // playlist for its expiry and caches the answer for hours, so one instance for the process.
    single { SubscriptionExpiry(get()) }
    single { SubscriptionNoticeStore(androidContext()) }
    // Settings → Layout → Home → MDBList: the feature's own DataStore (MdbListSettings — app-level, and
    // where the user's API key lives), its client riding the app's shared OkHttp client, and the cache +
    // matcher that turn a public list into a Home rail (MdbListRepository, see its header). Named
    // arguments: the repository takes nine dependencies, two of them DAOs of the same shape.
    single { MdbListSettings(androidContext()) }
    single { MdbListApi(get()) }
    single {
        MdbListRepository(
            context = androidContext(),
            settings = get(),
            api = get(),
            appSettings = get(),
            sourceDao = get(),
            movieDao = get(),
            seriesDao = get(),
            profileDao = get(),
            categoryDao = get(),
            database = get(),
        )
    }
    viewModelOf(::MdbListSettingsViewModel)
    // Settings → Layout → Home → Trakt: the feature's own DataStore (TraktSettings — app-level, where the
    // client credentials and tokens live), its client on the shared OkHttp client, the repository (login,
    // scrobbles, the watchlist rail — see its header) and the scrobbler VodTuner reports to. Named
    // arguments for the same reason as MDBList's.
    single { TraktSettings(androidContext()) }
    single { TraktApi(get()) }
    single {
        TraktRepository(
            context = androidContext(),
            settings = get(),
            api = get(),
            appSettings = get(),
            sourceDao = get(),
            movieDao = get(),
            seriesDao = get(),
            profileDao = get(),
            categoryDao = get(),
            customize = get(),
        )
    }
    single { TraktScrobbler(player = get(), repo = get(), metadata = get()) }
    viewModelOf(::TraktSettingsViewModel)
    viewModelOf(::HomeViewModel)
}
