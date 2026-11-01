package tv.own.owntv.features.sports.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trimmed from live ESPN summaries (NFL KC @ DEN and Premier League HUL @ ARS, November 2026). */
class MatchPreviewParserTest {

    private val nfl = """
        {"header":{"competitions":[{"competitors":[
          {"homeAway":"home","team":{"id":"7","abbreviation":"DEN"},"record":[{"type":"total","summary":"5-2"},{"type":"home","summary":"3-0"}]},
          {"homeAway":"away","team":{"id":"12","abbreviation":"KC"},"record":[{"type":"total","summary":"5-1"},{"type":"road","summary":"2-1"}]}]}]},
         "predictor":{"homeTeam":{"id":"7","gameProjection":"44"},"awayTeam":{"id":"12","gameProjection":"55.7"}},
         "article":{"type":"Preview","headline":"AFC West lead at stake","story":"DENVER -- The lead is at stake as <a href=\"x\">Patrick Mahomes</a> returns.\n\n\r\n   Nix &amp; the Broncos are 5-2.\n\n"},
         "gameInfo":{"venue":{"fullName":"Empower Field at Mile High","address":{"city":"Denver"}},"weather":{"temperature":63,"precipitation":48}},
         "injuries":[{"team":{"id":"7"},"injuries":[{"status":"Injured Reserve","athlete":{"displayName":"RJ Harvey","position":{"abbreviation":"RB"}},"details":{"type":"Knee"}}]},
                     {"team":{"id":"12"},"injuries":[]}]}
    """.trimIndent()

    private val epl = """
        {"header":{"competitions":[{"competitors":[
          {"homeAway":"home","team":{"id":"359","abbreviation":"ARS"},"record":[{"type":"total","summary":"8-0-1"}]},
          {"homeAway":"away","team":{"id":"306","abbreviation":"HUL"},"record":[{"type":"total","summary":"2-5-2"}]}]}]},
         "article":null,
         "gameInfo":{"venue":{"fullName":"Emirates Stadium","address":{"city":"London"}}},
         "seasonseries":[{"type":"head-to-head","summary":"ARS leads series 4-0-1","events":[
           {"date":"2016-09-17T14:00:00Z","statusType":{"completed":true},"competitors":[{"homeAway":"home","team":{"abbreviation":"HUL"},"score":"1"},{"homeAway":"away","team":{"abbreviation":"ARS"},"score":"4"}]},
           {"date":"2017-02-11T12:30:00Z","statusType":{"completed":true},"competitors":[{"homeAway":"home","team":{"abbreviation":"ARS"},"score":"2"},{"homeAway":"away","team":{"abbreviation":"HUL"},"score":"0"}]},
           {"date":"2026-11-07T15:00:00Z","statusType":{"completed":false},"competitors":[{"homeAway":"home","team":{"abbreviation":"ARS"},"score":"0"},{"homeAway":"away","team":{"abbreviation":"HUL"},"score":"0"}]}]}]}
    """.trimIndent()

    @Test
    fun nflPreviewReadsEveryPart() {
        val p = req(MatchPreviewParser.parse(nfl))
        assertEquals("AFC West lead at stake", p.headline)
        assertEquals(listOf("DENVER -- The lead is at stake as Patrick Mahomes returns.", "Nix & the Broncos are 5-2."), p.paragraphs)
        assertEquals("DEN", p.home?.abbreviation)
        assertEquals("5-2", p.home?.record)
        assertEquals("3-0", p.home?.venueRecord)
        assertEquals("2-1", p.away?.venueRecord)
        assertEquals(44.0, p.home?.winChance!!, 0.001)
        assertEquals(55.7, p.away?.winChance!!, 0.001)
        assertEquals(63, p.temperatureF)
        assertEquals(48, p.rainChance)
        assertEquals("Empower Field at Mile High, Denver", p.venue)
        assertEquals(PreviewInjury("RJ Harvey", "RB", "Injured Reserve", "Knee"), p.home?.injuries?.single())
        assertTrue(p.away?.injuries.isNullOrEmpty())
    }

    @Test
    fun footballHeadToHeadSkipsTheUnplayedMeetingAndSortsNewestFirst() {
        val p = req(MatchPreviewParser.parse(epl))
        assertNull(p.headline)
        assertNull(p.home?.winChance)
        assertEquals("ARS leads series 4-0-1", p.headToHead)
        assertEquals(
            listOf(PreviewMeeting("2017-02-11", "ARS", "2", "HUL", "0"), PreviewMeeting("2016-09-17", "HUL", "1", "ARS", "4")),
            p.meetings,
        )
    }

    @Test
    fun nothingToShowIsNull() {
        assertNull(MatchPreviewParser.parse("""{"gameInfo":{"venue":{"fullName":"Somewhere"}}}"""))
        assertNull(MatchPreviewParser.parse("{}"))
    }

    @Test
    fun paragraphsAreCapped() {
        val story = (1..10).joinToString("\n\n") { "Paragraph $it." }
        assertEquals(MatchPreviewParser.MAX_PARAGRAPHS, MatchPreviewParser.paragraphs(story).size)
    }

    private fun <T> req(v: T?): T = v ?: throw AssertionError("expected a value")
}
