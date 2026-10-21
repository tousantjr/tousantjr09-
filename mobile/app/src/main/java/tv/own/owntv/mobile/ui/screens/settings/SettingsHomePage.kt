package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.core.trending.TrendingAvailability
import tv.own.owntv.mobile.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.StageSegmented
import tv.own.owntv.mobile.ui.stage.StageSettingRow
import tv.own.owntv.mobile.ui.stage.StageSwitch
import androidx.compose.ui.semantics.Role
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import tv.own.owntv.mobile.mdblist.MdbListConfig
import tv.own.owntv.mobile.mdblist.MdbListInfo
import tv.own.owntv.mobile.mdblist.MdbListRepository
import tv.own.owntv.mobile.mdblist.MdbListSettings
import tv.own.owntv.mobile.mdblist.MdbListSettingsViewModel
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.SheetScroll
import tv.own.owntv.mobile.ui.stage.StageChevron
import tv.own.owntv.mobile.ui.stage.StageMenuItem
import tv.own.owntv.mobile.ui.stage.StageRowValue
import tv.own.owntv.mobile.ui.stage.StageSheetHeader
import tv.own.owntv.mobile.ui.stage.StageTextRow
import android.content.Intent
import android.net.Uri
import tv.own.owntv.mobile.trakt.TraktConfig
import tv.own.owntv.mobile.trakt.TraktLogin
import tv.own.owntv.mobile.trakt.TraktRepository
import tv.own.owntv.mobile.trakt.TraktSettingsViewModel

/**
 * Home, as the profile wants it: which rows appear, in what order, and what the Keep watching row is
 * allowed to draw from.
 *
 * Now Trending is first and apart from the rest, because it is the one row that can be empty for
 * reasons the user cannot see — metadata turned off, a channels-only playlist, a sync that has not
 * run. The sentence under it is core's own verdict, so the phone and the television never give two
 * different explanations for one empty row.
 */
