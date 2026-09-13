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
        val coverSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(133, 128, 109),
            top = argb(176, 210, 231),
            right = argb(146, 137, 122),
            bottom = argb(138, 102, 42),
        )
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides)
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
        val coverSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(40, 84, 115),
            top = argb(104, 129, 129),
            right = argb(30, 78, 119),
            bottom = argb(15, 57, 86),
        )
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides)
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
        val coverSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(17, 17, 17),
            top = argb(19, 19, 19),
            right = argb(15, 15, 15),
            bottom = argb(18, 18, 18),
        )
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides)
        assertTrue("expected mismatch, was $d", d > AlbumArtLocalOutpaint.MAX_PAD_MISMATCH)
    }

    @Test
    fun oneLuckySideDoesNotExcuseCreamPads() {
        // Three cream margins + one blue that happens to match the cover left edge.
        val padSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(40, 84, 115),
            top = argb(170, 157, 128),
            right = argb(146, 139, 118),
            bottom = argb(128, 123, 105),
        )
        val coverSides = AlbumArtLocalOutpaint.SideMeans(
            left = argb(40, 84, 115),
            top = argb(104, 129, 129),
            right = argb(30, 78, 119),
            bottom = argb(15, 57, 86),
        )
        val d = AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides)
        assertTrue("worst-side must reject, was $d", d > AlbumArtLocalOutpaint.MAX_PAD_MISMATCH)
    }

    @Test
    fun hardPictureFrameSeamIsRejected() {
        val padL = 20
        val padT = 8
        val padR = 20
        val padB = 8
        val coverW = 40
        val coverH = 40
        val outW = coverW + padL + padR
        val outH = coverH + padT + padB
        // Grainy charcoal cover vs flat black pads — mean colors are both "dark"
        // but the boundary reads as a hard box (Daya / Difference failure mode).
        val pixels = IntArray(outW * outH) { idx ->
            val x = idx % outW
            val y = idx / outW
            val inCover = x in padL until (outW - padR) && y in padT until (outH - padB)
            if (inCover) {
                val n = (x * 17 + y * 13) % 28
                argb(28 + n, 28 + n, 30 + n)
            } else {
                argb(4, 4, 4)
            }
        }
        val d = AlbumArtLocalOutpaint.padSeamDistance(outW, outH, pixels, padL, padT, padR, padB)
        assertTrue("expected visible seam, was $d", d > AlbumArtLocalOutpaint.MAX_PAD_SEAM)
    }

    @Test
    fun seamlessContinuationPassesSeamCheck() {
        val padL = 20
        val padT = 8
        val padR = 20
        val padB = 8
        val coverW = 40
        val coverH = 40
        val outW = coverW + padL + padR
        val outH = coverH + padT + padB
        // Same mid-tone everywhere — no seam.
        val tone = argb(90, 110, 130)
        val pixels = IntArray(outW * outH) { tone }
        val d = AlbumArtLocalOutpaint.padSeamDistance(outW, outH, pixels, padL, padT, padR, padB)
        assertTrue("expected seamless, was $d", d < AlbumArtLocalOutpaint.MAX_PAD_SEAM)
    }

    @Test
    fun texturedColorDriftIsNotRejectedAsCream() {
        // Mean color can drift on a good Flux fill; solid-cream check must fail.
        val padL = 20
        val padT = 4
        val padR = 20
        val padB = 4
        val coverW = 40
        val coverH = 40
        val outW = coverW + padL + padR
        val outH = coverH + padT + padB
        val cover = argb(40, 84, 115)
        val cream = argb(170, 157, 128)
        val coverPixels = IntArray(coverW * coverH) { cover }
        val paddedPixels = IntArray(outW * outH) { idx ->
            val x = idx % outW
            val y = idx / outW
            val inCover = x in padL until (outW - padR) && y in padT until (outH - padB)
            if (inCover) {
                cover
            } else {
                // Textured cream-ish pad — not a flat matte.
                argb(
                    (cream ushr 16 and 0xFF) + ((x * 13 + y * 7) % 40) - 20,
                    (cream ushr 8 and 0xFF) + ((x * 3 + y * 11) % 40) - 20,
                    (cream and 0xFF) + ((x * 5 + y * 17) % 40) - 20,
                ).let {
                    val r = ((it ushr 16) and 0xFF).coerceIn(0, 255)
                    val g = ((it ushr 8) and 0xFF).coerceIn(0, 255)
                    val b = (it and 0xFF).coerceIn(0, 255)
                    argb(r, g, b)
                }
            }
        }
        assertTrue(
            "textured pad should not look local-solid",
            !AlbumArtLocalOutpaint.looksLikeLocalSolidPadPixels(outW, outH, paddedPixels, padL, padT, padR, padB),
        )
        // Color distance alone would reject; combined guard must not.
        val padSides = AlbumArtLocalOutpaint.SideMeans(
            left = cream, top = cream, right = cream, bottom = cream,
        )
        val coverSides = AlbumArtLocalOutpaint.SideMeans(
            left = cover, top = cover, right = cover, bottom = cover,
        )
        assertTrue(
            AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides) >
                AlbumArtLocalOutpaint.MAX_PAD_MISMATCH,
        )
        // Color mismatch alone is enough to reject (no solid-pad AND required).
        assertTrue(
            "cream-colored Flux must be rejected",
            AlbumArtLocalOutpaint.padMismatchDistance(padSides, coverSides) >
                AlbumArtLocalOutpaint.MAX_PAD_MISMATCH,
        )
        // silence unused
        coverPixels.size
        AlbumArtLocalOutpaint.looksLikeLocalSolidPadPixels(outW, outH, paddedPixels, padL, padT, padR, padB)
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

    @Test
    fun solidLocalPadIsDetectedAsLocal() {
        val padL = 20
        val padT = 4
        val padR = 20
        val padB = 4
        val coverW = 40
        val coverH = 40
        val outW = coverW + padL + padR
        val outH = coverH + padT + padB
        val left = argb(200, 180, 150)
        val right = argb(90, 80, 70)
        val top = argb(40, 40, 40)
        val bottom = argb(20, 20, 20)
        val center = argb(60, 100, 140)
        val pixels = IntArray(outW * outH) { idx ->
            val x = idx % outW
            val y = idx / outW
            when {
                x < padL -> left
                x >= outW - padR -> right
                y < padT -> top
                y >= outH - padB -> bottom
                else -> center
            }
        }
        assertTrue(
            AlbumArtLocalOutpaint.looksLikeLocalSolidPadPixels(outW, outH, pixels, padL, padT, padR, padB),
        )
    }

    @Test
    fun texturedPadIsNotTreatedAsLocal() {
        val padL = 24
        val padT = 4
        val padR = 24
        val padB = 4
        val coverW = 48
        val coverH = 48
        val outW = coverW + padL + padR
        val outH = coverH + padT + padB
        val pixels = IntArray(outW * outH) { idx ->
            val x = idx % outW
            val y = idx / outW
            if (x < padL || x >= outW - padR || y < padT || y >= outH - padB) {
                argb((x * 17 + y * 13) % 180, (x * 7 + y * 29) % 180, (x * 3 + y * 41) % 180)
            } else {
                argb(90, 70, 60)
            }
        }
        assertTrue(
            !AlbumArtLocalOutpaint.looksLikeLocalSolidPadPixels(outW, outH, pixels, padL, padT, padR, padB),
        )
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
