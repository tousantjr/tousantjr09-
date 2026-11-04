package tv.own.owntv.features.intro

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroTest {
    private val launcher = setOf(Intent.CATEGORY_LAUNCHER)
    private val tvLauncher = setOf(Intent.CATEGORY_LEANBACK_LAUNCHER)

    @Test
    fun playsOnNormalLauncherStart() {
        assertTrue(Intro.shouldPlay(true, Intent.ACTION_MAIN, launcher, hasData = false, hasExtras = false))
        assertTrue(Intro.shouldPlay(true, Intent.ACTION_MAIN, tvLauncher, hasData = false, hasExtras = false))
    }

    @Test
    fun skipsRestoresDeepLinksAndNotificationTaps() {
        assertFalse(Intro.shouldPlay(false, Intent.ACTION_MAIN, launcher, hasData = false, hasExtras = false))
        assertFalse(Intro.shouldPlay(true, Intent.ACTION_VIEW, launcher, hasData = true, hasExtras = false))
        assertFalse(Intro.shouldPlay(true, Intent.ACTION_MAIN, launcher, hasData = false, hasExtras = true))
        assertFalse(Intro.shouldPlay(true, Intent.ACTION_MAIN, null, hasData = false, hasExtras = false))
        assertFalse(Intro.shouldPlay(true, null, launcher, hasData = false, hasExtras = false))
    }
}
