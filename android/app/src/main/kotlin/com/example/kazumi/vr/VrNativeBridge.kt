package com.example.kazumi.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Thin native bridge for the Flutter-rendered VR mode.
 *
 * Design constraint: the Google Cardboard SDK has no Maven artifact, its
 * CardboardView GL surface cannot sample media_kit's Flutter texture, and the
 * project has no Compose compiler plugin. Therefore VR rendering happens in
 * Flutter; this class only supplies two data streams using Android framework
 * APIs, with zero third-party Gradle dependencies:
 *
 *  - pose  : rotation-vector quaternion from SensorManager (head tracking)
 *  - input : raw gamepad KeyEvent / MotionEvent (menu + navigation)
 *
 * All interpretation (dead zones, repeats, menu state) lives in Dart.
 */
class VrNativeBridge(private val context: Context, engine: FlutterEngine) {

    private val messenger = engine.dartExecutor.binaryMessenger
    private val poseChannel = EventChannel(messenger, POSE_CHANNEL)
    private val inputChannel = EventChannel(messenger, INPUT_CHANNEL)
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var poseSink: EventChannel.EventSink? = null
    @Volatile private var inputSink: EventChannel.EventSink? = null
    @Volatile private var vrActive = false
    @Volatile private var sensorRegistered = false

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // GAME_ROTATION_VECTOR avoids magnetometer drift; fall back to the fused
    // ROTATION_VECTOR on devices that do not expose the game variant.
    private val rotationSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size < 3) return
            val x = v[0]
            val y = v[1]
            val z = v[2]
            // Some devices omit the scalar component on TYPE_ROTATION_VECTOR.
            val w = if (v.size >= 4) {
                v[3]
            } else {
                kotlin.math.sqrt(
                    (1.0f - x * x - y * y - z * z).coerceAtLeast(0f).toDouble()
                ).toFloat()
            }
            val payload = listOf<Any>(x, y, z, w, event.timestamp / 1_000_000L)
            mainHandler.post { poseSink?.success(payload) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    init {
        poseChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                poseSink = events
                startSensors()
            }
            override fun onCancel(arguments: Any?) {
                poseSink = null
                stopSensors()
            }
        })

        inputChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                inputSink = events
            }
            override fun onCancel(arguments: Any?) {
                inputSink = null
            }
        })

        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "setVrActive" -> {
                    vrActive = call.argument<Boolean>("active") ?: false
                    if (vrActive) startSensors() else stopSensors()
                    result.success(null)
                }
                "isVrActive" -> result.success(vrActive)
                "hasRotationSensor" -> result.success(rotationSensor != null)
                else -> result.notImplemented()
            }
        }
    }

    fun startSensors() {
        if (sensorRegistered || rotationSensor == null) return
        if (sensorManager?.registerListener(
                sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME
            ) == true
        ) {
            sensorRegistered = true
        }
    }

    fun stopSensors() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(sensorListener)
        sensorRegistered = false
    }

    fun onHostResume() {
        if (poseSink != null) startSensors()
    }

    fun onHostPause() {
        stopSensors()
    }

    /** Returns true when the event was consumed for the VR layer. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!vrActive) return false
        val sink = inputSink ?: return false
        val source = event.source
        val isGamepad =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        if (!isGamepad && event.keyCode != KeyEvent.KEYCODE_BACK) return false
        sink.success(
            mapOf<String, Any>(
                "type" to if (event.keyCode == KeyEvent.KEYCODE_BACK) "back" else "key",
                "keyCode" to event.keyCode,
                "action" to event.action,
                "repeatCount" to event.repeatCount,
                "eventTime" to event.eventTime,
                "source" to source,
            )
        )
        return true
    }

    /** Returns true when the event was consumed for the VR layer. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!vrActive) return false
        val sink = inputSink ?: return false
        val source = event.source
        val isGamepad =
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isGamepad) return false
        sink.success(
            mapOf<String, Any>(
                "type" to "motion",
                "action" to event.actionMasked,
                "axisX" to event.getAxisValue(MotionEvent.AXIS_X),
                "axisY" to event.getAxisValue(MotionEvent.AXIS_Y),
                "axisZ" to event.getAxisValue(MotionEvent.AXIS_Z),
                "axisRz" to event.getAxisValue(MotionEvent.AXIS_RZ),
                "hatX" to event.getAxisValue(MotionEvent.AXIS_HAT_X),
                "hatY" to event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                "eventTime" to event.eventTime,
                "source" to source,
            )
        )
        return true
    }

    fun dispose() {
        stopSensors()
        poseChannel.setStreamHandler(null)
        inputChannel.setStreamHandler(null)
        methodChannel.setMethodCallHandler(null)
        poseSink = null
        inputSink = null
    }

    companion object {
        const val METHOD_CHANNEL = "com.predidit.kazumi/vr"
        const val POSE_CHANNEL = "com.predidit.kazumi/vr/pose"
        const val INPUT_CHANNEL = "com.predidit.kazumi/vr/input"
    }
}
