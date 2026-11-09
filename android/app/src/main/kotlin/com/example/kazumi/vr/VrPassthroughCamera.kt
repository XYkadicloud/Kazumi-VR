package com.example.kazumi.vr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Owns the REAL phone camera used by the VR "透视 / Passthrough" window.
 *
 * Design constraints (all deliberate):
 *
 *  1. NO WebRTC / CameraX dependency. The app already has a WebRTC-based recorder camera
 *     (`RecordingCameraCapture`) that feeds the stream; reusing it would couple passthrough to the
 *     encoder pipeline and steal the only camera client. This class uses the framework Camera2 API
 *     directly and writes frames into a [Surface] backed by the renderer's own
 *     `SurfaceTexture` (GL_TEXTURE_EXTERNAL_OES) — the same zero-copy pattern as the video stream.
 *  2. All camera work happens on ONE private [HandlerThread]. `start()` / `stop()` / `release()`
 *     only post messages and never block the GL thread or the UI thread. Camera2 callbacks are
 *     delivered to that same handler, so the state machine has no cross-thread races.
 *  3. FAILURE IS NEVER FATAL. Missing CAMERA permission, no matching lens, a busy device (e.g. the
 *     stream is already recording from the camera), or any CameraAccessException is reported via
 *     [Listener.onUnavailable]; the renderer then simply keeps the original pure-black / 360°
 *     background. VR never black-screens or crashes because of passthrough.
 *  4. [generation] invalidates late asynchronous callbacks: after stop()/release(), an in-flight
 *     `onOpened` closes the device instead of opening a session on a dead surface.
 *
 * 2026-11-09 real-device fix (方向错 / 直接拉伸 / 缺分辨率与帧率):
 *  - Old code hardcoded `setDefaultBufferSize(1280x960 / 1280x720)` in the renderer and built the
 *    window quad from a GUESSED 4:3 / 16:9 ratio. Camera2 only ever outputs sizes it advertises
 *    in StreamConfigurationMap, so the real buffer ratio often differed from the quad and the
 *    picture was visibly stretched. Now THIS class picks the real size, applies it to the
 *    SurfaceTexture before session creation (so getTransformMatrix() carries no crop), and reports
 *    the REAL width/height/frame-rate/rotation through Listener.onPreviewConfig; the renderer
 *    builds the quad from those exact numbers and applies the effective rotation's aspect swap.
 *  - Resolution tiers (360P/480P/720P/1080P) and frame-rate tiers (30/60) are requested through
 *    VrPassthroughResolution / VrPassthroughFrameRate. The frame rate is written into
 *    CONTROL_AE_TARGET_FPS_RANGE from CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES; a 30 fps-only phone
 *    silently falls back to 30 and the effective value is reported back for the status panel.
 *
 * The camera is ALWAYS released when the VR host leaves the composition (see
 * VrTheaterRenderer.onRendererShutdown), so the stream's own camera recording can take it back.
 */
