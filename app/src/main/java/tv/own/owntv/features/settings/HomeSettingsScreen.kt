package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.model.HeroKind
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.core.trending.TrendingAvailability
import tv.own.owntv.features.home.displayLabel
import tv.own.owntv.features.home.displayTitle
import tv.own.owntv.features.home.settingsDescription
import tv.own.owntv.features.mdblist.MdbListConfig
import tv.own.owntv.features.mdblist.MdbListRepository
import tv.own.owntv.features.mdblist.MdbListSettings
import tv.own.owntv.features.mdblist.MdbListSettingsViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Layout › Home screen: Now trending and its layout, the Home rows (shown or hidden,
 * in Home's order; the focused row's Hide / Move / Mode in the panel, Hold OK = Move), then Keep watching
 * and Android TV home.
 */
@Composable
fun HomeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val settingsVm: SettingsViewModel = koinViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val trendingAvailability by vm.trendingAvailability.collectAsStateWithLifecycle()
    val heroPreviewEnabled by vm.heroPreviewEnabled.collectAsStateWithLifecycle()
    val androidTvHomeEnabled by settingsVm.androidTvHomeEnabled.collectAsStateWithLifecycle()
    val tvHomeRefresh by settingsVm.tvHomeRefresh.collectAsStateWithLifecycle()
    val hideAdultCategoriesHome by vm.hideAdultCategoriesHome.collectAsStateWithLifecycle()
    val playTrailersOnHome by vm.playTrailersOnHome.collectAsStateWithLifecycle()
    val trendingEnabled = HomeRow.TRENDING !in config.hidden
    val trendingStatus = trendingStatusText(hidden = !trendingEnabled, availability = trendingAvailability)

    val rowsFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    val rowFocus = remember { HomeRow.entries.associateWith { FocusRequester() } }
    // The row being moved with ▲ ▼ (Hold OK, or Move in the panel); OK or Back ends it.
    var moving by remember { mutableStateOf<HomeRow?>(null) }
    // The row whose actions the panel shows while focus is in them.
    var acting by remember { mutableStateOf<HomeRow?>(null) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { rowsFocus.requestFocus() } }

    val ok = stringResource(R.string.common_ok)
    val back = stringResource(R.string.common_back)
    val layout = stringResource(R.string.settings_group_layout)
    val rowCount = (if (trendingEnabled) 2 else 1) + MDBLIST_ROWS + config.settingsRows.size + 1
    StageFullPage(
        parents = listOf(layout),
        title = stringResource(R.string.settings_home_screen),
        count = pluralStringResource(R.plurals.settings_home_row_count, rowCount, rowCount),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
        handleBack = moving == null,
    ) {
        StageSettingsHeading(stringResource(R.string.home_row_now_trending), null, first = true)
        val trendingTitle = stringResource(R.string.home_row_now_trending)
        val trendingValue = SettingValue.Switch(trendingEnabled)
        StageSettingRow(
            icon = OwnTVIcon.STAR,
            title = trendingTitle,
            desc = trendingStatus,
            value = trendingValue,
            onClick = { vm.setRowHidden(HomeRow.TRENDING, trendingEnabled) },
            help = settingHelp(null, trendingTitle, HomeRow.TRENDING.settingsDescription(), trendingValue, pinnable = false),
        )
        // Only while the row is on: with Trending off there is nothing for the choice to apply to.
        if (trendingEnabled) {
            val styleTitle = stringResource(R.string.home_trending_style)
            val styleValue = SettingValue.Segmented(
                listOf(stringResource(R.string.home_trending_style_full_bleed), stringResource(R.string.home_trending_style_posters)),
                if (config.trendingStyle == HomeTrendingStyle.HERO) 0 else 1,
            )
            val flip = {
                vm.setTrendingStyle(if (config.trendingStyle == HomeTrendingStyle.HERO) HomeTrendingStyle.POSTERS else HomeTrendingStyle.HERO)
            }
            StageSettingRow(
                icon = OwnTVIcon.MOVIES,
                title = styleTitle,
                desc = stringResource(R.string.settings_line_trending_layout),
                value = styleValue,
                onClick = flip,
                onStep = { step -> vm.setTrendingStyle(if (step < 0) HomeTrendingStyle.HERO else HomeTrendingStyle.POSTERS) },
                help = settingHelp(null, styleTitle, stringResource(R.string.home_row_trending_description), styleValue, choices = styleValue.options, pinnable = false),
            )
        }

        val hideAdultTitle = stringResource(R.string.settings_home_hide_adult_categories)
        val hideAdultDesc = stringResource(R.string.settings_home_hide_adult_categories_description)
        val hideAdultValue = SettingValue.Switch(hideAdultCategoriesHome)
        StageSettingRow(
            icon = OwnTVIcon.EYE_OFF,
            title = hideAdultTitle,
            desc = hideAdultDesc,
            value = hideAdultValue,
            onClick = { vm.setHideAdultCategoriesHome(!hideAdultCategoriesHome) },
            help = settingHelp(null, hideAdultTitle, hideAdultDesc, hideAdultValue, pinnable = false),
        )

        val trailersTitle = stringResource(R.string.settings_home_play_trailers)
        val trailersDesc = stringResource(R.string.settings_home_play_trailers_description)
        val trailersValue = SettingValue.Switch(playTrailersOnHome)
        StageSettingRow(
            icon = OwnTVIcon.PLAY_CIRCLE,
            title = trailersTitle,
            desc = trailersDesc,
            value = trailersValue,
            onClick = { vm.setPlayTrailersOnHome(!playTrailersOnHome) },
            help = settingHelp(null, trailersTitle, trailersDesc, trailersValue, pinnable = false),
        )

        MdbListRows()

        StageSettingsHeading(stringResource(R.string.settings_sections), config.settingsRows.size)
        val hiddenNote = stringResource(R.string.settings_hidden_sections)
        config.settingsRows.forEach { row ->
            key(row) {
                val hidden = row in config.hidden
                val liveMode = when (row) {
                    HomeRow.RECENT_CHANNELS -> config.recentLiveMode
                    HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
                    else -> null
                }
                val focus = rowFocus.getValue(row)
                val startMove: () -> Unit = { moving = row; acting = null; runCatching { focus.requestFocus() } }
                val actions = listOfNotNull(
                    StageAction(
                        if (hidden) OwnTVIcon.PLAY_CIRCLE else OwnTVIcon.EYE_OFF,
                        stringResource(if (hidden) R.string.settings_home_show_row else R.string.settings_home_hide_row),
                        { vm.setRowHidden(row, !hidden) },
                    ),
                    StageAction(OwnTVIcon.MOVE, stringResource(R.string.settings_home_move_row), startMove),
                    liveMode?.let { mode -> StageAction(OwnTVIcon.GRID, mode.displayLabel(), { vm.setLiveRowMode(row, mode.toggled()) }) },
                )
                val isMoving = moving == row
                val title = row.displayTitle()
                val hiddenWord = stringResource(R.string.settings_hidden)
                StageSettingRow(
                    icon = row.icon,
                    title = title,
                    desc = if (hidden) listOfNotNull(hiddenWord, liveMode?.displayLabel()).joinToString(dotSeparator())
                        else row.settingsDescription(),
                    value = SettingValue.Custom {
                        Text(
                            if (hidden) hiddenWord else stringResource(R.string.settings_home_shown),
                            style = stageText(18, 700),
                            color = if (hidden) StageColors.Dim else stageAccent.accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { if (isMoving) moving = null else { acting = row; runCatching { actionsFocus.requestFocus() } } },
                    onLongClick = startMove,
                    marked = isMoving,
                    keepPanel = acting == row,
                    help = SettingHelp(
                        title = title,
                        text = row.settingsDescription() + "\n" + hiddenNote,
                        hints = if (isMoving) {
                            listOf("▲ ▼" to stringResource(R.string.content_move), ok to stringResource(R.string.settings_done))
                        } else {
                            listOf(
                                "▶" to stringResource(R.string.settings_key_actions),
                                stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_move),
                                back to layout,
                            )
                        },
                        extra = { if (!isMoving) StageActionColumn(actions, focus, actionsFocus) },
                    ),
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusProperties { if (!isMoving) right = actionsFocus }
                        .onFocusChanged { if (it.isFocused) acting = null }
                        .onPreviewKeyEvent { e ->
                            if (!isMoving) return@onPreviewKeyEvent false
                            val up = when (e.key) {
                                Key.DirectionUp -> true
                                Key.DirectionDown -> false
                                else -> return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                            }
                            if (e.type == KeyEventType.KeyDown) vm.move(row, up)
                            true
                        },
                )
            }
        }

        StageSettingsHeading(stringResource(R.string.settings_keep_watching), 4)
        HomeSwitchRow(OwnTVIcon.LIVE_TV, R.string.settings_live_keep_watching, R.string.settings_live_keep_watching_description, config.heroIncludeLive) {
            vm.setHeroInclude(HeroKind.LIVE, !config.heroIncludeLive)
        }
        HomeSwitchRow(OwnTVIcon.MOVIES, R.string.settings_movies_keep_watching, R.string.settings_movies_keep_watching_description, config.heroIncludeMovies) {
            vm.setHeroInclude(HeroKind.MOVIES, !config.heroIncludeMovies)
        }
        HomeSwitchRow(OwnTVIcon.SERIES, R.string.settings_series_keep_watching, R.string.settings_series_keep_watching_description, config.heroIncludeSeries) {
            vm.setHeroInclude(HeroKind.SERIES, !config.heroIncludeSeries)
        }
        HomeSwitchRow(OwnTVIcon.PLAY, R.string.settings_hero_preview, R.string.settings_hero_preview_description, heroPreviewEnabled) {
            vm.setHeroPreviewEnabled(!heroPreviewEnabled)
        }

        StageSettingsHeading(stringResource(R.string.settings_android_tv_home), if (androidTvHomeEnabled) 2 else 1)
        HomeSwitchRow(OwnTVIcon.HISTORY, R.string.settings_android_tv_home, R.string.settings_android_tv_home_description, androidTvHomeEnabled) {
            settingsVm.setAndroidTvHomeEnabled(!androidTvHomeEnabled)
        }
        if (androidTvHomeEnabled) {
            val refreshTitle = stringResource(R.string.settings_refresh_now)
            val refreshValue = when (tvHomeRefresh) {
                SettingsViewModel.TvHomeRefresh.REFRESHING -> SettingValue.Action(stringResource(R.string.settings_rebuilding))
                SettingsViewModel.TvHomeRefresh.DONE -> SettingValue.Action(stringResource(R.string.settings_done_check))
                else -> null
            }
            val refreshDesc = stringResource(R.string.settings_refresh_description)
            StageSettingRow(
                icon = OwnTVIcon.REFRESH,
                title = refreshTitle,
                desc = refreshDesc,
                value = refreshValue,
                onClick = { if (tvHomeRefresh == SettingsViewModel.TvHomeRefresh.IDLE) settingsVm.refreshAndroidTvHome() },
                help = SettingHelp(refreshTitle, refreshDesc, hints = settingHints(null, pinnable = false)),
            )
        }
    }
    BackHandler(enabled = moving != null) { moving = null }
}

