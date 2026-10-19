package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.mobile.ui.screens.sports.SportsPlayback
import tv.own.owntv.mobile.ui.screens.sports.SportsSettingsViewModel
import tv.own.owntv.mobile.ui.screens.sports.SportsUpcomingViewModel
import tv.own.owntv.mobile.ui.screens.sports.SportsViewModel
import tv.own.owntv.mobile.ui.screens.sports.boxing.BoxingSource
import tv.own.owntv.mobile.ui.screens.sports.boxing.BoxingUpcomingViewModel

/** Sports Hub: the screens' view-models and what the player knows about a match. Lazy; nothing runs at launch. */
val sportsModule = module {
    singleOf(::SportsPlayback)
    viewModel { SportsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SportsSettingsViewModel(get(), get(), get(), get(), get(), get()) }
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