internal class VrPassthroughCamera(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the camera thread; the renderer only flips a volatile flag. */
        fun onUnavailable(reason: String)

        /**
         * Called on the camera thread once the device is open, BEFORE the capture session starts.
         *
         * [width] / [height] are the REAL preview buffer size chosen from StreamConfigurationMap —
         * the renderer MUST build its window quad from these (after applying the effective
         * rotation) so the picture is never stretched. [fps] is the frame rate actually written
         * into the AE request (can be lower than requested on a 30 fps-only phone).
         * [autoRotationDeg] is the sensor-installation angle used by the AUTO rotation setting;
         * [autoMirror] is the front-lens default mirroring. The renderer only stores these as
         * volatile fields.
         */
        fun onPreviewConfig(
            width: Int,
            height: Int,
            fps: Int,
            autoRotationDeg: Int,
            autoMirror: Boolean,
        )
    }

    private companion object {
        const val TAG = "VrPassthroughCamera"
    }

    private val gate = Any()
    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** All fields below are touched ONLY on the camera thread. */
    private var running = false
    private var currentFacing: VrPassthroughFacing? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var currentSurface: Surface? = null
    /** Camera parameters are part of the idempotency key: changing ANY of them MUST restart the
     *  capture session, otherwise a resolution / frame-rate switch would silently do nothing. */
    private var currentResolution: VrPassthroughResolution? = null
    private var currentAspect: VrPassthroughAspect? = null
    private var currentFrameRate: VrPassthroughFrameRate? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var generation = 0

    @Volatile
    private var released = false

    private fun ensureThread(): Handler = synchronized(gate) {
        val existing = handler
        if (existing != null) {
            existing
        } else {
            val t = HandlerThread(TAG).apply { start() }
            thread = t
            Handler(t.looper).also { handler = it }
        }
    }

    /**
     * Starts (or restarts with a different lens / size / frame rate) the preview. Idempotent for an
     * unchanged request, so `VrModeHost.update` calling applySettings() on every recomposition is
     * harmless. Changing ANY camera parameter forces a session restart, which is what makes the
     * resolution / frame-rate options actually take effect.
     */
    fun start(
        surfaceTexture: SurfaceTexture,
        surface: Surface,
        facing: VrPassthroughFacing,
        resolution: VrPassthroughResolution,
        aspect: VrPassthroughAspect,
        frameRate: VrPassthroughFrameRate,
    ) {
        val h = ensureThread()
        h.post {
            if (released) return@post
            if (running && currentFacing == facing && currentSurface === surface &&
                currentSurfaceTexture === surfaceTexture &&
                currentResolution == resolution && currentAspect == aspect &&
                currentFrameRate == frameRate
            ) {
                return@post
            }
            stopLocked()
            // Capture into a local val: smart-casting a nullable property inside nested callback
            // objects is fragile, and a compile error here costs a whole CI round.
            val mgr = cameraManager
            if (mgr == null) {
                reportUnavailable("no CameraManager on this device")
                return@post
            }
            if (!hasCameraPermission()) {
                reportUnavailable("CAMERA runtime permission not granted")
                return@post
            }
            val id = pickCameraId(mgr, facing)
            if (id == null) {
                reportUnavailable("no camera device available")
                return@post
            }
            running = true
            currentFacing = facing
            currentSurfaceTexture = surfaceTexture
            currentSurface = surface
            currentResolution = resolution
            currentAspect = aspect
            currentFrameRate = frameRate
            val gen = ++generation
            try {
                mgr.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (released || gen != generation || !running) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        val chars = try {
                            mgr.getCameraCharacteristics(id)
                        } catch (e: Exception) {
                            null
                        }
                        val targetW = requestedWidth(resolution, aspect)
                        val targetH = resolution.shortEdge
                        val size = choosePreviewSize(chars, targetW, targetH)
                        val fpsRange = chooseFpsRange(chars, frameRate.fps)
                        val effectiveFps = fpsRange?.upper ?: frameRate.fps
                        val autoRotationDeg = previewRotationDeg(id)
                        // Native setter: no current GL context needed. Doing it BEFORE the capture
                        // session is created means the Camera2 producer and the GL texture agree on
                        // the exact buffer size, so getTransformMatrix() carries no crop/scale and
                        // the renderer's UV rotation stays valid. updateTexImage() and
                        // getTransformMatrix() still run on the GL thread in the renderer.
                        try {
                            surfaceTexture.setDefaultBufferSize(size.width, size.height)
                        } catch (e: Exception) {
                            failLocked(gen, "setDefaultBufferSize failed: ${e.javaClass.simpleName}")
                            return
                        }
                        listener.onPreviewConfig(
                            size.width,
                            size.height,
                            effectiveFps,
                            autoRotationDeg,
                            facing == VrPassthroughFacing.FRONT,
                        )
                        Log.i(
                            TAG,
                            "passthrough preview config: ${size.width}x${size.height} " +
                                "requested=${targetW}x${targetH} fps=$effectiveFps " +
                                "rotation=$autoRotationDeg facing=$facing",
                        )
                        openSession(camera, surface, gen, fpsRange)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (gen == generation) failLocked(gen, "camera disconnected")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (gen == generation) failLocked(gen, "camera error code $error")
                    }
                }, h)
            } catch (e: Exception) {
                failLocked(gen, "openCamera failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun openSession(
        camera: CameraDevice,
        surface: Surface,
        gen: Int,
        fpsRange: Range<Int>?,
    ) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (released || gen != generation || !running) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply {
                                    addTarget(surface)
                                    if (fpsRange != null) {
                                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                                    }
                                }
                            session.setRepeatingRequest(builder.build(), null, handler)
                            Log.i(TAG, "passthrough preview started (${currentFacing}, fps=$fpsRange)")
                        } catch (e: Exception) {
                            failLocked(gen, "repeating request failed: ${e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        failLocked(gen, "capture session configure failed")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            failLocked(gen, "createCaptureSession failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Stops the preview but keeps the thread alive so a later start() is cheap. */
    fun stop() {
        val h = handler ?: return
        h.post { stopLocked() }
    }

    /** Camera-thread-only state reset. Closes session and device, invalidates pending callbacks. */
    private fun stopLocked() {
        generation++
        running = false
        currentFacing = null
        currentSurfaceTexture = null
        currentSurface = null
        currentResolution = null
        currentAspect = null
        currentFrameRate = null
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
    }

    private fun failLocked(gen: Int, reason: String) {
        stopLocked()
        if (!released && gen != 0) reportUnavailable(reason)
    }

    /** Final teardown: quits the thread after draining the queued stop(). Never called twice. */
    fun release() {
        synchronized(gate) {
            if (released) return
            released = true
        }
        val h = handler
        val t = thread
        if (h != null && t != null) {
            h.post { stopLocked() }
            t.quitSafely()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * AUTO rotation baseline for the preview stream.
     *
     * The old implementation used `(SENSOR_ORIENTATION - displayRotation + 360) % 360`, which is
     * the JPEG_ORIENTATION formula used when SAVING a photo. Camera2 does NOT rotate PREVIEW
     * buffers for the display, and SurfaceTexture.getTransformMatrix() contains no rotation either
     * (only a vertical flip / crop). In VR we force landscape, so with the usual sensor angle of
     * 90 that formula evaluated to 0 and the live picture was left lying on its side by exactly
     * 90° — the real-device "方向是错的" report. The correct preview baseline is the sensor
     * installation angle itself. A manual 0/90/180/270 override in VrSettings (see
     * VrPassthroughRotation) covers devices with unusual sensors or viewer mounting.
     */
    private fun previewRotationDeg(cameraId: String): Int = try {
        cameraManager?.getCameraCharacteristics(cameraId)
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Exception) {
        90
    }

    /** Requested width for a resolution tier's short edge and the chosen aspect. */
    private fun requestedWidth(
        resolution: VrPassthroughResolution,
        aspect: VrPassthroughAspect,
    ): Int {
        val shortEdge = resolution.shortEdge
        return if (aspect == VrPassthroughAspect.FOUR_THREE) shortEdge * 4 / 3 else shortEdge * 16 / 9
    }

    /**
     * Picks the size the device ACTUALLY advertises instead of assuming 1280x960 / 1280x720.
     * Priority:
     *   1. ratio matches the requested aspect (3% tolerance) AND short edge is nearest the tier;
     *   2. otherwise the nearest short edge overall — its REAL ratio is reported to the renderer,
     *      which builds the window quad from it, so the picture is still never stretched.
     */
    private fun choosePreviewSize(chars: CameraCharacteristics?, targetW: Int, targetH: Int): Size {
        val sizes: Array<Size> = try {
            chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        if (sizes.isEmpty()) return Size(targetW, targetH)
        val targetAspect = targetW.toFloat() / targetH.toFloat()

        var bestMatch: Size? = null
        var bestMatchScore = Float.MAX_VALUE
        for (s in sizes) {
            val ar = s.width.toFloat() / s.height.toFloat()
            if (abs(ar - targetAspect) > 0.03f) continue
            val score = abs(minOf(s.width, s.height) - targetH).toFloat() + abs(ar - targetAspect)
            if (score < bestMatchScore) {
                bestMatchScore = score
                bestMatch = s
            }
        }
        if (bestMatch != null) return bestMatch

        var best = sizes[0]
        var bestDelta = Int.MAX_VALUE
        for (s in sizes) {
            val delta = abs(minOf(s.width, s.height) - targetH)
            if (delta < bestDelta) {
                bestDelta = delta
                best = s
            }
        }
        return best
    }

    /**
     * Highest AE fps range whose upper bound is within the requested tier (15 / 30 / 60). A 15 fps
     * request prefers a fixed [15,15] over [7,15]; a 60 fps request on a 30 fps-only phone resolves
     * to 30 instead of failing or dropping the preview. If no range fits, the slowest stable range
     * is used. Null = "let Camera2 choose".
     */
    private fun chooseFpsRange(chars: CameraCharacteristics?, target: Int): Range<Int>? {
        val ranges: Array<Range<Int>>? = try {
            chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (e: Exception) {
            null
        }
        if (ranges == null || ranges.isEmpty()) return null
        var best: Range<Int>? = null
        var bestUpper = -1
        var bestLower = -1
        for (r in ranges) {
            if (r.upper > target) continue
            // Prefer the highest upper bound, then the highest LOWER bound. Most phones advertise
            // both [15,30] and [30,30]: picking [15,30] would let the AE drop the passthrough
            // preview to 15 fps in low light, which reads as stutter even though "30 fps" was
            // selected. Preferring the fixed range keeps the requested tier stable.
            if (r.upper > bestUpper || (r.upper == bestUpper && r.lower > bestLower)) {
                bestUpper = r.upper
                bestLower = r.lower
                best = r
            }
        }
        if (best != null) return best
        var slowest: Range<Int>? = null
        var slowestUpper = Int.MAX_VALUE
        for (r in ranges) {
            if (r.upper < slowestUpper) {
                slowestUpper = r.upper
                slowest = r
            }
        }
        return slowest
    }

    /**
     * Prefers the requested lens; if the device has no such lens (common on TV boxes / single-
     * camera phones) it falls back to the first available camera instead of failing outright.
     */
    private fun pickCameraId(manager: CameraManager, facing: VrPassthroughFacing): String? {
        val wanted = if (facing == VrPassthroughFacing.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList unavailable", e)
            return null
        }
        val exact = ids.firstOrNull { id ->
            try {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            } catch (e: Exception) {
                false
            }
        }
        return exact ?: ids.firstOrNull()
    }

    private fun reportUnavailable(reason: String) {
        Log.w(TAG, "passthrough unavailable: $reason")
        listener.onUnavailable(reason)
    }
}
