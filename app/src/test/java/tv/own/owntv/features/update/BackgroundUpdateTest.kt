package tv.own.owntv.features.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUpdateTest {
    @Test
    fun `a version never announced is news`() {
        assertTrue(BackgroundUpdate.shouldNotify("1.0.18", null))
    }

    @Test
    fun `the version already announced is not news again`() {
        assertFalse(BackgroundUpdate.shouldNotify("1.0.18", "1.0.18"))
    }

    @Test
    fun `a v prefix or spaces do not make it a different release`() {
        assertFalse(BackgroundUpdate.shouldNotify("v1.0.18", "1.0.18"))
        assertFalse(BackgroundUpdate.shouldNotify(" V1.0.18 ", "v1.0.18"))
    }

    @Test
    fun `a newer release after an announced one is news`() {
        assertTrue(BackgroundUpdate.shouldNotify("1.0.19", "1.0.18"))
    }

    @Test
    fun `a blank version is never announced`() {
        assertFalse(BackgroundUpdate.shouldNotify("", null))
        assertFalse(BackgroundUpdate.shouldNotify(null, null))
        assertFalse(BackgroundUpdate.shouldNotify("v", null))
    }

    @Test
    fun `normalize strips the prefix only`() {
        assertEquals("1.0.18", BackgroundUpdate.normalize("v1.0.18"))
        assertEquals("", BackgroundUpdate.normalize(null))
    }
}
