package tv.own.owntv.features.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh

class EpgBackgroundRefreshTest {
    private val hour = 3_600_000L
    private val now = 100 * hour
    private val startup = EpgRefresh(EpgAutoRefresh.STARTUP)

    @Test
    fun `an Off or unset guide is never refreshed in the background`() {
        assertFalse(EpgBackgroundRefresh.isDue(EpgRefresh.OFF, null, now))
        assertFalse(EpgBackgroundRefresh.isDue(null, null, now))
    }

    @Test
    fun `a guide never synced is due`() {
        assertTrue(EpgBackgroundRefresh.isDue(startup, null, now))
    }

    @Test
    fun `a guide synced within the interval is not due`() {
        assertFalse(EpgBackgroundRefresh.isDue(startup, now - hour, now))
    }

    @Test
    fun `a guide synced three hours ago is due`() {
        assertTrue(EpgBackgroundRefresh.isDue(startup, now - 3 * hour, now))
        assertTrue(EpgBackgroundRefresh.isDue(EpgRefresh(EpgAutoRefresh.HOURS_24), now - 3 * hour, now))
    }
}
