package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePreloadTest {

    @Test
    fun `preload is off by default`() {
        assertFalse(LivePreloadSettings.DEFAULT)
    }

    @Test
    fun `never preloads on a one-connection playlist or a one-session host`() {
        assertFalse(preloadAllowed(maxConnections = 1, singleSession = false))
        assertFalse(preloadAllowed(maxConnections = 3, singleSession = true))
        assertTrue(preloadAllowed(maxConnections = 2, singleSession = false))
        // 0 = the panel never reported a limit.
        assertTrue(preloadAllowed(maxConnections = 0, singleSession = false))
    }

    @Test
    fun `warms the neighbour in the direction of the last zap`() {
        assertEquals(1, preloadDirection(null))
        assertEquals(1, preloadDirection(1))
        assertEquals(-1, preloadDirection(-1))
    }

    @Test
    fun `a preload never joins a burst of zaps`() {
        // The settle wait must outlast the window in which presses count as a burst (ZapTuneDelay.kt).
        assertTrue(PRELOAD_SETTLE_MS > ZAP_ISOLATED_PRESS_MS)
        assertTrue(PRELOAD_SETTLE_MS > ZAP_TUNE_DELAY_MS)
    }
}
