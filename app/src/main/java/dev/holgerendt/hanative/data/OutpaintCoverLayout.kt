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

fun alignedOutpaintPlacement(
    imageWidth: Int,
    imageHeight: Int,
    layout: OutpaintCoverLayout,
    cardWidth: Float,
    coverSize: Float,
    stageHeight: Float,
): OutpaintPlacement {
    val sourceWidth = imageWidth * layout.coverWidthFrac
    val sourceHeight = imageHeight * layout.coverHeightFrac
    require(sourceWidth > 0 && sourceHeight > 0 && coverSize > 0)
    val scale = maxOf(coverSize / sourceWidth, coverSize / sourceHeight)
    val coverLeft = (cardWidth - coverSize) / 2f
    val coverTop = (stageHeight - coverSize) / 2f
    return OutpaintPlacement(
        scale = scale,
        left = coverLeft - (sourceWidth * scale - coverSize) / 2f - imageWidth * layout.coverLeftFrac * scale,
        top = coverTop - (sourceHeight * scale - coverSize) / 2f - imageHeight * layout.coverTopFrac * scale,
    )
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
