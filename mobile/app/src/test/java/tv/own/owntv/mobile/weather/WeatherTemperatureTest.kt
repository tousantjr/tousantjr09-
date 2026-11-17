package tv.own.owntv.mobile.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTemperatureTest {
    @Test fun celsiusRounds() {
        assertEquals(21, WeatherTemperature.display(20.6f, TemperatureUnit.CELSIUS))
        assertEquals(-3, WeatherTemperature.display(-2.6f, TemperatureUnit.CELSIUS))
    }

    @Test fun fahrenheitConverts() {
        assertEquals(32, WeatherTemperature.display(0f, TemperatureUnit.FAHRENHEIT))
        assertEquals(212, WeatherTemperature.display(100f, TemperatureUnit.FAHRENHEIT))
        assertEquals(72, WeatherTemperature.display(22.2f, TemperatureUnit.FAHRENHEIT))
    }

    @Test fun unitFollowsSetting() {
        assertEquals(TemperatureUnit.FAHRENHEIT, WeatherTemperature.unitFor(true))
        assertEquals(TemperatureUnit.CELSIUS, WeatherTemperature.unitFor(false))
    }
}