@Composable
fun SettingsHomePage(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val profileId = vm.settings.activeProfileId.pref(-1L)
    val home = remember(profileId) {
        if (profileId < 0) flowOf(HomeConfig()) else vm.settings.homeConfig(profileId)
    }.pref(HomeConfig())
    val availability by vm.trendingAvailability.collectAsStateWithLifecycle()
    val hideAdultCategoriesHome by vm.hideAdultCategoriesHome.collectAsStateWithLifecycle()

    fun editHome(transform: (HomeConfig) -> HomeConfig) {
        if (profileId < 0) return
        vm.edit { updateHomeConfig(profileId, transform) }
    }

    val trendingOn = HomeRow.TRENDING !in home.hidden

    SettingsPage(modifier) {
        settingsSection(R.string.home_row_now_trending)
        settingsNote(R.string.home_row_trending_description)
        settingsGroup(key = "trending") {
            StageSettingRow(
                title = stringResource(R.string.home_row_now_trending),
                // The status, not the description: the description is the note above, and what a
                // user opening this page wants is why the row is not there today.
                subtitle = trendingStatusText(hidden = !trendingOn, availability = availability),
                role = Role.Switch,
                onClick = {
                    editHome {
                        it.copy(hidden = if (!trendingOn) it.hidden - HomeRow.TRENDING else it.hidden + HomeRow.TRENDING)
                    }
                },
            ) { StageSwitch(trendingOn) }
            // Only worth offering while the row is actually on — with Trending off there is nothing
            // for the choice to apply to, so it is not shown at all.
            if (trendingOn) {
                // Two shapes, both in view: a segmented control, not a ⌄ that promises a list.
                val styles = HomeTrendingStyle.entries
                StageSettingRow(title = stringResource(R.string.home_trending_style)) {
                    StageSegmented(
                        options = styles.map { stringResource(it.labelRes()) },
                        selected = styles.indexOf(home.trendingStyle),
                        onSelect = { i -> editHome { it.copy(trendingStyle = styles[i]) } },
                    )
                }
            }
            // Additive to :core's own kids-profile category hiding — see HomeContentFilterSettings.
            StageSettingRow(
                title = stringResource(R.string.settings_home_hide_adult_categories),
                subtitle = stringResource(R.string.settings_home_hide_adult_categories_description),
                role = Role.Switch,
                onClick = { vm.setHideAdultCategoriesHome(!hideAdultCategoriesHome) },
            ) { StageSwitch(hideAdultCategoriesHome) }
        }

        // MDBList: curated rows under Now trending and a ratings line on details pages, behind a key
        // (typed, or built in) — see MdbListSettings. App-level: one key and one set of lists per device.
        settingsSection(R.string.mdblist_title)
        settingsGroup(key = "mdblist") { MdbListRows() }

        // Trakt: scrobbling and the watchlist row, behind a device-code login — see TraktSettings.
        // App-level: one connected account per device, scrobbling for every profile.
        settingsSection(R.string.trakt_title)
        settingsGroup(key = "trakt") { TraktRows() }

        settingsSection(R.string.settings_sections)
        settingsNote(R.string.settings_hidden_sections)
        settingsGroup(key = "rows") {
            home.settingsRows.forEachIndexed { index, row ->
                val order = home.settingsRows
                ReorderableSwitchRow(
                    title = stringResource(row.titleRes()),
                    subtitle = if (row in home.hidden) stringResource(R.string.settings_hidden)
                    else stringResource(row.descriptionRes()),
                    checked = row !in home.hidden,
                    onCheckedChange = { show ->
                        editHome { it.copy(hidden = if (show) it.hidden - row else it.hidden + row) }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onMove = { delta ->
                        val moved = order.toMutableList().apply { add(index + delta, removeAt(index)) }
                        editHome { it.copy(order = listOf(HomeRow.TRENDING) + moved) }
                    },
                )
                // Only the two channel rows have a second shape to draw in, so only they carry the
                // choice — the same rule, and the same stored value, the television uses.
                row.liveMode(home)?.let { mode ->
                    StageSettingRow(
                        title = stringResource(R.string.settings_mode, stringResource(mode.labelRes())),
                        onClick = {
                            editHome {
                                when (row) {
                                    HomeRow.RECENT_CHANNELS -> it.copy(recentLiveMode = mode.toggled())
                                    else -> it.copy(favoriteLiveMode = mode.toggled())
                                }
                            }
                        },
                    )
                }
            }
        }

        settingsSection(R.string.settings_keep_watching) {
            val heroPreview = vm.settings.heroPreviewEnabled.pref(vm.settings.heroPreviewDefault)
            StageSettingRow(
                title = stringResource(R.string.settings_hero_preview),
                subtitle = stringResource(R.string.settings_hero_preview_description),
                role = Role.Switch,
                onClick = { vm.edit { setHeroPreviewEnabled(!heroPreview) } },
            ) { StageSwitch(heroPreview) }
            StageSettingRow(
                title = stringResource(R.string.settings_live_keep_watching),
                subtitle = stringResource(R.string.settings_live_keep_watching_description),
                role = Role.Switch,
                onClick = { editHome { it.copy(heroIncludeLive = !home.heroIncludeLive) } },
            ) { StageSwitch(home.heroIncludeLive) }
            StageSettingRow(
                title = stringResource(R.string.settings_movies_keep_watching),
                subtitle = stringResource(R.string.settings_movies_keep_watching_description),
                role = Role.Switch,
                onClick = { editHome { it.copy(heroIncludeMovies = !home.heroIncludeMovies) } },
            ) { StageSwitch(home.heroIncludeMovies) }
            StageSettingRow(
                title = stringResource(R.string.settings_series_keep_watching),
                subtitle = stringResource(R.string.settings_series_keep_watching_description),
                role = Role.Switch,
                onClick = { editHome { it.copy(heroIncludeSeries = !home.heroIncludeSeries) } },
            ) { StageSwitch(home.heroIncludeSeries) }
        }
    }
}

/**
 * A row that can be both switched off and moved. The TV app picks a row up with OK and walks it with
 * the D-pad; a thumb gets two arrows instead, because there is nothing to hold on a touch screen.
 */
@Composable
private fun ReorderableSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    StageSettingRow(title = title, subtitle = subtitle, onClick = { onCheckedChange(!checked) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MoveKey(MobileIcons.KeyboardArrowUp, R.string.settings_row_menu_move_up, canMoveUp) { onMove(-1) }
            MoveKey(MobileIcons.KeyboardArrowDown, R.string.settings_row_menu_move_down, canMoveDown) { onMove(1) }
            StageSwitch(checked)
        }
    }
}

private fun HomeRow.titleRes() = when (this) {
    HomeRow.TRENDING -> R.string.home_row_now_trending
    HomeRow.HERO -> R.string.settings_keep_watching
    HomeRow.LIVE_SPORTS -> R.string.sports_home_row
    HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_channels
    HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_channels
    HomeRow.FAVORITE_MOVIES -> R.string.home_row_favorite_movies
    HomeRow.FAVORITE_SERIES -> R.string.home_row_favorite_series
    HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies
    HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series
}

