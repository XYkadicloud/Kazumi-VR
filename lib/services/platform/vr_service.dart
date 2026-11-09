import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Flutter facade over the NATIVE Google Cardboard theatre.
///
/// All rendering, head tracking, lens distortion, the in-VR GL menu and every
/// gamepad action live in the Android side (`com.example.kazumi.vr.*`). In
/// particular there is NO Flutter VR overlay: while VR is enabled a native
/// CardboardView covers the whole window and all touch/key input is consumed
/// by VrSession.
///
/// Settings have exactly ONE authoritative store: native `VrSettingsStore`
/// (SharedPreferences "opennow_vr_settings"). The 「内容与偏好 → VR 设置」 page
/// reads it with `getSettings`, writes patches with `updateSettings`, and the
/// in-headset GL menu writes to the same store. MainActivity pushes
/// `onSettingsChanged` / `onVrStateChanged` back here so both surfaces always
/// agree, including changes made with a gamepad while wearing the headset.
class VRService {
  VRService._();
  static final VRService instance = VRService._();

  static const MethodChannel _channel = MethodChannel('com.predidit.kazumi/vr');

  final ValueNotifier<bool> enabled = ValueNotifier<bool>(false);
  final ValueNotifier<Map<String, dynamic>> settings =
      ValueNotifier<Map<String, dynamic>>(<String, dynamic>{});
  final ValueNotifier<double> videoAspect = ValueNotifier<double>(16.0 / 9.0);

  bool _initialized = false;

  bool get _nativeAvailable =>
      defaultTargetPlatform == TargetPlatform.android;

