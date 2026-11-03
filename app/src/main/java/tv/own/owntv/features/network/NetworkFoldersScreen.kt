package tv.own.owntv.features.network

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

private val WarnColor = androidx.compose.ui.graphics.Color(0xFFFFB454)

/**
 * Network folders (SMB / NAS): the saved folders, a form to add one, and a simple file browser that
 * plays a video straight from the NAS. Back goes up a folder, then out of the share, then out of here.
 */
@Composable
fun NetworkFoldersScreen(
    onDismiss: () -> Unit,
    onPlaying: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
) {
    val vm: NetworkFoldersViewModel = koinViewModel()
    val shares by vm.shares.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SmbShare?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val b = browse

    BackHandler {
        when {
            adding -> { adding = false; editing = null; vm.clearSaveError() }
            b != null -> vm.up()
            else -> onDismiss()
        }
    }
    LaunchedEffect(adding, b?.path, b?.share?.id, b?.loading, shares.size) {
        kotlinx.coroutines.delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    Column(modifier.fillMaxSize().padding(start = contentStart + 8.mpx, top = 56.mpx, end = 64.mpx)) {
        Text(
            stringResource(R.string.network_folders_title), style = stageText(46, 800), color = StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val subtitle = when {
            adding -> stringResource(if (editing != null) R.string.network_folders_edit else R.string.network_folders_add)
            b != null -> listOf(b.share.name).plus(SmbRules.join(b.path).split('/').filter { it.isNotBlank() }).joinToString(" › ")
            else -> stringResource(R.string.network_folders_subtitle)
        }
        Text(
            subtitle, style = stageText(20, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.mpx, bottom = 24.mpx),
        )
        Box(Modifier.width(1100.mpx)) {
            when {
                adding -> ShareForm(
                    initial = editing, saving = saving, failed = saveFailed, firstFocus = firstFocus,
                    onSave = { name, server, share, user, pass ->
                        vm.save(editing?.id, name, server, share, user, pass) { adding = false; editing = null }
                    },
                    onCancel = { adding = false; editing = null; vm.clearSaveError() },
                )
                b != null -> Browser(b, firstFocus, onFolder = vm::openFolder, onPlay = { vm.play(it, onPlaying) }, onRetry = vm::retry)
                else -> ShareList(
                    shares, firstFocus,
                    onAdd = { editing = null; adding = true },
                    onOpen = vm::open,
                    onEdit = { editing = it; adding = true },
                    onRemove = vm::remove,
                )
            }
        }
    }
}

@Composable
private fun ShareList(
    shares: List<SmbShare>,
    firstFocus: FocusRequester,
    onAdd: () -> Unit,
    onOpen: (SmbShare) -> Unit,
    onEdit: (SmbShare) -> Unit,
    onRemove: (SmbShare) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.mpx), contentPadding = PaddingValues(bottom = 60.mpx)) {
        item {
            StageButton(
                stringResource(R.string.network_folders_add), onClick = onAdd, icon = OwnTVIcon.ADD, tinted = true,
                height = 56.mpx, textSize = 19, modifier = Modifier.focusRequester(firstFocus).padding(bottom = 12.mpx),
            )
        }
        if (shares.isEmpty()) item {
            Text(stringResource(R.string.network_folders_empty), style = stageText(19, 500), color = StageColors.Muted)
        }
        itemsIndexed(shares, key = { _, s -> s.id }) { _, share ->
            Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    StageMenuItem(
                        text = share.name, icon = OwnTVIcon.DNS, onClick = { onOpen(share) },
                        value = SmbRules.join(share.host, share.share, share.startPath),
                    )
                }
                StageButton(null, onClick = { onEdit(share) }, icon = OwnTVIcon.PENCIL, round = true, height = 50.mpx, textSize = 19)
                StageButton(null, onClick = { onRemove(share) }, icon = OwnTVIcon.TRASH, round = true, height = 50.mpx, textSize = 19)
            }
        }
    }
}

@Composable
private fun Browser(
    b: NetworkBrowse,
    firstFocus: FocusRequester,
    onFolder: (SmbEntry) -> Unit,
    onPlay: (SmbEntry) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    when {
        b.loading -> Text(stringResource(R.string.network_folders_loading), style = stageText(20, 500), color = StageColors.Muted)
        b.failed -> Column(verticalArrangement = Arrangement.spacedBy(16.mpx)) {
            Text(stringResource(R.string.network_folders_failed), style = stageText(20, 600), color = WarnColor)
            StageButton(
                stringResource(R.string.network_folders_retry), onClick = onRetry, icon = OwnTVIcon.REFRESH,
                height = 56.mpx, textSize = 19, modifier = Modifier.focusRequester(firstFocus),
            )
        }
        b.entries.isEmpty() -> Text(stringResource(R.string.network_folders_no_videos), style = stageText(20, 500), color = StageColors.Muted)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.mpx), contentPadding = PaddingValues(bottom = 60.mpx)) {
            itemsIndexed(b.entries, key = { _, e -> e.path }) { i, entry ->
                StageMenuItem(
                    text = entry.name,
                    icon = when {
                        entry.isFolder -> OwnTVIcon.FOLDER
                        SmbRules.isVideo(entry.name) -> OwnTVIcon.PLAY
                        else -> OwnTVIcon.AUDIO
                    },
                    value = if (entry.isFolder) null else Formatter.formatShortFileSize(context, entry.size),
                    onClick = { if (entry.isFolder) onFolder(entry) else onPlay(entry) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
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
    firstFocus: FocusRequester,
    onSave: (name: String, server: String, share: String, user: String, pass: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var share by rememberSaveable { mutableStateOf(initial?.let { SmbRules.join(it.share, it.startPath) }.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    val ready = server.isNotBlank() && !saving
    val save = { if (ready) onSave(name, server, share, user, pass) }
    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
        StageFieldRow(OwnTVIcon.DNS, stringResource(R.string.network_folders_server), server, { server = it }, modifier = Modifier.focusRequester(firstFocus))
        StageFieldRow(OwnTVIcon.FOLDER, stringResource(R.string.network_folders_share), share, { share = it })
        StageFieldRow(OwnTVIcon.PERSON, stringResource(R.string.network_folders_username), user, { user = it })
        StageFieldRow(OwnTVIcon.PENCIL, stringResource(R.string.network_folders_password), pass, { pass = it }, password = true)
        StageFieldRow(OwnTVIcon.STAR, stringResource(R.string.network_folders_name), name, { name = it }, onDone = save)
        Text(
            stringResource(
                when {
                    saving -> R.string.network_folders_checking
                    failed -> R.string.network_folders_save_failed
                    else -> R.string.network_folders_form_hint
                },
            ),
            style = stageText(17, 500), color = if (failed && !saving) WarnColor else StageColors.Muted,
            modifier = Modifier.padding(top = 10.mpx),
        )
        Row(Modifier.padding(top = 14.mpx), horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            StageButton(stringResource(R.string.network_folders_save), onClick = save, icon = OwnTVIcon.CHECK, tinted = true, height = 56.mpx, textSize = 19)
            StageButton(stringResource(R.string.common_cancel), onClick = onCancel, height = 56.mpx, textSize = 19)
        }
    }
}
