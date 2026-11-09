package com.example.kazumi

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.RemoteAction
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.net.Uri
import android.app.PictureInPictureParams
import android.graphics.drawable.Icon
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import com.ryanheise.audioservice.AudioService
import com.ryanheise.audioservice.AudioServiceActivity
import com.example.kazumi.vr.VrKeys
import com.example.kazumi.vr.VrSession
import com.example.kazumi.vr.VrSettingsCodec
import com.example.kazumi.vr.VrTheaterView
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout

class MainActivity: AudioServiceActivity() {
    private val CHANNEL = "com.predidit.kazumi/intent"
    private val STORAGE_CHANNEL = "com.predidit.kazumi/storage"
    private val PIP_CHANNEL = "com.predidit.kazumi/pip"
    private val VR_CHANNEL = "com.predidit.kazumi/vr"
    private var intentChannel: MethodChannel? = null
    private var pipChannel: MethodChannel? = null
    private var vrChannel: MethodChannel? = null
    private var downloadDirectoryChannel: MethodChannel? = null
    private var downloadDirectoryHandler: DownloadDirectoryHandler? = null

    private var pipIsPlaying = false
    private var pipDanmakuEnabled = false
    private var pipActionReceiverRegistered = false
    private var autoEnterPipOnHomeGesture = false
    private var pipInPlayerPage = false
    private var pipAspectWidth = 16
    private var pipAspectHeight = 9
    private var pipSourceRect: Rect? = null
    private var inPipMode = false
    private var systemBarsHidden = false
    private var originalWindowBackground: Drawable? = null
    private var windowBackgroundOverridden = false

    // VR state
    private var vrView: VrTheaterView? = null
    private var vrContainer: FrameLayout? = null
    private var vrEnabled = false

    private val actionPipPlayPause = "com.predidit.kazumi.pip.PLAY_PAUSE"
    private val actionPipForward = "com.predidit.kazumi.pip.FORWARD"
    private val actionPipToggleDanmaku = "com.predidit.kazumi.pip.TOGGLE_DANMAKU"

