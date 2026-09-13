package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutpaintCoverLayoutTest {
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
