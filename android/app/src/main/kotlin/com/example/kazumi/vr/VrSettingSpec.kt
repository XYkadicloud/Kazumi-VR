package com.example.kazumi.vr

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Machine-readable description of every numeric VR setting exposed by the PHONE-SIDE (2D) panel.
 *
 * Pure Kotlin on purpose: [VrSettingsSection] renders its sliders from this list, and JVM unit
 * tests verify ranges, step quantisation and read/write round-trips without any Android framework
 * stubs. Bounds come from [VrRanges], so the UI, storage and tests cannot drift apart.
 *
 * This is the single source of truth for the phone controls. [VrMenuModel] remains the
 * gamepad-driven in-VR menu; BOTH paths call [VrSession.updateSettings], so they stay consistent.
 */
data class VrSettingSpec(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val unit: String,
    val read: (VrSettings) -> Float,
    val write: (VrSettings, Float) -> VrSettings,
) {
    init {
        require(min < max) { "invalid range for $id" }
        require(step > 0f && step <= max - min) { "invalid step for $id" }
        require(read(VrSettings.DEFAULT) in min..max) { "default value out of range for $id" }
    }

    /** Material3 Slider's `steps` counts the tick marks EXCLUDING both endpoints. */
    val sliderSteps: Int
        get() = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)

    /** Snaps a raw slider value onto the declared step grid, then clamps into range. */
    fun quantize(raw: Float): Float {
        if (raw.isNaN() || raw.isInfinite()) return min
        val snapped = min + ((raw - min) / step).roundToInt() * step
        return snapped.coerceIn(min, max)
    }

    fun format(value: Float): String = when {
        step >= 1f -> String.format(Locale.US, "%d %s", value.roundToInt(), unit).trim()
        step >= 0.1f -> String.format(Locale.US, "%.1f %s", value, unit).trim()
        else -> String.format(Locale.US, "%.2f %s", value, unit).trim()
    }
}

/** All numeric controls shown on the phone. Order is the display order. */
object VrSettingSpecs {
    val ALL: List<VrSettingSpec> = listOf(
        VrSettingSpec(
            id = "screenScale",
            label = "串流窗口大小",
            min = VrRanges.SCREEN_SCALE_MIN,
            max = VrRanges.SCREEN_SCALE_MAX,
            step = VrRanges.SCREEN_SCALE_STEP,
            unit = "x",
            read = { it.screenScale },
            write = { s, v -> s.copy(screenScale = v) },
        ),
        VrSettingSpec(
            id = "screenDistance",
            label = "幕布距离",
            min = VrRanges.DISTANCE_MIN,
            max = VrRanges.DISTANCE_MAX,
            step = VrRanges.DISTANCE_STEP,
            unit = "m",
            read = { it.screenDistanceMeters },
            write = { s, v -> s.copy(screenDistanceMeters = v) },
        ),
        VrSettingSpec(
            id = "brightness",
            label = "画面亮度",
            min = VrRanges.BRIGHTNESS_MIN,
            max = VrRanges.BRIGHTNESS_MAX,
            step = VrRanges.BRIGHTNESS_STEP,
            unit = "",
            read = { it.brightness },
            write = { s, v -> s.copy(brightness = v) },
        ),
        VrSettingSpec(
            id = "curvature",
            label = "幕布曲面",
            min = VrRanges.CURVATURE_MIN,
            max = VrRanges.CURVATURE_MAX,
            step = VrRanges.CURVATURE_STEP,
            unit = "",
            read = { it.curvature },
            write = { s, v -> s.copy(curvature = v) },
        ),
        VrSettingSpec(
            id = "verticalOffset",
            label = "画面上下位置",
            min = VrRanges.VERTICAL_OFFSET_MIN,
            max = VrRanges.VERTICAL_OFFSET_MAX,
            step = VrRanges.VERTICAL_OFFSET_STEP,
            unit = "m",
            read = { it.screenVerticalOffset },
            write = { s, v -> s.copy(screenVerticalOffset = v) },
        ),
        VrSettingSpec(
            id = "headSensitivity",
            label = "头部追踪灵敏度",
            min = VrRanges.HEAD_SENSITIVITY_MIN,
            max = VrRanges.HEAD_SENSITIVITY_MAX,
            step = VrRanges.HEAD_SENSITIVITY_STEP,
            unit = "x",
            read = { it.headTrackingSensitivity },
            write = { s, v -> s.copy(headTrackingSensitivity = v) },
        ),
        VrSettingSpec(
            id = "gamepadDeadZone",
            label = "手柄摇杆死区",
            min = VrRanges.DEAD_ZONE_MIN,
            max = VrRanges.DEAD_ZONE_MAX,
            step = VrRanges.DEAD_ZONE_STEP,
            unit = "",
            read = { it.gamepadDeadZone },
            write = { s, v -> s.copy(gamepadDeadZone = v) },
        ),
        VrSettingSpec(
            id = "ipdScale",
            label = "瞳距 / IPD 缩放",
            min = VrRanges.IPD_SCALE_MIN,
            max = VrRanges.IPD_SCALE_MAX,
            step = VrRanges.IPD_SCALE_STEP,
            unit = "x",
            read = { it.ipdScale },
            write = { s, v -> s.copy(ipdScale = v) },
        ),
        VrSettingSpec(
            id = "zoomScale",
            label = "整体放大（上下填满）",
            min = VrRanges.ZOOM_MIN,
            max = VrRanges.ZOOM_MAX,
            step = VrRanges.ZOOM_STEP,
            unit = "x",
            read = { it.zoomScale },
            write = { s, v -> s.copy(zoomScale = v) },
        ),
        VrSettingSpec(
            id = "passthroughOpacity",
            label = "摄像头透视可见度",
            min = VrRanges.PASSTHROUGH_OPACITY_MIN,
            max = VrRanges.PASSTHROUGH_OPACITY_MAX,
            step = VrRanges.PASSTHROUGH_OPACITY_STEP,
            unit = "",
            read = { it.passthroughOpacity },
            write = { s, v -> s.copy(passthroughOpacity = v) },
        ),
        VrSettingSpec(
            id = "passthroughWindowScale",
            label = "透视窗口大小",
            min = VrRanges.PASSTHROUGH_WINDOW_MIN,
            max = VrRanges.PASSTHROUGH_WINDOW_MAX,
            step = VrRanges.PASSTHROUGH_WINDOW_STEP,
            unit = "x",
            read = { it.passthroughWindowScale },
            write = { s, v -> s.copy(passthroughWindowScale = v) },
        ),
        VrSettingSpec(
            id = "menuRepeatMs",
            label = "菜单连发间隔",
            min = VrRanges.REPEAT_MIN_MS.toFloat(),
            max = VrRanges.REPEAT_MAX_MS.toFloat(),
            step = VrRanges.REPEAT_STEP_MS.toFloat(),
            unit = "ms",
            read = { it.menuRepeatMs.toFloat() },
            write = { s, v -> s.copy(menuRepeatMs = v.roundToInt().toLong()) },
        ),
    )

    fun byId(id: String): VrSettingSpec? = ALL.firstOrNull { it.id == id }
}
