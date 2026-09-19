package dev.holgerendt.hanative.data

/**
 * Shared outpaint geometry and cache identity for local pads, on-device cache,
 * cover layout, and the mediagen LAN API (same version string as the server).
 */
object OutpaintPads {
    const val OUTPAINT_PAD_LEFT = 256
    const val OUTPAINT_PAD_TOP = 128
    const val OUTPAINT_PAD_RIGHT = 256
    const val OUTPAINT_PAD_BOTTOM = 128

    /**
     * Bump when pad geometry or generative prompt identity changes so stale
     * cache files are not reused. Must stay in sync with mediagen.
     */
    const val OUTPAINT_CACHE_VERSION = "empty-prompt-feather0-v5"
}
