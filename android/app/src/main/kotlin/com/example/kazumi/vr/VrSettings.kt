package com.example.kazumi.vr

/** Rendering mode. CARDBOARD = stereo + lens distortion; SIMULATION = monocular, bare-phone. */
enum class VrViewerMode { CARDBOARD, SIMULATION }

/**
 * How the stereo picture occupies the PHYSICAL screen (the "全屏填充" control).
 *
 * DISTORTED = original Cardboard path, unchanged. CardboardView.onRenderStereoFrame() calls
 * renderEyeToDisplay(), which warps both eyes through a lens-distortion mesh. That mesh is built
 * from a FOV clamped to the device params (lens_distortion.cc:189-194), so it only covers the
 * middle of the screen: the round/barrel eye windows with BLACK BANDS above/below in the user's
 * screenshot. This is the exact "之前那样" rendering and the one-click rollback.
 *
 * FILL = "单纯放大". The SDK stereo + lens-distortion rendering is left COMPLETELY intact (both
 * eye matrices, head pose, IPD shift, barrel correction, 360° background — everything). Once it
 * has been composited onto the default framebuffer, onFinishFrame() copies that finished picture
 * into a texture and blits it back with a centred, UNIFORM zoom of [VrSettings.zoomScale]. The
 * black bands are simply pushed off the edges; both eyes grow together so they still fuse in a
 * viewer. No FOV change, no projection change, no new geometry, no shader change, no SDK C++
 * change. Only the vertical direction is GUARANTEED to fill; the horizontal edges are allowed to
 * keep a small black margin ("不是说全部填满只是上下至少要填满").
 *
 * Adjustable and revertible: zoomScale = 1.00 is a no-op, and DISTORTED skips the post-process
 * entirely. DEFAULT is FILL because that is the requested behaviour.
 */
enum class VrScreenFillMode { DISTORTED, FILL }

/**
 * Which phone camera feeds the VR "透视 / Passthrough" background.
 *
 * BACK is the default: with a Cardboard viewer the rear lens points outward through the box, so it
 * is the one that shows the real surroundings. FRONT is offered because many cheap viewers have no
 * rear cut-out; the front lens then at least shows the user's own side of the room. Switching is a
 * first-class setting (not a hardcoded choice) because the correct answer depends on the physical
 * viewer, which the app cannot detect.
 */
enum class VrPassthroughFacing { FRONT, BACK }

/**
 * Aspect ratio of the head-locked passthrough WINDOW. The camera preview buffer and the window
 * quad always share the SAME ratio, so the picture is never stretched.
 *
 * FOUR_THREE is the default on purpose: the vast majority of phone camera sensors are natively
 * 4:3 (e.g. 4032x3024), and a 16:9 preview is just a cropped/centre-cut of that sensor. Using
 * 4:3 therefore shows MORE of the real surroundings (especially vertically) with no crop. The
 * 16:9 option is kept for devices whose Camera2 pipeline only advertises 16:9 preview sizes.
 */
enum class VrPassthroughAspect { FOUR_THREE, SIXTEEN_NINE }

/**
 * How the live camera window and the streamed game picture share the VR view.
 *
 *  - OVERLAY (default): camera = centred head-locked window BEHIND the game screen; the game
 *    stays full-size and on top. This is the original passthrough design.
 *  - CAMERA_MAIN: camera window is enlarged to the main picture; the stream shrinks to a small
 *    bottom-right picture-in-picture, i.e. it "goes to the background".
 *  - SIDE_BY_SIDE: camera window on the LEFT, game window on the RIGHT, two non-overlapping
 *    windows. Both keep their own aspect ratio (4:3 / 16:9) — nothing is ever stretched.
 *
 * The L2+R2 long-press is ONLY an ON/OFF switch for `passthroughEnabled` (user request: "快捷键
 * 只负责开启和关闭，具体的样式调整放在设置里面调就好"). It NEVER changes this layout, so a
 * gamepad shortcut cannot silently re-arrange the picture mid-game. The three styles are selected
 * exclusively in the phone-side settings section and the in-VR menu's 环境 page.
 */
enum class VrPassthroughMode { OVERLAY, CAMERA_MAIN, SIDE_BY_SIDE }