@Composable
private fun HomeSwitchRow(icon: OwnTVIcon, titleRes: Int, descRes: Int, on: Boolean, onClick: () -> Unit) {
    val title = stringResource(titleRes)
    val desc = stringResource(descRes)
    val value = SettingValue.Switch(on)
    StageSettingRow(
        icon = icon,
        title = title,
        desc = desc,
        value = value,
        onClick = onClick,
        help = settingHelp(null, title, desc, value, pinnable = false),
    )
}

private val HomeRow.icon: OwnTVIcon get() = when (this) {
    HomeRow.TRENDING -> OwnTVIcon.STAR
    HomeRow.HERO -> OwnTVIcon.PLAY_CIRCLE
    HomeRow.LIVE_SPORTS -> OwnTVIcon.SPORTS
    HomeRow.RECENT_CHANNELS -> OwnTVIcon.LIVE_TV
    HomeRow.FAVORITE_CHANNELS -> OwnTVIcon.FAVORITE
    HomeRow.FAVORITE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.FAVORITE_SERIES -> OwnTVIcon.SERIES
    HomeRow.CONTINUE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.CONTINUE_SERIES -> OwnTVIcon.SERIES
}

@Composable
private fun trendingStatusText(hidden: Boolean, availability: TrendingAvailability): String = when {
    hidden -> stringResource(R.string.settings_trending_status_off)
    availability == TrendingAvailability.Building -> stringResource(R.string.settings_trending_status_building)
    availability == TrendingAvailability.MetadataDisabled -> stringResource(R.string.settings_trending_status_metadata_disabled)
    availability == TrendingAvailability.NoVodScope -> stringResource(R.string.settings_trending_status_no_vod)
    availability == TrendingAvailability.Failed -> stringResource(R.string.settings_trending_status_failed)
    availability == TrendingAvailability.WaitingForSync -> stringResource(R.string.settings_trending_status_waiting)
    availability is TrendingAvailability.BelowThreshold && availability.matched == 0 -> stringResource(
        R.string.settings_trending_status_no_matches,
    )
    availability is TrendingAvailability.BelowThreshold -> pluralStringResource(
        R.plurals.settings_trending_status_below_threshold,
        availability.matched,
        availability.matched,
    )
    availability is TrendingAvailability.Showing && availability.refreshFailed -> pluralStringResource(
        R.plurals.settings_trending_status_showing_refresh_failed,
        availability.count,
        availability.count,
    )
    availability is TrendingAvailability.Showing -> pluralStringResource(
        R.plurals.settings_trending_status_showing,
        availability.count,
        availability.count,
    )
    else -> stringResource(R.string.settings_trending_status_waiting)
}

