package tv.own.owntv.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdateTest {
    @Test
    fun `a version never announced is news`() {
        assertTrue(BackgroundUpdate.shouldNotify("1.1.10", null))
    }

    @Test
    fun `the version already announced is not news again`() {
        assertFalse(BackgroundUpdate.shouldNotify("1.1.10", "1.1.10"))
        assertFalse(BackgroundUpdate.shouldNotify("v1.1.10", "1.1.10"))
    }

    @Test
    fun `a newer release after an announced one is news`() {
        assertTrue(BackgroundUpdate.shouldNotify("1.1.11", "1.1.10"))
    }

    @Test
    fun `a blank version is never announced`() {
        assertFalse(BackgroundUpdate.shouldNotify("", null))
        assertFalse(BackgroundUpdate.shouldNotify(null, "1.1.10"))
    }

    @Test
    fun `normalize strips prefix and spaces`() {
        assertEquals("1.1.10", BackgroundUpdate.normalize(" V1.1.10 "))
    }
}
