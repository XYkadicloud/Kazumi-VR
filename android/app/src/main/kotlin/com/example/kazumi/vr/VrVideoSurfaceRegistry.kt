package com.example.kazumi.vr

import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between the streaming decoder and the VR GL thread.
 *
 * When VR is active, the host app asks this registry for a [Surface] to feed video frames into.
 * The GL thread consumes those frames via [consumeFrameAvailable] → [SurfaceTexture.updateTexImage]
 * and renders them onto the theatre quad inside [VrTheaterRenderer].
 *
 * Lifecycle rule: install()/clear() may be called from any thread, but updateTexImage() and
 * getTransformMatrix() MUST be called on the GL thread that created the SurfaceTexture.
 */
internal object VrVideoSurfaceRegistry {
    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var textureId: Int = 0

    private val frameAvailable = AtomicBoolean(false)

    /** Returns true when a new decoder frame has arrived since the last consume. */
    val isFrameAvailable: Boolean get() = frameAvailable.get()

    fun currentTexture(): SurfaceTexture? = surfaceTexture

    fun currentTextureId(): Int = textureId

    /** Returns the raw Surface that the decoder should write to, or null if no texture is installed. */
    fun currentSurface(): Surface? = surface

    /** Called by the SurfaceTexture listener (decoder thread). */
    fun markFrameAvailable() {
        frameAvailable.set(true)
    }

    /** Returns true at most once per newly available frame. */
    fun consumeFrameAvailable(): Boolean = frameAvailable.compareAndSet(true, false)

    /** Called from VrTheaterRenderer.onSurfaceCreated (GL thread). */
    fun install(id: Int, texture: SurfaceTexture, output: Surface) {
        clear()
        textureId = id
        surfaceTexture = texture
        surface = output
    }

    /**
     * Detaches the decoder target. Clearing here only drops references so the decoder immediately
     * falls back to the normal 2D surface. VrTheaterRenderer owns the actual Surface /
     * SurfaceTexture / GL texture release in releaseVideoTexture() / onRendererShutdown().
     */
    fun clear() {
        surface = null
        surfaceTexture = null
        textureId = 0
        frameAvailable.set(false)
    }
}