/**
 * Camera preview RESOLUTION tier, expressed as the picture's SHORT edge in pixels (the usual
 * "360P / 480P / 720P / 1080P" naming). P480 is the default: it is the tier the user actually
 * uses on their 30 fps phone and it keeps the preview cheap enough for a low-end VR device, while
 * P720 / P1080 are offered for faster phones / higher-detail viewers.
 *
 * This is a TARGET, not a hardcoded buffer size: VrPassthroughCamera picks the closest size the
 * device actually advertises in StreamConfigurationMap (preferring an exact aspect match), and the
 * renderer builds the window quad from that REAL size. So the picture is never stretched even when
 * the device has no exact 640x480 / 1280x720 preview mode.
 */
enum class VrPassthroughResolution(val shortEdge: Int) { P360(360), P480(480), P720(720), P1080(1080) }

/**
 * Target preview frame rate. FPS_30 is the default. FPS_15 is a low-power / low-light tier for
 * weak phones or when passthrough only needs to be a rough "see the room" guide; FPS_60 is for
 * phones whose AE ranges allow it. Values are ordered low -> high so the menu / settings button
 * cycles in a natural direction. A device that cannot honour a tier silently falls back to the
 * closest supported AE range and the EFFECTIVE fps is shown in the VR status panel.
 */
enum class VrPassthroughFrameRate(val fps: Int) { FPS_15(15), FPS_30(30), FPS_60(60) }

/**
 * Clockwise rotation applied to the camera preview so it reads upright in the VR window.
 *
 * AUTO uses the sensor-installation angle reported by the Camera2 Listener (see
 * VrPassthroughCamera.previewRotationDeg) — the preview buffer is NOT rotated by the display and
 * getTransformMatrix() contains no rotation, so the sensor angle is the correct baseline. Every
 * other value is a MANUAL override: sensor orientation and physical viewer mounting differ wildly
 * between phones, and the user explicitly asked to be able to "自己调一下" against a real photo.
 * The status panel shows the effective angle so the correct override is one A-press away.
 */
enum class VrPassthroughRotation(val degrees: Int) { AUTO(-1), R0(0), R90(90), R180(180), R270(270) }

/** Numeric bounds for every adjustable VR parameter. Kept here so UI, storage and tests agree. */
object VrRanges {
    const val SCREEN_SCALE_MIN = 0.5f
    const val SCREEN_SCALE_MAX = 3.0f
    const val SCREEN_SCALE_STEP = 0.1f

    const val DISTANCE_MIN = 0.8f
    const val DISTANCE_MAX = 6.0f
    const val DISTANCE_STEP = 0.2f

    const val BRIGHTNESS_MIN = 0.4f
    const val BRIGHTNESS_MAX = 1.4f
    const val BRIGHTNESS_STEP = 0.05f

    const val CURVATURE_MIN = 0.0f
    const val CURVATURE_MAX = 1.0f
    const val CURVATURE_STEP = 0.1f

    const val VERTICAL_OFFSET_MIN = -1.0f
    const val VERTICAL_OFFSET_MAX = 1.0f
    const val VERTICAL_OFFSET_STEP = 0.1f

    const val HEAD_SENSITIVITY_MIN = 0.0f
    const val HEAD_SENSITIVITY_MAX = 1.5f
    const val HEAD_SENSITIVITY_STEP = 0.1f

    const val DEAD_ZONE_MIN = 0.05f
    const val DEAD_ZONE_MAX = 0.5f
    const val DEAD_ZONE_STEP = 0.05f

    const val BACKGROUND_BRIGHTNESS_MIN = 0.2f
    const val BACKGROUND_BRIGHTNESS_MAX = 1.5f
    const val BACKGROUND_BRIGHTNESS_STEP = 0.05f

    /** Interpupillary distance, expressed as a multiplier of the Cardboard device default IPD. */
    const val IPD_SCALE_MIN = 0.5f
    const val IPD_SCALE_MAX = 1.6f
    const val IPD_SCALE_STEP = 0.05f

