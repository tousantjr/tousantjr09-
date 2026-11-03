package tv.own.owntv.mobile.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Test

class VodGlowTest {
    @Test fun `halo breathes between its limits`() {
        assertEquals(VodGlow.MIN, VodGlow.alpha(-1f), 0.0001f)
        assertEquals(VodGlow.MAX, VodGlow.alpha(2f), 0.0001f)
        assertEquals((VodGlow.MIN + VodGlow.MAX) / 2, VodGlow.alpha(0.5f), 0.0001f)
    }
}
