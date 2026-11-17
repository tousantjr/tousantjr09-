package tv.own.owntv.mobile.ui.screens.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewGridMathTest {
    @Test fun everyModeGivesOneRectPerTile() {
        for (count in 1..4) for (mode in MultiviewLayoutMode.entries) {
            assertEquals(count, MultiviewGridMath.resolvedRects(mode, count, null, 1600f, 900f).size)
            assertEquals(count, MultiviewGridMath.resolvedRects(mode, count, null, 400f, 800f).size)
        }
    }

    @Test fun rectsStayInsideTheArea() {
        for (count in 1..4) for (mode in MultiviewLayoutMode.entries) {
            MultiviewGridMath.resolvedRects(mode, count, 0, 1600f, 900f).forEach {
                assertTrue(it.left >= -0.01f && it.top >= -0.01f && it.right <= 1600.01f && it.bottom <= 900.01f)
            }
        }
    }

    @Test fun spotlitTileGetsTheBiggestRect() {
        val rects = MultiviewGridMath.resolvedRects(MultiviewLayoutMode.Auto, 4, 2, 1600f, 900f)
        val biggest = rects.indices.maxBy { rects[it].width * rects[it].height }
        assertEquals(2, biggest)
    }

    @Test fun stackedIsOneColumn() {
        val rects = MultiviewGridMath.resolvedRects(MultiviewLayoutMode.Stacked, 3, null, 1600f, 900f)
        assertTrue(rects.all { it.width == 1600f })
    }

    @Test fun unknownKeyFallsBackToAuto() {
        assertEquals(MultiviewLayoutMode.Auto, MultiviewLayoutMode.from("nope"))
        assertEquals(MultiviewLayoutMode.Stacked, MultiviewLayoutMode.from("stacked"))
    }
}
