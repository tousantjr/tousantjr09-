package tv.own.owntv.mobile.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLoginTest {
    @Test
    fun xtreamNeedsUsernameAndPassword() {
        assertTrue(QuickLogin.ready(QuickLoginKind.XTREAM, "john", "secret", "", "", false))
        assertFalse(QuickLogin.ready(QuickLoginKind.XTREAM, "john", "", "", "", false))
        assertFalse(QuickLogin.ready(QuickLoginKind.XTREAM, "  ", "secret", "", "", false))
    }

    @Test
    fun m3uNeedsUrl() {
        assertTrue(QuickLogin.ready(QuickLoginKind.M3U, "", "", "http://x/list.m3u", "", false))
        assertFalse(QuickLogin.ready(QuickLoginKind.M3U, "john", "secret", "", "", false))
    }

    @Test
    fun portalNeedsUrlAndValidMac() {
        assertTrue(QuickLogin.ready(QuickLoginKind.PORTAL, "", "", "", "http://host/c/", true))
        assertFalse(QuickLogin.ready(QuickLoginKind.PORTAL, "", "", "", "http://host/c/", false))
        assertFalse(QuickLogin.ready(QuickLoginKind.PORTAL, "", "", "", "", true))
    }
}