    // Ratios outside [1:2.39, 2.39:1] make enterPictureInPictureMode throw.
    private val maxPipAspectRatio = 2.39f

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                actionPipPlayPause -> notifyFlutterPipAction("play_pause")
                actionPipForward -> notifyFlutterPipAction("forward")
                actionPipToggleDanmaku -> notifyFlutterPipAction("toggle_danmaku")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerPipActionReceiverIfNeeded()
        // The CardboardView is a plain Android View layered ABOVE the Flutter content view. VR
        // therefore keeps the same Activity, FlutterEngine and media_kit Player; the decoder is
        // never restarted and the 2D playback chain stays untouched.
        createVrContainer()
    }

    private fun setupVrChannel(engine: FlutterEngine) {
        vrChannel = MethodChannel(engine.dartExecutor.binaryMessenger, VR_CHANNEL)
        vrChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "setVrActive" -> {
                    // VrSession is the single source of truth. onEnabledChanged drives the
                    // CardboardView overlay, so an exit from inside the headset also updates
                    // Flutter's VRService.enabled.
                    VrSession.setEnabled(call.argument<Boolean>("active") ?: false)
                    result.success(VrSession.enabled.value)
                }
                "isVrActive" -> result.success(VrSession.enabled.value)
                "getSettings" -> result.success(VrSettingsCodec.toMap(VrSession.settings.value))
                "updateSettings" -> {
                    val patch = call.argument<Map<String, Any?>>("settings") ?: emptyMap()
                    VrSession.updateSettings(
                        VrSettingsCodec.fromMap(VrSession.settings.value, patch),
                    )
                    vrView?.applySettings(VrSession.settings.value)
                    result.success(VrSettingsCodec.toMap(VrSession.settings.value))
                }
                "setVideoAspect" -> {
                    VrSession.setVideoAspect(
                        call.argument<Int>("width") ?: 0,
                        call.argument<Int>("height") ?: 0,
                    )
                    result.success(null)
                }
                "recenter" -> {
                    VrSession.recenter()
                    result.success(null)
                }
                "scanQrCode" -> {
                    scanCardboardViewerQr()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    /** Idempotent: configureFlutterEngine runs BEFORE the onCreate body, so Dart can call
     *  setVrActive() before onCreate had a chance to add the container. */
    private fun createVrContainer() {
        if (vrContainer != null) return
        vrContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // Opaque black behind the Cardboard GL surface: no 2D UI may bleed through the
            // headset. The GL SurfaceView itself is promoted with setZOrderOnTop(true).
            setBackgroundColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
        }
        // Window.getDecorView() is typed as View; the actual DecorView is a FrameLayout, so the
        // container can be added as a sibling of the Flutter content view (i.e. drawn on top).
        (window.decorView as ViewGroup).addView(
            vrContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** Called from VrSession.onEnabledChanged; always marshalled onto the UI thread. */
    private fun applyVrEnabled(enabled: Boolean) {
        runOnUiThread {
            vrEnabled = enabled
            if (vrContainer == null) createVrContainer()
            val container = vrContainer ?: return@runOnUiThread
            if (enabled) {
                if (vrView == null) {
                    vrView = VrTheaterView(this).also { v ->
                        container.addView(
                            v,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                }
                container.visibility = View.VISIBLE
                container.bringToFront()
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowCompat.getInsetsController(window, window.decorView).let { controller ->
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Cardboard's head pose assumes a landscape display. configChanges declares
                // orientation, so this rotates in-place without recreating the Activity or the
                // media_kit decoder.
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                vrView?.start()
            } else {
                vrView?.stop()
                container.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                applySystemBarsState()
            }
            vrChannel?.invokeMethod("onVrStateChanged", mapOf("enabled" to enabled))
        }
    }

    private fun scanCardboardViewerQr() {
        // The SDK's QR JNI (qr_code.cc) starts QrCodeCaptureActivity from the context_ that
        // Initialize.initialize(context) installs. That call normally happens inside the
        // CardboardViewApi constructor, which only exists once a VrTheaterView has been built.
        // When the user scans from「内容与偏好 → VR 设置」before ever entering VR, context_ is
        // still null and the static entry silently does nothing — so initialize explicitly.
        try {
            val view = vrView
            if (view != null) {
                view.scanViewerQrCode()
            } else {
                com.google.cardboard.sdk.Initialize.initialize(this)
                com.google.cardboard.sdk.QrCode.scanQrCodeAndSaveDeviceParams()
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Cardboard QR scan unavailable", t)
        }
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1001)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            // Update passthrough setting based on permission result
            if (!granted) {
                val current = VrSession.settings.value
                VrSession.updateSettings(current.copy(passthroughEnabled = false))
            }
        }
        downloadDirectoryHandler?.onRequestPermissionsResult(requestCode)
    }

    override fun onDestroy() {
        VrSession.onEnabledChanged = null
        VrSession.onSettingsChanged = null
        VrSession.setEnabled(false)
        vrView?.release()
        vrView = null
        vrContainer?.let {
            (window.decorView as? ViewGroup)?.removeView(it)
        }
        vrContainer = null
        vrChannel?.setMethodCallHandler(null)
        vrChannel = null
        VrSession.requestCameraPermission = null
        VrSession.onRecenter = null
        VrSession.onScanQr = null
        VrSession.onVrMenuClosed = null
        downloadDirectoryChannel?.setMethodCallHandler(null)
        downloadDirectoryChannel = null
        downloadDirectoryHandler?.dispose()
        downloadDirectoryHandler = null
        unregisterPipActionReceiverIfNeeded()
        stopService(Intent(this, AudioService::class.java))
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (vrEnabled) vrView?.start()
    }

    override fun onPause() {
        if (vrEnabled) vrView?.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !vrEnabled) {
            applySystemBarsState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncPictureInPictureMode()
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        syncPictureInPictureMode()
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // configureFlutterEngine runs inside super.onCreate(), BEFORE our onCreate body, so
        // the settings store, the VR container and all VrSession callbacks must be ready
        // before Dart can call getSettings / setVrActive.
        createVrContainer()
        VrSession.attach(this)
        VrSession.onScanQr = { scanCardboardViewerQr() }
        VrSession.onRecenter = { vrView?.recenter() }
        VrSession.onVrMenuClosed = { /* Flutter owns its own focus; nothing to hand back. */ }
        VrSession.requestCameraPermission = { requestCameraPermission() }
        VrSession.onEnabledChanged = { active -> applyVrEnabled(active) }
        VrSession.onSettingsChanged = { settings ->
            runOnUiThread {
                vrView?.applySettings(settings)
                vrChannel?.invokeMethod("onSettingsChanged", VrSettingsCodec.toMap(settings))
            }
        }
        setupVrChannel(flutterEngine)

        downloadDirectoryHandler = DownloadDirectoryHandler(this)
        downloadDirectoryChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger, DownloadDirectoryHandler.CHANNEL
        )
        downloadDirectoryChannel?.setMethodCallHandler(downloadDirectoryHandler)
        intentChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        intentChannel?.setMethodCallHandler { call, result ->
            if (call.method == "openWithMime") {
                val url = call.argument<String>("url")
                val mimeType = call.argument<String>("mimeType")
                if (url != null && mimeType != null) {
                    openWithMime(url, mimeType)
                    result.success(null)
                } else {
                    result.error("INVALID_ARGUMENT", "URL and MIME type required", null)
                }
            } else if (call.method == "getAndroidSdkVersion") {
                val sdkVersion = getAndroidSdkVersion()
                result.success(sdkVersion)
            } else if (call.method == "setSystemBarsHidden") {
                systemBarsHidden = call.arguments as? Boolean ?: false
                if (!vrEnabled) applySystemBarsState()
                result.success(null)
            } else {
                result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, STORAGE_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "getAvailableStorage") {
                val path = call.argument<String>("path") ?: filesDir.absolutePath
                val availableBytes = getAvailableStorage(path)
                result.success(availableBytes)
            } else {
                result.notImplemented()
            }
        }

        pipChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PIP_CHANNEL)
        pipChannel?.setMethodCallHandler { call, result ->
            if (call.method == "isPictureInPictureSupported") {
                result.success(isPictureInPictureSupported())
            } else if (call.method == "enterPictureInPictureMode") {
                pipAspectWidth = call.argument<Int>("width") ?: pipAspectWidth
                pipAspectHeight = call.argument<Int>("height") ?: pipAspectHeight
                val entered = enterPictureInPicture()
                result.success(entered)
            } else if (call.method == "updatePictureInPictureActions") {
                val playing = call.argument<Boolean>("playing") ?: false
                val danmakuEnabled = call.argument<Boolean>("danmakuEnabled") ?: false
                pipAspectWidth = call.argument<Int>("width") ?: pipAspectWidth
                pipAspectHeight = call.argument<Int>("height") ?: pipAspectHeight
                updatePipSourceRect(call)
                updatePictureInPictureActions(playing, danmakuEnabled)
                result.success(true)
            } else if (call.method == "setAndroidAutoEnterPIPEnabled") {
                autoEnterPipOnHomeGesture = call.argument<Boolean>("enabled") ?: false
                refreshPictureInPictureParamsIfNeeded()
                result.success(true)
            } else if (call.method == "setAndroidPIPInPlayerPage") {
                pipInPlayerPage = call.argument<Boolean>("inPlayerPage") ?: false
                if (!pipInPlayerPage) {
                    pipSourceRect = null
                }
                refreshWindowBackground()
                refreshPictureInPictureParamsIfNeeded()
                result.success(true)
            } else {
                result.notImplemented()
            }
        }

    }

    private fun openWithMime(url: String, mimeType: String) {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.setDataAndType(Uri.parse(url), mimeType)
        startActivity(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        downloadDirectoryHandler?.onActivityResult(requestCode, resultCode, data)
    }

    private fun getAndroidSdkVersion(): Int {
        return Build.VERSION.SDK_INT
    }

    // System bars belong to the full size window; replayed on leaving PiP.
    private fun applySystemBarsState() {
        if (inPipMode || vrEnabled) {
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (systemBarsHidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Either PiP callback may arrive first, depending on the device.
    private fun syncPictureInPictureMode() {
        val current = isInPictureInPictureMode
        if (current != inPipMode) {
            inPipMode = current
            pipChannel?.invokeMethod("onModeChanged", mapOf("isInPipMode" to current))
            refreshWindowBackground()
        }
        applySystemBarsState()
    }

    // Cover the surface with black until Flutter renders the resized frame.
    private fun refreshWindowBackground() {
        val blackBackground = inPipMode || pipInPlayerPage
        if (blackBackground == windowBackgroundOverridden) {
            return
        }
        if (blackBackground) {
            originalWindowBackground = window.decorView.background
            window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        } else {
            window.setBackgroundDrawable(originalWindowBackground)
            originalWindowBackground = null
        }
        windowBackgroundOverridden = blackBackground
    }

    private fun isPictureInPictureSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun enterPictureInPicture(): Boolean {
        if (!isPictureInPictureSupported()) {
            return false
        }
        if (isInPictureInPictureMode) {
            return true
        }
        return try {
            enterPictureInPictureMode(buildPictureInPictureParams())
        } catch (e: Exception) {
            false
        }
    }

    private fun updatePictureInPictureActions(
        playing: Boolean,
        danmakuEnabled: Boolean
    ) {
        if (!isPictureInPictureSupported()) {
            return
        }
        pipIsPlaying = playing
        pipDanmakuEnabled = danmakuEnabled
        refreshPictureInPictureParamsIfNeeded()
    }

    // In picture in picture the rect would describe the small window, so the
    // pre-PiP one is kept as the expand back target.
    private fun updatePipSourceRect(call: MethodCall) {
        if (inPipMode) {
            return
        }
        val left = call.argument<Int>("sourceLeft") ?: return
        val top = call.argument<Int>("sourceTop") ?: return
        val right = call.argument<Int>("sourceRight") ?: return
        val bottom = call.argument<Int>("sourceBottom") ?: return
        if (right <= left || bottom <= top) {
            return
        }
        pipSourceRect = Rect(left, top, right, bottom)
    }

    private fun buildPipSourceRectHint(): Rect? {
        val rect = pipSourceRect ?: return null
        val contentView = window.decorView.findViewById<View>(android.R.id.content) ?: return null
        val contentBounds = Rect()
        if (!contentView.getGlobalVisibleRect(contentBounds)) {
            return null
        }
        val hint = Rect(rect)
        hint.offset(contentBounds.left, contentBounds.top)
        if (!hint.intersect(contentBounds) || hint.isEmpty) {
            return null
        }
        return hint
    }

    private fun buildPipAspectRatio(): Rational {
        val width = if (pipAspectWidth > 0) pipAspectWidth else 16
        val height = if (pipAspectHeight > 0) pipAspectHeight else 9
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > maxPipAspectRatio -> Rational(239, 100)
            ratio < 1f / maxPipAspectRatio -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val actions = buildPipActions()
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(buildPipAspectRatio())
        buildPipSourceRectHint()?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnterPipOnHomeGesture && pipInPlayerPage)
            builder.setSeamlessResizeEnabled(true)
        }
        if (actions.isNotEmpty()) {
            builder.setActions(actions)
        }
        return builder.build()
    }

    private fun refreshPictureInPictureParamsIfNeeded() {
        if (!isPictureInPictureSupported()) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPictureInPictureParams())
            } catch (e: Exception) {
                // The activity can reject params while it is not resumed.
            }
        }
    }

    private fun buildPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return emptyList()
        }

        val allActions = mutableListOf<RemoteAction>(
            createPipAction(
                action = actionPipToggleDanmaku,
                requestCode = 1003,
                iconRes = if (pipDanmakuEnabled) R.drawable.ic_pip_danmaku_on else R.drawable.ic_pip_danmaku_off,
                title = if (pipDanmakuEnabled) "Danmaku On" else "Danmaku Off",
                description = if (pipDanmakuEnabled) "Turn off danmaku" else "Turn on danmaku",
                enabled = true
            ),
            createPipAction(
                action = actionPipPlayPause,
                requestCode = 1001,
                iconRes = if (pipIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                title = if (pipIsPlaying) "Pause" else "Play",
                description = if (pipIsPlaying) "Pause playback" else "Play playback",
                enabled = true
            ),
            createPipAction(
                action = actionPipForward,
                requestCode = 1002,
                iconRes = R.drawable.ic_pip_forward_80,
                title = "Forward",
                description = "Forward by custom seconds",
                enabled = true
            )
        )

        val maxActions = maxNumPictureInPictureActions
        if (allActions.size > maxActions) {
            allActions.subList(maxActions, allActions.size).clear()
        }
        return allActions
    }

    private fun createPipAction(
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String,
        description: String,
        enabled: Boolean
    ): RemoteAction {
        val intent = Intent(action).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            description,
            pendingIntent
        ).apply {
            setEnabled(enabled)
        }
    }

    private fun notifyFlutterPipAction(action: String) {
        pipChannel?.invokeMethod("onAction", mapOf("action" to action))
    }

    private fun registerPipActionReceiverIfNeeded() {
        if (pipActionReceiverRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(actionPipPlayPause)
            addAction(actionPipForward)
            addAction(actionPipToggleDanmaku)
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pipActionReceiverRegistered = true
    }

    private fun unregisterPipActionReceiverIfNeeded() {
        if (!pipActionReceiverRegistered) {
            return
        }
        unregisterReceiver(pipActionReceiver)
        pipActionReceiverRegistered = false
    }

    private fun getAvailableStorage(path: String): Long {
        return try {
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1L
        }
    }

    // ---- VR input forwarding ---------------------------------------------------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // VrSession handles L1+R1 ENTRY while VR is off, and owns EVERY key once VR is on.
        if (VrSession.onKeyEvent(event)) return true
        // Nothing may reach the hidden 2D Flutter UI while the phone is inside the headset.
        if (VrSession.enabled.value) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (VrSession.enabled.value) {
            val l2 = event.getAxisValue(VrKeys.AXIS_LTRIGGER)
            val r2 = event.getAxisValue(VrKeys.AXIS_RTRIGGER)
            VrSession.onTriggerEvent(l2, r2)
            if (VrSession.onMotionEvent(event)) return true
            // D-pad hat / stick events the menu did not consume must not drive the 2D UI.
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_DPAD)
            ) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Gamepad-only VR: a finger on the screen behind the cardboard box is never meaningful.
        if (VrSession.enabled.value) return true
        return super.dispatchTouchEvent(event)
    }

    /**
     * Belt-and-braces exit path. dispatchKeyEvent() normally consumes BACK while VR is on, but a
     * few OEM builds route the system back gesture straight here. Without this, BACK inside the
     * headset could pop the Flutter route and leave the CardboardView covering a dead player.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (VrSession.enabled.value) {
            VrSession.setEnabled(false)
            return
        }
        super.onBackPressed()
    }
}
