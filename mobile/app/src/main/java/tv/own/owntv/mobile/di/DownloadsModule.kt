package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.mobile.network.SmbClientPool
import tv.own.owntv.mobile.network.SmbShareStore
import tv.own.owntv.mobile.network.SmbStreamServer
import tv.own.owntv.mobile.ui.screens.downloads.DownloadsViewModel
import tv.own.owntv.mobile.ui.screens.downloads.NetworkFoldersViewModel

/**
 * The Downloads screen. Its queue is core's and already registered there — this is only the view
 * model. It needs the context for the volume list and the system save dialog.
 */
val downloadsModule = module {
    viewModel {
        DownloadsViewModel(
            context = androidContext(),
            downloadDao = get(),
            movieDao = get(),
            seriesDao = get(),
            categoryDao = get(),
            profileDao = get(),
            customize = get(),
            settings = get(),
            downloadManager = get(),
            vodTuner = get(),
            tracker = get(),
        )
    }

    // MPTV: network folders (SMB / NAS). One client and one local stream server for the whole app.
    single { SmbShareStore(androidContext()) }
    single { SmbClientPool(get()) }
    single { SmbStreamServer(get(), get()) }
    viewModel { NetworkFoldersViewModel(store = get(), pool = get(), server = get(), vodTuner = get()) }
}
