package com.example.kazumi.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.google.cardboard.sdk.CardboardView
import com.google.cardboard.sdk.HeadTransform
import com.google.cardboard.sdk.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the "half VR" theatre on CardboardView.Renderer.
 *
 * WHAT IS ON SCREEN
 *  - Background: pure black (set via glClearColor; the SDK performs the actual clear).
 *  - Exactly ONE flat 2D quad in front of the viewer. No 3D scene, no geometry, no skybox.
 *  - With a stream: the decoder SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) is the quad's texture.
 *    Zero-copy: the video never passes through a Bitmap or a second render target.
 *  - Without a stream: a 2D Canvas panel (GL_TEXTURE_2D) shows status and gamepad hints, so
 *    entering VR is never a blank screen.
 *  - When the gamepad opens the settings menu (long-press START+SELECT or HOME/MODE), the SAME
 *    quad shows a Canvas-drawn two-column menu driven exclusively by VrGamepadRouter commands.
 *    It is not a persistent overlay: it appears only while the user holds it open and disappears
 *    on B / SELECT / START / MODE.
 *  - Both eyes sample the SAME quad and the SAME texture. This is a virtual cinema screen, NOT
 *    stereoscopic 3D video. No SBS, no parallax, no per-eye content.
 *
 * CORRECTNESS NOTE (do not "simplify" this away)
 *  CardboardView.onRenderStereoFrame() binds ONE shared FBO, clears it once, then separates the
 *  eyes only with glViewport() - there is NO scissor test. Calling glClear() inside onDrawEye()
 *  would therefore erase the left eye when the right eye is drawn. The black background is
 *  produced by glClearColor() in onSurfaceCreated() plus the SDK's own clear. Do NOT add a clear
 *  to onDrawEye().
 *
 * LOW-END PERFORMANCE BUDGET (OPPO F9 / Helio P60 / Mali-G72 MP3; see HANDOFF.md section 9)
 *  - Zero heap allocation per NORMAL frame: reused matrices (multiplyInto / scaledRotationInto),
 *    reused direct FloatBuffers, reused Bitmap/Canvas/Paints, no string formatting in the hot
 *    path. The Cardboard SDK's Eye matrices are already preallocated and returned by reference.
 *  - Diagnostic logging (added to locate "VR shows nothing"): lifecycle events and state edges
 *    log once; the onDrawEye heartbeat logs only once every LOG_INTERVAL_FRAMES (120) frames.
 *    That single throttled line is the only per-frame-string cost and is dev-only; it does not
 *    affect steady-state throughput and can be silenced with logcat filters.
 *  - Flat screen => 4 vertices instead of 50 (24-segment strip only when curvature > 0).
 *  - Only ONE textured quad is drawn per eye, including while the menu is open.
 *  - Panel is redrawn only when its content actually changed (menu navigation / settings) and
 *    uploaded with texSubImage2D, so no texture is reallocated.
 *  - No blending, no depth test, no MSAA, no mipmaps, GL_DITHER disabled, LINEAR filtering only.
 *  - Brightness is a single multiply in the fragment shader, not an extra render pass.
 *  - The external-OES program is optional: if a driver cannot compile it, initialisation falls
 *    back to the 2D panel instead of crashing the GL thread.
 *  - The frame-available callback only flips an AtomicBoolean; it is not posted to the main
 *    thread, so 60 fps decoding does not enqueue 60 main-loop messages per second.
 *
 * All in-VR control remains physical gamepad input. No gaze / eye / dwell / reticle logic exists.
 */
class VrTheaterRenderer(private val context: Context) : CardboardView.Renderer {

    private class ProgramHandles(
        val program: Int,
        val position: Int,
        val texCoord: Int,
        val mvp: Int,
        val texture: Int,
        val brightness: Int,
        val stMatrix: Int,
        val flip: Int,
    )

    private var panel: ProgramHandles? = null
    private var video: ProgramHandles? = null

    private var panelTextureId = 0
    private var videoTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    // 360° background sphere state (see buildBackgroundMesh / drawBackground).
    private var bgPositionBuffer: FloatBuffer? = null
    private var bgTexCoordBuffer: FloatBuffer? = null
    private var bgVertexCount = 0
    private var bgTextureId = 0
    private var bgReady = false
    @Volatile private var bgDirty = true

    @Volatile private var hasVideoFrame = false
    @Volatile private var panelDirty = true
    private var forcePanelUpload = true

    private var panelBitmap: Bitmap? = null
    private var panelCanvas: Canvas? = null
    private var borderPaint: Paint? = null
    private var panelBackgroundPaint: Paint? = null
    private var titlePaint: Paint? = null
    private var bodyPaint: Paint? = null
    private var valuePaint: Paint? = null
    private var hintPaint: Paint? = null
    private var selectedPaint: Paint? = null
    private var selectedRect: RectF? = null

    @Volatile private var settings: VrSettings = VrSession.settings.value
    @Volatile private var vertexCount = 0
    @Volatile private var meshDirty = true
    private var lastAspect: Float = VrSession.videoAspect
    private var lastMenuOpen = false
    private var lastSelectedIndex = -1
    private var lastMenuPage: VrMenuPage = VrMenuModel.pages.first()
    private var lastEditMode = false

    // Diagnostic-only logging state. All access is on the single GL thread, so plain fields are OK.
    // Per-frame logging is throttled to one line every LOG_INTERVAL_FRAMES; key state changes
    // (shader link, first video frame, useVideo flips) are logged once at the edge.
    private var logFrameCounter = 0
    private var logLastUseVideo: Boolean? = null
    private var logLastHandlesNull = false
    private var logLastStatusSkip = false
    private var logFirstFrameAvailable = false
    private var logSurfaceCreated = false

    // Preallocated render-loop state. Nothing below is allocated inside onDrawEye().
    private val headQuaternion = FloatArray(4).also { it[3] = 1f }
    /** Stored center orientation for "recenter". Identity until user triggers recenter. */
    private val centerOffsetQuaternion = FloatArray(4).also { it[3] = 1f }
    private val stMatrix = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    private val identityMatrix = VrMatrix4.identity()
    private val headRotation = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private val positionBuffer: FloatBuffer = newFloatBuffer(MAX_VERTICES * 3)
    private val texCoordBuffer: FloatBuffer = newFloatBuffer(MAX_VERTICES * 2)
    // Video-only texture coordinates. The stream quad can be mirrored / flipped independently of
    // the status panel, the menu and the 360° background — those keep sampling texCoordBuffer.
    private val videoTexCoordBuffer: FloatBuffer = newFloatBuffer(MAX_VERTICES * 2)

    // Fixed PHYSICAL-SCREEN centre alignment line: a 4-vertex quad in NDC, drawn in onFinishFrame()
    // with an identity MVP. It is NOT part of the VR scene: no head rotation, no eye view matrix,
    // no per-eye viewport and no distortion warp, so it stays pinned to the screen centre.
    private val linePositionBuffer: FloatBuffer = newFloatBuffer(12)
    private val lineTexCoordBuffer: FloatBuffer = newFloatBuffer(8)
    private var lineTextureId = 0

    // -----------------------------------------------------------------------------------------
    // "单纯放大 / 上下填满" FILL (VrScreenFillMode.FILL) — a PURE POST-PROCESS of the FINISHED
    // stereo frame. Nothing above this line in the render path is changed.
    //
    // The user's 2026-11-08 screenshot shows two barrel-shaped eye windows with big black bands
    // above/below. Those bands come from the SDK's lens-distortion MESH, whose FOV is clamped to
    // the device params (lens_distortion.cc:189-194). An earlier attempt re-drew both eyes into
    // rectangular half-screen viewports: that removed the distortion but ALSO removed the barrel
    // correction, broke eye fusion and still left the picture a narrow banner, because the SDK's
    // off-axis projection was reused unchanged. WRONG DIRECTION.
    //
    // Correct approach ("单纯放大"): keep 100% of the original stereo + distortion rendering, then
    // in onFinishFrame() copy the composited default framebuffer into a texture and blit it back
    // with ONE centred, UNIFORM zoom (VrSettings.zoomScale). The black bands are pushed off the
    // screen edges; both eye windows grow together so they still overlap/fuse in a cardboard
    // viewer. Only the vertical direction is guaranteed to fill — the horizontal edges may keep
    // a small black margin, exactly as requested. No FOV / projection / geometry / shader / SDK
    // C++ change, and the zero-copy OES video path is untouched.
    // -----------------------------------------------------------------------------------------
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var zoomTextureId = 0
    private var zoomTexWidth = 0
    private var zoomTexHeight = 0
    private var zoomTexMode = -1
    /**
     * -1 = not probed; 0..ZOOM_COPY_FORMATS.lastIndex = a glCopyTexSubImage2D format this driver
     * accepted; ZOOM_READPIXELS = every copy format failed, fall back to glReadPixels + upload.
     *
     * WHY THIS MATTERS: the first FILL build allocated ONLY an RGBA8 texture. Most devices hand
     * Cardboard an RGB565 default framebuffer, and GLES2 rejects that copy with
     * GL_INVALID_OPERATION. The build then "safely" fell back to the untouched SDK frame forever —
     * which is exactly the real-device report "依然好像是不能全屏". Probing formats (and keeping a
     * readPixels escape hatch) makes the zoom actually run instead of silently disabling itself.
     */
    private var zoomSnapshotMode = -1
    /** Set only when EVERY snapshot strategy failed; keeps the untouched SDK frame from then on. */
    private var zoomCopyUnsupported = false
    /** Reused RGBA buffer for the readPixels fallback; reallocated only when the size changes. */
    private var zoomPixels: ByteBuffer? = null
    private var zoomPixelsWidth = 0
    private var zoomPixelsHeight = 0
    /** Static full-screen NDC quad (TRIANGLE_STRIP); filled once in onSurfaceCreated. */
    private val zoomPositionBuffer: FloatBuffer = newFloatBuffer(12)
    private val zoomTexCoordBuffer: FloatBuffer = newFloatBuffer(8)
    /**
     * Edge-triggered diagnostics for the "依然不能全屏" real-device report. Every early return in
     * drawZoomFill() records WHY the zoom did not run, so "no visible change" is diagnosable from
     * logcat instead of being a silent no-op. A healthy render loop stays log-silent.
     */
    private var logLastZoomSkip: String? = null
    private var logLastZoomActive = false

