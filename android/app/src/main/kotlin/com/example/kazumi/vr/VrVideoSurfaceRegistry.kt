package com.example.kazumi.vr

import android.graphics.SurfaceTexture
import android.view.Surface
import com.alexmercerind.media_kit_video.VrSurfaceRegistry

/**
 * VR GL thread ↔ libmpv video output bridge.
 *
 * The actual state lives in the vendored media_kit_video plugin module: Flutter plugin modules
 * cannot see app classes (and Kotlin `internal` is module-scoped), but the app CAN see plugin
 * classes. Keeping ONE shared registry is what lets `VideoOutput` repoint `--wid` at the
 * SurfaceTexture created here by VrTheaterRenderer.setupVideoTexture().
 *
 * Lifecycle rule: install()/clear() may be called from any thread, but `updateTexImage()` and
 * `getTransformMatrix()` MUST run on the GL thread that created the SurfaceTexture.
 */
internal object VrVideoSurfaceRegistry {
    val isFrameAvailable: Boolean get() = VrSurfaceRegistry.isFrameAvailable()

    fun currentTexture(): SurfaceTexture? = VrSurfaceRegistry.currentTexture()

    fun currentTextureId(): Int = VrSurfaceRegistry.currentTextureId()

    fun currentSurface(): Surface? = VrSurfaceRegistry.currentSurface()

    fun markFrameAvailable() = VrSurfaceRegistry.markFrameAvailable()

    fun consumeFrameAvailable(): Boolean = VrSurfaceRegistry.consumeFrameAvailable()

    /** Called from VrTheaterRenderer.onSurfaceCreated (GL thread). */
    fun install(id: Int, texture: SurfaceTexture, output: Surface) =
        VrSurfaceRegistry.install(id, texture, output)

    /** VR off / GL surface gone: libmpv falls back to the normal 2D Flutter surface. */
    fun clear() = VrSurfaceRegistry.clear()
}
