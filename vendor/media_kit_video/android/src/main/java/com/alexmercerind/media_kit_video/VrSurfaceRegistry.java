/**
 * VR video-surface bridge for Kazumi's native Cardboard theatre.
 *
 * <p>The Google Cardboard SDK renders into its own GLSurfaceView: a Flutter `Texture` widget
 * cannot be sampled from that GL context. Instead the VR renderer creates an
 * {@code GL_TEXTURE_EXTERNAL_OES} backed {@link android.graphics.SurfaceTexture} and publishes the
 * producing {@link android.view.Surface} here. Every live {@link VideoOutput} then repoints libmpv's
 * {@code --wid} at that Surface, so frames go straight into the VR theatre with no Bitmap copy and
 * no second decode. Disabling VR restores the ordinary Flutter surface.
 *
 * <p>This class lives in the plugin module (not the app module) on purpose: Flutter plugin modules
 * cannot reference app classes, while the app CAN reference plugin classes. Kotlin `internal`
 * visibility is module-scoped, so an `internal object` in the app would be invisible here.
 *
 * <p>Lifecycle: {@link #install} / {@link #clear} may be called from the GL thread. All
 * {@code textureUpdateCallback} calls (which cross a Flutter MethodChannel) are marshalled onto
 * the Android main looper.
 */
package com.alexmercerind.media_kit_video;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VrSurfaceRegistry {
    private static final Object LOCK = new Object();
    /** Lazy: pure-JVM app tests must be able to load this class without an Android Looper. */
    private static volatile Handler main;
    private static final List<VideoOutput> OUTPUTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FRAME_AVAILABLE = new AtomicBoolean(false);
    /** Bumped on every install/clear so VideoOutput can drop a stale JNI global ref. */
    private static final AtomicInteger GENERATION = new AtomicInteger(0);

    private static Surface vrSurface;
    private static SurfaceTexture vrSurfaceTexture;
    private static int vrTextureId = 0;

    private VrSurfaceRegistry() {}

    static void register(VideoOutput output) {
        if (!OUTPUTS.contains(output)) {
            OUTPUTS.add(output);
        }
        output.applyVrSurface();
    }

    static void unregister(VideoOutput output) {
        OUTPUTS.remove(output);
    }

    /** Called on the VR GL thread once the external-OES texture has been created. */
    public static void install(int textureId, SurfaceTexture texture, Surface surface) {
        synchronized (LOCK) {
            vrTextureId = textureId;
            vrSurfaceTexture = texture;
            vrSurface = surface;
            FRAME_AVAILABLE.set(false);
            GENERATION.incrementAndGet();
        }
        notifyOutputs();
    }

    /** VR is off / the GL surface is gone: resume writing to the ordinary 2D surface. */
    public static void clear() {
        synchronized (LOCK) {
            vrSurface = null;
            vrSurfaceTexture = null;
            vrTextureId = 0;
            FRAME_AVAILABLE.set(false);
            GENERATION.incrementAndGet();
        }
        notifyOutputs();
    }

    /**
     * Returns a main-looper Handler, or {@code null} when running under a pure-JVM unit test
     * (the mockable android.jar throws for every android.os.Looper method). Class initialization
     * must never touch the Android framework: VrSession.setEnabled(false) calls clear() from
     * JVM tests.
     */
    private static Handler mainHandler() {
        Handler handler = main;
        if (handler == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    handler = new Handler(looper);
                    main = handler;
                }
            } catch (Throwable ignored) {
                // No Android runtime (Robolectric-free JVM test): run inline instead.
            }
        }
        return handler;
    }

    private static void notifyOutputs() {
        final Runnable task = () -> {
            for (VideoOutput output : OUTPUTS) {
                output.applyVrSurface();
            }
        };
        Handler handler = mainHandler();
        if (handler == null) {
            task.run();
            return;
        }
        boolean onMain;
        try {
            onMain = Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable ignored) {
            onMain = false;
        }
        if (onMain) {
            task.run();
        } else {
            handler.post(task);
        }
    }

    public static Surface currentSurface() {
        synchronized (LOCK) {
            Surface surface = vrSurface;
            return surface != null && surface.isValid() ? surface : null;
        }
    }

    public static SurfaceTexture currentTexture() {
        synchronized (LOCK) {
            return vrSurfaceTexture;
        }
    }

    public static int currentTextureId() {
        synchronized (LOCK) {
            return vrTextureId;
        }
    }

    public static int generation() {
        return GENERATION.get();
    }

    public static boolean isFrameAvailable() {
        return FRAME_AVAILABLE.get();
    }

    /** Called from the SurfaceTexture's onFrameAvailableListener (decoder thread). */
    public static void markFrameAvailable() {
        FRAME_AVAILABLE.set(true);
    }

    /** Consumed on the VR GL thread immediately before updateTexImage(). */
    public static boolean consumeFrameAvailable() {
        return FRAME_AVAILABLE.compareAndSet(true, false);
    }
}