  /// Mirrors `VrSettings.DEFAULT`. Used only as a fallback before the native
  /// store answers (or on non-Android platforms), never as a second data source.
  static const Map<String, dynamic> defaults = <String, dynamic>{
    'screenScale': 1.0,
    'screenDistanceMeters': 2.2,
    'brightness': 1.0,
    'curvature': 0.0,
    'screenVerticalOffset': 0.0,
    'viewerMode': 'CARDBOARD',
    'headTrackingEnabled': true,
    'headTrackingSensitivity': 1.0,
    'gamepadDeadZone': 0.20,
    'menuRepeatMs': 140,
    'showStatus': true,
    'showCenterAlignmentLine': false,
    'horizontalMirror': false,
    'verticalFlip': false,
    'backgroundEnabled': false,
    'backgroundImageUri': '',
    'backgroundBrightness': 1.0,
    'ipdScale': 1.0,
    'screenFillMode': 'FILL',
    'zoomScale': 1.35,
    'passthroughEnabled': false,
    'passthroughCameraFacing': 'BACK',
    'passthroughOpacity': 1.0,
    'passthroughWindowScale': 0.85,
    'passthroughAspect': 'FOUR_THREE',
    'passthroughMode': 'OVERLAY',
    'passthroughResolution': 'P480',
    'passthroughFrameRate': 'FPS_30',
    'passthroughRotation': 'AUTO',
    'passthroughMirror': false,
    'passthroughHeadLocked': false,
  };

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    settings.value = Map<String, dynamic>.unmodifiable(defaults);
    if (!_nativeAvailable) return;
    _channel.setMethodCallHandler(_onNativeEvent);
    await refreshFromNative();
  }

  /// Re-reads the native state (settings + on/off). Safe after hot restart.
  Future<void> refreshFromNative() async {
    if (!_nativeAvailable) return;
    try {
      final dynamic raw = await _channel.invokeMethod<dynamic>('getSettings');
      if (raw is Map) {
        settings.value = _merge(raw);
      }
      final dynamic active =
          await _channel.invokeMethod<bool>('isVrActive');
      if (active is bool) enabled.value = active;
    } on MissingPluginException {
      // VR native bridge is Android-only.
    } on PlatformException {
      // Keep the fallback defaults; the settings page stays usable.
    } catch (_) {
      // Never let a channel failure break normal playback.
    }
  }

  Future<dynamic> _onNativeEvent(MethodCall call) async {
    switch (call.method) {
      case 'onVrStateChanged':
        final Object? args = call.arguments;
        if (args is Map && args['enabled'] is bool) {
          enabled.value = args['enabled'] as bool;
        }
        break;
      case 'onSettingsChanged':
        final Object? args = call.arguments;
        if (args is Map) {
          settings.value = _merge(args);
        }
        break;
    }
    return null;
  }

  Map<String, dynamic> _merge(Map<dynamic, dynamic> raw) =>
      Map<String, dynamic>.unmodifiable(<String, dynamic>{
        ...defaults,
        ...Map<String, dynamic>.from(raw),
      });

  Future<void> setVREnabled(bool value) async {
    await initialize();
    // Optimistic update so the player button reacts instantly; the native
    // onVrStateChanged callback is the authoritative confirmation (it also
    // fires when the user exits VR from inside the headset with BACK).
    enabled.value = value;
    if (!_nativeAvailable) return;
    try {
      final dynamic result = await _channel.invokeMethod<bool>(
        'setVrActive',
        <String, dynamic>{'active': value},
      );
      if (result is bool) enabled.value = result;
    } on MissingPluginException {
      enabled.value = false;
    } on PlatformException {
      enabled.value = false;
    } catch (_) {
      enabled.value = false;
    }
  }

  Future<void> toggleVR() async {
    await initialize();
    await setVREnabled(!enabled.value);
  }

  Future<bool> isVREnabled() async {
    await initialize();
    return enabled.value;
  }

  /// Sends the decoded frame size to the native renderer so the theatre quad
  /// keeps the video's real aspect ratio instead of stretching it.
  Future<void> setVideoAspect(int width, int height) async {
    if (width <= 0 || height <= 0) return;
    final double aspect = width / height;
    if (aspect.isNaN || aspect.isInfinite) return;
    videoAspect.value = aspect.clamp(0.25, 8.0).toDouble();
    if (!_nativeAvailable) return;
    try {
      await _channel.invokeMethod<void>(
        'setVideoAspect',
        <String, dynamic>{'width': width, 'height': height},
      );
    } catch (_) {
      // Non-fatal: the renderer keeps its last known aspect.
    }
  }

  Future<void> recenter() async {
    await initialize();
    if (!_nativeAvailable) return;
    try {
      await _channel.invokeMethod<void>('recenter');
    } catch (_) {
      // Ignore on unsupported platforms.
    }
  }

  /// Launches the official Cardboard SDK QR scanner, which persists the viewer
  /// profile (lens distortion / FOV / IPD) used by the native renderer.
  Future<void> scanQrCode() async {
    await initialize();
    if (!_nativeAvailable) return;
    try {
      await _channel.invokeMethod<void>('scanQrCode');
    } catch (_) {
      // Ignore on unsupported platforms.
    }
  }

  /// Applies a partial patch to the SINGLE native settings store.
  Future<void> updateSettings(Map<String, dynamic> patch) async {
    await initialize();
    settings.value = Map<String, dynamic>.unmodifiable(
      <String, dynamic>{...settings.value, ...patch},
    );
    if (!_nativeAvailable) return;
    try {
      final dynamic raw = await _channel.invokeMethod<dynamic>(
        'updateSettings',
        <String, dynamic>{'settings': patch},
      );
      if (raw is Map) settings.value = _merge(raw);
    } on MissingPluginException {
      // Local optimistic value remains.
    } on PlatformException {
      // Local optimistic value remains.
    } catch (_) {
      // Local optimistic value remains.
    }
  }

  Future<Map<String, dynamic>> getSettings() async {
    await initialize();
    return Map<String, dynamic>.from(settings.value);
  }

  /// Kept for API compatibility. There is no longer a separate Flutter copy
  /// to push: re-reading the native store is all that is needed.
  Future<void> syncSettingsToNative() => refreshFromNative();

  /// Cancels the native callback handler (teardown / hot restart).
  Future<void> dispose() async {
    if (_nativeAvailable) {
      try {
        _channel.setMethodCallHandler(null);
      } catch (_) {
        // Ignore.
      }
    }
    _initialized = false;
  }
}
