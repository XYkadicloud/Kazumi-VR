package com.example.kazumi.vr

/**
 * Flutter ↔ Kotlin conversion for [VrSettings].
 *
 * There is exactly ONE authoritative settings store: native `VrSettingsStore`
 * (SharedPreferences "opennow_vr_settings"). The Flutter "VR 设置" page reads it through
 * `getSettings`, writes patches through `updateSettings`, and the in-VR GL menu writes to the
 * same store. MainActivity pushes `onSettingsChanged` back to Flutter on every native change so
 * both surfaces stay in sync — including changes made with a gamepad while wearing the headset.
 *
 * Keys are the VrSettings field names, with enums as their `name` string. Legacy Flutter values
 * ("MONO") are accepted and mapped so older persisted preferences do not break the page.
 */
object VrSettingsCodec {

    fun toMap(s: VrSettings): Map<String, Any> = mapOf(
        "screenScale" to s.screenScale.toDouble(),
        "screenDistanceMeters" to s.screenDistanceMeters.toDouble(),
        "brightness" to s.brightness.toDouble(),
        "curvature" to s.curvature.toDouble(),
        "screenVerticalOffset" to s.screenVerticalOffset.toDouble(),
        "viewerMode" to s.viewerMode.name,
        "headTrackingEnabled" to s.headTrackingEnabled,
        "headTrackingSensitivity" to s.headTrackingSensitivity.toDouble(),
        "gamepadDeadZone" to s.gamepadDeadZone.toDouble(),
        "menuRepeatMs" to s.menuRepeatMs,
        "showStatus" to s.showStatus,
        "showCenterAlignmentLine" to s.showCenterAlignmentLine,
        "horizontalMirror" to s.horizontalMirror,
        "verticalFlip" to s.verticalFlip,
        "backgroundEnabled" to s.backgroundEnabled,
        "backgroundImageUri" to s.backgroundImageUri,
        "backgroundBrightness" to s.backgroundBrightness.toDouble(),
        "ipdScale" to s.ipdScale.toDouble(),
        "screenFillMode" to s.screenFillMode.name,
        "zoomScale" to s.zoomScale.toDouble(),
        "passthroughEnabled" to s.passthroughEnabled,
        "passthroughCameraFacing" to s.passthroughCameraFacing.name,
        "passthroughOpacity" to s.passthroughOpacity.toDouble(),
        "passthroughWindowScale" to s.passthroughWindowScale.toDouble(),
        "passthroughAspect" to s.passthroughAspect.name,
        "passthroughMode" to s.passthroughMode.name,
        "passthroughResolution" to s.passthroughResolution.name,
        "passthroughFrameRate" to s.passthroughFrameRate.name,
        "passthroughRotation" to s.passthroughRotation.name,
        "passthroughMirror" to s.passthroughMirror,
        "passthroughHeadLocked" to s.passthroughHeadLocked,
    )

    /** Applies a partial patch onto [base]; unknown keys are ignored. */
    fun fromMap(base: VrSettings, patch: Map<String, Any?>): VrSettings = base.copy(
        screenScale = patch.float("screenScale", base.screenScale),
        screenDistanceMeters = patch.float("screenDistanceMeters", base.screenDistanceMeters),
        brightness = patch.float("brightness", base.brightness),
        curvature = patch.float("curvature", base.curvature),
        screenVerticalOffset = patch.float("screenVerticalOffset", base.screenVerticalOffset),
        viewerMode = patch.enum("viewerMode", base.viewerMode, legacyViewerMode = true),
        headTrackingEnabled = patch.bool("headTrackingEnabled", base.headTrackingEnabled),
        headTrackingSensitivity = patch.float("headTrackingSensitivity", base.headTrackingSensitivity),
        gamepadDeadZone = patch.float("gamepadDeadZone", base.gamepadDeadZone),
        menuRepeatMs = patch.long("menuRepeatMs", base.menuRepeatMs),
        showStatus = patch.bool("showStatus", base.showStatus),
        showCenterAlignmentLine = patch.bool("showCenterAlignmentLine", base.showCenterAlignmentLine),
        horizontalMirror = patch.bool("horizontalMirror", base.horizontalMirror),
        verticalFlip = patch.bool("verticalFlip", base.verticalFlip),
        backgroundEnabled = patch.bool("backgroundEnabled", base.backgroundEnabled),
        backgroundImageUri = patch.string("backgroundImageUri", base.backgroundImageUri),
        backgroundBrightness = patch.float("backgroundBrightness", base.backgroundBrightness),
        ipdScale = patch.float("ipdScale", base.ipdScale),
        screenFillMode = patch.enum("screenFillMode", base.screenFillMode),
        zoomScale = patch.float("zoomScale", base.zoomScale),
        passthroughEnabled = patch.bool("passthroughEnabled", base.passthroughEnabled),
        passthroughCameraFacing = patch.enum("passthroughCameraFacing", base.passthroughCameraFacing),
        passthroughOpacity = patch.float("passthroughOpacity", base.passthroughOpacity),
        passthroughWindowScale = patch.float("passthroughWindowScale", base.passthroughWindowScale),
        passthroughAspect = patch.enum("passthroughAspect", base.passthroughAspect),
        passthroughMode = patch.enum("passthroughMode", base.passthroughMode),
        passthroughResolution = patch.enum("passthroughResolution", base.passthroughResolution),
        passthroughFrameRate = patch.enum("passthroughFrameRate", base.passthroughFrameRate),
        passthroughRotation = patch.enum("passthroughRotation", base.passthroughRotation),
        passthroughMirror = patch.bool("passthroughMirror", base.passthroughMirror),
        passthroughHeadLocked = patch.bool("passthroughHeadLocked", base.passthroughHeadLocked),
    )

    private fun Map<String, Any?>.float(key: String, fallback: Float): Float =
        (this[key] as? Number)?.toFloat() ?: fallback

    private fun Map<String, Any?>.long(key: String, fallback: Long): Long =
        (this[key] as? Number)?.toLong() ?: fallback

    private fun Map<String, Any?>.bool(key: String, fallback: Boolean): Boolean =
        this[key] as? Boolean ?: fallback

    private fun Map<String, Any?>.string(key: String, fallback: String): String =
        this[key] as? String ?: fallback

    private inline fun <reified T : Enum<T>> Map<String, Any?>.enum(
        key: String,
        fallback: T,
        legacyViewerMode: Boolean = false,
    ): T {
        val raw = this[key] as? String ?: return fallback
        if (legacyViewerMode && (raw == "MONO" || raw == "MONOCULAR")) {
            return enumValueOf<T>("SIMULATION")
        }
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }
}
