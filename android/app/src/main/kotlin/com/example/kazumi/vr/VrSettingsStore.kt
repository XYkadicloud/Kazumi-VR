package com.example.kazumi.vr

import android.content.Context

/** Persists VR settings. Only this class and VrSession touch SharedPreferences. */
class VrSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("opennow_vr_settings", Context.MODE_PRIVATE)

    fun load(): VrSettings = VrSettings(
        screenScale = prefs.getFloat(K_SCALE, VrSettings.DEFAULT.screenScale),
        screenDistanceMeters = prefs.getFloat(K_DISTANCE, VrSettings.DEFAULT.screenDistanceMeters),
        brightness = prefs.getFloat(K_BRIGHTNESS, VrSettings.DEFAULT.brightness),
        curvature = prefs.getFloat(K_CURVATURE, VrSettings.DEFAULT.curvature),
        screenVerticalOffset = prefs.getFloat(K_OFFSET, VrSettings.DEFAULT.screenVerticalOffset),
        viewerMode = runCatching {
            VrViewerMode.valueOf(prefs.getString(K_MODE, VrSettings.DEFAULT.viewerMode.name) ?: "")
        }.getOrDefault(VrSettings.DEFAULT.viewerMode),
        headTrackingEnabled = prefs.getBoolean(K_HEAD, VrSettings.DEFAULT.headTrackingEnabled),
        headTrackingSensitivity = prefs.getFloat(K_HEAD_SENS, VrSettings.DEFAULT.headTrackingSensitivity),
        gamepadDeadZone = prefs.getFloat(K_DEAD_ZONE, VrSettings.DEFAULT.gamepadDeadZone),
        menuRepeatMs = prefs.getLong(K_REPEAT, VrSettings.DEFAULT.menuRepeatMs),
        showStatus = prefs.getBoolean(K_STATUS, VrSettings.DEFAULT.showStatus),
        showCenterAlignmentLine = prefs.getBoolean(
            K_ALIGN_LINE, VrSettings.DEFAULT.showCenterAlignmentLine,
        ),
        horizontalMirror = prefs.getBoolean(K_H_MIRROR, VrSettings.DEFAULT.horizontalMirror),
        verticalFlip = prefs.getBoolean(K_V_FLIP, VrSettings.DEFAULT.verticalFlip),
        backgroundEnabled = prefs.getBoolean(K_BG_ENABLED, VrSettings.DEFAULT.backgroundEnabled),
        backgroundImageUri = prefs.getString(K_BG_URI, VrSettings.DEFAULT.backgroundImageUri) ?: "",
        backgroundBrightness = prefs.getFloat(K_BG_BRIGHT, VrSettings.DEFAULT.backgroundBrightness),
        ipdScale = prefs.getFloat(K_IPD_SCALE, VrSettings.DEFAULT.ipdScale),
        screenFillMode = runCatching {
            VrScreenFillMode.valueOf(
                prefs.getString(K_FILL_MODE, VrSettings.DEFAULT.screenFillMode.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.screenFillMode),
        zoomScale = prefs.getFloat(K_ZOOM, VrSettings.DEFAULT.zoomScale),
        passthroughEnabled = prefs.getBoolean(K_PASSTHROUGH, VrSettings.DEFAULT.passthroughEnabled),
        passthroughCameraFacing = runCatching {
            VrPassthroughFacing.valueOf(
                prefs.getString(K_PASSTHROUGH_FACING, VrSettings.DEFAULT.passthroughCameraFacing.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughCameraFacing),
        passthroughOpacity = prefs.getFloat(K_PASSTHROUGH_OPACITY, VrSettings.DEFAULT.passthroughOpacity),
        passthroughWindowScale = prefs.getFloat(
            K_PASSTHROUGH_WINDOW, VrSettings.DEFAULT.passthroughWindowScale,
        ),
        passthroughAspect = runCatching {
            VrPassthroughAspect.valueOf(
                prefs.getString(K_PASSTHROUGH_ASPECT, VrSettings.DEFAULT.passthroughAspect.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughAspect),
        passthroughMode = runCatching {
            VrPassthroughMode.valueOf(
                prefs.getString(K_PASSTHROUGH_MODE, VrSettings.DEFAULT.passthroughMode.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughMode),
        passthroughResolution = runCatching {
            VrPassthroughResolution.valueOf(
                prefs.getString(K_PASSTHROUGH_RESOLUTION, VrSettings.DEFAULT.passthroughResolution.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughResolution),
        passthroughFrameRate = runCatching {
            VrPassthroughFrameRate.valueOf(
                prefs.getString(K_PASSTHROUGH_FPS, VrSettings.DEFAULT.passthroughFrameRate.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughFrameRate),
        passthroughRotation = runCatching {
            VrPassthroughRotation.valueOf(
                prefs.getString(K_PASSTHROUGH_ROTATION, VrSettings.DEFAULT.passthroughRotation.name) ?: "",
            )
        }.getOrDefault(VrSettings.DEFAULT.passthroughRotation),
        passthroughMirror = prefs.getBoolean(K_PASSTHROUGH_MIRROR, VrSettings.DEFAULT.passthroughMirror),
        passthroughHeadLocked = prefs.getBoolean(
            K_PASSTHROUGH_HEAD_LOCKED, VrSettings.DEFAULT.passthroughHeadLocked,
        ),
    ).sanitized()

    fun save(settings: VrSettings) {
        val s = settings.sanitized()
        prefs.edit()
            .putFloat(K_SCALE, s.screenScale)
            .putFloat(K_DISTANCE, s.screenDistanceMeters)
            .putFloat(K_BRIGHTNESS, s.brightness)
            .putFloat(K_CURVATURE, s.curvature)
            .putFloat(K_OFFSET, s.screenVerticalOffset)
            .putString(K_MODE, s.viewerMode.name)
            .putBoolean(K_HEAD, s.headTrackingEnabled)
            .putFloat(K_HEAD_SENS, s.headTrackingSensitivity)
            .putFloat(K_DEAD_ZONE, s.gamepadDeadZone)
            .putLong(K_REPEAT, s.menuRepeatMs)
            .putBoolean(K_STATUS, s.showStatus)
            .putBoolean(K_ALIGN_LINE, s.showCenterAlignmentLine)
            .putBoolean(K_H_MIRROR, s.horizontalMirror)
            .putBoolean(K_V_FLIP, s.verticalFlip)
            .putBoolean(K_BG_ENABLED, s.backgroundEnabled)
            .putString(K_BG_URI, s.backgroundImageUri)
            .putFloat(K_BG_BRIGHT, s.backgroundBrightness)
            .putFloat(K_IPD_SCALE, s.ipdScale)
            .putString(K_FILL_MODE, s.screenFillMode.name)
            .putFloat(K_ZOOM, s.zoomScale)
            .putBoolean(K_PASSTHROUGH, s.passthroughEnabled)
            .putString(K_PASSTHROUGH_FACING, s.passthroughCameraFacing.name)
            .putFloat(K_PASSTHROUGH_OPACITY, s.passthroughOpacity)
            .putFloat(K_PASSTHROUGH_WINDOW, s.passthroughWindowScale)
            .putString(K_PASSTHROUGH_ASPECT, s.passthroughAspect.name)
            .putString(K_PASSTHROUGH_MODE, s.passthroughMode.name)
            .putString(K_PASSTHROUGH_RESOLUTION, s.passthroughResolution.name)
            .putString(K_PASSTHROUGH_FPS, s.passthroughFrameRate.name)
            .putString(K_PASSTHROUGH_ROTATION, s.passthroughRotation.name)
            .putBoolean(K_PASSTHROUGH_MIRROR, s.passthroughMirror)
            .putBoolean(K_PASSTHROUGH_HEAD_LOCKED, s.passthroughHeadLocked)
            .apply()
    }

    private companion object {
        const val K_SCALE = "screenScale"
        const val K_DISTANCE = "screenDistance"
        const val K_BRIGHTNESS = "brightness"
        const val K_CURVATURE = "curvature"
        const val K_OFFSET = "verticalOffset"
        const val K_MODE = "viewerMode"
        const val K_HEAD = "headTracking"
        const val K_HEAD_SENS = "headSensitivity"
        const val K_DEAD_ZONE = "gamepadDeadZone"
        const val K_REPEAT = "menuRepeatMs"
        const val K_STATUS = "showStatus"
        const val K_ALIGN_LINE = "showCenterAlignmentLine"
        const val K_H_MIRROR = "horizontalMirror"
        const val K_V_FLIP = "verticalFlip"
        const val K_BG_ENABLED = "backgroundEnabled"
        const val K_BG_URI = "backgroundImageUri"
        const val K_BG_BRIGHT = "backgroundBrightness"
        const val K_IPD_SCALE = "ipdScale"
        const val K_FILL_MODE = "screenFillMode"
        const val K_ZOOM = "zoomScale"
        const val K_PASSTHROUGH = "passthroughEnabled"
        const val K_PASSTHROUGH_FACING = "passthroughCameraFacing"
        const val K_PASSTHROUGH_OPACITY = "passthroughOpacity"
        const val K_PASSTHROUGH_WINDOW = "passthroughWindowScale"
        const val K_PASSTHROUGH_ASPECT = "passthroughAspect"
        const val K_PASSTHROUGH_MODE = "passthroughMode"
        const val K_PASSTHROUGH_RESOLUTION = "passthroughResolution"
        const val K_PASSTHROUGH_FPS = "passthroughFrameRate"
        const val K_PASSTHROUGH_ROTATION = "passthroughRotation"
        const val K_PASSTHROUGH_MIRROR = "passthroughMirror"
        const val K_PASSTHROUGH_HEAD_LOCKED = "passthroughHeadLocked"
    }
}
