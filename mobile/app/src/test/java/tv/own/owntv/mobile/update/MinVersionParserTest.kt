package tv.own.owntv.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinVersionParserTest {
    private val app = MinVersionParser.APP_PHONE

    @Test fun olderBuildGetsNotice() {
        val n = MinVersionParser.parse("""{"phone":"1.0.19","message":"Please update"}""", app, "1.0.9")
        assertEquals(MinVersionNotice("1.0.19", "Please update"), n)
    }

    @Test fun sameOrNewerBuildIsFine() {
        assertNull(MinVersionParser.parse("""{"phone":"1.0.19"}""", app, "1.0.19"))
        assertNull(MinVersionParser.parse("""{"phone":"1.0.19"}""", app, "1.1.0"))
    }

    @Test fun blankMessageUsesAppWording() {
        assertEquals(MinVersionNotice("1.0.20", null), MinVersionParser.parse("""{"phone":"v1.0.20","message":"  "}""", app, "1.0.19"))
    }

    @Test fun otherAppsKeyIgnored() {
        assertNull(MinVersionParser.parse("""{"tv":"9.9.9"}""", app, "1.0.0"))
    }

    @Test fun blankMissingOrBrokenMeansNoMinimum() {
        assertNull(MinVersionParser.parse("""{"phone":""}""", app, "1.0.0"))
        assertNull(MinVersionParser.parse(null, app, "1.0.0"))
        assertNull(MinVersionParser.parse("not json", app, "1.0.0"))
    }

    @Test fun localDebugBuildNeverBlocked() {
        assertNull(MinVersionParser.parse("""{"phone":"5.0.0"}""", app, "99.99.99"))
    }
}
