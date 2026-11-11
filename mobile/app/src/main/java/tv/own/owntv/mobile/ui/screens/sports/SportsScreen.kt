package tv.own.owntv.mobile.ui.screens.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.components.sheetOpen
import tv.own.owntv.mobile.ui.player.VideoStage
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.stage.StageLiveBadge
import tv.own.owntv.mobile.ui.stage.StageRadii
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.sports.GuideSportItem
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.stage.LocalStagePalette
import tv.own.owntv.mobile.ui.stage.StageButton
import tv.own.owntv.mobile.ui.stage.StageButtonStyle
import tv.own.owntv.mobile.ui.stage.StageIcon
import tv.own.owntv.mobile.ui.stage.StageProgress
import tv.own.owntv.mobile.ui.stage.StageRow
import tv.own.owntv.mobile.ui.stage.StageTag
import tv.own.owntv.mobile.ui.stage.StageTagTone
import tv.own.owntv.mobile.ui.stage.StageTool
import tv.own.owntv.mobile.ui.stage.drawInnerRing
import tv.own.owntv.mobile.ui.stage.stageAccent
import tv.own.owntv.mobile.ui.stage.stageText

/**
 * The Sports page: Scores | Calendar | Leagues under the title, then two multi-select chip rows (sports
 * and My teams, then the chosen sports' leagues; none = All) ending in ⟳, shared by the three tabs. With
 * nothing followed, a note and a button into Settings → Sports and leagues. A match opens its page; a
 * team its team page.
 */
@Composable
fun SportsScreen(
    scrollToTop: SharedFlow<String>,
    route: String,
    onOpenMatch: (Match) -> Unit,
    onOpenTeam: (TeamRef) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onChooseSports: () -> Unit,
    onOpenUpcoming: () -> Unit,
    onOpenBoxing: () -> Unit,
    vm: SportsViewModel = koinViewModel(),
    /** The day strip's other days (not today), fetched per followed league — see [SportsUpcomingViewModel.loadDay]. */
    dayVm: SportsUpcomingViewModel = koinViewModel(),
) {
    val config by vm.sportsConfig.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val chips by vm.chips.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val filter = remember(config, chips, leagues) { ChipFilter(config, chips, leagues) }
    // MPTV day strip: 0 = today (core's live board); any other day is fetched on its own. Kept here so
    // the ⟳ in the shared chip row can refetch the day on show.
    val playback = koinInject<SportsPlayback>()
    var dayOffset by remember { mutableStateOf(playback.hubDay) }
    val followedKeys = config.followedLeagues()
    LaunchedEffect(dayOffset, followedKeys) {
        playback.hubDay = dayOffset
        if (dayOffset != 0) dayVm.loadDay(SportsDays.dateOf(dayOffset))
    }
    // One list per tab, each kept while the page is: switching tab returns to where that tab was.
    val lists = SportsTab.entries.map { rememberLazyListState() }
    val listState = lists[tab.ordinal]
    listState.ObeyScrollToTop(route = route, scrollToTop = scrollToTop)
    val frame = SportsFrame(vm, filter, tab, listState, onChooseSports) {
        if (dayOffset != 0) dayVm.loadDay(SportsDays.dateOf(dayOffset), force = true)
    }
    when (tab) {
        SportsTab.SCORES -> ScoresTab(
            vm, frame, onOpenMatch, onOpenTeam, onPlayChannel, onOpenUpcoming, onOpenBoxing,
            dayVm, dayOffset, onDay = { dayOffset = it },
        )
        SportsTab.CALENDAR -> SportsCalendarTab(vm, frame, onOpenMatch, onOpenTeam)
        SportsTab.LEAGUES -> SportsLeaguesTab(vm, frame, onOpenMatch, onOpenTeam)
    }
}

/** What every tab draws around its own content: the shared header and chip rows, over one list. */
internal class SportsFrame(
    val vm: SportsViewModel,
    val filter: ChipFilter,
    val tab: SportsTab,
    val listState: LazyListState,
    val onChooseSports: () -> Unit,
    /** MPTV: the ⟳ also refetches the day strip's other day. */
    val onRefresh: () -> Unit = {},
)

