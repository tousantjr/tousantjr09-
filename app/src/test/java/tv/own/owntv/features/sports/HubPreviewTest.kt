package tv.own.owntv.features.sports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubPreviewTest {
    private val now = 1_000_000_000L

    @Test
    fun liveGamePreviews() = assertTrue(SportsViewModel.previewable(isLive = true, startMs = now - 3_600_000L, now = now))

    @Test
    fun kickOffWithinLeadPreviews() = assertTrue(SportsViewModel.previewable(isLive = false, startMs = now + 10 * 60_000L, now = now))

    @Test
    fun kickOffAtLeadEdgePreviews() = assertTrue(SportsViewModel.previewable(isLive = false, startMs = now + SportsViewModel.PREVIEW_LEAD_MS, now = now))

    @Test
    fun laterGameDoesNot() = assertFalse(SportsViewModel.previewable(isLive = false, startMs = now + SportsViewModel.PREVIEW_LEAD_MS + 1, now = now))

    @Test
    fun finishedGameDoesNot() = assertFalse(SportsViewModel.previewable(isLive = false, startMs = now - 60_000L, now = now))
}