/** Rows the MDBList group adds to the page's count: the two switches, the key, Test key, Status and Add a list. */
private const val MDBLIST_ROWS = 6

/**
 * MDBList (Settings › Layout › Home): curated rails and the ratings line, the key, which lists, and a
 * key test. The two switches read as off, and cannot be switched, until a key exists — built in or
 * typed. App-level like the store behind them (MdbListSettings): a list picked here is on Home for
 * every profile.
 */
@Composable
private fun MdbListRows() {
    val vm: MdbListSettingsViewModel = koinViewModel()
    val c by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val topLists by vm.topLists.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val sep = dotSeparator()

    StageSettingsHeading(stringResource(R.string.mdblist_title), null)
    val needsKey = stringResource(R.string.mdblist_needs_key)

    val rowsTitle = stringResource(R.string.mdblist_curated_rows)
    val rowsDesc = if (c.hasKey) stringResource(R.string.mdblist_curated_rows_description) else needsKey
    val rowsValue = SettingValue.Switch(c.rowsOn)
    StageSettingRow(
        icon = OwnTVIcon.LIST, title = rowsTitle, desc = rowsDesc, value = rowsValue, enabled = c.hasKey,
        onClick = { vm.setCuratedRows(!c.curatedRows) },
        help = settingHelp(null, rowsTitle, rowsDesc, rowsValue, pinnable = false),
    )

    val ratingsTitle = stringResource(R.string.mdblist_ratings)
    val ratingsDesc = if (c.hasKey) stringResource(R.string.mdblist_ratings_description) else needsKey
    val ratingsValue = SettingValue.Switch(c.ratingsOn)
    StageSettingRow(
        icon = OwnTVIcon.STAR, title = ratingsTitle, desc = ratingsDesc, value = ratingsValue, enabled = c.hasKey,
        onClick = { vm.setRatings(!c.ratings) },
        help = settingHelp(null, ratingsTitle, ratingsDesc, ratingsValue, pinnable = false),
    )

    val keyTitle = stringResource(R.string.mdblist_api_key)
    var apiKey by remember(c.apiKey) { mutableStateOf(c.apiKey) }
    // Saved a moment after typing stops (and on Done), not per keystroke: the store's echo must not fight the field.
    LaunchedEffect(apiKey) {
        if (apiKey.trim() != c.apiKey) {
            kotlinx.coroutines.delay(600)
            vm.setApiKey(apiKey)
        }
    }
    StageFieldRow(
        icon = OwnTVIcon.PENCIL, label = keyTitle, value = apiKey, onValueChange = { apiKey = it },
        help = settingHelp(null, keyTitle, stringResource(R.string.mdblist_api_key_description), null, pinnable = false),
        // With nothing typed and a key built in, the row says so — and never shows that key.
        placeholder = stringResource(if (c.usesBuiltInKey) R.string.mdblist_api_key_built_in else R.string.mdblist_api_key_hint),
        password = true, onDone = { vm.setApiKey(apiKey) },
    )

    val testTitle = stringResource(R.string.mdblist_test_key)
    val result = test
    val testDesc = when {
        testing -> stringResource(R.string.mdblist_testing)
        result == null -> stringResource(R.string.mdblist_test_key_description)
        result.ok -> pluralStringResource(R.plurals.mdblist_test_ok, result.listsParsed, result.listsParsed)
        result.httpCode == 0 -> stringResource(R.string.mdblist_status_unreachable)
        result.httpCode == 401 || result.httpCode == 403 -> stringResource(R.string.mdblist_status_key_rejected)
        else -> stringResource(R.string.mdblist_test_failed, result.httpCode)
    }
    StageSettingRow(
        icon = OwnTVIcon.CHECK, title = testTitle, desc = testDesc,
        value = SettingValue.Action(stringResource(R.string.mdblist_test_now)), enabled = c.hasKey && !testing,
        onClick = { vm.testKey() },
        help = settingHelp(null, testTitle, testDesc, null, pinnable = false),
    )

    val statusTitle = stringResource(R.string.mdblist_status)
    val statusDesc = mdbStatusText(c, status)
    StageSettingRow(
        icon = OwnTVIcon.INFO, title = statusTitle, desc = statusDesc, value = null, onClick = {},
        help = settingHelp(null, statusTitle, statusDesc, null, pinnable = false),
    )

    // Which lists: the public top lists as switches (five at most), any list typed by hand, and a field to type one.
    val listsTitle = stringResource(R.string.mdblist_lists)
    val listsDesc = stringResource(R.string.mdblist_lists_description)
    StageSettingsHeading(listsTitle, c.lists.size.takeIf { it > 0 })
    val full = c.lists.size >= MdbListSettings.MAX_LISTS
    topLists.forEach { list ->
        key(list.id) {
            val picked = list.ref in c.lists
            val line = listOfNotNull(
                list.user.takeIf { it.isNotBlank() },
                pluralStringResource(R.plurals.mdblist_list_titles, list.items, list.items).takeIf { list.items > 0 },
            ).joinToString(sep)
            val value = SettingValue.Switch(picked)
            StageSettingRow(
                icon = OwnTVIcon.LIST, title = list.name, desc = line.ifBlank { null }, value = value, enabled = picked || !full,
                onClick = { vm.toggleList(list.ref) },
                help = settingHelp(null, list.name, listsDesc, value, pinnable = false),
            )
        }
    }
    c.lists.filter { ref -> topLists.none { it.ref == ref } }.forEach { ref ->
        key(ref) {
            StageSettingRow(
                icon = OwnTVIcon.LIST, title = ref, desc = null, value = SettingValue.Action(stringResource(R.string.mdblist_remove)),
                onClick = { vm.removeList(ref) },
                help = settingHelp(null, ref, listsDesc, null, pinnable = false),
            )
        }
    }
    val addTitle = stringResource(R.string.mdblist_add_list)
    var typed by remember { mutableStateOf("") }
    StageFieldRow(
        icon = OwnTVIcon.PENCIL, label = addTitle, value = typed, onValueChange = { typed = it },
        help = settingHelp(null, addTitle, stringResource(R.string.mdblist_add_list_description), null, pinnable = false),
        placeholder = stringResource(R.string.mdblist_add_list_hint),
        onDone = { if (vm.addList(typed)) typed = "" },
    )
}

/** The Status row: the key first, then what the last call came to. */
@Composable
private fun mdbStatusText(c: MdbListConfig, status: MdbListRepository.Status): String = when {
    !c.hasKey -> stringResource(R.string.mdblist_status_no_key)
    !c.rowsOn && !c.ratingsOn -> stringResource(R.string.mdblist_status_off)
    else -> when (status) {
        MdbListRepository.Status.Idle -> stringResource(R.string.mdblist_status_ready)
        MdbListRepository.Status.NoKey -> stringResource(R.string.mdblist_status_no_key)
        MdbListRepository.Status.KeyRejected -> stringResource(R.string.mdblist_status_key_rejected)
        MdbListRepository.Status.Quota -> stringResource(R.string.mdblist_status_quota)
        is MdbListRepository.Status.HttpError -> stringResource(R.string.mdblist_status_http, status.code)
        MdbListRepository.Status.Unreachable -> stringResource(R.string.mdblist_status_unreachable)
        MdbListRepository.Status.NoMatches -> stringResource(R.string.mdblist_status_no_matches)
        is MdbListRepository.Status.Shown -> stringResource(R.string.mdblist_status_shown, status.rails, status.titles)
    }
}
