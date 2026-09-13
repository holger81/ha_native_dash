package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumArtLocalOutpaintTest {
    @Test
    fun blackFramedCoverGetsPureBlackFill() {
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { 0xFF224466.toInt() }
        // 3px black matte around the photo.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 3 || x >= w - 3 || y < 3 || y >= h - 3) {
                    pixels[y * w + x] = 0xFF000000.toInt()
                }
            }
        }
        val fill = AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, pixels)
        assertEquals(0xFF000000.toInt(), fill)
    }

    @Test
    fun flatStudioBackdropGetsMatchingFill() {
        val w = 32
        val h = 32
        val beige = 0xFFC8B89A.toInt()
        val pixels = IntArray(w * h) { beige }
        val fill = AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, pixels)
        assertNotNull(fill)
        assertEquals(0xFF, (fill!! ushr 24) and 0xFF)
        assertEquals(0xC8, (fill ushr 16) and 0xFF)
        assertEquals(0xB8, (fill ushr 8) and 0xFF)
        assertEquals(0x9A, fill and 0xFF)
    }

    @Test
    fun pictorialEdgesSkipLocalPad() {
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            // High-variance checkerboard on the rim → photo-like edge.
            val light = ((x + y) % 2 == 0)
            if (light) 0xFFE8F0FF.toInt() else 0xFF102030.toInt()
        }
        assertNull(AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, pixels))
    }
}
