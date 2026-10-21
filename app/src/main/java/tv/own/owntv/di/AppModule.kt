package tv.own.owntv.di

import tv.own.owntv.features.sports.SportsPlayback
import tv.own.owntv.features.sports.SportsSettingsViewModel
import tv.own.owntv.features.sports.SportsUpcomingViewModel
import tv.own.owntv.features.sports.SportsViewModel
import tv.own.owntv.features.sports.boxing.BoxingSource
import tv.own.owntv.features.sports.boxing.BoxingUpcomingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import tv.own.owntv.features.more.MoreCountsViewModel
import tv.own.owntv.features.shell.RailCountsViewModel
import org.koin.dsl.module
import tv.own.owntv.features.customize.CustomizeItemsViewModel
import tv.own.owntv.features.customize.CustomizeViewModel
import tv.own.owntv.features.downloads.DownloadsViewModel
import tv.own.owntv.features.epg.EpgViewModel
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.home.HomeFeedReader
import tv.own.owntv.core.live.GuideReader
import tv.own.owntv.core.live.LiveEpgReader
import tv.own.owntv.features.home.HomeContentFilterSettings
import tv.own.owntv.features.home.HomeViewModel
import org.koin.android.ext.koin.androidContext
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.mdblist.MdbListSettingsViewModel
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.profiles.ProfileGateSessionViewModel
import tv.own.owntv.features.profiles.ProfilesViewModel
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.features.settings.LocalSyncViewModel
import tv.own.owntv.features.settings.BackupViewModel
import tv.own.owntv.features.settings.DeleteSubtitlesViewModel
import tv.own.owntv.features.settings.DiagnosticsViewModel
import tv.own.owntv.features.settings.EpgSourcesViewModel
import tv.own.owntv.features.settings.HomeSettingsViewModel
import tv.own.owntv.features.settings.LanguageSettingsViewModel
import tv.own.owntv.features.settings.OpenSubtitlesViewModel
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.setup.DisplaySizeViewModel
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.features.shell.ShellViewModel
import tv.own.owntv.features.subtitles.SubtitleSearchViewModel

/**
 * Root Koin module. Each feature will contribute its own bindings as the app grows;
 * for now this wires settings persistence and the shell view model.
 *
 * ViewModels are bound with `viewModelOf(::X)` constructor references, never positional `get()`
 * lists: with 15-23 parameters, a positional list only has to *count* right, so inserting or
 * reordering two same-typed parameters would silently swap two dependencies at runtime with no
 * compile error and no Koin error. A constructor reference binds by the declared constructor, so
 * reordering is safe and a missing binding fails immediately, naming the type.
 */
