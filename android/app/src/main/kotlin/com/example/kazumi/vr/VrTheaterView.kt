package com.example.kazumi.vr

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.view.View
import com.google.cardboard.sdk.CardboardView

/**
 * Google Cardboard SDK view hosted inside MainActivity (NOT a separate Activity).
 * Keeping it in the same Activity preserves the existing NativeStreamInputRouter gamepad path.
 *
 * LAYERING — how the VR scene wins over the 2D phone stream (2026-11-07):
 *  - The app's 2D UI owns `StreamVideoSurface`, whose children are SurfaceViews. SurfaceView layers
 *    are composited by SurfaceFlinger, NOT by the Compose draw order, so VrModeHost being drawn
 *    *after* OpenNowApp does NOT put the Cardboard GL layer above the 2D stream layer. Without an
 *    explicit promotion, the 2D stream SurfaceView stays on top and "VR" looks exactly like the
 *    flat full-screen 2D picture (the reported bug).
 *  - FIX: promote the Cardboard GL SurfaceView with `setZOrderOnTop(true)`. That makes the VR GL
 *    surface the top-most surface in the window — above the 2D stream SurfaceView AND above the
 *    Compose 2D UI — so the pure-black VR scene (black clear + flat video quad) is authoritative.
 *  - TIMING IS THE WHOLE GAME. `setZOrderOnTop` only takes effect on the NEXT surface creation; if
 *    it is applied AFTER the GL surface already exists it forces a surface RE-creation, which on the
 *    OPPO F9 left the GL layer transparent (CardboardView uses RENDERMODE_WHEN_DIRTY + a Choreographer
 *    loop, so the re-created surface never got a frame and the 2D stream showed through — this was
 *    the 3cfcade failure). The glView SurfaceView child is `addView`'d inside CardboardView's own
 *    constructor (CardboardView.initialize()), i.e. BEFORE VrTheaterView.init runs, but its surface
 *    is only created later when the view is attached to the window. So we apply setZOrderOnTop(true)
 *    ONCE, in init, and never touch it again (not in onAttachedToWindow, not on settings changes).
 *  - The 2D stream SurfaceViews are deliberately LEFT ALIVE (not hidden/GONE): hiding them would
 *    destroy their native Surface and force a decoder restart / black frame on VR exit. Left alive
 *    they simply composite behind the top-most VR GL surface, which is exactly what we want.
 *
 * The SDK 2D touch overlay (alignment marker, X / gear buttons) is hidden: this project is
 * gamepad-only, and setStereoRenderMode(true) calls showAll() on that overlay, so it is re-hidden
 * after every settings change and on attach.
 *
 * DIAGNOSTIC LOGGING: every lifecycle edge logs under TAG "VrTheaterView". These are one-shot
 * events (never in the per-frame loop), so they cost nothing on a low-end device. Filter with
 * `logcat -s VrSession,VrModeHost,VrTheaterView,VrTheaterRenderer`.
 */
class VrTheaterView(context: Context) : CardboardView(context) {
    companion object { private const val TAG = "VrTheaterView" }

    private val theaterRenderer = VrTheaterRenderer(context)

    init {
        Log.d(TAG, "init: constructing VrTheaterView; childCount=$childCount")
        // Promote the Cardboard GL SurfaceView ABOVE the 2D phone stream SurfaceView.
        // SurfaceView layers are composited by SurfaceFlinger, not the Compose draw order, so
        // without this the 2D stream SurfaceView stays on top and VR looks like the flat 2D picture.
        // setZOrderOnTop must be applied BEFORE the GL surface is created (here, in init, long
        // before the view is attached to the window). Re-applying it after surface creation forces
        // a surface re-creation that left the GL layer transparent on low-end devices, so we call it
        // exactly ONCE and never again. The glView SurfaceView child is added by CardboardView's own
        // constructor, so it is already present here and its surface does not yet exist.
        promoteGlSurface()
        setRenderer(theaterRenderer)
        // Headset use has no touch input to reset the screen-off timer.
        setKeepScreenOn(true)
        applySettings(VrSession.settings.value)
    }

    /**
     * Makes the Cardboard GL SurfaceView the top-most surface in the window so the black VR scene
     * (black clear + flat video quad) is composited above the 2D phone stream and the 2D Compose UI.
     * Call ONLY from init, before the GL surface is created. Never re-call it (e.g. from
     * onAttachedToWindow) — re-applying setZOrderOnTop after the surface exists forces a surface
     * re-creation that can leave the GL layer transparent and let the 2D stream show through.
     */
    private fun promoteGlSurface() {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is SurfaceView) {
                child.setZOrderOnTop(true)
                Log.d(TAG, "promoteGlSurface: setZOrderOnTop(true) on SurfaceView child #$index (${child.javaClass.simpleName})")
            }
        }
    }

    /** Recenter head tracking: current head orientation becomes the new "forward". */
    fun recenter() {
        theaterRenderer.recenter()
    }

    fun applySettings(value: VrSettings) {
        theaterRenderer.applySettings(value)
        val wantStereo = value.viewerMode == VrViewerMode.CARDBOARD
        Log.d(
            TAG,
            "applySettings: viewerMode=${value.viewerMode} wantStereo=$wantStereo " +
                "wasStereo=$isStereoRenderModeEnabled showStatus=${value.showStatus}",
        )
        if (isStereoRenderModeEnabled != wantStereo) setStereoRenderMode(wantStereo)
        hideTouchOverlay()
    }

    /** Hides the SDK's non-SurfaceView touch overlay (gamepad-only project). */
    private fun hideTouchOverlay() {
        var hidden = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !is SurfaceView) {
                child.visibility = View.GONE
                hidden++
            }
        }
        Log.d(TAG, "hideTouchOverlay: childCount=$childCount hiddenNonSurface=$hidden")
    }

    fun start() {
        Log.d(TAG, "start: onResume()")
        onResume()
    }

    fun stop() {
        Log.d(TAG, "stop: onPause()")
        onPause()
    }

    fun release() {
        Log.d(TAG, "release: onDestroy()")
        onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow: childCount=$childCount width=$width height=$height")
        // setStereoRenderMode(true) re-shows the SDK overlay, so re-hide it on every attach.
        hideTouchOverlay()
    }

    override fun onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow")
        VrVideoSurfaceRegistry.clear()
        super.onDetachedFromWindow()
    }
}