private fun HomeRow.descriptionRes() = when (this) {
    HomeRow.TRENDING -> R.string.home_row_trending_description
    HomeRow.HERO -> R.string.home_row_hero_description
    HomeRow.LIVE_SPORTS -> R.string.sports_home_row_description
    HomeRow.RECENT_CHANNELS -> R.string.home_row_recent_description
    HomeRow.FAVORITE_CHANNELS -> R.string.home_row_favorite_description
    HomeRow.FAVORITE_MOVIES -> R.string.home_row_favorite_movies_description
    HomeRow.FAVORITE_SERIES -> R.string.home_row_favorite_series_description
    HomeRow.CONTINUE_MOVIES -> R.string.home_row_continue_movies_description
    HomeRow.CONTINUE_SERIES -> R.string.home_row_continue_series_description
}

/** Which of the two channel rows this is, and the shape it is currently drawn in. */
private fun HomeRow.liveMode(config: HomeConfig): HomeLiveRowMode? = when (this) {
    HomeRow.RECENT_CHANNELS -> config.recentLiveMode
    HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
    else -> null
}

private fun HomeTrendingStyle.labelRes() = when (this) {
    HomeTrendingStyle.HERO -> R.string.home_trending_style_hero
    HomeTrendingStyle.POSTERS -> R.string.home_trending_style_posters
}

private fun HomeLiveRowMode.labelRes() = when (this) {
    HomeLiveRowMode.CARDS -> R.string.home_row_cards
    HomeLiveRowMode.ON_NOW -> R.string.home_row_on_now
}

