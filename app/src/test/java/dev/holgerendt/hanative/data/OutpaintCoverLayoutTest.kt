package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutpaintCoverLayoutTest {
    @Test
    fun playerPadHorizonMatchesFloatingCoverRatherThanCardBounds() {
        val pads = MusicPlayerOutpaint.padsForSource(512, 512)
        val w = pads.outWidth(512)
        val h = pads.outHeight(512)
        val layout = outpaintCoverLayout(w, h, pads.padLeft, pads.padTop, pads.padRight, pads.padBottom)!!
        val placement = alignedOutpaintPlacement(w, h, layout, 520f, 196f, 220f)
        assertEquals(162f, placement.left + pads.padLeft * placement.scale, 0.001f)
        assertEquals(12f, placement.top + pads.padTop * placement.scale, 0.001f)
        // A horizon 80% down the original must meet precisely at the foreground edge.
        assertEquals(168.8f, placement.top + (pads.padTop + 512 * 0.8f) * placement.scale, 0.001f)
        assertEquals(196f, 512 * placement.scale, 0.001f)
    }

    @Test
    fun asymmetricLegacyPadsAlsoRegisterAtDoubleDensity() {
        val layout = outpaintCoverLayout(1000, 900, 100, 40, 300, 260)!!
        val placement = alignedOutpaintPlacement(1000, 900, layout, 1040f, 392f, 440f)
        assertEquals(324f, placement.left + 100 * placement.scale, 0.001f)
        assertEquals(24f, placement.top + 40 * placement.scale, 0.001f)
        assertEquals(392f, 600 * placement.scale, 0.001f)
    }

    @Test
    fun rectangularSourceMatchesCenteredSquareCropWithoutDistortion() {
        val layout = outpaintCoverLayout(1200, 1000, 200, 100, 200, 500)!!
        val placement = alignedOutpaintPlacement(1200, 1000, layout, 520f, 196f, 220f)
        // 800x400 source is cropped to its centered 400x400 region by MusicCover.
        assertEquals(162f, placement.left + (200 + 200) * placement.scale, 0.001f)
        assertEquals(12f, placement.top + 100 * placement.scale, 0.001f)
        assertEquals(196f, 400 * placement.scale, 0.001f)
    }

    @Test
    fun fractionsFor512SquareMatchWorkflowPads() {
        // 512 + 256+256 = 1024 wide; 512 + 128+128 = 768 tall
        val layout = outpaintCoverLayout(1024, 768)
        assertNotNull(layout)
        assertEquals(1024f / 768f, layout!!.outAspectRatio, 0.0001f)
        assertEquals(256f / 1024f, layout.coverLeftFrac, 0.0001f)
        assertEquals(128f / 768f, layout.coverTopFrac, 0.0001f)
        assertEquals(512f / 1024f, layout.coverWidthFrac, 0.0001f)
        assertEquals(512f / 768f, layout.coverHeightFrac, 0.0001f)
    }

    @Test
    fun rejectsDegenerateBounds() {
        assertNull(outpaintCoverLayout(100, 100)) // pads exceed size
        assertNull(outpaintCoverLayout(0, 768))
    }
}
