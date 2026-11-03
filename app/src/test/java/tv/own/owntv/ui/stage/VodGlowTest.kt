package tv.own.owntv.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VodGlowTest {
    @Test fun `halo breathes between its limits and is brighter focused`() {
        assertEquals(VodGlow.IDLE_MIN, VodGlow.alpha(false, 0f), 0.0001f)
        assertEquals(VodGlow.IDLE_MAX, VodGlow.alpha(false, 1f), 0.0001f)
        assertEquals(VodGlow.FOCUSED_MAX, VodGlow.alpha(true, 5f), 0.0001f)
        assertTrue(VodGlow.alpha(true, 0f) > VodGlow.alpha(false, 1f))
    }
}