/** The ten sentences the row can be explained by, in the television's own order of precedence. */
@Composable
private fun trendingStatusText(hidden: Boolean, availability: TrendingAvailability): String = when {
    hidden -> stringResource(R.string.settings_trending_status_off)
    availability == TrendingAvailability.Building ->
        stringResource(R.string.settings_trending_status_building)
    availability == TrendingAvailability.MetadataDisabled ->
        stringResource(R.string.settings_trending_status_metadata_disabled)
    availability == TrendingAvailability.NoVodScope ->
        stringResource(R.string.settings_trending_status_no_vod)
    availability == TrendingAvailability.Failed ->
        stringResource(R.string.settings_trending_status_failed)
    availability is TrendingAvailability.BelowThreshold && availability.matched == 0 ->
        stringResource(R.string.settings_trending_status_no_matches)
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

/**
 * MDBList (Settings › Layout › Home): the two switches (off, and not tappable, until a key exists — built
 * in or typed), the key, which lists (a sheet of the public top lists, five at most, plus one typed by
 * hand), Test key and a status line. The same rows the television has, in the phone's shapes.
 */
@Composable
private fun MdbListRows() {
    val vm: MdbListSettingsViewModel = koinViewModel()
    val c by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val topLists by vm.topLists.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    var listsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val needsKey = stringResource(R.string.mdblist_needs_key)

    StageSettingRow(
        title = stringResource(R.string.mdblist_curated_rows),
        subtitle = if (c.hasKey) stringResource(R.string.mdblist_curated_rows_description) else needsKey,
        role = Role.Switch,
        onClick = if (c.hasKey) ({ vm.setCuratedRows(!c.curatedRows) }) else null,
    ) { StageSwitch(c.rowsOn) }
    StageSettingRow(
        title = stringResource(R.string.mdblist_ratings),
        subtitle = if (c.hasKey) stringResource(R.string.mdblist_ratings_description) else needsKey,
        role = Role.Switch,
        onClick = if (c.hasKey) ({ vm.setRatings(!c.ratings) }) else null,
    ) { StageSwitch(c.ratingsOn) }
    // Typed once and saved from its sheet; with nothing typed and a key built in, the row says so and
    // never shows that key.
    StageTextRow(
        icon = MobileIcons.Lock, label = stringResource(R.string.mdblist_api_key), value = c.apiKey, onSave = vm::setApiKey,
        placeholder = stringResource(if (c.usesBuiltInKey) R.string.mdblist_api_key_built_in else R.string.mdblist_api_key_hint),
        password = true,
    )
    StageSettingRow(
        title = stringResource(R.string.mdblist_lists),
        subtitle = stringResource(R.string.mdblist_lists_description),
        onClick = if (c.hasKey) ({ listsOpen = true }) else null,
    ) {
        StageRowValue(
            if (c.lists.isEmpty()) stringResource(R.string.mdblist_lists_default) else c.lists.size.toString(),
            if (c.hasKey) StageChevron.FORWARD else StageChevron.NONE,
        )
    }
    // A list typed by hand: its number, user/list-name, or its mdblist.com address.
    StageTextRow(
        icon = MobileIcons.Add, label = stringResource(R.string.mdblist_add_list), value = "",
        onSave = { typed ->
            if (typed.isNotBlank() && !vm.addList(typed)) Toast.makeText(context, R.string.mdblist_add_list_invalid, Toast.LENGTH_SHORT).show()
        },
        placeholder = stringResource(R.string.mdblist_add_list_hint),
    )
    val result = test
    val testLine = when {
        testing -> stringResource(R.string.mdblist_testing)
        result == null -> stringResource(R.string.mdblist_test_key_description)
        result.ok -> pluralStringResource(R.plurals.mdblist_test_ok, result.listsParsed, result.listsParsed)
        result.httpCode == 0 -> stringResource(R.string.mdblist_status_unreachable)
        result.httpCode == 401 || result.httpCode == 403 -> stringResource(R.string.mdblist_status_key_rejected)
        else -> stringResource(R.string.mdblist_test_failed, result.httpCode)
    }
    StageSettingRow(
        title = stringResource(R.string.mdblist_test_key),
        subtitle = testLine,
        onClick = if (c.hasKey && !testing) ({ vm.testKey() }) else null,
    ) { StageRowValue(stringResource(R.string.mdblist_test_now), plain = true) }
    StageSettingRow(title = stringResource(R.string.mdblist_status), subtitle = mdbStatusText(c, status))

    if (listsOpen) MdbListsSheet(vm, c, topLists) { listsOpen = false }
}

/** The public top lists, each ticked or not (five at most), and the lists typed by hand, each removable. */
@Composable
private fun MdbListsSheet(vm: MdbListSettingsViewModel, c: MdbListConfig, topLists: List<MdbListInfo>, onDismiss: () -> Unit) {
    val sep = stringResource(R.string.content_epg_bits_separator)
    MobileBottomSheet(onDismissRequest = onDismiss) {
        StageSheetHeader(stringResource(R.string.mdblist_lists))
        SheetScroll {
            val full = c.lists.size >= MdbListSettings.MAX_LISTS
            topLists.forEach { list ->
                val picked = list.ref in c.lists
                val line = listOfNotNull(
                    list.user.takeIf { it.isNotBlank() },
                    pluralStringResource(R.plurals.mdblist_list_titles, list.items, list.items).takeIf { list.items > 0 },
                ).joinToString(sep)
                StageMenuItem(list.name, { vm.toggleList(list.ref) }, value = line.ifBlank { null }, checked = picked, enabled = picked || !full)
            }
            c.lists.filter { ref -> topLists.none { it.ref == ref } }.forEach { ref ->
                StageMenuItem(ref, { vm.removeList(ref) }, checked = true)
            }
        }
    }
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
        is MdbListRepository.Status.Shown -> pluralStringResource(R.plurals.mdblist_status_shown, status.rails, status.rails, status.titles)
    }
}

/**
 * Trakt (Settings › Layout › Home): Connect / Disconnect (the device-code login runs on the row — it
 * shows the code to type at trakt.tv/activate, and a tap opens that page), a Cancel row while it
 * waits, the scrobble and watchlist switches (off, and not tappable, until connected), the API app's
 * client id and its optional secret, and a status line. The television's rows, in the phone's shapes.
 */
