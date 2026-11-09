package com.example.kazumi.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VrSettingsTest {

    @Test
    fun defaultsAreInsideEveryDeclaredRange() {
        val s = VrSettings.DEFAULT
        assertTrue(s.screenScale in VrRanges.SCREEN_SCALE_MIN..VrRanges.SCREEN_SCALE_MAX)
        assertTrue(s.screenDistanceMeters in VrRanges.DISTANCE_MIN..VrRanges.DISTANCE_MAX)
        assertTrue(s.brightness in VrRanges.BRIGHTNESS_MIN..VrRanges.BRIGHTNESS_MAX)
        assertTrue(s.curvature in VrRanges.CURVATURE_MIN..VrRanges.CURVATURE_MAX)
        assertTrue(s.screenVerticalOffset in VrRanges.VERTICAL_OFFSET_MIN..VrRanges.VERTICAL_OFFSET_MAX)
        assertTrue(s.headTrackingSensitivity in VrRanges.HEAD_SENSITIVITY_MIN..VrRanges.HEAD_SENSITIVITY_MAX)
        assertTrue(s.gamepadDeadZone in VrRanges.DEAD_ZONE_MIN..VrRanges.DEAD_ZONE_MAX)
        assertTrue(s.menuRepeatMs in VrRanges.REPEAT_MIN_MS..VrRanges.REPEAT_MAX_MS)
        assertTrue(s.ipdScale in VrRanges.IPD_SCALE_MIN..VrRanges.IPD_SCALE_MAX)
        assertTrue(s.zoomScale in VrRanges.ZOOM_MIN..VrRanges.ZOOM_MAX)
        assertEquals(1.0f, s.ipdScale, 0f)
        // Raised from 1.18 to 1.35 after real-device feedback that the bands were still visible.
        assertEquals(1.35f, s.zoomScale, 0f)
        assertEquals(2.50f, VrRanges.ZOOM_MAX, 0f)
        assertEquals(VrViewerMode.CARDBOARD, s.viewerMode)
    }

    @Test
    fun sanitizeClampsOutOfRangeValues() {
        val wild = VrSettings(
            screenScale = 999f,
            screenDistanceMeters = -4f,
            brightness = 0f,
            curvature = 7f,
            screenVerticalOffset = -12f,
            headTrackingSensitivity = 100f,
            gamepadDeadZone = -1f,
            menuRepeatMs = 0L,
            ipdScale = 99f,
            zoomScale = -5f,
        )
        val s = wild.sanitized()
        assertEquals(VrRanges.SCREEN_SCALE_MAX, s.screenScale, 0f)
        assertEquals(VrRanges.DISTANCE_MIN, s.screenDistanceMeters, 0f)
        assertEquals(VrRanges.BRIGHTNESS_MIN, s.brightness, 0f)
        assertEquals(VrRanges.CURVATURE_MAX, s.curvature, 0f)
        assertEquals(VrRanges.VERTICAL_OFFSET_MIN, s.screenVerticalOffset, 0f)
        assertEquals(VrRanges.HEAD_SENSITIVITY_MAX, s.headTrackingSensitivity, 0f)
        assertEquals(VrRanges.DEAD_ZONE_MIN, s.gamepadDeadZone, 0f)
        assertEquals(VrRanges.REPEAT_MIN_MS, s.menuRepeatMs)
        assertEquals(VrRanges.ZOOM_MIN, s.zoomScale, 0f)
    }

    @Test
    fun sanitizeIsIdempotent() {
        val once = VrSettings(screenScale = 99f, brightness = -1f).sanitized()
        assertEquals(once, once.sanitized())
    }

    @Test
    fun nonNumericFieldsSurviveSanitize() {
        val s = VrSettings(viewerMode = VrViewerMode.SIMULATION, showStatus = false, headTrackingEnabled = false)
            .sanitized()
        assertEquals(VrViewerMode.SIMULATION, s.viewerMode)
        assertEquals(false, s.showStatus)
        assertEquals(false, s.headTrackingEnabled)
    }

    @Test
    fun streamOrientationAndOverlayFlagsDefaultOffAndSurviveSanitize() {
        val defaults = VrSettings.DEFAULT
        assertEquals(false, defaults.horizontalMirror)
        assertEquals(false, defaults.verticalFlip)
        assertEquals(false, defaults.showCenterAlignmentLine)
        val toggled = VrSettings(
            horizontalMirror = true,
            verticalFlip = true,
            showCenterAlignmentLine = true,
        ).sanitized()
        assertEquals(true, toggled.horizontalMirror)
        assertEquals(true, toggled.verticalFlip)
        assertEquals(true, toggled.showCenterAlignmentLine)
    }

    @Test
    fun screenFillModeDefaultsAndSurvivesSanitize() {
        val defaults = VrSettings.DEFAULT
        assertEquals(VrScreenFillMode.FILL, defaults.screenFillMode)
        val toggled = VrSettings(screenFillMode = VrScreenFillMode.DISTORTED).sanitized()
        assertEquals(VrScreenFillMode.DISTORTED, toggled.screenFillMode)
    }

    @Test
    fun passthroughDefaultsAreOffNative43AndOverlay() {
        val s = VrSettings.DEFAULT
        assertEquals(false, s.passthroughEnabled)
        assertEquals(VrPassthroughFacing.BACK, s.passthroughCameraFacing)
        assertEquals(1.00f, s.passthroughOpacity, 0f)
        assertEquals(0.85f, s.passthroughWindowScale, 0f)
        assertEquals(VrPassthroughAspect.FOUR_THREE, s.passthroughAspect)
        assertEquals(VrPassthroughMode.OVERLAY, s.passthroughMode)
        // Camera controls requested after real-device feedback (方向错 / 拉伸 / 分辨率与帧率).
        assertEquals(VrPassthroughResolution.P480, s.passthroughResolution)
        assertEquals(VrPassthroughFrameRate.FPS_30, s.passthroughFrameRate)
        assertEquals(VrPassthroughRotation.AUTO, s.passthroughRotation)
        assertEquals(false, s.passthroughMirror)
        // Default OFF = camera window shares the stream's MVP, no relative slide on head turns.
        assertEquals(false, s.passthroughHeadLocked)
    }

    @Test
    fun cameraControlTiersAndFlagsSurviveSanitize() {
        val wild = VrSettings(
            passthroughResolution = VrPassthroughResolution.P1080,
            passthroughFrameRate = VrPassthroughFrameRate.FPS_60,
            passthroughRotation = VrPassthroughRotation.R270,
            passthroughMirror = true,
            passthroughHeadLocked = true,
        ).sanitized()
        assertEquals(VrPassthroughResolution.P1080, wild.passthroughResolution)
        assertEquals(VrPassthroughFrameRate.FPS_60, wild.passthroughFrameRate)
        assertEquals(VrPassthroughRotation.R270, wild.passthroughRotation)
        assertEquals(true, wild.passthroughMirror)
        assertEquals(true, wild.passthroughHeadLocked)
        assertEquals(360, VrPassthroughResolution.P360.shortEdge)
        assertEquals(480, VrPassthroughResolution.P480.shortEdge)
        assertEquals(720, VrPassthroughResolution.P720.shortEdge)
        assertEquals(1080, VrPassthroughResolution.P1080.shortEdge)
        assertEquals(15, VrPassthroughFrameRate.FPS_15.fps)
        assertEquals(30, VrPassthroughFrameRate.FPS_30.fps)
        assertEquals(60, VrPassthroughFrameRate.FPS_60.fps)
        assertEquals(3, VrPassthroughFrameRate.values().size)
        // 15 fps is a real persisted tier, not just a UI label.
        assertEquals(VrPassthroughFrameRate.FPS_15,
            VrSettings(passthroughFrameRate = VrPassthroughFrameRate.FPS_15)
                .sanitized().passthroughFrameRate)
        assertEquals(-1, VrPassthroughRotation.AUTO.degrees)
        assertEquals(90, VrPassthroughRotation.R90.degrees)
    }

    @Test
    fun passthroughEnumsSurviveSanitizeAndRangesClamp() {
        val wild = VrSettings(
            passthroughEnabled = true,
            passthroughCameraFacing = VrPassthroughFacing.FRONT,
            passthroughOpacity = 99f,
            passthroughWindowScale = -3f,
            passthroughAspect = VrPassthroughAspect.SIXTEEN_NINE,
            passthroughMode = VrPassthroughMode.SIDE_BY_SIDE,
        ).sanitized()
        assertEquals(true, wild.passthroughEnabled)
        assertEquals(VrPassthroughFacing.FRONT, wild.passthroughCameraFacing)
        assertEquals(VrRanges.PASSTHROUGH_OPACITY_MAX, wild.passthroughOpacity, 0f)
        assertEquals(VrRanges.PASSTHROUGH_WINDOW_MIN, wild.passthroughWindowScale, 0f)
        assertEquals(VrPassthroughAspect.SIXTEEN_NINE, wild.passthroughAspect)
        assertEquals(VrPassthroughMode.SIDE_BY_SIDE, wild.passthroughMode)
    }
}
