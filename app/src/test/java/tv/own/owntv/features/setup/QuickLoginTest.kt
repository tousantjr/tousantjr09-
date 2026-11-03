package tv.own.owntv.features.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLoginTest {
    @Test
    fun needsUsernameAndPassword() {
        assertTrue(QuickLogin.ready("john", "secret"))
        assertFalse(QuickLogin.ready("john", ""))
        assertFalse(QuickLogin.ready("  ", "secret"))
        assertFalse(QuickLogin.ready("", ""))
    }
}