@Composable
private fun TraktRows() {
    val vm: TraktSettingsViewModel = koinViewModel()
    val c by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val login by vm.login.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val needsId = stringResource(R.string.trakt_needs_client_id)
    val notConnected = stringResource(R.string.trakt_needs_connection)

    val l = login
    val waiting = l?.state == TraktLogin.State.WAITING
    val connectLine = when {
        c.connected -> if (c.username.isNotBlank()) stringResource(R.string.trakt_connected_as, c.username) else stringResource(R.string.trakt_status_ready)
        !c.hasCredentials -> needsId
        l == null -> stringResource(R.string.trakt_connect_description)
        l.state == TraktLogin.State.WAITING -> stringResource(R.string.trakt_connect_code, l.verificationUrl, l.userCode)
        l.state == TraktLogin.State.EXPIRED -> stringResource(R.string.trakt_connect_expired)
        l.state == TraktLogin.State.DENIED -> stringResource(R.string.trakt_connect_denied)
        l.code == 0 -> stringResource(R.string.trakt_status_unreachable)
        else -> stringResource(R.string.trakt_connect_declined, l.code)
    }
    StageSettingRow(
        title = stringResource(if (c.connected) R.string.trakt_connected else R.string.trakt_connect),
        subtitle = connectLine,
        onClick = if (!c.hasCredentials) null else ({
            when {
                c.connected -> vm.disconnect()
                waiting && l != null -> runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.verificationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                else -> vm.connect()
            }
        }),
    ) {
        StageRowValue(
            stringResource(
                when {
                    c.connected -> R.string.trakt_disconnect
                    waiting -> R.string.trakt_connect_open
                    else -> R.string.trakt_connect_now
                },
            ),
            plain = true,
        )
    }
    if (waiting) {
        StageSettingRow(title = stringResource(R.string.trakt_connect_cancel), onClick = { vm.cancelConnect() })
    }
    StageSettingRow(
        title = stringResource(R.string.trakt_scrobble),
        subtitle = if (c.connected) stringResource(R.string.trakt_scrobble_description) else notConnected,
        role = Role.Switch,
        onClick = if (c.connected) ({ vm.setScrobble(!c.scrobble) }) else null,
    ) { StageSwitch(c.scrobbleOn) }
    StageSettingRow(
        title = stringResource(R.string.trakt_watchlist_row),
        subtitle = if (c.connected) stringResource(R.string.trakt_watchlist_row_description) else notConnected,
        role = Role.Switch,
        onClick = if (c.connected) ({ vm.setWatchlistRow(!c.watchlistRow) }) else null,
    ) { StageSwitch(c.watchlistOn) }
    // Typed once and saved from their sheets; with nothing typed and one built in, a row says so and
    // never shows it. The secret is optional: Trakt's newer apps are PKCE apps and have none.
    StageTextRow(
        icon = MobileIcons.Lock, label = stringResource(R.string.trakt_client_id), value = c.clientId, onSave = vm::setClientId,
        placeholder = stringResource(if (c.usesBuiltInId) R.string.trakt_built_in else R.string.trakt_client_id_hint),
        password = true,
    )
    StageTextRow(
        icon = MobileIcons.Lock, label = stringResource(R.string.trakt_client_secret), value = c.clientSecret, onSave = vm::setClientSecret,
        placeholder = stringResource(if (c.usesBuiltInSecret) R.string.trakt_built_in else R.string.trakt_client_secret_hint),
        password = true,
    )
    StageSettingRow(title = stringResource(R.string.trakt_status), subtitle = traktStatusText(c, status))
}

/** The Status row: which credentials are in use, then the connection, then what the last call came to. */
@Composable
private fun traktStatusText(c: TraktConfig, status: TraktRepository.Status): String {
    if (!c.hasCredentials) return stringResource(R.string.trakt_status_no_client_id)
    val sep = stringResource(R.string.content_epg_bits_separator)
    val setup = listOf(
        stringResource(if (c.usesBuiltInId) R.string.trakt_status_built_in_id else R.string.trakt_status_typed_id),
        stringResource(if (c.hasSecret) R.string.trakt_status_with_secret else R.string.trakt_status_no_secret),
    ).joinToString(sep)
    val state = when {
        !c.connected -> stringResource(R.string.trakt_status_not_connected)
        !c.scrobbleOn && !c.watchlistOn -> stringResource(R.string.trakt_status_off)
        else -> when (status) {
            TraktRepository.Status.Idle ->
                if (c.username.isNotBlank()) stringResource(R.string.trakt_connected_as, c.username) else stringResource(R.string.trakt_status_ready)
            is TraktRepository.Status.Declined ->
                if (status.code == 0) stringResource(R.string.trakt_status_unreachable) else stringResource(R.string.trakt_connect_declined, status.code)
            TraktRepository.Status.TokenRejected -> stringResource(R.string.trakt_status_token_rejected)
            is TraktRepository.Status.HttpError -> stringResource(R.string.trakt_status_http, status.code)
            TraktRepository.Status.Unreachable -> stringResource(R.string.trakt_status_unreachable)
            TraktRepository.Status.NoMatches -> stringResource(R.string.trakt_status_no_matches)
            is TraktRepository.Status.Shown -> pluralStringResource(R.plurals.trakt_status_shown, status.titles, status.titles)
            is TraktRepository.Status.Scrobbled -> stringResource(R.string.trakt_status_scrobbled, status.title)
        }
    }
    return listOf(setup, state).joinToString(sep)
}