val appModule = module {
    viewModelOf(::ShellViewModel)
    // Home's rails are core's, shared with the phone app; the view model only decorates them.
    // LiveEpgReader is registered because GuideReader now needs one: a guide row whose stored data
    // has run out falls back to the provider's short-EPG, which is this reader's to fetch. One
    // instance, so the grid, the Live rows and the preview pane share its cache instead of each
    // asking the provider the same question.
    singleOf(::LiveEpgReader)
    singleOf(::GuideReader)
    singleOf(::SearchReader)
    singleOf(::HomeFeedReader)
    // Home's "Hide adult categories" toggle (Settings → Layout → Home): app-level, not per-profile,
    // and additive to :core's own kids-profile category hiding — see HomeContentFilterSettings.
    single { HomeContentFilterSettings(androidContext()) }
    // Home's "Play trailers on Home" toggle (Settings → Layout → Home): app-level, its own DataStore for
    // the same reason — see HomeTrailerSettings.
    single { tv.own.owntv.features.home.HomeTrailerSettings(androidContext()) }
    // Settings → Layout → Home → MDBList: the feature's own DataStore (MdbListSettings — app-level, and
    // where the user's API key lives), its client riding the app's shared OkHttp client, and the cache +
    // matcher that turn a public list into a Home rail (MdbListRepository, see its header). Named
    // arguments: the repository takes nine dependencies, two of them DAOs of the same shape.
    single { tv.own.owntv.features.mdblist.MdbListSettings(androidContext()) }
    single { tv.own.owntv.features.mdblist.MdbListApi(get()) }
    single {
        tv.own.owntv.features.mdblist.MdbListRepository(
            context = androidContext(),
            settings = get(),
            api = get(),
            appSettings = get(),
            sourceDao = get(),
            movieDao = get(),
            seriesDao = get(),
            profileDao = get(),
            categoryDao = get(),
        )
    }
    viewModelOf(::MdbListSettingsViewModel)
    // Home's subscription notice: asks each Xtream playlist for its expiry and caches the answer for
    // hours, so one instance for the process — see SubscriptionExpiry.
    single { tv.own.owntv.features.home.SubscriptionExpiry(get()) }
    // The player's connection-limit line under a failed stream: asks the Xtream account for its
    // active/max connections after a failure that could be a refusal — see ConnectionLimitNotices.
    single { tv.own.owntv.player.ConnectionLimitNotices(get(), get()) }
    // Sports Hub: which match the playing channel came from. Lazy; nothing runs until it is asked.
    singleOf(::SportsPlayback)
    // Process-long scope for work that must outlive the screen that started it (a first-run restore).
    single<kotlinx.coroutines.CoroutineScope> {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    }
    viewModelOf(::HomeViewModel)
    viewModelOf(::SetupViewModel)
    viewModelOf(::DisplaySizeViewModel)
    // Takes a Context first; Koin resolves it from androidContext().
    //
    // Spelled out rather than `viewModelOf(::LiveViewModel)`: that reflective helper is generated for
    // up to 22 constructor parameters and this class now has 24. The failure is a "none of the
    // following candidates is applicable" at the call above, which says nothing about arity — hence
    // this note. Every argument is resolved by type, so the order here does not matter.
    viewModel {
        LiveViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
    viewModelOf(::MovieViewModel)
    viewModelOf(::SportsViewModel)
    viewModelOf(::SportsSettingsViewModel)
    // Sports hub → Upcoming: its own small view model, outside core's Demand/Feed system (see
    // SportsUpcomingViewModel) — the already-bound SportsSource (EspnSportsSource) is reused as is.
    viewModelOf(::SportsUpcomingViewModel)
    // Sports hub → Boxing: core's Sport enum has no BOXING case, so this does not reuse SportsSource/
    // SportsRepository at all — BoxingSource is its own tiny OkHttp client riding the app's shared one
    // (see BoxingSource/BoxingUpcomingViewModel for the full reasoning).
    singleOf(::BoxingSource)
    viewModelOf(::BoxingUpcomingViewModel)
    // Settings → Sports → Betting odds: the display's own DataStore (OddsSettings — app-level, and
    // where the user's API key lives), the two read-only sources riding the app's shared OkHttp client
    // like BoxingSource, and the cache that keeps The Odds API's free quota intact (OddsRepository).
    single { tv.own.owntv.features.sports.odds.OddsSettings(androidContext()) }
    single { tv.own.owntv.features.sports.odds.EspnOddsSource(get()) }
    single { tv.own.owntv.features.sports.odds.TheOddsApiSource(get()) }
    single { tv.own.owntv.features.sports.odds.OddsRepository(get(), get(), get()) }
    viewModelOf(::SeriesViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::ProfilesViewModel)
    // Activity-scoped session state for the profile gate (configuration-only retention, no saved
    // state — see ProfileGateSessionViewModel).
    viewModelOf(::ProfileGateSessionViewModel)
    // 24 constructor parameters — past the highest arity Koin's `*Of` DSL generates, so this one
    // binding stays explicit. Named arguments give it the same guarantee viewModelOf gives the rest:
    // reordering the constructor is safe, and adding a parameter is a compile error here, not a
    // silently mis-wired dependency at runtime.
    viewModel {
        SettingsViewModel(
            profileDao = get(),
            sourceDao = get(),
            sourceRepository = get(),
            settings = get(),
            importFinalizer = get(),
            channelDao = get(),
            categoryDao = get(),
            customizationStore = get(),
            navVisibility = get(),
            userDataWriter = get(),
            epgRepository = get(),
            epgSourceStore = get(),
            launcherIntegrationRepository = get(),
            catalogSyncScheduler = get(),
            okHttpClient = get(),
            metadataProvider = get(),
            metadataRepository = get(),
            metadataBudget = get(),
            stalkerAuth = get(),
            stalkerClient = get(),
            xtreamClient = get(),
            sourceTester = get(),
            companion = get(),
            vodEngineStore = get(),
            forceMpvStore = get(),
            archiveDecodeStore = get(),
            playbackPrefs = get(),
            connectionLimits = get(),
            player = get(),
            livePreview = get(),
            enginePool = get(),
            importer = get(),
        )
    }
    viewModelOf(::LocalSyncViewModel)
    viewModelOf(::HomeSettingsViewModel)
    viewModelOf(::LanguageSettingsViewModel)
    viewModelOf(::OpenSubtitlesViewModel)
    viewModelOf(::DeleteSubtitlesViewModel)
    viewModelOf(::SubtitleSearchViewModel)
    viewModelOf(::DownloadsViewModel)
    viewModelOf(::EpgViewModel)
    viewModelOf(::CustomizeViewModel)
    viewModelOf(::CustomizeItemsViewModel)
    viewModelOf(::BackupViewModel)
    viewModelOf(::EpgSourcesViewModel)
    viewModelOf(::DiagnosticsViewModel)
    // The counts the More rows and the Favourites / History tabs carry.
    viewModelOf(::MoreCountsViewModel)
    // The counts beside the sections in the open Stage rail.
    viewModelOf(::RailCountsViewModel)
}
