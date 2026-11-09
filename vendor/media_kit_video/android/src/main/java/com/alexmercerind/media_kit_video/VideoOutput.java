/**
 * This file is a part of media_kit (https://github.com/media-kit/media-kit).
 * <p>
 * Copyright © 2021 & onwards, Hitesh Kumar Saini <saini123hitesh@gmail.com>.
 * All rights reserved.
 * Use of this source code is governed by MIT license that can be found in the LICENSE file.
 *
 * Kazumi VR change: the published `wid` is no longer permanently bound to the Flutter surface.
 * When the native Cardboard theatre installs an external-OES SurfaceTexture, every live
 * VideoOutput repoints libmpv's --wid at it and restores the Flutter surface on exit. See
 * VrSurfaceRegistry.
 */
package com.alexmercerind.media_kit_video;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper;
import java.util.HashSet;
import java.util.Locale;

import io.flutter.view.TextureRegistry;

public class VideoOutput implements TextureRegistry.SurfaceProducer.Callback {
    private static final String TAG = "VideoOutput";
    private static final HashSet<Long> deletedGlobalObjectRefs = new HashSet<>();
    /** Lazy: loading this class in a pure-JVM unit test must not require an Android Looper. */
    private static volatile Handler handler;

    private static Handler mainHandler() {
        Handler result = handler;
        if (result == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    result = new Handler(looper);
                    handler = result;
                }
            } catch (Throwable ignored) {
                // Mockable android.jar in JVM tests: delete the ref inline instead.
            }
        }
        return result;
    }


    private long id = 0;
    /** Currently published JNI global ref (what libmpv's --wid points at). */
    private long wid = 0;
    /** Global ref to the ordinary Flutter surface. Owned by this VideoOutput. */
    private long defaultWid = 0;
    /** Global ref to the VR SurfaceTexture's Surface. Owned by this VideoOutput. */
    private long vrWid = 0;
    private int vrGeneration = -1;
    private boolean lastWantVr = false;

    private final TextureUpdateCallback textureUpdateCallback;

    private final boolean enableSurfaceProducer;
    private final TextureRegistry.SurfaceProducer surfaceProducer;
    private final TextureRegistry.SurfaceTextureEntry surfaceTextureEntry;
    private Surface surfaceTextureSurface;
    private int surfaceTextureWidth = 1;
    private int surfaceTextureHeight = 1;
    private boolean surfaceTextureNotified = false;

    private final Object lock = new Object();

    VideoOutput(TextureRegistry textureRegistryReference, boolean enableSurfaceProducer, TextureUpdateCallback textureUpdateCallback) {
        this.textureUpdateCallback = textureUpdateCallback;
        this.enableSurfaceProducer = enableSurfaceProducer;

        if (enableSurfaceProducer) {
            Log.i(TAG, "Android video output rendering path: SurfaceProducer");
            surfaceProducer = textureRegistryReference.createSurfaceProducer();
            surfaceProducer.setCallback(this);
            surfaceTextureEntry = null;
        } else {
            Log.i(TAG, "Android video output rendering path: SurfaceTexture");
            surfaceProducer = null;
            surfaceTextureEntry = textureRegistryReference.createSurfaceTexture();
            id = surfaceTextureEntry.id();
            createSurfaceTextureSurface();
        }
        // VR may already be running when a new Player is created (e.g. switching episodes while
        // inside the headset). Pick up the installed VR surface immediately.
        VrSurfaceRegistry.register(this);
    }

    public void dispose() {
        synchronized (lock) {
            VrSurfaceRegistry.unregister(this);
            if (enableSurfaceProducer) {
                try {
                    surfaceProducer.getSurface().release();
                } catch (Throwable e) {
                    Log.e(TAG, "dispose", e);
                }
                try {
                    surfaceProducer.release();
                } catch (Throwable e) {
                    Log.e(TAG, "dispose", e);
                }
                onSurfaceCleanup();
            } else {
                onSurfaceTextureCleanup();
                try {
                    surfaceTextureEntry.release();
                } catch (Throwable e) {
                    Log.e(TAG, "dispose", e);
                }
            }
        }
    }

    public void setSurfaceSize(int width, int height) {
        setSurfaceSize(width, height, false);
    }

    private void setSurfaceSize(int width, int height, boolean force) {
        synchronized (lock) {
            if (enableSurfaceProducer) {
                try {
                    if (!force && surfaceProducer.getWidth() == width && surfaceProducer.getHeight() == height) {
                        return;
                    }
                    surfaceProducer.setSize(width, height);
                    onSurfaceAvailable();
                } catch (Throwable e) {
                    Log.e(TAG, "setSurfaceSize", e);
                }
            } else {
                try {
                    if (!force && surfaceTextureNotified && surfaceTextureWidth == width && surfaceTextureHeight == height) {
                        return;
                    }
                    surfaceTextureEntry.surfaceTexture().setDefaultBufferSize(width, height);
                    surfaceTextureWidth = width;
                    surfaceTextureHeight = height;
                    surfaceTextureNotified = true;
                    createSurfaceTextureSurface();
                    applyVrSurface();
                    // Always republish: even if the wid did not change, Dart needs the new
                    // width/height (this is the real decoded frame size, used by the VR mesh).
                    publish(wid);
                } catch (Throwable e) {
                    Log.e(TAG, "setSurfaceSize", e);
                }
            }
        }
    }

    @Override
    public void onSurfaceAvailable() {
        synchronized (lock) {
            Log.i(TAG, "onSurfaceAvailable");
            id = surfaceProducer.id();
            long ref = newGlobalObjectRef(surfaceProducer.getSurface());
            if (defaultWid != 0 && defaultWid != ref) {
                scheduleDelete(defaultWid);
            }
            defaultWid = ref;
            applyVrSurface();
            publish(wid);
        }
    }

    @Override
    public void onSurfaceCleanup() {
        synchronized (lock) {
            Log.i(TAG, "onSurfaceCleanup");
            if (defaultWid != 0) {
                scheduleDelete(defaultWid);
                defaultWid = 0;
            }
            // If VR still owns a valid surface, keep feeding it instead of setting vo=null.
            if (vrWid != 0 && VrSurfaceRegistry.currentSurface() != null) {
                publish(vrWid);
            } else {
                publish(0);
            }
        }
    }

    private void createSurfaceTextureSurface() {
        // The SurfaceTexture fallback is only effective with Android's Skia backend.
        if (surfaceTextureSurface != null) {
            return;
        }
        surfaceTextureSurface = new Surface(surfaceTextureEntry.surfaceTexture());
        long ref = newGlobalObjectRef(surfaceTextureSurface);
        if (defaultWid != 0 && defaultWid != ref) {
            scheduleDelete(defaultWid);
        }
        defaultWid = ref;
        applyVrSurface();
    }

    private void onSurfaceTextureCleanup() {
        Log.i(TAG, "onSurfaceTextureCleanup");
        publish(0);
        if (surfaceTextureSurface != null) {
            try {
                surfaceTextureSurface.release();
            } catch (Throwable e) {
                Log.e(TAG, "onSurfaceTextureCleanup", e);
            }
            surfaceTextureSurface = null;
        }
        if (defaultWid != 0) {
            scheduleDelete(defaultWid);
            defaultWid = 0;
        }
        if (vrWid != 0) {
            scheduleDelete(vrWid);
            vrWid = 0;
        }
        wid = 0;
    }

    /**
     * Re-evaluates which Surface libmpv should render into. Called on VR install/clear, on
     * VideoOutput construction and after the Flutter surface becomes available.
     *
     * <p>The VR Surface is owned by the Cardboard renderer; this class must never release it.
     * Only the JNI global references created here are released, with a delay so mpv can finish
     * any in-flight frame on the old surface.
     */
    void applyVrSurface() {
        synchronized (lock) {
            Surface vr = VrSurfaceRegistry.currentSurface();
            final boolean wantVr = vr != null;
            final int generation = VrSurfaceRegistry.generation();
            if (wantVr) {
                if (vrWid == 0 || vrGeneration != generation) {
                    if (vrWid != 0) {
                        scheduleDelete(vrWid);
                    }
                    vrWid = newGlobalObjectRef(vr);
                    vrGeneration = generation;
                }
            } else if (vrWid != 0) {
                scheduleDelete(vrWid);
                vrWid = 0;
                vrGeneration = generation;
            }
            long target = wantVr ? vrWid : defaultWid;
            if (target == 0) {
                // Flutter producer surface not created yet; onSurfaceAvailable() will retry.
                return;
            }
            if (target == wid) {
                if (wantVr != lastWantVr) {
                    // Force a 0 -> wid edge even in the unlikely case the JNI runtime reused the
                    // very same global-ref address, so Dart's wid listener always fires.
                    publish(0);
                    publish(target);
                }
            } else {
                publish(target);
            }
            lastWantVr = wantVr;
        }
    }

    private void publish(long value) {
        wid = value;
        textureUpdateCallback.onTextureUpdate(id, value, currentWidth(), currentHeight());
    }

    private int currentWidth() {
        if (enableSurfaceProducer) {
            try {
                return surfaceProducer.getWidth();
            } catch (Throwable e) {
                return 1;
            }
        }
        return surfaceTextureWidth;
    }

    private int currentHeight() {
        if (enableSurfaceProducer) {
            try {
                return surfaceProducer.getHeight();
            } catch (Throwable e) {
                return 1;
            }
        }
        return surfaceTextureHeight;
    }

    private static void scheduleDelete(final long ref) {
        if (ref == 0) {
            return;
        }
        Handler main = mainHandler();
        if (main == null) {
            deleteGlobalObjectRef(ref);
        } else {
            main.postDelayed(() -> deleteGlobalObjectRef(ref), 5000);
        }
    }

    private static long newGlobalObjectRef(Object object) {
        Log.i(TAG, String.format(Locale.ENGLISH, "newGlobalRef: object = %s", object));
        try {
            return MediaKitAndroidHelper.newGlobalObjectRef(object);
        } catch (Throwable e) {
            Log.e(TAG, "newGlobalRef", e);
            return 0;
        }
    }

    private static void deleteGlobalObjectRef(long ref) {
        if (deletedGlobalObjectRefs.contains(ref)) {
            Log.i(TAG, String.format(Locale.ENGLISH, "deleteGlobalObjectRef: ref = %d ALREADY DELETED", ref));
            return;
        }
        if (deletedGlobalObjectRefs.size() > 100) {
            deletedGlobalObjectRefs.clear();
        }
        deletedGlobalObjectRefs.add(ref);
        Log.i(TAG, String.format(Locale.ENGLISH, "deleteGlobalObjectRef: ref = %d", ref));
        try {
            MediaKitAndroidHelper.deleteGlobalObjectRef(ref);
        } catch (Throwable e) {
            Log.e(TAG, "deleteGlobalObjectRef", e);
        }
    }
}