    /**
     * Uniform post-process zoom used by VrScreenFillMode.FILL. 1.00 = original SDK output.
     * The upper bound was raised from 2.00 to 2.50 after real-device feedback: Cardboard QR
     * profiles with a narrow distortion mesh need more than 2x to push the top/bottom bands off.
     */
    const val ZOOM_MIN = 1.00f
    const val ZOOM_MAX = 2.50f
    const val ZOOM_STEP = 0.02f

    /**
     * 透视 / Passthrough background visibility. 0.00 = camera picture fully hidden (the original
     * pure-black background and the optional 360° panorama take over), 1.00 = fully live camera.
     * Implemented as a brightness multiply on the camera quad over a pure-black clear, which is
     * visually identical to alpha blending against black but needs NO GL_BLEND, NO shader change
     * and NO mutation of the Cardboard SDK's GL state.
     */
    const val PASSTHROUGH_OPACITY_MIN = 0.00f
    const val PASSTHROUGH_OPACITY_MAX = 1.00f
    const val PASSTHROUGH_OPACITY_STEP = 0.05f

    /**
     * Size of the head-locked camera window (relative to a baseline that already clears the
     * stream screen). A phone camera is NOT a 360° camera: it has one ~70° view, so its picture
     * must stay a centred, correctly-proportioned window instead of being stretched over the whole
     * VR environment. 1.00 is the balanced default; smaller values turn it into a small picture-
     * in-picture window, larger values let it approach the viewer's full field of view.
     */
    const val PASSTHROUGH_WINDOW_MIN = 0.30f
    const val PASSTHROUGH_WINDOW_MAX = 1.50f
    const val PASSTHROUGH_WINDOW_STEP = 0.05f

    const val REPEAT_MIN_MS = 60L
    const val REPEAT_MAX_MS = 500L
    const val REPEAT_STEP_MS = 20L
}

/**
 * All user-adjustable VR parameters. Pure Kotlin, no Android imports — safe for JVM unit tests.
 *
 * screenScale is the headline feature: it enlarges the flat (2D) stream screen. The stream itself
 * is NEVER rendered as stereoscopic 3D; only the virtual environment is stereo.
 */
