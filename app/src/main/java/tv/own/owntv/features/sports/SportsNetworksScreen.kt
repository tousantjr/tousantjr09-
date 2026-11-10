package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** One League Networks line: a network in one country, its copies (HD / FHD / SD) best first. */
data class NetworkEntry(
    val network: SportsNetwork,
    val country: String?,
    val copies: List<ChannelEntity>,
    val pinned: Boolean,
) {
    val key: String get() = "${network.name}|${country.orEmpty()}"
}

/**
 * Sports hub → League Networks: NFL RedZone, NBA TV, Golf Channel, LaLiga TV and the rest, as found in
 * the profile's playlists, with what is on now and next. OK plays the best copy; Hold OK lists every
 * copy, and the one picked is kept for that network (Fix channel). Idea from Nuvio + IPTV (GPL-3.0).
 */
@Composable
fun SportsNetworksScreen(
    vm: SportsViewModel,
    liveVm: LiveViewModel,
    onPlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onBack: () -> Unit,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    androidx.activity.compose.BackHandler(enabled = active) { onBack() }
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<NetworkEntry>?>(null) }
    val guide = remember { mutableStateMapOf<Long, EpgNowNext>() }
    LaunchedEffect(reload) {
        entries = null
        val found = vm.leagueNetworks()
        entries = found
        for (e in found) {
            val ch = e.copies.firstOrNull() ?: continue
            liveVm.nowNextFor(ch)?.let { guide[ch.id] = it }
        }
    }
    var menuFor by remember { mutableStateOf<NetworkEntry?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(entries?.isNotEmpty()) {
        if (entries.isNullOrEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(60)
        runCatching { first.requestFocus() }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = contentStart, end = 60.mpx, top = 39.mpx, bottom = 90.mpx),
        ) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sports_networks_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(9.mpx))
                        Text(stringResource(R.string.sports_networks_subtitle), style = stageText(18, 400), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SportsChip("", false, icon = OwnTVIcon.REFRESH) { reload++ }
                }
                Spacer(Modifier.height(18.mpx))
            }
            val list = entries
            when {
                list == null -> item(key = "loading") {
                    tv.own.owntv.ui.components.OwnTVSpinner(sizeDp = 18, modifier = Modifier.padding(top = 60.mpx))
                }
                list.isEmpty() -> item(key = "empty") {
                    Text(stringResource(R.string.sports_networks_empty), style = stageText(22, 600), color = StageColors.Muted, modifier = Modifier.padding(top = 60.mpx))
                }
                else -> items(list, key = { it.key }) { e ->
                    NetworkRow(
                        e, guide[e.copies.firstOrNull()?.id ?: -1L],
                        onClick = { e.copies.firstOrNull()?.let { onPlayChannel(it, e.copies) } },
                        onHold = { menuFor = e },
                        modifier = if (e === list.first()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
        StageKeyHints(
            listOf(
                stringResource(R.string.common_ok) to stringResource(R.string.sports_watch_full_screen),
                stringResource(R.string.content_key_hold_ok) to stringResource(R.string.sports_networks_pick),
            ),
            Modifier.align(Alignment.BottomEnd).padding(end = 60.mpx, bottom = 27.mpx),
            textSize = 18,
        )
    }

    menuFor?.let { e ->
        NetworkCopiesMenu(
            e,
            onPick = { ch -> menuFor = null; vm.pinNetwork(e.network.pinKey, ch); reload++; onPlayChannel(ch, e.copies) },
            onClear = if (e.pinned) { { menuFor = null; vm.forgetPin(e.network.pinKey); reload++ } } else null,
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun NetworkRow(e: NetworkEntry, guide: EpgNowNext?, onClick: () -> Unit, onHold: () -> Unit, modifier: Modifier) {
    val sep = stringResource(R.string.content_epg_bits_separator)
    StageRow(onClick = onClick, onLongClick = onHold, height = 96.mpx, modifier = modifier.fillMaxWidth(), horizontalPadding = 18.mpx) { _ ->
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                Text(e.network.name, style = stageText(21, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.country?.let { StageTag(it) }
                if (e.pinned) Text("★", style = stageText(17, 700), color = TierPick, maxLines = 1)
            }
            val now = guide?.now
            val next = guide?.next
            val line = listOfNotNull(
                now?.let { stringResource(R.string.sports_networks_now, it.title) },
                next?.let { stringResource(R.string.sports_networks_next, timeText(it.startMs), it.title) },
            ).joinToString(sep).ifBlank { stringResource(R.string.sports_networks_no_guide) }
            Text(line, style = stageText(17, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            pluralStringResource(R.plurals.sports_networks_copies, e.copies.size, e.copies.size),
            style = stageText(17, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Hold OK on a network: every copy, the one picked plays and is kept for the network. */
@Composable
private fun NetworkCopiesMenu(e: NetworkEntry, onPick: (ChannelEntity) -> Unit, onClear: (() -> Unit)?, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(620.mpx).heightIn(max = 760.mpx).trapAllFocusExit().focusGroup()) {
                StageMenuHeader(e.network.name, stringResource(R.string.sports_networks_pick_subtitle))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    e.copies.forEachIndexed { i, ch ->
                        StageMenuItem(
                            text = ch.name,
                            icon = OwnTVIcon.LIVE_TV,
                            checked = i == 0 && e.pinned,
                            onClick = { onPick(ch) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                    if (onClear != null) {
                        StageMenuItem(text = stringResource(R.string.sports_fix_clear), icon = OwnTVIcon.CLOSE, onClick = onClear)
                    }
                }
            }
        }
    }
}