/**
 * A tab's page: the tabs, its [subtitle], the chip rows (none while nothing is followed — the note
 * instead), then [content]. Everything scrolls together, so the tabs and chips go up with the list.
 */
@Composable
internal fun SportsTabPage(
    frame: SportsFrame,
    subtitle: @Composable () -> Unit,
    /** Drawn over the top of the list (the Calendar's folded week). */
    overlay: @Composable BoxScope.() -> Unit = {},
    /** MPTV: items above the chip rows that show even while nothing is followed (Upcoming · Boxing). */
    top: LazyListScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val vm = frame.vm
    val filter = frame.filter
    val config = filter.config
    val followedSports = remember(config) { vm.followedSports(config) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = frame.listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "tabs") { SportsTabs(frame.tab, vm::setTab) }
            item(key = "sub") { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) { subtitle() } }
            top()
            if (config.followedLeagues().isEmpty()) {
                item(key = "none") { NothingFollowedNote(frame.onChooseSports) }
                return@LazyColumn
            }
            item(key = "chips") {
                LazyRow(
                    Modifier.padding(top = 10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    fun toggle(c: HubChip) = vm.setChips(if (c in filter.chips) filter.chips - c else filter.chips + c, vm.leagues.value)
                    item { StageTool(onClick = { vm.setChips(emptySet(), emptySet()) }, label = stringResource(R.string.content_epg_all), on = filter.chips.isEmpty()) }
                    item { StageTool(onClick = { toggle(HubChip.MyTeams) }, label = stringResource(R.string.sports_my_teams), icon = MobileIcons.Star, on = filter.myTeams) }
                    items(followedSports, key = { it.name }) { s ->
                        StageTool(onClick = { toggle(HubChip.One(s)) }, label = "${sportEmoji(s)} ${sportLabel(s)}", on = HubChip.One(s) in filter.chips)
                    }
                    // ⟳: build the whole board again (and mark the saved months out of date); its progress shows in the status pill.
                    item {
                        StageTool(onClick = { vm.rebuild(); frame.onRefresh() }, icon = MobileIcons.Refresh, contentDescription = stringResource(R.string.sports_refresh_data_now))
                    }
                }
            }
            if (filter.sportLeagues.size > 1) {
                item(key = "leagues") {
                    LazyRow(
                        Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        val active = filter.activeLeagues
                        item { StageTool(onClick = { vm.setChips(filter.chips, emptySet()) }, label = stringResource(R.string.content_epg_all), on = active.isEmpty()) }
                        items(filter.sportLeagues, key = { it.key }) { l ->
                            StageTool(onClick = { vm.setChips(filter.chips, if (l.key in active) active - l.key else active + l.key) }, label = leagueLabel(l), on = l.key in active)
                        }
                    }
                }
            }
            content()
        }
        overlay()
    }
}

/**
 * `.pseg`: Scores | Calendar | Leagues, full width under the page title so a thumb reaches each; the open
 * one in the accent with its ring. Calendar and Leagues carry NEW.
 */
