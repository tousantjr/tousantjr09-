package tv.own.owntv.features.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbRulesTest {
    @Test fun `server box accepts plain host, unc and smb url`() {
        assertEquals(Triple("192.168.1.20", "Movies", ""), SmbRules.parseServer("192.168.1.20", "Movies"))
        assertEquals(Triple("NAS", "Movies", "Kids"), SmbRules.parseServer("\\\\NAS\\Movies\\Kids", ""))
        assertEquals(Triple("nas", "Movies", "Kids/4K"), SmbRules.parseServer("smb://nas/Movies/Kids/4K/", ""))
        assertEquals(Triple("nas", "Films", "Kids"), SmbRules.parseServer("nas", "/Films/Kids/"))
    }

    @Test fun `host port defaults to 445`() {
        assertEquals("nas.local" to 445, SmbRules.hostPort("nas.local"))
        assertEquals("nas.local" to 4450, SmbRules.hostPort("nas.local:4450"))
        assertEquals("nas.local:99999" to 445, SmbRules.hostPort("nas.local:99999"))
    }

    @Test fun `range header`() {
        assertNull(SmbRules.parseRange(null, 100))
        assertEquals(0L..99L, SmbRules.parseRange("bytes=0-", 100))
        assertEquals(10L..19L, SmbRules.parseRange("bytes=10-19", 100))
        assertEquals(50L..99L, SmbRules.parseRange("bytes=50-500", 100))
        assertEquals(90L..99L, SmbRules.parseRange("bytes=-10", 100))
        assertTrue(SmbRules.parseRange("bytes=100-", 100)!!.isEmpty())
        assertNull(SmbRules.parseRange("items=0-5", 100))
        assertNull(SmbRules.parseRange("bytes=20-10", 100))
    }

    @Test fun `paths join and go up`() {
        assertEquals("a/b/c", SmbRules.join("/a/", "b\\c", ""))
        assertEquals("a/b", SmbRules.parent("a/b/c"))
        assertEquals("", SmbRules.parent("a"))
        assertEquals("a\\b", SmbRules.smbPath("/a/b/"))
    }

    @Test fun `playable files and hidden names`() {
        assertTrue(SmbRules.isVideo("Film.MKV"))
        assertTrue(SmbRules.isPlayable("song.flac"))
        assertFalse(SmbRules.isPlayable("notes.txt"))
        assertEquals("video/x-matroska", SmbRules.mimeType("a.mkv"))
        assertTrue(SmbRules.hidden("@eaDir"))
        assertTrue(SmbRules.hidden("IPC$"))
        assertFalse(SmbRules.hidden("Movies"))
    }

    @Test fun `folders first then natural order`() {
        val list = listOf(
            SmbEntry("Episode 10.mkv", "e10", false, 1),
            SmbEntry("episode 2.mkv", "e2", false, 1),
            SmbEntry("Season 1", "s1", true, 0),
        ).sortedWith(SmbRules.order)
        assertEquals(listOf("Season 1", "episode 2.mkv", "Episode 10.mkv"), list.map { it.name })
    }
}