    // -----------------------------------------------------------------------------------------
    // 摄像头透视 / Passthrough — a HEAD-LOCKED WINDOW, deliberately NOT a 360° environment.
    //
    // A phone camera has ONE ~70° view, not a panorama. Stretching a single frame over the whole
    // VR sphere would fake information that does not exist and warp the picture. Instead the live
    // camera is drawn as ONE centred, correctly-proportioned (4:3 by default) window:
    //   * HEAD-LOCKED: its MVP uses eye perspective + eyeFromHead but NO head rotation. The phone
    //     and its camera are rigidly fixed inside the Cardboard viewer, so when the user turns
    //     their head the camera turns too — the picture must stay straight ahead. Keeping the eye
    //     matrices (minus rotation) gives proper binocular parallax, so it reads as "a window in
    //     front of me", not a 2D sticker glued to the eyeball.
    //   * BEHIND the stream screen: drawn first, at a greater distance; the game picture is
    //     composited on top and is never obscured. Everything outside the window stays pure black.
    //   * NO shader change / NO GL_BLEND / NO SDK state mutation: it reuses the existing
    //     VIDEO_FRAGMENT_SHADER (samplerExternalOES + uSTMatrix). Opacity is uBrightness, which
    //     over the pure-black clear is exactly mix(black, camera, opacity).
    //   * Camera2 owns a private HandlerThread; every GL object is created/updated/released on the
    //     GL thread via onSurfaceCreated / onNewFrame / onRendererShutdown. Failure of any kind
    //     (no permission, no lens, busy device) simply stops drawing the window and the original
    //     black / 360° background takes over — VR can never black-screen because of passthrough.
    // -----------------------------------------------------------------------------------------
    private var ptCamera: VrPassthroughCamera? = null
    private var ptTextureId = 0
    private var ptSurfaceTexture: SurfaceTexture? = null
    private var ptSurface: Surface? = null
    private val ptStMatrix = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    private val ptFrameAvailable = AtomicBoolean(false)
    private val ptHasFrame = AtomicBoolean(false)
    private val ptUnavailable = AtomicBoolean(false)
    /** AUTO rotation baseline (sensor installation angle) and front-lens default mirror. */
    @Volatile private var ptAutoRotationDeg = 90
    @Volatile private var ptAutoMirror = false
    /** REAL preview buffer dimensions / effective frame rate, reported by the camera thread.
     *  The window quad is built from THESE numbers, never from a guessed ratio. */
    @Volatile private var ptPreviewW = 0
    @Volatile private var ptPreviewH = 0
    @Volatile private var ptPreviewFps = 0
    /** Bumped by applySettings() and by the camera's config callback (GL thread consumes). */
    @Volatile private var ptConfigDirty = true
    private val ptPositionBuffer: FloatBuffer = newFloatBuffer(12)
    private val ptTexCoordBuffer: FloatBuffer = newFloatBuffer(8)
    /** Head-LOCKED MVP: eye perspective * eyeFromHead, with the head rotation removed. */
    private val headLockedMvpMatrix = FloatArray(16)
    /**
     * Model transform applied to the EXISTING stream quad in CAMERA_MAIN / SIDE_BY_SIDE. Identity
     * for OVERLAY. Applied via MVP (not by rebuilding geometry) so curvature, vertex count and the
     * panel/menu quad are all untouched. Written on the GL thread by rebuildPassthroughQuad().
     */
    private val gameModelMatrix = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    private val gameMvpMatrix = FloatArray(16)
    /** Stream aspect last used for the layout; a change forces rebuildPassthroughQuad(). */
    private var ptAppliedGameAspect = 0f

    private val ptListener = object : VrPassthroughCamera.Listener {
        override fun onUnavailable(reason: String) {
            ptUnavailable.set(true)
            ptHasFrame.set(false)
            // Called on the camera thread; only volatile/atomic state is touched here.
            Log.w(TAG, "passthrough unavailable ($reason) -> black / 360° background fallback")
        }

        override fun onPreviewConfig(
            width: Int,
            height: Int,
            fps: Int,
            autoRotationDeg: Int,
            autoMirror: Boolean,
        ) {
            // Camera-thread callback: only volatile fields are written. The GL thread consumes
            // them on the next syncPassthrough() and rebuilds the quad from the REAL size.
            ptPreviewW = width
            ptPreviewH = height
            ptPreviewFps = fps
            ptAutoRotationDeg = autoRotationDeg
            ptAutoMirror = autoMirror
            ptConfigDirty = true
        }
    }

    // Preallocated paints for the modern two-level menu. Allocated once in createPanelResources();
    // drawMenu()/drawStatus() only re-assign colours, so the render loop stays allocation-free.
    private var menuCardPaint: Paint? = null
    private var menuCardStrokePaint: Paint? = null
    private var menuAccentPaint: Paint? = null
    private var menuTrackPaint: Paint? = null
    private var menuProgressPaint: Paint? = null
    private var menuTabPaint: Paint? = null
    private var menuGradientPaint: Paint? = null

    fun applySettings(value: VrSettings) {
        val prev = settings
        val next = value.sanitized()
        val geometryChanged = next.screenScale != prev.screenScale ||
            next.screenDistanceMeters != prev.screenDistanceMeters ||
            next.screenVerticalOffset != prev.screenVerticalOffset ||
            next.curvature != prev.curvature ||
            next.horizontalMirror != prev.horizontalMirror ||
            next.verticalFlip != prev.verticalFlip
        val panelChanged = next != prev
        settings = next
        // Geometry buffers are only touched on the GL thread (see onDrawEye); applySettings runs
        // on the main thread, so it just raises a flag instead of rebuilding in place.
        if (geometryChanged) meshDirty = true
        if (panelChanged) panelDirty = true
        // Background: only raise a flag. applySettings runs on the MAIN thread; the GL texture
        // (re)load and release MUST happen on the GL thread, so drawBackground() owns all GL work.
        val bgChanged = next.backgroundEnabled != prev.backgroundEnabled ||
            next.backgroundImageUri != prev.backgroundImageUri ||
            next.backgroundBrightness != prev.backgroundBrightness
        if (bgChanged) bgDirty = true
        // Passthrough: camera (re)start, preview buffer size and the window quad all need the GL
        // thread, so applySettings() only raises ONE flag. A screenScale / distance change also
        // rebuilds the window quad so it keeps its size relationship with the stream screen.
        val passthroughChanged = next.passthroughEnabled != prev.passthroughEnabled ||
            next.passthroughCameraFacing != prev.passthroughCameraFacing ||
            next.passthroughOpacity != prev.passthroughOpacity ||
            next.passthroughWindowScale != prev.passthroughWindowScale ||
            next.passthroughAspect != prev.passthroughAspect ||
            next.passthroughMode != prev.passthroughMode ||
            next.passthroughResolution != prev.passthroughResolution ||
            next.passthroughFrameRate != prev.passthroughFrameRate ||
            next.passthroughRotation != prev.passthroughRotation ||
            next.passthroughMirror != prev.passthroughMirror ||
            next.passthroughHeadLocked != prev.passthroughHeadLocked ||
            next.screenScale != prev.screenScale ||
            next.screenDistanceMeters != prev.screenDistanceMeters
        if (passthroughChanged) ptConfigDirty = true
    }

    /** Called from VrSession when user triggers "重置中心点". Runs on GL thread via renderer callback. */
    fun recenter() {
        // Capture current head orientation as the new center. Copy so we own the data.
        centerOffsetQuaternion[0] = headQuaternion[0]
        centerOffsetQuaternion[1] = headQuaternion[1]
        centerOffsetQuaternion[2] = headQuaternion[2]
        centerOffsetQuaternion[3] = headQuaternion[3]
        Log.d(TAG, "recenter: new center offset = (${headQuaternion[0]}, ${headQuaternion[1]}, ${headQuaternion[2]}, ${headQuaternion[3]})")
    }

    override fun onSurfaceCreated(config: EGLConfig) {
        logSurfaceCreated = true
        Log.d(TAG, "onSurfaceCreated: GL thread surfaced")
        // The SDK clears with the current clear colour once per frame; this makes the background
        // pure black with no extra full-screen clear of our own.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DITHER)

        // Shader compilation happens at runtime, not in CI. Neither failure may crash VR entry.
        panel = runCatching { createHandles(PANEL_FRAGMENT_SHADER) }.getOrElse {
            Log.e(TAG, "PANEL shader link FAILED", it)
            null
        }
        video = runCatching { createHandles(VIDEO_FRAGMENT_SHADER) }.getOrElse {
            Log.e(TAG, "VIDEO (external-OES) shader link FAILED — falling back to 2D panel", it)
            null
        }
        Log.d(TAG, "onSurfaceCreated: panelProgram=${panel != null} videoProgram=${video != null}")
        // If external-OES is unavailable on an old GLES2 driver, leave the decoder on the ordinary
        // 2D surface and keep showing the 2D panel instead of crashing the GL thread.

        panelTextureId = createPanelResources()
        setupVideoTexture()
        setupPassthroughTexture()
        rebuildMesh()
        buildBackgroundMesh()
        createLineResources()
        createZoomQuad()
        rebuildPassthroughQuad(settings)
        ptConfigDirty = true
        meshDirty = false
        // Draw the panel once up front so the very first frame is never an uninitialised texture.
        uploadPanel()
        Log.d(
            TAG,
            "onSurfaceCreated done: panelTextureId=$panelTextureId videoTextureId=$videoTextureId " +
                "vertexCount=$vertexCount",
        )
    }

    override fun onNewFrame(headTransform: HeadTransform) {
        headTransform.getQuaternion(headQuaternion, 0)
        // Camera work (start/stop, buffer size, updateTexImage) MUST happen on this GL thread.
        // onNewFrame runs exactly once per frame BEFORE both onDrawEye() calls.
        syncPassthrough()
        if (VrVideoSurfaceRegistry.consumeFrameAvailable()) {
            if (!logFirstFrameAvailable) {
                logFirstFrameAvailable = true
                Log.d(TAG, "onNewFrame: FIRST video frame available from decoder")
            }
            val texture = videoSurfaceTexture
            if (texture != null) {
                try {
                    // updateTexImage() must run on the GL thread that created the SurfaceTexture.
                    texture.updateTexImage()
                    // SurfaceTexture.getTransformMatrix takes ONE argument on Android.
                    texture.getTransformMatrix(stMatrix)
                    hasVideoFrame = true
                } catch (e: RuntimeException) {
                    Log.w(TAG, "onNewFrame: updateTexImage() threw", e)
                    hasVideoFrame = false
                }
            } else {
                Log.w(TAG, "onNewFrame: frame available but videoSurfaceTexture is null")
            }
        }
    }

