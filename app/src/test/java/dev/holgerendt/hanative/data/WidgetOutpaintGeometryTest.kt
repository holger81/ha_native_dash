package dev.holgerendt.hanative.data

import org.junit.Assert.*
import org.junit.Test

class WidgetOutpaintGeometryTest {
    @Test fun fullCardUsesMeasuredCanvasAndCoverRectangle() {
        val geometry = WidgetOutpaintGeometry(520, 400, 162, 12, 196, 196)
        assertEquals(OutpaintCanvasSpec(520, 400, 162, 12), geometry.canvas)
        assertEquals(OutpaintPadLayout(162, 12, 162, 192), geometry.pads)
        assertEquals(520, geometry.pads.outWidth(196))
        assertEquals(400, geometry.pads.outHeight(196))
    }

    @Test fun rejectsScaledCanvasAndShiftedSourceEvenWithSameAspect() {
        val geometry = WidgetOutpaintGeometry(520, 400, 162, 12, 196, 196)
        assertTrue(geometry.acceptsSize(520, 400, geometry.pads))
        assertFalse(geometry.acceptsSize(1040, 800, geometry.pads))
        assertFalse(geometry.acceptsSize(528, 400, geometry.pads))
        assertFalse(geometry.acceptsSize(520, 400, OutpaintPadLayout(160, 12, 164, 192)))
    }

    @Test fun geometryIdentityChangesWithCoverPositionAndDensity() {
        val geometry = WidgetOutpaintGeometry(521, 400, 163, 12, 196, 196)
        assertEquals(162, geometry.pads.padRight)
        assertNotEquals(geometry.key, geometry.copy(coverX = 162).key)
        assertNotEquals(geometry.key, WidgetOutpaintGeometry(1042, 800, 326, 24, 392, 392).key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesCoverOutsideWidget() {
        WidgetOutpaintGeometry(520, 400, 400, 12, 196, 196)
    }

    @Test
    fun coverCornerRadiusMatchesHeroFractionEvenOnThinTopChrome() {
        // ExactWidget FullMusicCard often has ~12px top chrome — radius must still
        // be the 22/196 hero fraction so ear punch covers the square stamp tips.
        val geo = WidgetOutpaintGeometry(520, 400, 162, 12, 196, 196)
        val radius = geo.coverWidth * WidgetOutpaintGeometry.COVER_CORNER_RADIUS_FRAC
        assertEquals(22f, radius, 0.01f)
        assertTrue(geo.coverY < radius)
        // Outside-corner sample for TL still exists (left × top chrome).
        assertTrue(geo.coverX > 0)
        assertTrue(geo.coverY > 0)
        // BR outside-corner sample exists (right × bottom chrome).
        assertTrue(geo.pads.padRight > 0)
        assertTrue(geo.pads.padBottom > 0)
    }

    @Test
    fun heroCornerRadiusPxScalesWithMeasuredCoverWidth() {
        // Density-independent: 392px cover (2× 196.dp) → 44px radius (2× 22.dp).
        assertEquals(44f, 392f * WidgetOutpaintGeometry.COVER_CORNER_RADIUS_FRAC, 0.01f)
        assertEquals(22f / 196f, WidgetOutpaintGeometry.COVER_CORNER_RADIUS_FRAC, 0.0001f)
    }
}
