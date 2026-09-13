package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun noisyBlackFrameStillCountsAsBlack() {
        val w = 48
        val h = 48
        val pixels = IntArray(w * h) { 0xFF336699.toInt() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 3 || x >= w - 3 || y < 3 || y >= h - 3) {
                    // JPEG-like noise on a near-black rim.
                    val n = (x + y) % 5
                    pixels[y * w + x] = (0xFF000000.toInt()) or (n shl 16) or (n shl 8) or n
                }
            }
        }
        assertEquals(
            0xFF000000.toInt(),
            AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, pixels),
        )
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

    /**
     * Side/rim colors below are measured from real ComfyUI runs on real covers
     * (Beeches "Holy Shiitake", Leon Bridges "Happiness Anytime", Hunter Metts
     * "Where It Ends"), so the guard is pinned to observed behaviour.
     */
    @Test
    fun faithfulContinuationIsAccepted() {
        val padSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(147, 144, 129),
            top = argb(157, 194, 217),
            right = argb(149, 139, 122),
            bottom = argb(121, 89, 40),
        )
        val coverRim = argb(148, 144, 126)
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverRim)
        assertTrue("expected close match, was $d", d < AlbumArtLocalOutpaint.MAX_PAD_MISMATCH)
    }

    @Test
    fun inventedCreamPadOverBlueWaterIsRejected() {
        val padSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(143, 135, 113),
            top = argb(170, 157, 128),
            right = argb(146, 139, 118),
            bottom = argb(128, 123, 105),
        )
        val coverRim = argb(47, 87, 112)
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverRim)
        assertTrue("expected mismatch, was $d", d > AlbumArtLocalOutpaint.MAX_PAD_MISMATCH)
    }

    @Test
    fun grayWallPadOverBlackFramedCoverIsRejected() {
        val padSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(160, 155, 145),
            top = argb(121, 122, 119),
            right = argb(154, 149, 139),
            bottom = argb(123, 122, 115),
        )
        val coverRim = argb(17, 17, 17)
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverRim)
        assertTrue("expected mismatch, was $d", d > AlbumArtLocalOutpaint.MAX_PAD_MISMATCH)
    }

    @Test
    fun rimMeanKeepsDarkCoversDarkWithoutSnapping() {
        val w = 40
        val h = 40
        val pixels = IntArray(w * h) { argb(200, 200, 200) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < 6 || x >= w - 6 || y < 6 || y >= h - 6) pixels[y * w + x] = argb(20, 24, 30)
            }
        }
        val rim = AlbumArtLocalOutpaint.analyzeEdgeMeanArgb(w, h, pixels)
        assertEquals(20, (rim ushr 16) and 0xFF)
        assertEquals(24, (rim ushr 8) and 0xFF)
        assertEquals(30, rim and 0xFF)
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun pictorialEdgesSkipUniformFillButSideMeansFollowWater() {
        val w = 40
        val h = 40
        val blue = 0xFF1A4A8A.toInt()
        val pixels = IntArray(w * h) { blue }
        // High-variance checker only in the center — rim stays blue.
        for (y in 8 until 32) {
            for (x in 8 until 32) {
                pixels[y * w + x] = if ((x + y) % 2 == 0) 0xFFE8F0FF.toInt() else 0xFF102030.toInt()
            }
        }
        // Whole-rim uniform blue → solid fill path.
        assertEquals(blue, AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, pixels))

        val noisyRim = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if ((x + y) % 2 == 0) 0xFFE8F0FF.toInt() else 0xFF102030.toInt()
        }
        assertNull(AlbumArtLocalOutpaint.analyzeUniformEdgeFillArgb(w, h, noisyRim))
        val sides = AlbumArtLocalOutpaint.analyzeSideMeans(w, h, noisyRim)
        // Means of checkerboard rim are mid-tone, not cream beige.
        assertEquals(0xFF, (sides.left ushr 24) and 0xFF)
    }
}
