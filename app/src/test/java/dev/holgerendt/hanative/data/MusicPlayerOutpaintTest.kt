package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerOutpaintTest {
    @Test
    fun canvasPlaces512CoverTopCenterOnPortraitPlayer() {
        val canvas = MusicPlayerOutpaint.canvasForSource(512, 512)
        assertEquals(1024, canvas.outWidth)
        assertEquals(1280, canvas.outHeight)
        assertEquals(256, canvas.x)
        assertTrue(canvas.y >= 0)
        assertTrue(canvas.y + 512 <= canvas.outHeight)
        val pads = canvas.toPads(512, 512)
        assertEquals(256, pads.padLeft)
        assertEquals(256, pads.padRight)
        assertEquals(canvas.y, pads.padTop)
        assertEquals(canvas.outHeight - canvas.y - 512, pads.padBottom)
    }

    @Test
    fun coverWidthIsHalfCanvas() {
        val canvas = MusicPlayerOutpaint.canvasForSource(400, 400)
        assertEquals(800, canvas.outWidth)
        assertEquals((400f / MusicPlayerOutpaint.COVER_WIDTH_FRAC).toInt(), canvas.outWidth)
        assertEquals((canvas.outWidth - 400) / 2, canvas.x)
    }

    @Test
    fun layoutTagMatchesMediagenFormat() {
        val pads = OutpaintPadLayout(10, 20, 30, 40)
        assertEquals("pads:10,20,30,40\u0000", pads.layoutTag().toString(Charsets.UTF_8))
        assertEquals("10,20,30,40", pads.headerPad())
        assertEquals(pads, OutpaintPadLayout.parseHeader("10,20,30,40"))
    }
}
