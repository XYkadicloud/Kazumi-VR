package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the two real-device regressions reported on 2026-11-09:
 *  - the passthrough window turned into a PORTRAIT 9:16 rect because a 90° rotation caused the
 *    quad ratio to be reciprocated;
 *  - the camera picture was stretched instead of centre-cropped.
 */
class VrPassthroughGeometryTest {

    @Test
    fun windowAspectIsAlwaysLandscapeAndNeverFollowsRotation() {
        val a43 = VrPassthroughGeometry.windowAspect(true)
        val a169 = VrPassthroughGeometry.windowAspect(false)
        assertEquals(4f / 3f, a43, 1e-5f)
        assertEquals(16f / 9f, a169, 1e-5f)
        assertTrue("4:3 window must be landscape", a43 > 1f)
        assertTrue("16:9 window must be landscape", a169 > 1f)
        // The exact old bug: 16:9 preview + 90° -> 1/(16/9) = 9/16 portrait.
        val oldPortrait = 1f / (16f / 9f)
        assertTrue(oldPortrait < 1f)
        assertTrue("window must never be that reciprocal", a169 > 1f && oldPortrait < 1f)
    }

    @Test
    fun previewAspectIsNormalisedToLandscape() {
        assertEquals(16f / 9f, VrPassthroughGeometry.previewAspect(1280, 720, 4f / 3f), 1e-5f)
        // Vendor-advertised PORTRAIT size must not produce a portrait window.
        assertEquals(16f / 9f, VrPassthroughGeometry.previewAspect(720, 1280, 4f / 3f), 1e-5f)
        assertEquals(4f / 3f, VrPassthroughGeometry.previewAspect(640, 480, 16f / 9f), 1e-5f)
        // Before the first camera frame: fallback, still landscape.
        assertEquals(4f / 3f, VrPassthroughGeometry.previewAspect(0, 0, 4f / 3f), 1e-5f)
    }

    @Test
    fun autoRotationProducesNoCropWhenTierMatchesWindow() {
        val preview = VrPassthroughGeometry.previewAspect(640, 480, 4f / 3f)
        val window = VrPassthroughGeometry.windowAspect(true)
        val content = VrPassthroughGeometry.contentAspect(preview, 0)
        val (cu, cv) = VrPassthroughGeometry.centreCrop(content, window)
        assertEquals("AUTO must not rotate the content", preview, content, 1e-5f)
        assertEquals(1f, cu, 1e-5f)
        assertEquals(1f, cv, 1e-5f)
    }

    @Test
    fun aManual90DegreeRotationKeepsTheWindowLandscapeAndCropsInsteadOfStretching() {
        val preview = VrPassthroughGeometry.previewAspect(1280, 720, 16f / 9f)
        val window = VrPassthroughGeometry.windowAspect(false)
        val content = VrPassthroughGeometry.contentAspect(preview, 90)
        assertTrue(content < 1f)
        assertEquals(16f / 9f, window, 1e-5f)
        val (cu, cv) = VrPassthroughGeometry.centreCrop(content, window)
        assertEquals(1f, cu, 1e-5f)
        assertTrue("excess height must be cropped", cv < 1f)
        assertEquals(content / window, cv, 1e-5f)
    }

    @Test
    fun sixteenNineWindowCropsA43PreviewSymmetrically() {
        val preview = VrPassthroughGeometry.previewAspect(640, 480, 4f / 3f)
        val window = VrPassthroughGeometry.windowAspect(false)
        val (cu, cv) = VrPassthroughGeometry.centreCrop(
            VrPassthroughGeometry.contentAspect(preview, 0),
            window,
        )
        assertEquals(1f, cu, 1e-5f)
        assertEquals(preview / window, cv, 1e-5f)
        val lo = VrPassthroughGeometry.cropUv(0f, cv)
        val hi = VrPassthroughGeometry.cropUv(1f, cv)
        assertEquals("crop must be centred", 1f - hi, lo, 1e-5f)
        assertTrue(lo >= 0f && hi <= 1f)
        assertEquals("no-crop is identity", 0.5f, VrPassthroughGeometry.cropUv(0.5f, 1f), 1e-5f)
    }

    @Test
    fun cropUvAlwaysStaysInsideTheUnitRange() {
        listOf(0.25f, 0.42f, 0.75f, 1f).forEach { keep ->
            listOf(0f, 0.5f, 1f).forEach { c ->
                val v = VrPassthroughGeometry.cropUv(c, keep)
                assertTrue("keep=$keep c=$c -> $v", v >= 0f && v <= 1f)
            }
        }
    }
}