data class VrSettings(
    val screenScale: Float = 1.0f,
    val screenDistanceMeters: Float = 2.2f,
    val brightness: Float = 1.0f,
    val curvature: Float = 0.0f,
    val screenVerticalOffset: Float = 0.0f,
    val viewerMode: VrViewerMode = VrViewerMode.CARDBOARD,
    val headTrackingEnabled: Boolean = true,
    val headTrackingSensitivity: Float = 1.0f,
    val gamepadDeadZone: Float = 0.20f,
    val menuRepeatMs: Long = 140L,
    val showStatus: Boolean = true,
    /** When true, draws ONE vertical center-alignment line as a FIXED SCREEN-SPACE overlay:
     *  it is NOT part of the VR quad / panel texture, does NOT follow head rotation, and is drawn
     *  once per eye viewport at the exact screen centre (from the bottom up to the middle).
     *  Used to confirm the two eyes are rendering stereoscopically. Adjustable. */
    val showCenterAlignmentLine: Boolean = false,
    /** When true, mirrors the STREAM only, left-right. Status panel, menu and 360° background are
     *  untouched. Implemented with quad texture coordinates, no shader source change. */
    val horizontalMirror: Boolean = false,
    /** When true, flips the STREAM only, top-bottom. Status panel, menu and 360° background are
     *  untouched. Independent of [horizontalMirror]; enabling both rotates the stream by 180°. */
    val verticalFlip: Boolean = false,
    /** When true, draws a 360° (equirectangular) image as the VR environment behind the screen.
     *  Off by default to keep the original pure-black behaviour and the low-end perf budget. */
    val backgroundEnabled: Boolean = false,
    /** Content URI or file path of the user-imported 360° image. Empty = built-in procedural
     *  starfield, so the background always shows something when enabled (no asset required). */
    val backgroundImageUri: String = "",
    /** Independent brightness for the 360° background only (the screen keeps its own brightness). */
    val backgroundBrightness: Float = 1.0f,
    /**
     * Effective interpupillary distance / 等效瞳距, as a multiplier of the Cardboard default.
     *
     * 1.0 = unchanged SDK rendering (no shift at all). Lower values pull the two eyes' images
     * together (weaker stereo, easier fusion); higher values push them apart (stronger stereo).
     *
     * Implemented as an equal-and-opposite screen-space shift of each eye's final clip-space x
     * (`VrMatrix4.applyIpdShiftInto`), NOT by scaling `Eye.getEyeFromHead()` — the latter also
     * feeds the off-axis projection and distortion mesh, so scaling it alone desynchronises the
     * camera from its frustum and breaks stereo fusion. Pure Kotlin, NO shader source change,
     * NO SDK device-parameter mutation. MONOCULAR/SIMULATION mode is unaffected.
     */
    val ipdScale: Float = 1.0f,
    /**
     * DISTORTED = original Cardboard stereo + lens-distortion mesh (round windows, black bands
     * top/bottom). FILL = the SAME stereo + distortion, uniformly enlarged afterwards until the
     * vertical direction is filled. One-click rollback to DISTORTED at any time.
     */
    val screenFillMode: VrScreenFillMode = VrScreenFillMode.FILL,
    /**
     * "单纯放大": uniform zoom applied to the FINISHED stereo frame in onFinishFrame().
     * 1.00 = no zoom at all (bit-identical to the original SDK output). 1.35 is the new default:
     * real-device feedback on the first build ("依然不能全屏") showed 1.18 was not enough even
     * when the post-process ran. Raise it further if your viewer/QR profile has wider bands.
     * Pure post-process: no FOV, projection, geometry, shader or SDK C++ change, both eyes stay
     * fused, and the settings panel is deliberately NOT zoomed so its rows are never cropped.
     */
    val zoomScale: Float = 1.35f,
    /**
     * 摄像头透视 / Passthrough: show the live phone camera as the VR background instead of pure
     * black, so the surroundings stay visible without taking the headset off. OFF by default —
     * it needs the CAMERA runtime permission and opens a real camera device, so it must never be
     * enabled implicitly. While on it takes precedence over the 360° panorama (both are
     * "background" layers; drawing both would double-composite). If the camera is missing,
     * permission is denied, or the device is busy (e.g. the stream is recording from it), the
     * renderer silently falls back to the original black / panorama background — VR never fails.
     */
    val passthroughEnabled: Boolean = false,
    /** Which physical lens to open. Adjustable; see [VrPassthroughFacing]. */
    val passthroughCameraFacing: VrPassthroughFacing = VrPassthroughFacing.BACK,
    /** Camera-background visibility, 0.00..1.00. See [VrRanges.PASSTHROUGH_OPACITY_MIN]. */
    val passthroughOpacity: Float = 1.00f,
    /**
     * Size of the head-locked camera WINDOW. The camera is not 360°, so its picture is drawn as a
     * centred 4:3 (or 16:9, see [passthroughAspect]) window in VR space (eye perspective + IPD
     * translation, head rotation removed) rather than stretched over the whole environment.
     * The stream screen is drawn ON TOP of it;
     * the area outside the window stays pure black. See [VrRanges.PASSTHROUGH_WINDOW_MIN].
     */
    val passthroughWindowScale: Float = 0.85f,
    /**
     * Aspect of the camera window. 4:3 by default — see [VrPassthroughAspect]. The Camera2 preview
     * buffer is sized to exactly this ratio and the window quad is built from the same ratio, so
     * switching it never stretches the picture; it only changes how much of the sensor is visible.
     */
    val passthroughAspect: VrPassthroughAspect = VrPassthroughAspect.FOUR_THREE,
    /** Layout of the camera window vs the game picture. See [VrPassthroughMode]. */
    val passthroughMode: VrPassthroughMode = VrPassthroughMode.OVERLAY,
    /** Preview resolution tier (short edge). See [VrPassthroughResolution]. Default 480P. */
    val passthroughResolution: VrPassthroughResolution = VrPassthroughResolution.P480,
    /** Target preview frame rate (15 / 30 / 60). Default 30 = the user's phone limit. */
    val passthroughFrameRate: VrPassthroughFrameRate = VrPassthroughFrameRate.FPS_30,
    /**
     * Clockwise preview rotation. AUTO follows the sensor baseline; 0/90/180/270 are manual
     * overrides for weird sensor orientations / viewer mounting. See [VrPassthroughRotation].
     */
    val passthroughRotation: VrPassthroughRotation = VrPassthroughRotation.AUTO,
    /**
     * Extra horizontal mirror XOR'd with the camera's automatic front-lens mirroring. Lets the
     * user flip ANY lens back if the picture reads backwards in their viewer. Default off.
     */
    val passthroughMirror: Boolean = false,
    /**
     * Whether the camera window is pinned to the viewer's eyes (head-locked) or rigidly attached
     * to the stream screen (world-locked).
     *
     * Default FALSE. On a real device the ORIGINAL head-locked window stayed dead centre while the
     * world-locked cinema screen drifted with head motion, so the two layers visibly slid against
     * each other — the "很大的不协调感" report. FALSE makes both layers share the SAME base MVP:
     * they move together, the window reads as a pane mounted behind the screen, and there is no
     * relative motion at all. TRUE restores the old "pinned in front of your eyes" behaviour for
     * viewers who prefer it; it is kept as an option rather than deleted.
     */
    val passthroughHeadLocked: Boolean = false,
) {
    /** Clamps every numeric field into its declared range. Idempotent. */
    fun sanitized(): VrSettings = copy(
        screenScale = screenScale.coerceIn(VrRanges.SCREEN_SCALE_MIN, VrRanges.SCREEN_SCALE_MAX),
        screenDistanceMeters = screenDistanceMeters.coerceIn(VrRanges.DISTANCE_MIN, VrRanges.DISTANCE_MAX),
        brightness = brightness.coerceIn(VrRanges.BRIGHTNESS_MIN, VrRanges.BRIGHTNESS_MAX),
        curvature = curvature.coerceIn(VrRanges.CURVATURE_MIN, VrRanges.CURVATURE_MAX),
        screenVerticalOffset = screenVerticalOffset.coerceIn(
            VrRanges.VERTICAL_OFFSET_MIN, VrRanges.VERTICAL_OFFSET_MAX,
        ),
        headTrackingSensitivity = headTrackingSensitivity.coerceIn(
            VrRanges.HEAD_SENSITIVITY_MIN, VrRanges.HEAD_SENSITIVITY_MAX,
        ),
        gamepadDeadZone = gamepadDeadZone.coerceIn(VrRanges.DEAD_ZONE_MIN, VrRanges.DEAD_ZONE_MAX),
        menuRepeatMs = menuRepeatMs.coerceIn(VrRanges.REPEAT_MIN_MS, VrRanges.REPEAT_MAX_MS),
        showCenterAlignmentLine = showCenterAlignmentLine,
        horizontalMirror = horizontalMirror,
        verticalFlip = verticalFlip,
        backgroundEnabled = backgroundEnabled,
        backgroundImageUri = backgroundImageUri,
        backgroundBrightness = backgroundBrightness.coerceIn(
            VrRanges.BACKGROUND_BRIGHTNESS_MIN, VrRanges.BACKGROUND_BRIGHTNESS_MAX,
        ),
        ipdScale = ipdScale.coerceIn(VrRanges.IPD_SCALE_MIN, VrRanges.IPD_SCALE_MAX),
        screenFillMode = screenFillMode,
        zoomScale = zoomScale.coerceIn(VrRanges.ZOOM_MIN, VrRanges.ZOOM_MAX),
        passthroughEnabled = passthroughEnabled,
        passthroughCameraFacing = passthroughCameraFacing,
        passthroughOpacity = passthroughOpacity.coerceIn(
            VrRanges.PASSTHROUGH_OPACITY_MIN, VrRanges.PASSTHROUGH_OPACITY_MAX,
        ),
        passthroughWindowScale = passthroughWindowScale.coerceIn(
            VrRanges.PASSTHROUGH_WINDOW_MIN, VrRanges.PASSTHROUGH_WINDOW_MAX,
        ),
        passthroughAspect = passthroughAspect,
        passthroughMode = passthroughMode,
        passthroughResolution = passthroughResolution,
        passthroughFrameRate = passthroughFrameRate,
        passthroughRotation = passthroughRotation,
        passthroughMirror = passthroughMirror,
        passthroughHeadLocked = passthroughHeadLocked,
    )

    companion object {
        val DEFAULT = VrSettings()
    }
}
