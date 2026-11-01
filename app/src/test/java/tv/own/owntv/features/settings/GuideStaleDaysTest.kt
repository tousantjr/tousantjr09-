package tv.own.owntv.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideStaleDaysTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun neverSyncedIsNotStale() = assertNull(guideStaleDays(null, now))

    @Test fun freshFeedIsNotStale() {
        assertNull(guideStaleDays(now - day, now))
        assertNull(guideStaleDays(now - 3 * day, now))
    }

    @Test fun pastThreeDaysCountsWholeDays() {
        assertEquals(3, guideStaleDays(now - 3 * day - 1, now))
        assertEquals(9, guideStaleDays(now - 9 * day - day / 2, now))
    }
}
