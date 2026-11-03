package tv.own.owntv.mobile.ui.screens.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.network.SmbEntry
import tv.own.owntv.mobile.network.SmbRules
import tv.own.owntv.mobile.network.SmbShare
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.SheetScroll
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageField
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * MPTV: network folders (SMB / NAS) in a sheet — the saved folders, a form to add one, and a simple
 * browser that plays a video straight from the NAS.
 */
@Composable
fun NetworkFoldersSheet(
    onDismiss: () -> Unit,
    onPlayerOpened: () -> Unit,
    vm: NetworkFoldersViewModel = koinViewModel(),
) {
    val shares by vm.shares.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SmbShare?>(null) }
    val b = browse
    val title = stringResource(R.string.network_folders_title)

    LaunchedEffect(playing) {
        if (playing) {
            vm.playerOpened()
            vm.close()
            onDismiss()
            onPlayerOpened()
        }
    }

    MobileBottomSheet(onDismissRequest = { vm.close(); onDismiss() }, title = title) {
        StageSheetHeader(
            title = title,
            subtitle = when {
                adding -> stringResource(if (editing != null) R.string.network_folders_edit else R.string.network_folders_add)
                b != null -> listOf(b.share.name).plus(SmbRules.join(b.path).split('/').filter { it.isNotBlank() }).joinToString(" › ")
                else -> stringResource(R.string.network_folders_subtitle)
            },
        )
        when {
            adding -> ShareForm(
                initial = editing, saving = saving, failed = saveFailed,
                onSave = { name, server, share, user, pass ->
                    vm.save(editing?.id, name, server, share, user, pass) { adding = false; editing = null }
                },
                onCancel = { adding = false; editing = null; vm.clearSaveError() },
            )
            b != null -> Browser(b, onUp = vm::up, onFolder = vm::openFolder, onPlay = vm::play, onRetry = vm::retry)
            else -> ShareList(
                shares,
                onAdd = { editing = null; adding = true },
                onOpen = vm::open,
                onEdit = { editing = it; adding = true },
                onRemove = vm::remove,
            )
        }
    }
}

@Composable
private fun ShareList(
    shares: List<SmbShare>,
    onAdd: () -> Unit,
    onOpen: (SmbShare) -> Unit,
    onEdit: (SmbShare) -> Unit,
    onRemove: (SmbShare) -> Unit,
) {
    val palette = LocalStagePalette.current
    SheetScroll {
        StageMenuItem(stringResource(R.string.network_folders_add), onClick = onAdd, icon = MobileIcons.Add)
        if (shares.isEmpty()) {
            Text(
                stringResource(R.string.network_folders_empty), style = stageText(12.5f, 500), color = palette.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        shares.forEach { share ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageMenuItem(
                    share.name, onClick = { onOpen(share) }, icon = MobileIcons.Storage,
                    value = SmbRules.join(share.host, share.share, share.startPath),
                    modifier = Modifier.weight(1f),
                )
                StageButton(onClick = { onEdit(share) }, icon = MobileIcons.Edit, small = true)
                StageButton(onClick = { onRemove(share) }, icon = MobileIcons.Delete, small = true)
            }
        }
    }
}

@Composable
private fun Browser(
    b: NetworkBrowse,
    onUp: () -> Unit,
    onFolder: (SmbEntry) -> Unit,
    onPlay: (SmbEntry) -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalStagePalette.current
    val context = LocalContext.current
    SheetScroll {
        StageMenuItem(stringResource(R.string.network_folders_back), onClick = onUp, icon = MobileIcons.ArrowBack)
        when {
            b.loading -> Text(
                stringResource(R.string.network_folders_loading), style = stageText(12.5f, 500), color = palette.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            b.failed -> {
                Text(
                    stringResource(R.string.network_folders_failed), style = stageText(12.5f, 600), color = palette.warn,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                StageMenuItem(stringResource(R.string.network_folders_retry), onClick = onRetry, icon = MobileIcons.Refresh)
            }
            b.entries.isEmpty() -> Text(
                stringResource(R.string.network_folders_no_videos), style = stageText(12.5f, 500), color = palette.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            else -> b.entries.forEach { entry ->
                StageMenuItem(
                    entry.name,
                    onClick = { if (entry.isFolder) onFolder(entry) else onPlay(entry) },
                    icon = when {
                        entry.isFolder -> MobileIcons.Folder
                        SmbRules.isVideo(entry.name) -> MobileIcons.PlayArrow
                        else -> MobileIcons.MusicNote
                    },
                    value = if (entry.isFolder) null else Formatter.formatShortFileSize(context, entry.size),
                )
            }
        }
    }
}

@Composable
private fun ShareForm(
    initial: SmbShare?,
    saving: Boolean,
    failed: Boolean,
    onSave: (name: String, server: String, share: String, user: String, pass: String) -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalStagePalette.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var share by rememberSaveable { mutableStateOf(initial?.let { SmbRules.join(it.share, it.startPath) }.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    val ready = server.isNotBlank() && !saving
    val save = { if (ready) onSave(name, server, share, user, pass) }
    SheetScroll {
        StageField(server, { server = it }, stringResource(R.string.network_folders_server), imeAction = ImeAction.Next)
        StageField(share, { share = it }, stringResource(R.string.network_folders_share), imeAction = ImeAction.Next)
        StageField(user, { user = it }, stringResource(R.string.network_folders_username), imeAction = ImeAction.Next)
        StageField(pass, { pass = it }, stringResource(R.string.network_folders_password), password = true, imeAction = ImeAction.Next)
        StageField(name, { name = it }, stringResource(R.string.network_folders_name), onImeDone = save)
        Text(
            stringResource(
                when {
                    saving -> R.string.network_folders_checking
                    failed -> R.string.network_folders_save_failed
                    else -> R.string.network_folders_form_hint
                },
            ),
            style = stageText(12f, 500), color = if (failed && !saving) palette.warn else palette.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StageButton(
                onClick = save, text = stringResource(R.string.network_folders_save), icon = MobileIcons.Check,
                style = StageButtonStyle.PRIMARY, enabled = ready,
            )
            StageButton(onClick = onCancel, text = stringResource(R.string.common_cancel))
        }
    }
}
