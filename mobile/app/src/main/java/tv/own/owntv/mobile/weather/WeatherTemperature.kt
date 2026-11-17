package tv.own.owntv.mobile.weather

import kotlin.math.roundToInt

/** The unit a temperature is shown in. */
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/**
 * The one place a weather temperature becomes the number on screen. Core's weather comes from
 * Open-Meteo in Celsius (`WeatherInfo.temperatureC`), so this is the only conversion there is.
 */
object WeatherTemperature {

    /** °F = °C × 9/5 + 32. */
    fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f

    /** Whole degrees in [unit] — rounded, not truncated. */
    fun display(celsius: Float, unit: TemperatureUnit): Int = when (unit) {
        TemperatureUnit.CELSIUS -> celsius.roundToInt()
        TemperatureUnit.FAHRENHEIT -> celsiusToFahrenheit(celsius).roundToInt()
    }

    fun unitFor(prefersFahrenheit: Boolean): TemperatureUnit =
        if (prefersFahrenheit) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
}
