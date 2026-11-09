package com.example.kazumi.vr

/**
 * Pure geometry maths for the camera-passthrough window. Deliberately has NO Android / OpenGL
 * dependency, so JVM unit tests can lock the two real-device regressions reported on 2026-11-09:
 *
 *  1. The window must ALWAYS be LANDSCAPE (4:3 or 16:9). The old code took the reciprocal of the
 *     preview aspect whenever the effective rotation was 90/270, which turned a normal 16:9
 *     preview into a portrait 9:16 window — the "被强制拉伸成了竖屏的 9:16 比例" report.
 *  2. Camera pixels must NEVER be stretched. When the upright content ratio and the window ratio
 *     differ, the sampled UVs are CENTRE-CROPPED instead of squeezing the quad. Rotation only ever
 *     changes what part of the buffer is sampled; it never changes the shape of the window.
 *
 * Keeping this separate from [VrTheaterRenderer] also means the rules survive a GL context loss
 * and can be verified without an emulator.
 */
internal object VrPassthroughGeometry {
    const val ASPECT_4_3 = 4f / 3f
    const val ASPECT_16_9 = 16f / 9f

    /** Window ratio chosen by the user. NEVER portrait, NEVER derived from the rotation. */
    fun windowAspect(fourThree: Boolean): Float = if (fourThree) ASPECT_4_3 else ASPECT_16_9

    /**
     * Real preview buffer ratio, normalised to landscape (long edge / short edge). Some devices
     * advertise portrait sizes such as 720x1280; normalising here keeps the window horizontal even
     * on those devices. [fallback] is used only before the camera has opened.
     */
    fun previewAspect(width: Int, height: Int, fallback: Float): Float {
        if (width <= 0 || height <= 0) return fallback.coerceIn(0.25f, 8f)
        val w = maxOf(width, height).toFloat()
        val h = minOf(width, height).toFloat()
        return (w / h).coerceIn(0.25f, 8f)
    }

    /** Ratio of the UPRIGHT picture after a clockwise rotation of [rotationDeg]. */
    fun contentAspect(previewAspect: Float, rotationDeg: Int): Float =
        if (rotationDeg == 90 || rotationDeg == 270) {
            (1f / previewAspect).coerceIn(0.25f, 8f)
        } else {
            previewAspect.coerceIn(0.25f, 8f)
        }

    /**
     * Fraction of the sampled UV range to KEEP on each axis. Exactly one axis is < 1f: the excess
     * of the wider dimension is cropped symmetrically, so every pixel keeps its original aspect.
     * A perfect match (e.g. AUTO rotation + 4:3 preview + 4:3 window) returns (1, 1) = no crop.
     */
    fun centreCrop(contentAspect: Float, windowAspect: Float): Pair<Float, Float> {
        val c = contentAspect.coerceIn(0.25f, 8f)
        val w = windowAspect.coerceIn(0.25f, 8f)
        return if (c > w) Pair(w / c, 1f) else Pair(1f, c / w)
    }

    /** Apply a centred crop to a UV coordinate in [0,1]; the result always stays in [0,1]. */
    fun cropUv(coord: Float, keep: Float): Float = (1f - keep) / 2f + coord * keep
}
