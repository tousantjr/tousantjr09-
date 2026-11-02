package tv.own.owntv.mobile.ui.screens.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/** Anti-freeze's pure rules: which channels can switch, the URL swap, the per-channel key and the rebuffer window. */
class AntiFreezeTest {

    private fun channel(url: String) = ChannelEntity(id = 1, sourceId = 7, categoryId = 1, name = "Ch", streamUrl = url, remoteId = "1")
    private fun source(type: SourceType, preferHls: Boolean = false) =
        SourceEntity(id = 7, name = "S", type = type, url = "http://x", preferHls = preferHls)

    @Test
    fun onlyXtreamWithAKnownExtensionIsSwitchable() {
        assertTrue(AntiFreeze.switchable(channel("http://x/live/u/p/1.ts"), source(SourceType.XTREAM)))
        assertTrue(AntiFreeze.switchable(channel("http://x/live/u/p/1.m3u8?t=1"), source(SourceType.XTREAM)))
        assertFalse(AntiFreeze.switchable(channel("http://x/live/u/p/1"), source(SourceType.XTREAM)))
        assertFalse(AntiFreeze.switchable(channel("http://x/1.ts"), source(SourceType.M3U)))
        assertFalse(AntiFreeze.switchable(channel("http://x/1.ts"), source(SourceType.STALKER)))
        assertFalse(AntiFreeze.switchable(channel("http://x/1.ts"), null))
    }

    @Test
    fun withFormatSwapsTheExtensionAndKeepsTheQuery() {
        val (c, s) = AntiFreeze.withFormat(channel("http://x/live/u/p/1.ts?token=a"), source(SourceType.XTREAM), LiveFormat.HLS)
        assertEquals("http://x/live/u/p/1.m3u8?token=a", c.streamUrl)
        assertTrue(s.preferHls)
        val (back, s2) = AntiFreeze.withFormat(c, s, LiveFormat.TS)
        assertEquals("http://x/live/u/p/1.ts?token=a", back.streamUrl)
        assertFalse(s2.preferHls)
    }

    @Test
    fun currentFormatFollowsPreferHls() {
        assertEquals(LiveFormat.TS, AntiFreeze.currentFormat(channel("http://x/1.ts"), source(SourceType.XTREAM)))
        assertEquals(LiveFormat.HLS, AntiFreeze.currentFormat(channel("http://x/1.ts"), source(SourceType.XTREAM, preferHls = true)))
        assertEquals(LiveFormat.HLS, AntiFreeze.currentFormat(channel("http://x/1.m3u8"), source(SourceType.XTREAM)))
        assertEquals(LiveFormat.TS, AntiFreeze.other(LiveFormat.HLS))
    }

    @Test
    fun bothFormatsShareOneChannelKey() {
        assertEquals(
            AntiFreeze.channelKey(channel("http://x/live/u/p/1.ts")),
            AntiFreeze.channelKey(channel("http://x/live/u/p/1.m3u8?t=2")),
        )
    }

    @Test
    fun threeRebuffersInsideTheWindowCountAsFrozen() {
        val counter = RebufferCounter()
        assertFalse(counter.onRebuffer(0))
        assertFalse(counter.onRebuffer(10_000))
        assertTrue(counter.onRebuffer(20_000))
    }

    @Test
    fun spreadOutRebuffersDoNot() {
        val counter = RebufferCounter()
        assertFalse(counter.onRebuffer(0))
        assertFalse(counter.onRebuffer(20_000))
        assertFalse(counter.onRebuffer(40_000))
        assertFalse(counter.onRebuffer(60_000))
        counter.reset()
        assertFalse(counter.onRebuffer(61_000))
    }
}