@Composable
private fun SportsTabs(open: SportsTab, onOpen: (SportsTab) -> Unit) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(palette.controlFill).padding(3.dp),
    ) {
        SportsTab.entries.forEach { t ->
            val on = t == open
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .then(if (on) Modifier.background(accent.copy(alpha = 0.18f)).drawBehind { drawInnerRing(accent.copy(alpha = 0.7f), 1.5.dp.toPx(), 11.dp.toPx()) } else Modifier)
                        .clickable(role = Role.Tab) { onOpen(t) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = if (on) palette.text else palette.muted
                    StageIcon(
                        when (t) {
                            SportsTab.SCORES -> MobileIcons.FormatListBulleted
                            SportsTab.CALENDAR -> MobileIcons.CalendarMonth
                            SportsTab.LEAGUES -> MobileIcons.Sports
                        },
                        15.dp, fg,
                    )
                    Text(
                        stringResource(
                            when (t) {
                                SportsTab.SCORES -> R.string.sports_tab_scores
                                SportsTab.CALENDAR -> R.string.sports_tab_calendar
                                SportsTab.LEAGUES -> R.string.sports_tab_leagues
                            },
                        ),
                        style = stageText(14f, 700), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A tab's line under the tabs: the [accent] part, then the [rest] joined by dots, then "Scores: ESPN" dim. */
@Composable
internal fun SportsSubtitle(accentPart: String, vararg rest: String) {
    val palette = LocalStagePalette.current
    val accent = stageAccent.accent
    val sep = stringResource(R.string.content_epg_bits_separator)
    val credit = stringResource(R.string.sports_scores_espn)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(accentPart) }
            rest.forEach { append(sep); append(it) }
            append(sep)
            withStyle(SpanStyle(color = palette.dim)) { append(credit) }
        },
        style = stageText(12.5f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The Scores tab: today's counts, *Live now* with the featured first card, *Sport on your channels*
 * from the guide, and *Starting soon*. MPTV adds Upcoming · Boxing, the day strip (other days fetched
 * per followed league) and the long-press live preview.
 */
@Composable
private fun ScoresTab(
    vm: SportsViewModel,
    frame: SportsFrame,
    onOpenMatch: (Match) -> Unit,
    onOpenTeam: (TeamRef) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onOpenUpcoming: () -> Unit,
    onOpenBoxing: () -> Unit,
    dayVm: SportsUpcomingViewModel,
    dayOffset: Int,
    onDay: (Int) -> Unit,
) {
    val state by vm.hub.collectAsStateWithLifecycle()
    val filter = frame.filter
    val config = filter.config
    val now = System.currentTimeMillis()
    val otherDay = dayOffset != 0
    val dayDate = SportsDays.dateOf(dayOffset)
    val dayState by dayVm.day.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Match?>(null) }
    // The sheet's Remind me names a channel when Find channels already found one for that match.
    var menuChannels by remember { mutableStateOf<List<WatchOption>>(emptyList()) }
    LaunchedEffect(menuFor) { menuChannels = menuFor?.let { vm.channelsFound(it) }.orEmpty() }
    // ESPN's tennis and golf feeds list a whole tournament; "today" is what is live or starts today.
    val today = state.board.today.values.flatten().distinctBy { it.key }.filter { it.isLive || isToday(it.startMs) }
    val live = today.filter { !otherDay && it.isLive && filter.passes(it) }
        .sortedWith(compareBy<Match>({ config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }, { !filter.isMine(it) }, { it.startMs }))
    val soon = state.board.matches.filter { !otherDay && it.state == MatchState.PRE && it.startMs > now && filter.passes(it) }
        .sortedWith(compareBy<Match>({ config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }, { it.startMs })).take(12)
    val onNow = if (otherDay) emptyList() else state.onNow.values.flatten().take(6)
    val dayMatches = if (otherDay && dayState.date == dayDate) dayState.matches.filter(filter::passes) else emptyList()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val context = LocalContext.current

    // Live preview on Sports tab (Settings → Sports): a long press on a game that is on (or about to
    // start) also plays its first Watch live channel above the list. Never over a stream the user is
    // watching, not with Live TV's preview off, not while spoiler-free is on. A tap still opens the match.
    val tuner = koinInject<LiveTuner>()
    val cast = koinInject<tv.own.owntv.mobile.cast.CastController>()
    val notices by koinInject<SportsNoticeSettings>().config.collectAsStateWithLifecycle(SportsNoticeConfig())
    val livePreviewOn by koinInject<SettingsRepository>().livePreviewEnabled.collectAsStateWithLifecycle(true)
    val previewOn = notices.livePreview && livePreviewOn && !config.spoilerFree
    var previewFor by remember { mutableStateOf<Match?>(null) }
    var previewChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    val scope = rememberCoroutineScope()
    fun stopPreview() {
        if (previewChannel != null && tuner.previewing.value) tuner.stop()
        previewFor = null
        previewChannel = null
    }
    fun startPreview(m: Match) {
        if (!previewOn || previewFor?.key == m.key) return
        // Casting, the tuner's preview would put the channel on the television: a glance is the phone's only.
        if (tuner.channel.value != null && !tuner.previewing.value || cast.deviceName.value != null) return
        previewFor = m
        scope.launch {
            val channel = vm.previewChannel(m)
            if (previewFor?.key != m.key) return@launch
            if (channel == null) { stopPreview(); return@launch }
            previewChannel = channel
            tuner.preview(channel.id, muted = !notices.livePreviewSound)
        }
    }
    LaunchedEffect(previewOn) { if (!previewOn) stopPreview() }
    // Stopped elsewhere (leaving the app ends a preview): the box goes with it.
    val tuned by tuner.channel.collectAsStateWithLifecycle()
    LaunchedEffect(tuned?.id) {
        if (previewChannel != null && tuned?.id != previewChannel?.id) { previewFor = null; previewChannel = null }
    }
    val streamOnScreen = LocalStreamOnScreen.current
    DisposableEffect(previewChannel != null) {
        streamOnScreen.value = previewChannel != null
        onDispose { streamOnScreen.value = false }
    }
    DisposableEffect(Unit) { onDispose { if (previewChannel != null && tuner.previewing.value) tuner.stop() } }

    Column(Modifier.fillMaxSize()) {
    val shownPreview = previewChannel
    val shownFor = previewFor
    if (shownPreview != null && shownFor != null) {
        SportsLivePreview(tuner, shownPreview, onOpen = { onOpenMatch(shownFor) }, onClose = { stopPreview() })
    }
    Box(Modifier.fillMaxWidth().weight(1f)) {
    SportsTabPage(
        frame,
        subtitle = {
            Column {
                HubSubtitle(if (otherDay) dayMatches else today.filter(filter::passes), hubDayLabel(dayOffset, locale))
                if (state.board.paused || (otherDay && dayState.paused)) {
                    Text(stringResource(R.string.sports_paused), style = stageText(12.5f, 600), color = LocalStagePalette.current.warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        // Beyond today/tomorrow: the next 7 days per followed league; Boxing is its own schedule (core's
        // Sport enum has no BOXING). Shown regardless of what is followed.
        top = {
            item(key = "upcoming") {
                Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StageTool(onClick = onOpenUpcoming, label = stringResource(R.string.sports_upcoming_title), icon = MobileIcons.CalendarMonth)
                    StageTool(onClick = onOpenBoxing, label = stringResource(R.string.boxing_title), icon = MobileIcons.Sports)
                }
            }
        },
    ) {
        // The day strip: Yesterday · Today · Tomorrow · the four days after. Works with both chip rows above.
        item(key = "days") {
            LazyRow(
                Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(SportsDays.offsets, key = { it }) { o ->
                    StageTool(onClick = { onDay(o) }, label = hubDayLabel(o, locale), on = o == dayOffset)
                }
            }
        }
        val nothing = live.isEmpty() && soon.isEmpty() && onNow.isEmpty()
        if (otherDay) {
            if (dayMatches.isEmpty()) {
                item(key = "dayEmpty") {
                    if (dayState.loading || dayState.date != dayDate) {
                        androidx.compose.material3.CircularProgressIndicator(color = LocalStagePalette.current.muted, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 40.dp).size(16.dp))
                    } else {
                        Text(
                            stringResource(if (dayState.hasFailures) R.string.sports_empty_unreachable else R.string.sports_day_empty),
                            style = stageText(14f, 600), color = LocalStagePalette.current.muted,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 40.dp),
                        )
                    }
                }
            } else {
                if (dayState.hasFailures) {
                    item(key = "dayFailed") {
                        Text(
                            stringResource(R.string.sports_upcoming_partial_failure), style = stageText(12.5f, 600), color = LocalStagePalette.current.warn,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                // One row per competition, in the profile's order (Settings → Sports and leagues).
                dayMatches.groupBy { it.feed }.forEach { (feed, group) ->
                    item(key = "day-$feed") {
                        Column {
                            RowHeader(leagueName(group.first()))
                            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(group, key = { it.key }) { m ->
                                    MatchCard(
                                        m, featured = false, config = config,
                                        reminded = SportsViewModel.reminderKey(m.startMs, MatchNotifications.pair(context, m)) in state.reminders,
                                        onClick = { onOpenMatch(m) }, onLongClick = { menuFor = m },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (nothing && !state.rebuilding) {
            item(key = "empty") {
                val unreachable = state.board.failing.isNotEmpty() && state.board.today.isEmpty()
                Text(
                    stringResource(if (unreachable) R.string.sports_empty_unreachable else R.string.sports_empty_nothing),
                    style = stageText(14f, 600), color = LocalStagePalette.current.muted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 40.dp),
                )
            }
        }

        if (live.isNotEmpty()) {
            item(key = "live") {
                Column {
                    RowHeader(stringResource(R.string.sports_live_now), live = true)
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(live, key = { _, m -> m.key }) { i, m ->
                            MatchCard(m, featured = i == 0, config = config, reminded = false, onClick = { onOpenMatch(m) }, onLongClick = { menuFor = m; startPreview(m) })
                        }
                    }
                }
            }
        }
        if (onNow.isNotEmpty()) {
            item(key = "onNowHead") { RowHeader(stringResource(R.string.sports_on_channels_now_mobile)) }
            items(onNow, key = { "on-" + it.title + it.startMs + it.channels.first().id }) { item ->
                OnChannelsRow(item, now) { onPlayChannel(item.channels.first().id) }
            }
        }
        if (soon.isNotEmpty()) {
            item(key = "soonHead") { RowHeader(stringResource(R.string.sports_starting_soon)) }
            items(soon, key = { "soon-" + it.key }) { m ->
                SoonRow(
                    m,
                    reminded = SportsViewModel.reminderKey(m.startMs, MatchNotifications.pair(context, m)) in state.reminders,
                    onClick = { onOpenMatch(m) },
                    onLongClick = { menuFor = m; startPreview(m) },
                )
            }
        }
    }
    }
    }

    menuFor?.let { m ->
        FollowRemindSheet(
            match = m, config = config, channels = menuChannels,
            onFollow = { vm.toggleTeam(it) },
            onRemind = { vm.remind(m, menuChannels) },
            onOpenTeam = onOpenTeam,
            onDismiss = { menuFor = null },
        )
    }
}

/**
 * The Sports list's live preview: the long-pressed game's channel through Live TV's preview player, with sound by default,
 * with the LIVE badge and the channel's name; a tap opens the match page, ✕ stops it. Covered while a
 * sheet is open (the surface is punched through the window), as Live TV's preview is.
 */
@Composable
private fun SportsLivePreview(tuner: LiveTuner, channel: ChannelEntity, onOpen: () -> Unit, onClose: () -> Unit) {
    val shape = RoundedCornerShape(StageRadii.Video)
    val name = remember(channel.name) { ProviderTags.parse(channel.name).name }
    val close = stringResource(R.string.content_close)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .height(180.dp)
            .clip(shape)
            .background(Color.Black)
            .clickable(onClick = onOpen),
    ) {
        VideoStage(player = tuner.player, modifier = Modifier.fillMaxSize())
        if (sheetOpen()) Box(Modifier.fillMaxSize().background(Color.Black))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
        StageLiveBadge(stringResource(R.string.player_live), Modifier.align(Alignment.TopStart).padding(10.dp))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onClose)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) { StageIcon(MobileIcons.Close, 18.dp, Color.White) }
        Text(
            name, style = stageText(13.5f, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

/** "**Today** · 6 live · 41 matches · Scores: ESPN" (no channel count — channels are found per match). */
@Composable
private fun HubSubtitle(today: List<Match>, dayLabel: String) {
    val liveText = pluralStringResource(R.plurals.sports_hub_live, today.count { it.isLive }, today.count { it.isLive })
    val matchesText = pluralStringResource(R.plurals.sports_hub_matches, today.size, today.size)
    SportsSubtitle(dayLabel, liveText, matchesText)
}

/** A day strip chip: Yesterday / Today / Tomorrow, then the weekday and date ("Thu 5"), locale-formatted. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun hubDayLabel(offset: Int, locale: java.util.Locale): String = when (offset) {
    -1 -> stringResource(R.string.content_epg_yesterday)
    0 -> stringResource(R.string.content_epg_today)
    1 -> stringResource(R.string.content_epg_tomorrow)
    else -> java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEd"), locale)
        .format(SportsDays.dateOf(offset))
}

/** The hub with nothing followed: what to do, where, why to choose with care — and the way there. */
@Composable
internal fun NothingFollowedNote(onChooseSports: () -> Unit) {
    val palette = LocalStagePalette.current
    Column(Modifier.padding(horizontal = 14.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sports_empty_nothing_followed), style = stageText(16f, 800), color = palette.text)
        Text(stringResource(R.string.sports_empty_choose_in), style = stageText(13f, 600), color = palette.body)
        Text(stringResource(R.string.sports_load_time_warning), style = stageText(12f, 500, lineHeight = 1.5f), color = palette.muted)
        StageButton(onClick = onChooseSports, text = stringResource(R.string.sports_leagues), icon = MobileIcons.Sports, style = StageButtonStyle.PRIMARY, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A row's heading; *Live now* carries the red dot. */
@Composable
private fun RowHeader(title: String, live: Boolean = false) {
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) LiveMark("")
        Text(title, style = stageText(17f, 800), color = LocalStagePalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Sport on your channels": the logo, "Handball · Kiel – Magdeburg", "DYN SPORT 1 · until 20:45" and how far it is. */
@Composable
private fun OnChannelsRow(item: GuideSportItem, now: Long, onClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val ch = item.channels.first()
    val sep = stringResource(R.string.content_epg_bits_separator)
    StageRow(onClick = onClick, height = 64.dp, modifier = Modifier.padding(horizontal = 2.dp)) {
        Box(Modifier.size(46.dp, 32.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            ChannelLogoImage(url = ch.logoUrl, modifier = Modifier.fillMaxSize(), plateOn = palette.page, fallback = { StageIcon(MobileIcons.LiveTv, 16.dp, palette.dim) })
        }
        Column(Modifier.weight(1f)) {
            Text(guideSportLabel(item.sport) + sep + item.title, style = stageText(13.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tv.own.owntv.core.live.ProviderTags.parse(ch.name).name + sep + stringResource(R.string.sports_until, timeText(item.stopMs)),
                style = stageText(12f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val span = (item.stopMs - item.startMs).coerceAtLeast(1)
            StageProgress(((now - item.startMs).toFloat() / span).coerceIn(0f, 1f), Modifier.padding(top = 4.dp).fillMaxWidth(0.6f), thin = true)
        }
    }
}

/** "Starting soon": kick-off, both crests, the pair, and the bell lit once a reminder is set. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoonRow(m: Match, reminded: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val palette = LocalStagePalette.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(timeText(m.startMs), style = stageText(13f, 800), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            dayOf(m.startMs, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])?.let { Text(it, style = stageText(11f, 600), color = palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        m.home?.let { Crest(it.team, 24) }
        m.away?.let { Crest(it.team, 24) }
        Column(Modifier.weight(1f)) {
            Text(MatchNotifications.pair(context, m), style = stageText(13.5f, 700), color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(leagueName(m), style = stageText(11.5f, 500), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StageIcon(MobileIcons.Bell, 18.dp, if (reminded) stageAccent.accent else palette.dim)
    }
}
