package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.mobile.ui.screens.sports.SportsNoticeSettings
import tv.own.owntv.mobile.ui.screens.sports.SportsPlayback
import tv.own.owntv.mobile.ui.screens.sports.SportsSettingsViewModel
import tv.own.owntv.mobile.ui.screens.sports.SportsUpcomingViewModel
import tv.own.owntv.mobile.ui.screens.sports.SportsViewModel
import tv.own.owntv.mobile.ui.screens.sports.boxing.BoxingSource
import tv.own.owntv.mobile.ui.screens.sports.boxing.BoxingUpcomingViewModel
import tv.own.owntv.mobile.ui.screens.sports.odds.EspnOddsSource
import tv.own.owntv.mobile.ui.screens.sports.odds.OddsRepository
import tv.own.owntv.mobile.ui.screens.sports.odds.OddsSettings
import tv.own.owntv.mobile.ui.screens.sports.odds.TheOddsApiSource

/** Sports Hub: the screens' view-models and what the player knows about a match. Lazy; nothing runs at launch. */
val sportsModule = module {
    singleOf(::SportsPlayback)
    // Settings → Sports → Betting odds: the display's own DataStore (OddsSettings — app-level, and
    // where the user's API key lives), the two read-only sources riding the app's shared OkHttp client
    // like BoxingSource, and the cache that keeps The Odds API's free quota intact (OddsRepository).
    single { OddsSettings(androidContext()) }
    singleOf(::EspnOddsSource)
    singleOf(::TheOddsApiSource)
    singleOf(::OddsRepository)
    // Settings → Sports → the game-day heads-up and the score ticker: app-level too (SportsNoticeSettings),
    // core's SportsConfig being compiled; the store also keeps GameDayWorker's schedule in step.
    single { SportsNoticeSettings(androidContext()) }
    // Positional: a constructor parameter added to either view-model goes last, with one more get() here.
    viewModel { SportsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SportsSettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Sports hub → Upcoming: its own small view model, outside core's Demand/Feed system (see
    // SportsUpcomingViewModel) — the already-bound SportsSource (EspnSportsSource) is reused as is.
    viewModel { SportsUpcomingViewModel(get(), get()) }
    // Sports hub → Boxing: core's Sport enum has no BOXING case, so this does not reuse SportsSource/
    // SportsRepository at all — BoxingSource is its own tiny OkHttp client riding the app's shared one
    // (see BoxingSource/BoxingUpcomingViewModel for the full reasoning).
    singleOf(::BoxingSource)
    viewModel {
        BoxingUpcomingViewModel(
            context = androidContext(),
            localeStore = get(),
            settings = get(),
            source = get(),
            sourceDao = get(),
            channelDao = get(),
            reminders = get(),
        )
    }
}
