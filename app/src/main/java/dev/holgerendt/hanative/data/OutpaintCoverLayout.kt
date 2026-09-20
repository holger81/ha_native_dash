package dev.holgerendt.hanative.data

/**
 * Where the original cover sits inside a Flux [ImagePadForOutpaint] result.
 * Pads must match [OutpaintPads.OUTPAINT_PAD_*].
 */
data class OutpaintCoverLayout(
    /** Full outpaint width / height. */
    val outAspectRatio: Float,
    val coverLeftFrac: Float,
    val coverTopFrac: Float,
    val coverWidthFrac: Float,
    val coverHeightFrac: Float,
)

/** Uniform source-to-card transform, matching the sharp cover's centered Crop behavior. */
data class OutpaintPlacement(val scale: Float, val left: Float, val top: Float)

/**
 * Map the pad so its baked-in cover region coincides with the sharp hero cover,
 * then uniformly scale up around the cover center until the pad covers the full
 * card (no letterboxing / CLAMP edge streaks). Cover center stays pinned; the
 * baked region may grow larger than [coverSize] underneath the sharp cover.
 */
fun alignedOutpaintPlacement(
    imageWidth: Int,
    imageHeight: Int,
    layout: OutpaintCoverLayout,
    cardWidth: Float,
    coverSize: Float,
    stageHeight: Float,
    cardHeight: Float = stageHeight,
): OutpaintPlacement {
    val sourceWidth = imageWidth * layout.coverWidthFrac
    val sourceHeight = imageHeight * layout.coverHeightFrac
    require(sourceWidth > 0 && sourceHeight > 0 && coverSize > 0)
    require(cardWidth > 0 && cardHeight > 0)
    val alignScale = maxOf(coverSize / sourceWidth, coverSize / sourceHeight)
    val coverLeft = (cardWidth - coverSize) / 2f
    val coverTop = (stageHeight - coverSize) / 2f
    val coverCx = coverLeft + coverSize / 2f
    val coverCy = coverTop + coverSize / 2f
    val coverBmpCx = imageWidth * (layout.coverLeftFrac + layout.coverWidthFrac / 2f)
    val coverBmpCy = imageHeight * (layout.coverTopFrac + layout.coverHeightFrac / 2f)
    var scale = alignScale
    var left = coverCx - coverBmpCx * scale
    var top = coverCy - coverBmpCy * scale

    // Grow uniformly around the pinned cover center until the pad fills the card.
    val scaledW = imageWidth * scale
    val scaledH = imageHeight * scale
    val distLeft = coverCx - left
    val distRight = left + scaledW - coverCx
    val distTop = coverCy - top
    val distBottom = top + scaledH - coverCy
    require(distLeft > 0f && distRight > 0f && distTop > 0f && distBottom > 0f)
    val fillScale = maxOf(
        coverCx / distLeft,
        (cardWidth - coverCx) / distRight,
        coverCy / distTop,
        (cardHeight - coverCy) / distBottom,
        1f,
    )
    if (fillScale > 1f) {
        scale *= fillScale
        left = coverCx - coverBmpCx * scale
        top = coverCy - coverBmpCy * scale
    }
    return OutpaintPlacement(scale = scale, left = left, top = top)
}

fun outpaintCoverLayout(
    outWidthPx: Int,
    outHeightPx: Int,
    padLeft: Int = OutpaintPads.OUTPAINT_PAD_LEFT,
    padTop: Int = OutpaintPads.OUTPAINT_PAD_TOP,
    padRight: Int = OutpaintPads.OUTPAINT_PAD_RIGHT,
    padBottom: Int = OutpaintPads.OUTPAINT_PAD_BOTTOM,
): OutpaintCoverLayout? {
    if (outWidthPx <= 0 || outHeightPx <= 0) return null
    val srcW = outWidthPx - padLeft - padRight
    val srcH = outHeightPx - padTop - padBottom
    if (srcW <= 0 || srcH <= 0) return null
    return OutpaintCoverLayout(
        outAspectRatio = outWidthPx.toFloat() / outHeightPx.toFloat(),
        coverLeftFrac = padLeft.toFloat() / outWidthPx,
        coverTopFrac = padTop.toFloat() / outHeightPx,
        coverWidthFrac = srcW.toFloat() / outWidthPx,
        coverHeightFrac = srcH.toFloat() / outHeightPx,
    )
}