    override fun onDrawEye(eye: CardboardView.Eye) {
        // Deliberately NO glClear here - see the class KDoc.
        if (!logSurfaceCreated) {
            Log.w(TAG, "onDrawEye: called BEFORE onSurfaceCreated — renderer not initialised")
        }
        val current = settings
        val menuOpen = VrSession.menuOpen.value
        val selectedIndex = VrSession.selectedIndex.value
        val menuPage = VrSession.menuPage.value
        val editMode = VrSession.editMode.value
        // A page switch, an A-armed edit-mode change and a selection change all alter the menu
        // bitmap, so any of them must force a panel re-upload on the GL thread.
        if (menuOpen != lastMenuOpen || menuPage != lastMenuPage || editMode != lastEditMode ||
            (menuOpen && selectedIndex != lastSelectedIndex)
        ) {
            lastMenuOpen = menuOpen
            lastMenuPage = menuPage
            lastEditMode = editMode
            lastSelectedIndex = selectedIndex
            meshDirty = true
            forcePanelUpload = true
            panelDirty = true
        }

        val aspect = VrSession.videoAspect
        if (!menuOpen && aspect != lastAspect) {
            lastAspect = aspect
            meshDirty = true
        }
        if (meshDirty) {
            rebuildMesh()
            meshDirty = false
        }

        // While the menu is open it owns the quad; decoding keeps running underneath so closing
        // the menu returns to a live picture with no reconnection.
        val useVideo = !menuOpen && hasVideoFrame && video != null && videoTextureId != 0
        if (logLastUseVideo != useVideo) {
            Log.d(
                TAG,
                "onDrawEye: useVideo=$useVideo (menuOpen=$menuOpen hasVideoFrame=$hasVideoFrame " +
                    "videoProgram=${video != null} videoTextureId=$videoTextureId)",
            )
            logLastUseVideo = useVideo
        }

        if (!useVideo && (panelDirty || forcePanelUpload)) {
            uploadPanel()
            forcePanelUpload = false
        }

        // -------------------------------------------------------------------------------------
        // Per-eye MVP. Cardboard's eyeFromHead + off-axis projection + distortion mesh all derive
        // from the same inter_lens_distance; we keep them completely intact. The effective IPD is
        // applied AFTER the MVP as an equal-and-opposite screen-space shift (1.0 = strict no-op).
        // -------------------------------------------------------------------------------------
        val head = if (current.headTrackingEnabled) {
            VrHeadPose.viewRotationInto(
                headRotation,
                headQuaternion,
                centerOffsetQuaternion,
                current.headTrackingSensitivity
            )
        } else {
            identityMatrix
        }
        VrMatrix4.multiplyInto(viewMatrix, eye.getEyeFromHead(), head)
        VrMatrix4.multiplyInto(mvpMatrix, eye.getPerspective(0.1f, 100f), viewMatrix)
        // Head-LOCKED passthrough window: keep eye perspective + eyeFromHead (so the two eyes get
        // correct parallax) but drop the head rotation. The phone and its camera rotate together
        // inside the viewer, so the window must stay straight ahead while the user looks around.
        VrMatrix4.multiplyInto(
            headLockedMvpMatrix,
            eye.getPerspective(0.1f, 100f),
            eye.getEyeFromHead(),
        )
        if (current.ipdScale != 1.0f) {
            val eyeSign = when (eye.getEyeType()) {
                CardboardView.Eye.LEFT -> 1f
                CardboardView.Eye.RIGHT -> -1f
                else -> 0f
            }
            VrMatrix4.applyIpdShiftInto(mvpMatrix, current.ipdScale, eyeSign)
            VrMatrix4.applyIpdShiftInto(headLockedMvpMatrix, current.ipdScale, eyeSign)
        }

        val showPanel = useVideo || menuOpen || current.showStatus
        val activeHandles = if (useVideo) video else panel
        val drawPanel = showPanel && activeHandles != null && vertexCount != 0

        // Dev-only, edge-triggered diagnostics: a healthy render loop stays log-silent.
        if (!showPanel) {
            if (!logLastStatusSkip) {
                logLastStatusSkip = true
                Log.d(TAG, "onDrawEye: SKIP — panel suppressed by showStatus=false; nothing drawn")
            }
        } else {
            logLastStatusSkip = false
        }
        if (activeHandles == null) {
            if (!logLastHandlesNull) {
                logLastHandlesNull = true
                Log.w(TAG, "onDrawEye: SKIP — program handles null (useVideo=$useVideo); nothing drawn")
            }
        } else {
            logLastHandlesNull = false
        }
        if (logFrameCounter++ % LOG_INTERVAL_FRAMES == 0) {
            Log.d(
                TAG,
                "onDrawEye: draw frame#=$logFrameCounter useVideo=$useVideo vertexCount=$vertexCount " +
                    "showStatus=${current.showStatus} panelTextureId=$panelTextureId",
            )
        }

        drawEyeScene(mvpMatrix, headLockedMvpMatrix, current, useVideo, drawPanel, allowBackgroundLoad = true)
    }

    /**
     * Draws ONE eye's complete scene (passthrough window OR 360° sphere first, then the flat
     * cinema quad) with the given MVP into the Cardboard SDK's FBO. This is ONLY ever called from
     * onDrawEye(): the "单纯放大" FILL mode does not re-render the scene, it zooms the finished
     * composite afterwards.
     *
     * [headLockedMvp] is the eye-only transform (no head rotation). By default the camera window
     * shares the stream quad's base MVP instead, so the two layers cannot slide against each
     * other; only passthroughHeadLocked=true still uses [headLockedMvp] for the window.
     */
    private fun drawEyeScene(
        mvp: FloatArray,
        headLockedMvp: FloatArray,
        s: VrSettings,
        useVideo: Boolean,
        drawPanel: Boolean,
        allowBackgroundLoad: Boolean,
    ) {
        // Which base transform the stream quad uses in the current layout. OVERLAY keeps the
        // original head-tracked `mvp`; the two windowed layouts use the eye-only transform.
        val layoutRequested = useVideo && s.passthroughMode != VrPassthroughMode.OVERLAY
        val streamBaseMvp = if (layoutRequested) headLockedMvp else mvp

        // "很大的不协调感" fix: the OLD code ALWAYS drew the camera window with headLockedMvp while
        // the OVERLAY stream quad used the head-tracked mvp, so turning your head made the live
        // window slide against the game picture. Default = share the stream's base MVP (rigidly
        // attached, zero relative motion). Opt-in passthroughHeadLocked restores the pinned window.
        val cameraMvp = if (s.passthroughHeadLocked) headLockedMvp else streamBaseMvp

        // Both are BACKGROUND layers; never double-composite them. If passthrough is on but the
        // camera has no frame yet (or failed), fall back to the panorama / pure black as before.
        val passthroughDrawn = drawPassthroughWindow(cameraMvp, s)
        if (!passthroughDrawn) drawBackground(s, mvp, allowBackgroundLoad)
        if (!drawPanel) return
        val handles = (if (useVideo) video else panel) ?: return
        if (vertexCount == 0) return

        // In CAMERA_MAIN / SIDE_BY_SIDE the stream quad becomes a PiP / side window. Gated on
        // passthroughDrawn so a camera failure (no permission / busy lens) falls back to the
        // ORIGINAL full-size picture instead of leaving a tiny game window over pure black.
        // The panel and menu (useVideo == false) always keep the original MVP — never shrunk.
        val layoutActive = layoutRequested && passthroughDrawn
        val streamMvp = if (layoutActive) {
            VrMatrix4.multiplyInto(gameMvpMatrix, streamBaseMvp, gameModelMatrix)
            gameMvpMatrix
        } else {
            mvp
        }

        GLES20.glUseProgram(handles.program)
        GLES20.glUniformMatrix4fv(handles.mvp, 1, false, streamMvp, 0)
        GLES20.glUniform1f(handles.brightness, s.brightness)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(handles.texture, 0)

        if (useVideo) {
            // Horizontal mirror / vertical flip live in the STREAM QUAD's texCoords (rebuildMesh),
            // NOT in the status panel, the menu or the 360° sphere. No GLSL change, and the
            // SurfaceTexture transform matrix is left intact.
            GLES20.glUniformMatrix4fv(handles.stMatrix, 1, false, stMatrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        } else {
            // PANEL_FLIP_Y stays 0: panel/menu are ordinary GL_TEXTURE_2D bitmaps.
            GLES20.glUniform1f(handles.flip, PANEL_FLIP_Y)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTextureId)
        }

        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(handles.position)
        GLES20.glVertexAttribPointer(handles.position, 3, GLES20.GL_FLOAT, false, 12, positionBuffer)
        val quadTexCoords = if (useVideo) videoTexCoordBuffer else texCoordBuffer
        quadTexCoords.position(0)
        GLES20.glEnableVertexAttribArray(handles.texCoord)
        GLES20.glVertexAttribPointer(handles.texCoord, 2, GLES20.GL_FLOAT, false, 8, quadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(handles.position)
        GLES20.glDisableVertexAttribArray(handles.texCoord)
    }

    /**
     * Called AFTER the Cardboard SDK has composited both (distorted) eye textures onto the DEFAULT
     * framebuffer. That is the true 2D / physical-screen layer: drawing here yields ONE straight
     * line at the exact screen centre, with no per-eye duplication, no distortion warp, and no
     * head / eye / perspective transform. It reproduces the old Cardboard SDK ui_alignment_marker
     * the user saw on the 2D-only screen, which a normal Compose View can no longer show because
     * the VR GL SurfaceView is setZOrderOnTop(true).
     */
    override fun onFinishFrame(viewport: Viewport) {
        // Zoom the FINISHED composite (which still has both eyes + lens distortion), then draw the
        // fixed screen-space alignment line last so it is never scaled with the picture.
        if (settings.screenFillMode == VrScreenFillMode.FILL) {
            drawZoomFill(viewport, settings)
        } else {
            // Most common real-device cause of "依然不能全屏": SharedPreferences remembered an
            // older DISTORTED choice, so the whole zoom path is skipped before it can run.
            noteZoomSkip("screenFillMode=DISTORTED（原版／可回退），2D 设置页把它切回「单纯放大」")
        }
        drawScreenSpaceAlignmentLine(settings, viewport)
    }

    /** Logs each distinct skip reason ONCE; a healthy FILL frame clears the skip state instead. */
    private fun noteZoomSkip(reason: String) {
        if (logLastZoomSkip != reason) {
            logLastZoomSkip = reason
            Log.w(TAG, "zoom FILL skipped: $reason")
        }
    }

    private fun noteZoomActive(s: VrSettings, w: Int, h: Int) {
        if (!logLastZoomActive) {
            logLastZoomActive = true
            Log.i(
                TAG,
                "zoom FILL active: ${s.zoomScale}x on ${w}x${h}, " +
                    "snapshotMode=$zoomSnapshotMode, menuOpen=${VrSession.menuOpen.value}",
            )
        }
        logLastZoomSkip = null
    }

    /**
     * "单纯放大": copy the SDK's finished stereo+distortion frame into a texture, clear to black,
     * then blit it back with ONE centred, UNIFORM zoom.
     *
     * Nothing is re-rendered: both eye matrices, the barrel correction, head pose, IPD shift, the
     * 360° background and the zero-copy OES video path are all left exactly as Cardboard produced
     * them. Enlarging the whole composite pushes the black bands off the top/bottom edges while
     * the two eye windows grow together, so they keep fusing in a viewer. A viewport LARGER than
     * the framebuffer is legal in GLES2 — the overshoot is clipped, which is precisely how the
     * bands disappear. The horizontal edges may still keep a small black margin, as requested.
     *
     * zoomScale == 1.00 short-circuits, and VrScreenFillMode.DISTORTED skips this entirely, so
     * the original rendering is always one setting away.
     */
    private fun drawZoomFill(viewport: Viewport, s: VrSettings) {
        val w = if (viewport.width > 0) viewport.width else surfaceWidth
        val h = if (viewport.height > 0) viewport.height else surfaceHeight
        if (w <= 0 || h <= 0) {
            noteZoomSkip("invalid viewport ${w}x${h}")
            return
        }
        if (zoomCopyUnsupported) {
            noteZoomSkip("all snapshot strategies failed on this GL context（已永久降级，保留 SDK 原画）")
            return
        }
        val zoom = s.zoomScale
        if (zoom <= 1.001f) {
            noteZoomSkip("zoomScale=${s.zoomScale}（1.00x = 关闭放大）")
            return
        }
        // The settings panel is a screen-covering quad. Zooming it would crop its outer rows and
        // chips, so the composite is enlarged ONLY while streaming / on the status screen.
        if (VrSession.menuOpen.value) {
            noteZoomSkip("settings menu open（菜单本身不放大，关掉菜单即恢复）")
            return
        }
        val prog = panel
        if (prog == null) {
            noteZoomSkip("panel program not ready")
            return
        }

        // 1. Snapshot the finished frame. IMPORTANT: nothing is cleared until a snapshot actually
        //    succeeds, so a driver that refuses every strategy leaves the SDK frame 100% intact.
        if (!snapshotZoomFrame(w, h)) {
            noteZoomSkip("snapshot failed this frame（见上一条 zoom snapshot 日志）")
            return
        }

        // 2. Black behind the enlarged picture (only visible if zoom is small / aspect differs).
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 3. One centred uniform zoom of the WHOLE composite. Identity MVP => the quad's NDC
        //    coords already span the viewport, so the viewport itself does the scaling.
        val zoomedW = (w * zoom).roundToInt()
        val zoomedH = (h * zoom).roundToInt()
        GLES20.glViewport((w - zoomedW) / 2, (h - zoomedH) / 2, zoomedW, zoomedH)

        GLES20.glUseProgram(prog.program)
        GLES20.glUniformMatrix4fv(prog.mvp, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(prog.brightness, 1f)
        GLES20.glUniform1f(prog.flip, 0f)
        GLES20.glUniform1i(prog.texture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)

        zoomPositionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(prog.position)
        GLES20.glVertexAttribPointer(prog.position, 3, GLES20.GL_FLOAT, false, 12, zoomPositionBuffer)
        zoomTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(prog.texCoord)
        GLES20.glVertexAttribPointer(prog.texCoord, 2, GLES20.GL_FLOAT, false, 8, zoomTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(prog.position)
        GLES20.glDisableVertexAttribArray(prog.texCoord)

        // Restore the full-screen viewport. The alignment line reasserts its own anyway, but the
        // SDK may also draw after onFinishFrame() on some devices.
        GLES20.glViewport(0, 0, w, h)
        noteZoomActive(s, w, h)
    }

    /**
     * Copies the finished default framebuffer into [zoomTextureId], probing texture formats on the
     * first frame. Returns false WITHOUT touching the screen when no strategy works.
     */
    private fun snapshotZoomFrame(w: Int, h: Int): Boolean {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        drainGlErrors() // SDK distortion pass may have left unrelated errors behind.

        // A surface size change (rotation / window resize) must reallocate the snapshot texture
        // even AFTER the format probe succeeded, otherwise the copy keeps targeting stale
        // dimensions and the enlarged picture is stretched or blank. allocateZoomTexture() is a
        // no-op while w/h/mode are unchanged, so this stays off the per-frame hot path.
        if (zoomSnapshotMode == ZOOM_READPIXELS) {
            if (!allocateZoomTexture(w, h, ZOOM_READPIXELS)) return false
            return snapshotViaReadPixels(w, h)
        }
        if (zoomSnapshotMode >= 0) {
            if (!allocateZoomTexture(w, h, zoomSnapshotMode)) return false
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
            // Already proven on this GL context; no per-frame glGetError() hot path.
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
            return true
        }

        // First frame: the destination format must match the window framebuffer. RGBA8 alone is
        // rejected by RGB565 devices, so try RGB565 / RGB8 as well before giving up.
        for (mode in ZOOM_COPY_FORMATS.indices) {
            if (!allocateZoomTexture(w, h, mode)) return false
            drainGlErrors() // ignore errors from glTexImage2D itself, judge only the copy
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
                zoomSnapshotMode = mode
                Log.d(TAG, "zoom snapshot: copy-tex format $mode accepted (${w}x${h})")
                return true
            }
        }

        // Escape hatch: glReadPixels does the framebuffer->RGBA conversion in the driver and is
        // far more widely supported. It costs one full-screen readback per frame, so it is used
        // ONLY when copy-tex is entirely unavailable — still better than no zoom at all.
        if (!allocateZoomTexture(w, h, ZOOM_READPIXELS)) return false
        drainGlErrors()
        if (snapshotViaReadPixels(w, h)) {
            zoomSnapshotMode = ZOOM_READPIXELS
            Log.w(TAG, "zoom snapshot: copy-tex rejected everywhere; using glReadPixels fallback")
            return true
        }
        zoomCopyUnsupported = true
        Log.w(TAG, "zoom FILL disabled: no snapshot strategy works; keeping original SDK frame")
        return false
    }

    /** Last-resort snapshot: read the window framebuffer as RGBA8 and upload it into the texture. */
    private fun snapshotViaReadPixels(w: Int, h: Int): Boolean {
        val bytes = w.toLong() * h.toLong() * 4L
        if (bytes <= 0L || bytes > MAX_SNAPSHOT_BYTES) return false
        if (zoomPixels == null || zoomPixelsWidth != w || zoomPixelsHeight != h) {
            zoomPixels = ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())
            zoomPixelsWidth = w
            zoomPixelsHeight = h
        }
        val buf = zoomPixels ?: return false
        buf.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    /** Allocates / resizes the snapshot texture for [mode]. GL thread only, no per-frame alloc. */
    private fun allocateZoomTexture(w: Int, h: Int, mode: Int): Boolean {
        if (zoomTextureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            zoomTextureId = ids[0]
            if (zoomTextureId == 0) return false
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, zoomTextureId)
        }
        if (zoomTexWidth != w || zoomTexHeight != h || zoomTexMode != mode) {
            val fmt = ZOOM_COPY_FORMATS[if (mode == ZOOM_READPIXELS) 0 else mode]
            // NPOT textures are valid in GLES2 with CLAMP_TO_EDGE and no mipmaps.
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, fmt[0], w, h, 0, fmt[1], fmt[2], null,
            )
            zoomTexWidth = w
            zoomTexHeight = h
            zoomTexMode = mode
        }
        return true
    }

