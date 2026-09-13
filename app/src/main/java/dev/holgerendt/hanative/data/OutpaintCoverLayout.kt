package dev.holgerendt.hanative.data

/**
 * Where the original cover sits inside a Flux [ImagePadForOutpaint] result.
 * Pads must match [ComfyUiOutpaintClient.OUTPAINT_PAD_*].
 */
data class OutpaintCoverLayout(
    /** Full outpaint width / height. */
    val outAspectRatio: Float,
    val coverLeftFrac: Float,
    val coverTopFrac: Float,
    val coverWidthFrac: Float,
    val coverHeightFrac: Float,
)

fun outpaintCoverLayout(
    outWidthPx: Int,
    outHeightPx: Int,
    padLeft: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_LEFT,
    padTop: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_TOP,
    padRight: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_RIGHT,
    padBottom: Int = ComfyUiOutpaintClient.OUTPAINT_PAD_BOTTOM,
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
