package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutpaintCoverLayoutTest {
    @Test
    fun playerPadFillsCardWhilePinningCoverCenter() {
        val pads = MusicPlayerOutpaint.padsForSource(512, 512)
        val w = pads.outWidth(512)
        val h = pads.outHeight(512)
        val layout = outpaintCoverLayout(w, h, pads.padLeft, pads.padTop, pads.padRight, pads.padBottom)!!
        val cardW = 520f
        val cardH = 650f
        val coverSize = 196f
        val stageH = 220f
        val placement = alignedOutpaintPlacement(w, h, layout, cardW, coverSize, stageH, cardH)

        val coverCx = cardW / 2f
        val coverCy = stageH / 2f
        val coverBmpCx = w * (layout.coverLeftFrac + layout.coverWidthFrac / 2f)
        val coverBmpCy = h * (layout.coverTopFrac + layout.coverHeightFrac / 2f)
        assertEquals(coverCx, placement.left + coverBmpCx * placement.scale, 0.001f)
        assertEquals(coverCy, placement.top + coverBmpCy * placement.scale, 0.001f)

        // Full-bleed: pad covers the card (no letterboxing).
        assertTrue(placement.left <= 0.001f)
        assertTrue(placement.top <= 0.001f)
        assertTrue(placement.left + w * placement.scale >= cardW - 0.001f)
        assertTrue(placement.top + h * placement.scale >= cardH - 0.001f)

        // Baked cover may grow past the sharp 196.dp hero; never shrink below it.
        assertTrue(512 * placement.scale >= coverSize - 0.001f)
    }

    @Test
    fun asymmetricLegacyPadsAlsoFillAndPinCoverCenter() {
        val layout = outpaintCoverLayout(1000, 900, 100, 40, 300, 260)!!
        val cardW = 1040f
        val cardH = 900f
        val coverSize = 392f
        val stageH = 440f
        val placement = alignedOutpaintPlacement(1000, 900, layout, cardW, coverSize, stageH, cardH)
        val coverCx = cardW / 2f
        val coverCy = stageH / 2f
        val coverBmpCx = 1000 * (layout.coverLeftFrac + layout.coverWidthFrac / 2f)
        val coverBmpCy = 900 * (layout.coverTopFrac + layout.coverHeightFrac / 2f)
        assertEquals(coverCx, placement.left + coverBmpCx * placement.scale, 0.001f)
        assertEquals(coverCy, placement.top + coverBmpCy * placement.scale, 0.001f)
        assertTrue(placement.left <= 0.001f)
        assertTrue(placement.top <= 0.001f)
        assertTrue(placement.left + 1000 * placement.scale >= cardW - 0.001f)
        assertTrue(placement.top + 900 * placement.scale >= cardH - 0.001f)
        assertTrue(600 * placement.scale >= coverSize - 0.001f)
    }

    @Test
    fun rectangularSourceKeepsUniformScaleWithoutDistortion() {
        val layout = outpaintCoverLayout(1200, 1000, 200, 100, 200, 500)!!
        val placement = alignedOutpaintPlacement(1200, 1000, layout, 520f, 196f, 220f, 650f)
        // Uniform scale only — cover center stays pinned to stage center.
        assertEquals(260f, placement.left + 1200 * (layout.coverLeftFrac + layout.coverWidthFrac / 2f) * placement.scale, 0.001f)
        assertEquals(110f, placement.top + 1000 * (layout.coverTopFrac + layout.coverHeightFrac / 2f) * placement.scale, 0.001f)
        assertTrue(placement.left <= 0.001f)
        assertTrue(placement.left + 1200 * placement.scale >= 520f - 0.001f)
    }

    @Test
    fun stageOnlyHeightStillAlignsWhenPadAlreadyCovers() {
        // Tall pad relative to a short stage: align scale may already cover; fillScale stays 1.
        val layout = outpaintCoverLayout(400, 800, 50, 50, 50, 450)!!
        val placement = alignedOutpaintPlacement(400, 800, layout, 300f, 200f, 220f, 220f)
        val coverBmpCx = 400 * (layout.coverLeftFrac + layout.coverWidthFrac / 2f)
        val coverBmpCy = 800 * (layout.coverTopFrac + layout.coverHeightFrac / 2f)
        assertEquals(150f, placement.left + coverBmpCx * placement.scale, 0.001f)
        assertEquals(110f, placement.top + coverBmpCy * placement.scale, 0.001f)
        assertTrue(placement.left <= 0.001f)
        assertTrue(placement.top <= 0.001f)
        assertTrue(placement.left + 400 * placement.scale >= 300f - 0.001f)
        assertTrue(placement.top + 800 * placement.scale >= 220f - 0.001f)
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