    private fun drainGlErrors() {
        var drained = 0
        while (drained < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR) drained++
    }

    /**
     * Static full-screen NDC quad. glCopyTexSubImage2D and glTexImage2D both use a bottom-left
     * origin, so v=0 at the quad's bottom edge reproduces the finished frame upright with uFlip=0.
     */
    private fun createZoomQuad() {
        zoomPositionBuffer.clear()
        zoomPositionBuffer.put(
            floatArrayOf(
                -1f, -1f, 0f,
                1f, -1f, 0f,
                -1f, 1f, 0f,
                1f, 1f, 0f,
            ),
        )
        zoomPositionBuffer.position(0)
        zoomTexCoordBuffer.clear()
        zoomTexCoordBuffer.put(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f,
            ),
        )
        zoomTexCoordBuffer.position(0)
    }

    /** The SDK forwards the REAL GL surface size here (and calls setScreenParams on it). */
    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onRendererShutdown() {
        Log.d(TAG, "onRendererShutdown: releasing GL resources")
        VrVideoSurfaceRegistry.clear()
        // Stop the camera producer BEFORE tearing down the GL objects it feeds. release() posts a
        // stop to the camera thread and quits it; the stream's own camera recording can then take
        // the device back as soon as VR exits.
        ptCamera?.stop()
        ptCamera?.release()
        ptCamera = null
        releasePassthroughTexture()
        ptUnavailable.set(false)
        ptAutoRotationDeg = 90
        ptAutoMirror = false
        ptPreviewW = 0
        ptPreviewH = 0
        ptPreviewFps = 0
        ptAppliedGameAspect = 0f
        VrMatrix4.identityInto(gameModelMatrix)
        ptConfigDirty = true
        releaseVideoTexture()
        deleteBackgroundTexture()
        bgPositionBuffer = null
        bgTexCoordBuffer = null
        bgVertexCount = 0
        bgDirty = true
        if (lineTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lineTextureId), 0)
            lineTextureId = 0
        }
        if (zoomTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(zoomTextureId), 0)
            zoomTextureId = 0
        }
        zoomTexWidth = 0
        zoomTexHeight = 0
        zoomTexMode = -1
        // A new GL context gets a fresh format probe; the old driver verdict no longer applies.
        zoomSnapshotMode = -1
        zoomCopyUnsupported = false
        zoomPixels = null
        zoomPixelsWidth = 0
        zoomPixelsHeight = 0
        // New GL context => the previous verdict/reasons no longer apply; re-log once.
        logLastZoomSkip = null
        logLastZoomActive = false
        if (panelTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(panelTextureId), 0)
            panelTextureId = 0
        }
        panel?.let { GLES20.glDeleteProgram(it.program) }
        video?.let { GLES20.glDeleteProgram(it.program) }
        panel = null
        video = null
        panelBitmap?.recycle()
        panelBitmap = null
        panelCanvas = null
        borderPaint = null
        panelBackgroundPaint = null
        titlePaint = null
        bodyPaint = null
        valuePaint = null
        hintPaint = null
        selectedPaint = null
        selectedRect = null
        menuCardPaint = null
        menuCardStrokePaint = null
        menuAccentPaint = null
        menuTrackPaint = null
        menuProgressPaint = null
        menuTabPaint = null
        menuGradientPaint = null
        vertexCount = 0
        surfaceWidth = 0
        surfaceHeight = 0
        hasVideoFrame = false
        panelDirty = true
        forcePanelUpload = true
        meshDirty = true
        lastMenuOpen = false
        lastSelectedIndex = -1
        // Reset diagnostic state so a second VR entry logs its first frame / first draw again.
        logFrameCounter = 0
        logLastUseVideo = null
        logLastHandlesNull = false
        logFirstFrameAvailable = false
        logSurfaceCreated = false
    }

    private fun createHandles(fragmentSource: String): ProgramHandles {
        val program = VrGlProgram.createProgram(VERTEX_SHADER, fragmentSource)
        return ProgramHandles(
            program = program,
            position = GLES20.glGetAttribLocation(program, "aPosition"),
            texCoord = GLES20.glGetAttribLocation(program, "aTexCoord"),
            mvp = GLES20.glGetUniformLocation(program, "uMVP"),
            texture = GLES20.glGetUniformLocation(program, "uTexture"),
            brightness = GLES20.glGetUniformLocation(program, "uBrightness"),
            stMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix"),
            flip = GLES20.glGetUniformLocation(program, "uFlip"),
        )
    }

    private fun createPanelResources(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val bitmap = Bitmap.createBitmap(PANEL_W, PANEL_H, Bitmap.Config.ARGB_8888)
        panelBitmap = bitmap
        panelCanvas = Canvas(bitmap)
        panelBackgroundPaint = Paint().apply { color = Color.rgb(8, 10, 14) }
        borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.rgb(90, 200, 235)
        }
        titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 218, 232)
            textSize = 19f
        }
        valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 165, 195)
            textSize = 15f
        }
        selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 92, 138)
        }
        selectedRect = RectF()

        // Modern two-level menu: dark gradient backdrop, rounded cards, accent bars and progress
        // tracks. Allocated exactly once; drawMenu()/drawStatus() only re-assign colours.
        menuGradientPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, PANEL_W.toFloat(), PANEL_H.toFloat(),
                Color.rgb(10, 14, 26), Color.rgb(4, 6, 12), Shader.TileMode.CLAMP,
            )
        }
        menuCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        menuCardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        menuAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        menuTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        menuProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        menuTabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Allocate texture storage once. Later refreshes use texSubImage2D (no reallocation).
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    private fun setupVideoTexture() {
        releaseVideoTexture()
        if (video == null) {
            // Without external-OES support the decoder must stay on the ordinary 2D surface.
            VrVideoSurfaceRegistry.clear()
            return
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        videoTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val texture = SurfaceTexture(videoTextureId)
        // Only flips an AtomicBoolean. Do NOT post to the main looper: that would enqueue one
        // message per decoded frame on the UI thread of a low-end device.
        texture.setOnFrameAvailableListener { VrVideoSurfaceRegistry.markFrameAvailable() }
        val output = Surface(texture)
        videoSurfaceTexture = texture
        videoSurface = output
        VrVideoSurfaceRegistry.install(videoTextureId, texture, output)
    }

    private fun releaseVideoTexture() {
        videoSurface?.release()
        videoSurface = null
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (videoTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(videoTextureId), 0)
            videoTextureId = 0
        }
        hasVideoFrame = false
    }

    /**
     * Creates a SECOND external-OES texture for the passthrough camera. It is deliberately
     * separate from the decoder's video texture: two SurfaceTextures / producers must never share
     * one GL texture name or fight over updateTexImage(). The already-linked VIDEO program is
     * reused unchanged.
     */
    private fun setupPassthroughTexture() {
        releasePassthroughTexture()
        if (video == null) return // no external-OES on this driver -> passthrough stays unavailable
        // A driver that cannot create a SECOND external-OES SurfaceTexture must never kill the GL
        // thread. On any failure we release everything and leave ptTextureId == 0, so
        // drawPassthroughWindow() simply returns false and the original black / 360° background
        // takes over. This is part of the "passthrough failure is never fatal" contract.
        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            ptTextureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ptTextureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            val texture = SurfaceTexture(ptTextureId)
            // Only flips an AtomicBoolean. A camera preview at 30 fps must never enqueue 30
            // messages per second onto the main looper.
            texture.setOnFrameAvailableListener { ptFrameAvailable.set(true) }
            ptSurfaceTexture = texture
            ptSurface = Surface(texture)
            ptConfigDirty = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "passthrough texture setup failed; window disabled, black background kept", e)
            releasePassthroughTexture()
        }
    }

    private fun releasePassthroughTexture() {
        ptSurface?.release()
        ptSurface = null
        ptSurfaceTexture?.release()
        ptSurfaceTexture = null
        if (ptTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(ptTextureId), 0)
            ptTextureId = 0
        }
        ptPreviewW = 0
        ptPreviewH = 0
        ptPreviewFps = 0
        ptFrameAvailable.set(false)
        ptHasFrame.set(false)
    }

    /**
     * GL-thread-only. Applies settings to the camera, keeps the preview buffer size in sync with
     * the chosen aspect, and consumes newly arrived camera frames BEFORE both onDrawEye() calls.
     */
    private fun syncPassthrough() {
        val s = settings
        // The stream's real decoded aspect is part of the SIDE_BY_SIDE / PiP layout maths. A
        // resolution change does not go through applySettings(), so detect it here on the GL thread.
        if (VrSession.videoAspect != ptAppliedGameAspect) ptConfigDirty = true
        if (ptConfigDirty) {
            ptConfigDirty = false
            configurePassthrough(s)
            rebuildPassthroughQuad(s)
        }
        if (ptFrameAvailable.getAndSet(false)) {
            val st = ptSurfaceTexture
            if (st != null && ptTextureId != 0) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(ptStMatrix)
                    ptHasFrame.set(true)
                    ptUnavailable.set(false)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "passthrough updateTexImage() threw; window disabled this frame", e)
                    ptHasFrame.set(false)
                }
            }
        }
    }

    /**
     * Hands the SurfaceTexture + camera parameters to VrPassthroughCamera.
     *
     * The OLD code guessed a preview buffer here (1280x960 / 1280x720) with
     * setDefaultBufferSize() and built the quad from that same guessed ratio. Camera2 only outputs
     * sizes it advertises in StreamConfigurationMap, so the real buffer ratio frequently differed
     * and the picture was stretched. Preview-size selection, buffer sizing and the fps request now
     * live in the camera class, which reports the REAL values via onPreviewConfig(); the renderer
     * builds the quad from those values (see rebuildPassthroughQuad). start() is idempotent on
     * (facing, surface, resolution, aspect, frameRate), so a settings change cleanly restarts the
     * session while a recomposition with unchanged values is free.
     */
    private fun configurePassthrough(s: VrSettings) {
        val surfaceTexture = ptSurfaceTexture
        val surface = ptSurface
        if (surfaceTexture == null || surface == null || video == null || ptTextureId == 0 ||
            !s.passthroughEnabled || s.passthroughOpacity <= 0f
        ) {
            ptCamera?.stop()
            ptHasFrame.set(false)
            return
        }
        val cam = ptCamera ?: VrPassthroughCamera(context, ptListener).also { ptCamera = it }
        cam.start(
            surfaceTexture,
            surface,
            s.passthroughCameraFacing,
            s.passthroughResolution,
            s.passthroughAspect,
            s.passthroughFrameRate,
        )
    }

    /**
     * Effective clockwise rotation in {0, 90, 180, 270}. AUTO follows the camera's sensor
     * baseline; any other VrPassthroughRotation is a manual override. Both the quad ratio and the
     * UV rotation use this SAME value, so they can never disagree with each other.
     */
    private fun effectivePassthroughRotation(s: VrSettings): Int {
        val deg = if (s.passthroughRotation == VrPassthroughRotation.AUTO) {
            ptAutoRotationDeg
        } else {
            s.passthroughRotation.degrees
        }
        return ((deg / 90) * 90 + 360) % 360
    }

    /**
     * Builds the camera window quad AND the stream quad's model transform for the three layouts.
     * Only called when a setting / camera config / stream aspect changed, NEVER per frame.
     *
     * All layout numbers are expressed in the "equivalent z = -d1 plane" so they map directly onto
     * VrScreenGeometry's stream quad. The camera quad lives slightly further away (z = -d2), so
     * BOTH its size and its centre offset are multiplied by d2/d1 to preserve ANGULAR size. Nothing
     * is stretched: the camera window keeps the REAL Camera2 preview ratio (after the effective
     * rotation's aspect swap) and the stream quad keeps VrSession.videoAspect in every mode.
     */
    private fun rebuildPassthroughQuad(s: VrSettings) {
        val effectiveRotation = effectivePassthroughRotation(s)
        // Use the REAL preview dimensions reported by Camera2, NOT a guessed 4:3 / 16:9. This is
        // the single change that removes the "直接拉伸" distortion.
        val previewAspect = if (ptPreviewW > 0 && ptPreviewH > 0) {
            ptPreviewW.toFloat() / ptPreviewH.toFloat()
        } else if (s.passthroughAspect == VrPassthroughAspect.FOUR_THREE) {
            PASSTHROUGH_ASPECT_4_3
        } else {
            PASSTHROUGH_ASPECT_16_9
        }
        // A 90/270 rotation swaps the effective width and height of the upright picture. Building
        // the quad with the un-swapped ratio would squeeze it by aspect^2 — part of the reported
        // "很大的不协调感 / 压扁" — so take the reciprocal for quarter turns.
        val cameraAspect = (if (effectiveRotation == 90 || effectiveRotation == 270) {
            1f / previewAspect
        } else {
            previewAspect
        }).coerceIn(0.25f, 8f)
        val gameAspect = VrSession.videoAspect.coerceIn(0.25f, 8f)
        ptAppliedGameAspect = gameAspect
        val d1 = s.screenDistanceMeters.coerceAtLeast(0.1f)
        val d2 = d1 + PASSTHROUGH_WINDOW_DEPTH_M
        val depthScale = d2 / d1
        val u = VrScreenGeometry.halfHeight(s.screenScale)
        val scale = s.passthroughWindowScale

        // Defaults = OVERLAY. Every branch explicitly sets all fields it needs; initialising them
        // up front also keeps Kotlin's definite-assignment analysis happy.
        var camCx = 0f
        var camCy = 0f
        var camHalfH = u * PASSTHROUGH_WINDOW_VS_SCREEN * scale
        var camHalfW = camHalfH * cameraAspect
        var gameScaleX = 1f
        var gameScaleY = 1f
        var gameTx = 0f
        var gameTy = 0f
        when (s.passthroughMode) {
            VrPassthroughMode.OVERLAY -> Unit // camera behind, untouched full-size stream on top
            VrPassthroughMode.CAMERA_MAIN -> {
                // Camera = enlarged main picture; stream = small bottom-right picture-in-picture.
                camHalfH = u * PASSTHROUGH_CAMERA_MAIN_VS_SCREEN * scale
                camHalfW = camHalfH * cameraAspect
                val pip = PASSTHROUGH_GAME_PIP_SCALE
                gameScaleX = pip
                gameScaleY = pip
                // Slide the PiP towards the bottom-right corner of the ORIGINAL screen rectangle
                // (u * gameAspect wide, u high), leaving a small margin.
                gameTx = (1f - pip) * u * gameAspect * PASSTHROUGH_GAME_PIP_INSET
                gameTy = -(1f - pip) * u * PASSTHROUGH_GAME_PIP_INSET
            }
            VrPassthroughMode.SIDE_BY_SIDE -> {
                // Camera LEFT, stream RIGHT, two non-overlapping windows each with its own ratio.
                val halfW = u * PASSTHROUGH_SIDE_BY_SIDE_HALF_W * scale
                camHalfW = halfW
                camHalfH = halfW / cameraAspect
                camCx = -u * PASSTHROUGH_SIDE_BY_SIDE_CENTER_X
                val gameHalfH = halfW / gameAspect
                gameScaleX = halfW / (u * gameAspect)
                gameScaleY = gameHalfH / u
                gameTx = u * PASSTHROUGH_SIDE_BY_SIDE_CENTER_X
            }
        }

        // Camera quad: head-locked, drawn FIRST so the nearer stream quad composites on top.
        val cw = camHalfW * depthScale
        val ch = camHalfH * depthScale
        val ccx = camCx * depthScale
        val ccy = camCy * depthScale
        val z = -d2
        val positions = floatArrayOf(
            ccx - cw, ccy - ch, z,
            ccx - cw, ccy + ch, z,
            ccx + cw, ccy - ch, z,
            ccx + cw, ccy + ch, z,
        )
        ptPositionBuffer.clear()
        ptPositionBuffer.put(positions)
        ptPositionBuffer.position(0)

        // Stream quad transform: a pure model matrix (scale + translate) applied through the MVP
        // to the EXISTING positionBuffer. No geometry rebuild -> curvature and vertex count are
        // preserved, and the panel/menu quad (drawn with the original MVP) is never affected.
        VrMatrix4.identityInto(gameModelMatrix)
        gameModelMatrix[0] = gameScaleX
        gameModelMatrix[5] = gameScaleY
        gameModelMatrix[12] = gameTx
        gameModelMatrix[13] = gameTy

        // Base UVs match VrScreenGeometry (v=0 at the TOP of the quad). Bake the sensor/display
        // rotation and the front-camera mirror into the sampled coords: pure CPU buffer work,
        // NO shader change, NO GL_BLEND, no mutation of the Cardboard SDK's GL state.
        val uv = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        ptTexCoordBuffer.clear()
        var i = 0
        while (i < uv.size) {
            // su/sv (not u/v) so these UV locals never shadow the outer `val u` = halfHeight.
            var su = uv[i]
            var sv = uv[i + 1]
            when (effectiveRotation) {
                90 -> { val nu = sv; sv = 1f - su; su = nu }
                180 -> { su = 1f - su; sv = 1f - sv }
                270 -> { val nu = 1f - sv; sv = su; su = nu }
                else -> Unit
            }
            // Front lenses are mirrored by default; the user's extra mirror XORs with that.
            if (ptAutoMirror != s.passthroughMirror) su = 1f - su
            ptTexCoordBuffer.put(su)
            ptTexCoordBuffer.put(sv)
            i += 2
        }
        ptTexCoordBuffer.position(0)
    }

    /**
     * Draws the live camera as ONE head-locked window. Returns true when the window was drawn, so
     * drawEyeScene() can suppress the 360° panorama (mutually exclusive background layers).
     */
    private fun drawPassthroughWindow(mvp: FloatArray, s: VrSettings): Boolean {
        val handles = video ?: return false
        if (!s.passthroughEnabled || s.passthroughOpacity <= 0f) return false
        if (ptTextureId == 0 || !ptHasFrame.get()) return false
        GLES20.glUseProgram(handles.program)
        GLES20.glUniformMatrix4fv(handles.mvp, 1, false, mvp, 0)
        // Over the SDK's pure-black clear, multiplying the camera colour by opacity is exactly
        // mix(black, camera, opacity) — no blending state is touched.
        GLES20.glUniform1f(handles.brightness, s.passthroughOpacity)
        GLES20.glUniformMatrix4fv(handles.stMatrix, 1, false, ptStMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(handles.texture, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ptTextureId)
        ptPositionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(handles.position)
        GLES20.glVertexAttribPointer(handles.position, 3, GLES20.GL_FLOAT, false, 12, ptPositionBuffer)
        ptTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(handles.texCoord)
        GLES20.glVertexAttribPointer(handles.texCoord, 2, GLES20.GL_FLOAT, false, 8, ptTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(handles.position)
        GLES20.glDisableVertexAttribArray(handles.texCoord)
        return true
    }

    private fun uploadPanel() {
        val canvas = panelCanvas ?: return
        val bitmap = panelBitmap ?: return
        if (VrSession.menuOpen.value) drawMenu(canvas, settings) else drawStatus(canvas, settings)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panelTextureId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        panelDirty = false
    }

    /** Modern status board, shown only when there is no stream and the menu is closed. */
    private fun drawStatus(canvas: Canvas, s: VrSettings) {
        val card = menuCardPaint ?: return
        val stroke = menuCardStrokePaint ?: return
        val accent = menuAccentPaint ?: return
        val title = titlePaint ?: return
        val body = bodyPaint ?: return
        val hint = hintPaint ?: return
        val rect = selectedRect ?: return
        val c = MENU_COLORS

        canvas.drawPaint(menuGradientPaint ?: return)
        accent.color = c[4]
        canvas.drawRoundRect(24f, 20f, 30f, 58f, 3f, 3f, accent)
        title.color = c[6]
        title.textSize = 30f
        canvas.drawText("OpenNOW VR", 44f, 46f, title)
        hint.color = c[7]
        hint.textSize = 15f
        canvas.drawText("半 VR 影院 · 平面 2D 串流", 44f, 70f, hint)

        card.color = c[2]
        stroke.color = c[3]
        rect.set(24f, 88f, 744f, 208f)
        canvas.drawRoundRect(rect, 14f, 14f, card)
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
        body.color = c[6]
        body.textSize = 19f
        canvas.drawText("串流画面会显示在这块平面幕布上", 44f, 122f, body)
        body.color = c[7]
        body.textSize = 17f
        canvas.drawText(
            String.format(
                Locale.US, "窗口 %.1f x    距离 %.1f m    亮度 %.2f",
                s.screenScale, s.screenDistanceMeters, s.brightness,
            ),
            44f, 156f, body,
        )
        canvas.drawText(
            String.format(
                Locale.US, "曲面 %.1f   偏移 %+.1f   %s %.2fx",
                s.curvature, s.screenVerticalOffset,
                VrMenuModel.display(s, VrMenuItemId.SCREEN_FILL_MODE), s.zoomScale,
            ),
            44f, 188f, body,
        )

        rect.set(24f, 220f, 744f, 326f)
        canvas.drawRoundRect(rect, 14f, 14f, card)
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
        hint.color = c[4]
        hint.textSize = 17f
        canvas.drawText("手柄操作", 44f, 250f, hint)
        hint.color = c[7]
        hint.textSize = 15f
        canvas.drawText("长按 START+SELECT 或 HOME：打开设置（二级页面）", 44f, 280f, hint)
        canvas.drawText("系统返回键 / 设置「退出 VR」退出 · L1+R1 只进入", 44f, 306f, hint)

        // Real camera parameters, so "方向是错的 / 分辨率没生效" is diagnosable on the device
        // without adb. Only shown when passthrough is on, so the default board is unchanged.
        if (s.passthroughEnabled) {
            hint.color = c[4]
            hint.textSize = 15f
            val rot = effectivePassthroughRotation(s)
            val mirror = ptAutoMirror != s.passthroughMirror
            canvas.drawText(
                String.format(
                    Locale.US,
                    "透视 %dx%d · %d fps · 旋转 %d° · 镜像 %s · %s",
                    ptPreviewW, ptPreviewH, ptPreviewFps, rot,
                    if (mirror) "开" else "关",
                    if (ptHasFrame.get()) "画面正常" else "等待相机帧（黑底降级）",
                ),
                44f, 348f, hint,
            )
            canvas.drawText(
                if (s.passthroughHeadLocked) "窗口：钉在眼前（与幕布会相对滑动）" else "窗口：跟随幕布（默认·无相对滑动）",
                44f, 370f, hint,
            )
        }

        hint.color = c[7]
        hint.textSize = 14f
        canvas.drawText("不使用眼睛 / 凝视 / 头部确认 · 中心辅助线固定在物理屏幕层", 28f, 386f, hint)
        // NOTE: the centre alignment line is deliberately NOT drawn on this Canvas. It is a fixed
        // screen-space GL overlay in onFinishFrame() — see drawScreenSpaceAlignmentLine().
    }

    /**
     * MODERN TWO-LEVEL SETTINGS PANEL. Category chips on top, then ONLY the active page's rows as
     * rounded cards with value readouts and progress tracks. LB/RB switch pages, D-pad UP/DOWN
     * select a row. Numeric rows are edited ONLY after A arms editing (banner turns into "编辑中"),
     * and only the left stick can move a value.
     */
    private fun drawMenu(canvas: Canvas, s: VrSettings) {
        val title = titlePaint ?: return
        val label = bodyPaint ?: return
        val value = valuePaint ?: return
        val hint = hintPaint ?: return
        val card = menuCardPaint ?: return
        val stroke = menuCardStrokePaint ?: return
        val accent = menuAccentPaint ?: return
        val track = menuTrackPaint ?: return
        val progress = menuProgressPaint ?: return
        val tab = menuTabPaint ?: return
        val rect = selectedRect ?: return
        val c = MENU_COLORS

        canvas.drawPaint(menuGradientPaint ?: return)

        // ---- Header -------------------------------------------------------------------------
        accent.color = c[4]
        canvas.drawRoundRect(24f, 20f, 30f, 58f, 3f, 3f, accent)
        title.color = c[6]
        title.textSize = 30f
        canvas.drawText("VR 设置", 44f, 46f, title)
        val page = VrSession.menuPage.value
        val pageNumber = VrMenuModel.pageIndex(page) + 1
        value.color = c[4]
        value.textSize = 17f
        val counter = String.format(Locale.US, "%02d / %02d", pageNumber, VrMenuModel.pages.size)
        canvas.drawText(counter, 744f - value.measureText(counter), 44f, value)
        hint.color = c[7]
        hint.textSize = 14f
        val closeHint = "B 返回"
        canvas.drawText(closeHint, 744f - hint.measureText(closeHint), 62f, hint)

        // ---- Category chips (second level) --------------------------------------------------
        val chipW = 138f
        val chipH = 28f
        val chipGap = 8f
        val chipY = 70f
        VrMenuModel.pages.forEachIndexed { i, p ->
            val left = 24f + i * (chipW + chipGap)
            val active = p == page
            tab.color = if (active) c[4] else c[2]
            rect.set(left, chipY, left + chipW, chipY + chipH)
            canvas.drawRoundRect(rect, 14f, 14f, tab)
            hint.color = if (active) c[1] else c[7]
            hint.textSize = 15f
            val chipLabel = p.title
            canvas.drawText(
                chipLabel,
                left + (chipW - hint.measureText(chipLabel)) / 2f,
                chipY + 19f,
                hint,
            )
        }
        stroke.color = c[3]
        rect.set(24f, 106f, 744f, 107.5f)
        canvas.drawRoundRect(rect, 1f, 1f, stroke)

        // ---- Banner: help text, or the "editing" state --------------------------------------
        val editing = VrSession.editMode.value
        card.color = if (editing) Color.argb(46, 77, 200, 255) else c[2]
        rect.set(24f, 114f, 744f, 146f)
        canvas.drawRoundRect(rect, 12f, 12f, card)
        stroke.color = if (editing) c[4] else c[3]
        canvas.drawRoundRect(rect, 12f, 12f, stroke)
        hint.color = if (editing) c[4] else c[7]
        hint.textSize = 15f
        val banner = if (editing) {
            "编辑中 · 左摇杆 ←→ 细调 / ↑↓ 快调 · A 完成 · B 返回"
        } else {
            page.subtitle + "   ·   ↑↓/摇杆上下 选择   LB/RB 换页   A 编辑 / 开关"
        }
        canvas.drawText(banner, 36f, 135f, hint)

        // ---- Rows (active page only) --------------------------------------------------------
        val pageItems = VrMenuModel.itemsFor(page)
        val selected = VrSession.selectedIndex.value
        val firstIndex = VrMenuModel.firstIndexOf(page)
        val rowTop0 = 154f
        // The "画面" page now has 10 rows; 27f keeps the last row's progress track above the
        // 432px panel bottom (154 + 10*27 = 424).
        val rowH = MENU_ROW_HEIGHT
        pageItems.forEachIndexed { row, item ->
            val index = firstIndex + row
            val top = rowTop0 + row * rowH
            val isSelected = index == selected
            card.color = if (isSelected) Color.argb(235, 30, 42, 66) else c[2]
            rect.set(24f, top, 744f, top + rowH - 4f)
            canvas.drawRoundRect(rect, 10f, 10f, card)
            stroke.color = if (isSelected) c[4] else c[3]
            stroke.strokeWidth = if (isSelected) 2f else 1f
            canvas.drawRoundRect(rect, 10f, 10f, stroke)
            if (isSelected) {
                accent.color = c[4]
                canvas.drawRoundRect(24f, top + 2f, 28f, top + rowH - 6f, 2f, 2f, accent)
            }

            label.color = if (isSelected) c[6] else c[7]
            label.textSize = 18f
            val marker = if (isSelected) "▸ " else "   "
            canvas.drawText(marker + item.label, 40f, top + 20f, label)

            val display = VrMenuModel.display(s, item.id)
            if (item.kind == VrMenuItemKind.TOGGLE) {
                val on = VrMenuModel.rawValue(s, item.id) >= 0.5f
                tab.color = if (on) c[4] else c[5]
                rect.set(668f, top + 3f, 736f, top + rowH - 7f)
                canvas.drawRoundRect(rect, 11f, 11f, tab)
                value.color = if (on) c[1] else c[7]
                value.textSize = 15f
                canvas.drawText(
                    display,
                    668f + (68f - value.measureText(display)) / 2f,
                    top + 20f,
                    value,
                )
            } else {
                value.color = if (isSelected) c[4] else c[6]
                value.textSize = 17f
                canvas.drawText(display, 736f - value.measureText(display), top + 20f, value)
            }

            if (item.kind == VrMenuItemKind.RANGE) {
                val span = (item.max - item.min).coerceAtLeast(0.0001f)
                val frac = ((VrMenuModel.rawValue(s, item.id) - item.min) / span).coerceIn(0f, 1f)
                track.color = c[5]
                rect.set(40f, top + 25f, 300f, top + 27f)
                canvas.drawRoundRect(rect, 1f, 1f, track)
                progress.color = if (isSelected) c[4] else c[7]
                rect.set(40f, top + 25f, 40f + 260f * frac, top + 27f)
                canvas.drawRoundRect(rect, 1f, 1f, progress)
            }
        }
    }

    private fun rebuildMesh() {
        val s = settings
        // A perfectly flat plane needs 4 vertices. The 24-segment strip is only meaningful when
        // the user enables curvature, so low-end GPUs draw 4 vertices instead of 50 by default.
        val segments = if (s.curvature <= 0.001f) 1 else VrScreenGeometry.DEFAULT_SEGMENTS
        // The menu is a fixed 16:9 board; the stream quad follows the real decoded aspect.
        val aspect = if (VrSession.menuOpen.value) MENU_ASPECT else VrSession.videoAspect
        val mesh = VrScreenGeometry.build(s, aspect, segments)
        positionBuffer.clear()
        positionBuffer.put(mesh.positions)
        positionBuffer.position(0)
        texCoordBuffer.clear()
        texCoordBuffer.put(mesh.texCoords)
        texCoordBuffer.position(0)

        // Stream-only orientation. VrScreenGeometry emits u in [0,1] and v in {0,1}. Because the
        // SurfaceTexture transform matrix is affine, substituting u -> 1-u / v -> 1-v mirrors the
        // FINAL sampled coordinate (including any crop/translation carried by uSTMatrix). That is
        // exactly what the old stMatrix[0] negation failed to do. The status panel, the menu and
        // the 360° sphere keep sampling texCoordBuffer and are therefore never flipped.
        videoTexCoordBuffer.clear()
        var t = 0
        while (t < mesh.texCoords.size) {
            val u = mesh.texCoords[t]
            val v = mesh.texCoords[t + 1]
            videoTexCoordBuffer.put(if (s.horizontalMirror) 1f - u else u)
            videoTexCoordBuffer.put(if (s.verticalFlip) 1f - v else v)
            t += 2
        }
        videoTexCoordBuffer.position(0)
        vertexCount = mesh.vertexCount
    }

    // -----------------------------------------------------------------------------------------
    // Fixed screen-space centre alignment line (2D / physical-screen overlay).
    //
    // Drawn in onFinishFrame(), AFTER the SDK has composited both distorted eye textures onto the
    // default framebuffer. That is the real 2D screen layer: ONE straight line at x=0 (the exact
    // physical screen centre), y=-1..0 (bottom edge up to the middle). It is NOT in the VR scene,
    // NOT per-eye, NOT warped by distortion, and NO head quaternion / eye view / perspective is
    // applied — so it never moves with head rotation. This reproduces the old Cardboard SDK
    // ui_alignment_marker the user saw on the 2D-only screen. It reuses the existing panel
    // program + a 1x1 white texture: no shader source change, zero-copy video path untouched.
    // -----------------------------------------------------------------------------------------
    private fun createLineResources() {
        // onSurfaceCreated can run again after a GL surface loss; drop the old texture first.
        if (lineTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lineTextureId), 0)
            lineTextureId = 0
        }
        val w = 0.003f   // ~2 px on a 1080p screen; thin hairline like the old SDK marker
        // GL_TRIANGLE_STRIP: bottom-left, top-left, bottom-right, top-right, all in NDC.
        linePositionBuffer.clear()
        linePositionBuffer.put(
            floatArrayOf(
                -w, -1f, 0f,
                -w, 0f, 0f,
                w, -1f, 0f,
                w, 0f, 0f,
            ),
        )
        linePositionBuffer.position(0)
        lineTexCoordBuffer.clear()
        lineTexCoordBuffer.put(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        lineTexCoordBuffer.position(0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        lineTextureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lineTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        // Pure white hairline, matching the original Cardboard SDK alignment marker the user saw
        // in the old 2D-only screen (before the VR GL surface was promoted above the 2D UI layer).
        pixel.setPixel(0, 0, Color.WHITE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pixel, 0)
        pixel.recycle()
    }

    /**
     * Draws ONE fixed alignment line on the physical-screen layer. Called from onFinishFrame(),
     * after both eyes have been composited. Uses the panel program with an identity MVP — no head
     * pose, no eye view, no perspective — hence it never moves with the head or the VR quad.
     */
    private fun drawScreenSpaceAlignmentLine(s: VrSettings, viewport: Viewport) {
        if (!s.showCenterAlignmentLine) return
        val prog = panel ?: return
        if (lineTextureId == 0) return

        // The SDK has already rendered both eyes into the default framebuffer. Reassert the
        // binding + FULL screen viewport so the overlay does not inherit the last eye's half
        // viewport, and make sure no depth/scissor state from the distortion pass clips it.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        GLES20.glUseProgram(prog.program)
        // Identity MVP => aPosition is already in NDC. x=0 is now the exact PHYSICAL screen
        // centre and y=-1..0 runs from the bottom edge up to the middle: a single straight
        // hairline, exactly like the old SDK 2D alignment marker.
        GLES20.glUniformMatrix4fv(prog.mvp, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(prog.brightness, 1f)
        GLES20.glUniform1f(prog.flip, 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(prog.texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lineTextureId)

        linePositionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(prog.position)
        GLES20.glVertexAttribPointer(prog.position, 3, GLES20.GL_FLOAT, false, 12, linePositionBuffer)
        lineTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(prog.texCoord)
        GLES20.glVertexAttribPointer(prog.texCoord, 2, GLES20.GL_FLOAT, false, 8, lineTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(prog.position)
        GLES20.glDisableVertexAttribArray(prog.texCoord)
    }

    // -----------------------------------------------------------------------------------------
    // 360° equirectangular background (optional, default OFF).
    //
    // A low-poly sphere centred on the camera is drawn BEFORE the flat cinema panel. Both are
    // opaque and there is NO depth test, so draw order alone decides: the panel overdraws the
    // centre of the sphere and the sphere only shows around the edges. This keeps the zero-copy
    // video path untouched (the video still uses GL_TEXTURE_EXTERNAL_OES on its own quad) and
    // adds just one extra textured draw when the user enables it.
    // -----------------------------------------------------------------------------------------
    private fun buildBackgroundMesh() {
        val stacks = 16
        val slices = 24
        val radius = 10f
        val vertices = stacks * slices * 6
        val positions = FloatArray(vertices * 3)
        val texCoords = FloatArray(vertices * 2)
        var v = 0
        val stackCount = stacks.toDouble()
        val sliceCount = slices.toDouble()
        for (i in 0 until stacks) {
            val phi0 = Math.PI * i.toDouble() / stackCount
            val phi1 = Math.PI * (i + 1).toDouble() / stackCount
            val v0 = i.toFloat() / stacks.toFloat()
            val v1 = (i + 1).toFloat() / stacks.toFloat()
            for (j in 0 until slices) {
                val theta0 = 2.0 * Math.PI * j.toDouble() / sliceCount
                val theta1 = 2.0 * Math.PI * (j + 1).toDouble() / sliceCount
                val u0 = j.toFloat() / slices.toFloat()
                val u1 = (j + 1).toFloat() / slices.toFloat()
                // Quad (phi0,theta0)-(phi0,theta1)-(phi1,theta1)-(phi1,theta0) as two triangles.
                v = sphereVertex(positions, texCoords, v, phi0, theta0, radius, u0, v0)
                v = sphereVertex(positions, texCoords, v, phi0, theta1, radius, u1, v0)
                v = sphereVertex(positions, texCoords, v, phi1, theta1, radius, u1, v1)
                v = sphereVertex(positions, texCoords, v, phi0, theta0, radius, u0, v0)
                v = sphereVertex(positions, texCoords, v, phi1, theta1, radius, u1, v1)
                v = sphereVertex(positions, texCoords, v, phi1, theta0, radius, u0, v1)
            }
        }
        bgPositionBuffer = newFloatBuffer(positions.size).apply {
            put(positions)
            position(0)
        }
        bgTexCoordBuffer = newFloatBuffer(texCoords.size).apply {
            put(texCoords)
            position(0)
        }
        bgVertexCount = vertices
        Log.d(TAG, "buildBackgroundMesh: sphere vertices=$bgVertexCount radius=$radius")
    }

    /** Writes one sphere vertex (equirectangular UV) and returns the next vertex index. */
    private fun sphereVertex(
        positions: FloatArray,
        texCoords: FloatArray,
        index: Int,
        phi: Double,
        theta: Double,
        radius: Float,
        u: Float,
        vCoord: Float,
    ): Int {
        val sinPhi = Math.sin(phi)
        val r = radius.toDouble()
        val o = index * 3
        positions[o] = (r * sinPhi * Math.cos(theta)).toFloat()
        positions[o + 1] = (r * Math.cos(phi)).toFloat()
        positions[o + 2] = (r * sinPhi * Math.sin(theta)).toFloat()
        val t = index * 2
        texCoords[t] = u
        texCoords[t + 1] = vCoord
        return index + 1
    }

    /**
     * Draws the 360° sphere using the panel program (uFlip=0). Runs on the GL thread, so it also
     * owns releasing the texture when the user switches the feature off (no GL calls off-thread).
     */
    private fun drawBackground(s: VrSettings, mvp: FloatArray, allowBackgroundLoad: Boolean) {
        if (!s.backgroundEnabled) {
            if (allowBackgroundLoad && bgTextureId != 0) deleteBackgroundTexture()
            return
        }
        val prog = panel ?: return
        val posBuf = bgPositionBuffer ?: return
        val texBuf = bgTexCoordBuffer ?: return
        if (bgVertexCount == 0) return
        // Texture (re)load is GL work: only the FBO pass (allowBackgroundLoad=true) owns it. The
        // FILL replay must never re-decode a panorama mid-frame.
        if (allowBackgroundLoad && (bgDirty || bgTextureId == 0)) {
            ensureBackgroundTexture()
            bgDirty = false
        }
        if (!bgReady || bgTextureId == 0) return

        GLES20.glUseProgram(prog.program)
        GLES20.glUniformMatrix4fv(prog.mvp, 1, false, mvp, 0)
        GLES20.glUniform1f(prog.brightness, s.backgroundBrightness)
        GLES20.glUniform1f(prog.flip, 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(prog.texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)

        posBuf.position(0)
        GLES20.glEnableVertexAttribArray(prog.position)
        GLES20.glVertexAttribPointer(prog.position, 3, GLES20.GL_FLOAT, false, 12, posBuf)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(prog.texCoord)
        GLES20.glVertexAttribPointer(prog.texCoord, 2, GLES20.GL_FLOAT, false, 8, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, bgVertexCount)
        GLES20.glDisableVertexAttribArray(prog.position)
        GLES20.glDisableVertexAttribArray(prog.texCoord)
    }

    /** Creates / refreshes the GL_TEXTURE_2D holding the (optionally user-imported) panorama. */
    private fun ensureBackgroundTexture() {
        if (bgTextureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            bgTextureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        val source = loadBackgroundBitmap() ?: return
        // Downscale huge panoramas so a 4096x2048 image does not eat ~32 MB of VRAM on a Helio P60.
        val maxWidth = 2048
        val bitmap = if (source.width > maxWidth) {
            val h = max(1, source.height * maxWidth / source.width)
            Bitmap.createScaledBitmap(source, maxWidth, h, true)
        } else {
            source
        }
        val uploadedW = bitmap.width
        val uploadedH = bitmap.height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
        bgReady = true
        Log.d(TAG, "ensureBackgroundTexture: uploaded ${uploadedW}x${uploadedH}, id=$bgTextureId")
    }

    /**
     * Decodes the user-imported image (content:// URI, file:// URI or plain absolute path).
     * Falls back to a built-in procedural starfield so the background is never blank and needs
     * NO bundled asset — importing is entirely optional.
     */
    private fun loadBackgroundBitmap(): Bitmap? {
        val path = settings.backgroundImageUri
        if (path.isNotEmpty()) {
            val decoded = runCatching {
                if (path.startsWith("content://") || path.startsWith("file://")) {
                    context.contentResolver.openInputStream(Uri.parse(path))
                        ?.use { BitmapFactory.decodeStream(it) }
                } else {
                    BitmapFactory.decodeFile(path)
                }
            }.getOrElse {
                Log.w(TAG, "loadBackgroundBitmap: cannot decode '$path'", it)
                null
            }
            if (decoded != null) return decoded
            Log.w(TAG, "loadBackgroundBitmap: '$path' produced no bitmap; using procedural backdrop")
        }
        return createProceduralBackdrop()
    }

    /** Deterministic dark-blue gradient + stars, drawn once into a 2:1 equirectangular bitmap. */
    private fun createProceduralBackdrop(): Bitmap {
        val w = 1024
        val h = 512
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.rgb(14, 20, 42), Color.rgb(2, 3, 8), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = gradient })
        val star = Paint().apply { color = Color.argb(190, 210, 225, 255) }
        val random = Random(20261107L)
        repeat(240) {
            canvas.drawCircle(
                random.nextFloat() * w.toFloat(),
                random.nextFloat() * h.toFloat(),
                0.6f + random.nextFloat() * 1.3f,
                star,
            )
        }
        // Faint horizon: gives both eyes a common reference so stereo alignment is easy to judge.
        val horizon = Paint().apply {
            color = Color.argb(45, 120, 165, 225)
            strokeWidth = 2f
        }
        canvas.drawLine(0f, h.toFloat() / 2f, w.toFloat(), h.toFloat() / 2f, horizon)
        return bitmap
    }

    /** Frees the panorama texture; called when the feature is switched off or the GL surface dies. */
    private fun deleteBackgroundTexture() {
        if (bgTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(bgTextureId), 0)
            bgTextureId = 0
        }
        bgReady = false
    }

    private companion object {
        // --- 摄像头透视 / Passthrough --------------------------------------------------------
        // NO hardcoded preview size any more: VrPassthroughCamera picks the real
        // StreamConfigurationMap size and reports it via onPreviewConfig(). These two ratios are
        // only a FALLBACK for the first frame before the camera has opened.
        const val PASSTHROUGH_ASPECT_4_3 = 4f / 3f
        const val PASSTHROUGH_ASPECT_16_9 = 16f / 9f
        /**
         * Camera window depth behind the stream screen (metres). Was 0.8, which made the window
         * look like it was floating far behind the game and amplified the relative slide between
         * the head-locked camera and the head-tracked screen ("很大的不协调感"). 0.12 keeps the
         * two layers close together while still letting the nearer stream quad composite on top.
         */
        const val PASSTHROUGH_WINDOW_DEPTH_M = 0.12f
        /**
         * OVERLAY: camera window half-height / stream half-height at windowScale = 1. Was 2.2,
         * i.e. more than twice the game screen — a huge ring of live camera around the picture
         * that read as visual noise. 1.45 still shows the surroundings around the edges but keeps
         * the composition calm. Adjustable via passthroughWindowScale (0.30..1.50).
         */
        const val PASSTHROUGH_WINDOW_VS_SCREEN = 1.45f
        /** CAMERA_MAIN: camera is enlarged into the main picture (at windowScale = 1). */
        const val PASSTHROUGH_CAMERA_MAIN_VS_SCREEN = 1.7f
        /** SIDE_BY_SIDE: half-width of each window, relative to the stream half-height. */
        const val PASSTHROUGH_SIDE_BY_SIDE_HALF_W = 0.86f
        /** SIDE_BY_SIDE: horizontal centre offset of each window (same units). */
        const val PASSTHROUGH_SIDE_BY_SIDE_CENTER_X = 0.95f
        /** CAMERA_MAIN: isotropic scale of the stream picture-in-picture. */
        const val PASSTHROUGH_GAME_PIP_SCALE = 0.30f
        /** CAMERA_MAIN: how far the PiP slides towards the bottom-right (0 = centred, 1 = corner). */
        const val PASSTHROUGH_GAME_PIP_INSET = 0.80f

        /**
         * Destination texture formats tried against the window framebuffer, in order:
         * {internalFormat, format, type}. RGBA8 is first (fast path on RGBA8 displays), but an
         * RGB565 default framebuffer rejects it, hence the RGB565 and RGB8 alternatives.
         */
        val ZOOM_COPY_FORMATS = arrayOf(
            intArrayOf(GLES20.GL_RGBA, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE),
            intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5),
            intArrayOf(GLES20.GL_RGB, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE),
        )
        /** Sentinel for zoomSnapshotMode: use glReadPixels + glTexSubImage2D every frame. */
        const val ZOOM_READPIXELS = -2
        /** Refuse a readback that would allocate an unreasonable amount of native memory. */
        const val MAX_SNAPSHOT_BYTES = 32L * 1024L * 1024L

        const val TAG = "VrTheaterRenderer"
        /** One heartbeat line per this many onDrawEye calls (both eyes share one counter). */
        const val LOG_INTERVAL_FRAMES = 120

        const val PANEL_W = 768
        const val PANEL_H = 432
        const val MENU_ASPECT = 16f / 9f
        // The largest second-level page has 10 rows. 27px rows keep the last baseline and its
        // progress track inside the 432px-tall panel (154 + 10*27 = 424).
        const val MENU_ROWS_PER_COLUMN = 10
        const val MENU_ROW_HEIGHT = 27f

        /** [gradient start, gradient end, card, stroke, accent, track, primary text, muted text]. */
        val MENU_COLORS: IntArray = intArrayOf(
            Color.rgb(10, 14, 26),
            Color.rgb(4, 6, 12),
            Color.rgb(22, 30, 46),
            Color.rgb(58, 74, 100),
            Color.rgb(77, 200, 255),
            Color.rgb(40, 52, 74),
            Color.rgb(235, 243, 255),
            Color.rgb(140, 160, 185),
        )

        /**
         * Status panel / menu are ordinary GL_TEXTURE_2D bitmaps. VrScreenGeometry emits top v=0 /
         * bottom v=1 and GLUtils uploads the bitmap's first row at t=0, so the quad's top vertex
         * (v=0) already samples the top of the panel: NO flip is needed here.
         *
         * IMPORTANT: this uniform only exists in PANEL_FRAGMENT_SHADER. The stream uses
         * VIDEO_FRAGMENT_SHADER (samplerExternalOES + uSTMatrix), which has NO uFlip at all, so
         * this constant can NEVER fix a reversed stream. It was wrongly set to 1.0f in an earlier
         * attempt — that flipped the base UI upside down while leaving the video untouched.
         * Stream orientation is handled by the video quad texCoords in rebuildMesh() instead.
         */
        const val PANEL_FLIP_Y = 0.0f

        const val MAX_VERTICES = (VrScreenGeometry.DEFAULT_SEGMENTS + 1) * 2

        fun newFloatBuffer(capacityFloats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(capacityFloats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val PANEL_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uBrightness;
            uniform float uFlip;
            varying vec2 vTexCoord;
            void main() {
                float v = mix(vTexCoord.y, 1.0 - vTexCoord.y, uFlip);
                vec4 c = texture2D(uTexture, vec2(vTexCoord.x, v));
                gl_FragColor = vec4(c.rgb * uBrightness, 1.0);
            }
        """

        // #extension MUST be the first directive in a GLES ES 1.00 fragment shader, so it goes on
        // line 0 with no leading newline/indentation (const val cannot call trimIndent()).
        val VIDEO_FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform mat4 uSTMatrix;
            uniform float uBrightness;
            varying vec2 vTexCoord;
            void main() {
                vec2 uv = (uSTMatrix * vec4(vTexCoord.x, vTexCoord.y, 0.0, 1.0)).xy;
                vec4 c = texture2D(uTexture, uv);
                gl_FragColor = vec4(c.rgb * uBrightness, 1.0);
            }
            """.trimIndent()
    }
}
