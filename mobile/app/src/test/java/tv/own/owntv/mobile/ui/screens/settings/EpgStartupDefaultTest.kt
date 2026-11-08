package tv.own.owntv.mobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh

class EpgStartupDefaultTest {
    @Test
    fun `the default is refresh on startup`() {
        assertEquals(EpgAutoRefresh.STARTUP, EpgStartupDefault.DEFAULT.mode)
    }

    @Test
    fun `a guide with no setting always gets the default`() {
        assertTrue(EpgStartupDefault.shouldSwitch(null, firstPass = true))
        assertTrue(EpgStartupDefault.shouldSwitch(null, firstPass = false))
    }

    @Test
    fun `an Off guide is switched on the one-time pass only`() {
        assertTrue(EpgStartupDefault.shouldSwitch(EpgRefresh.OFF, firstPass = true))
        assertFalse(EpgStartupDefault.shouldSwitch(EpgRefresh.OFF, firstPass = false))
    }

    @Test
    fun `a guide the user set to a schedule is left alone`() {
        assertFalse(EpgStartupDefault.shouldSwitch(EpgRefresh(EpgAutoRefresh.HOURS_12), firstPass = true))
        assertFalse(EpgStartupDefault.shouldSwitch(EpgRefresh(EpgAutoRefresh.MANUAL, 3), firstPass = true))
    }
}
