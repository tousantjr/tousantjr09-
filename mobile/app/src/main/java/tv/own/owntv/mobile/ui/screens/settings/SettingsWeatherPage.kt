package tv.own.owntv.mobile.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.stage.StageTextRow
import java.util.Locale

/*
 * Settings › Appearance › Weather: whether Home shows the weather pill, where it is measured and in which
 * unit. Left blank, core looks the place up from the network address — on a phone that is wherever the
 * operator routes through — so the phone's own coarse position is offered as the better answer.
 */

@Composable
fun SettingsWeatherPage(modifier: Modifier = Modifier, vm: SettingsViewModel = koinViewModel()) {
    val enabled = vm.settings.weatherEnabled.pref(true)
    val fahrenheit = vm.settings.weatherFahrenheit.pref(false)
    val stored = vm.settings.weatherLocation.pref("")
    val context = LocalContext.current
    // A stored "52.530,13.400" is a fix this page wrote; anything else is a city the user typed.
    val usingDeviceLocation = isCoordinatePair(stored)

    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val fix = if (granted) context.lastKnownCoarseFix() else null
        if (fix != null) vm.edit { setWeatherLocation(fix) }
    }
    val celsiusLabel = stringResource(R.string.settings_degree_celsius)
    val fahrenheitLabel = stringResource(R.string.settings_degree_fahrenheit)

    SettingsStagePage(modifier) {
        item(key = "note") { PageNote(stringResource(R.string.settings_weather_description_root)) }
        item(key = "enabled") {
            SettingItemRow(
                switchItem(
                    "weather_on", MobileIcons.WbSunny, stringResource(R.string.settings_show_weather),
                    stringResource(R.string.settings_show_weather_description), enabled,
                ) { on -> vm.edit { setWeatherEnabled(on) } },
                {}, null,
            )
        }
        if (!enabled) return@SettingsStagePage
        item(key = "device_location") {
            SettingItemRow(
                switchItem(
                    "weather_device_location", MobileIcons.Phone, stringResource(R.string.settings_use_device_location),
                    stringResource(R.string.settings_use_device_location_description), usingDeviceLocation,
                ) { on ->
                    // Off clears the fix, so yesterday's coordinates are not left to be wrong in a new city.
                    if (!on) vm.edit { setWeatherLocation("") } else requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                {}, null,
            )
        }
        if (!usingDeviceLocation) {
            item(key = "city") {
                StageTextRow(
                    MobileIcons.Search, stringResource(R.string.settings_custom_location), stored,
                    { city -> vm.edit { setWeatherLocation(city.trim()) } },
                    placeholder = stringResource(R.string.settings_location_hint),
                )
            }
            item(key = "city_note") { PageNote(stringResource(R.string.settings_custom_location_description)) }
        }
        item(key = "unit") {
            SettingItemRow(
                SettingItem(
                    "weather_unit", MobileIcons.Tune, stringResource(R.string.settings_temperature_unit),
                    stringResource(R.string.settings_temperature_description),
                    SettingValue.Segmented(listOf(celsiusLabel, fahrenheitLabel), if (fahrenheit) 1 else 0) { i ->
                        vm.edit { setWeatherFahrenheit(i == 1) }
                    },
                ),
                {}, null,
            )
        }
    }
}

/** Core reads a `"lat,lon"` location straight through instead of geocoding it. */
internal fun isCoordinatePair(value: String): Boolean {
    val parts = value.split(',')
    return parts.size == 2 && parts.all { it.trim().toDoubleOrNull() != null }
}

/**
 * The last position the system already has, as `"lat,lon"`, or null when there is none. Weather is
 * measured over tens of kilometres, so a cached coarse fix is as good as a fresh one and wakes no radio.
 */
private fun Context.lastKnownCoarseFix(): String? {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val fix = runCatching {
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull() ?: return null
    // Locale.ROOT: this is a value for core's parser, not text for a person.
    return String.format(Locale.ROOT, "%.3f,%.3f", fix.latitude, fix.longitude)
}
