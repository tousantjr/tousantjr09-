package tv.own.owntv.mobile.announce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AnnouncementParserTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val app = AnnouncementParser.APP_TV

    @Test fun fullMessage() {
        val a = AnnouncementParser.parse("""{"id":"a1","title":"Hi","message":"New channels"}""", app, today)
        assertEquals(Announcement("a1", "Hi", "New channels"), a)
    }

    @Test fun blankIdOrMessageIsNothing() {
        assertNull(AnnouncementParser.parse("""{"id":"","message":"x"}""", app, today))
        assertNull(AnnouncementParser.parse("""{"id":"a","message":"  "}""", app, today))
    }

    @Test fun brokenOrMissingFileIsNothing() {
        assertNull(AnnouncementParser.parse("<html>404</html>", app, today))
        assertNull(AnnouncementParser.parse(null, app, today))
    }

    @Test fun appsFilter() {
        val phoneOnly = """{"id":"a","message":"m","apps":["phone"]}"""
        assertNull(AnnouncementParser.parse(phoneOnly, app, today))
        assertEquals("a", AnnouncementParser.parse(phoneOnly, AnnouncementParser.APP_PHONE, today)?.id)
    }

    @Test fun untilDate() {
        val json = """{"id":"a","message":"m","until":"2026-11-01"}"""
        assertEquals("a", AnnouncementParser.parse(json, app, today)?.id)
        assertNull(AnnouncementParser.parse(json, app, today.plusDays(1)))
        assertEquals("a", AnnouncementParser.parse("""{"id":"a","message":"m","until":"soon"}""", app, today)?.id)
    }
}
