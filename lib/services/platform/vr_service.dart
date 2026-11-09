import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show BuildContext, showDialog;
import 'package:flutter/services.dart';
import 'package:kazumi/pages/player/vr/vr_qr_dialog.dart';
import 'package:kazumi/services/storage/storage.dart';

/// Head-pose quaternion produced by Android's rotation-vector sensor.
class VrPose {
  const VrPose(this.x, this.y, this.z, this.w);

  final double x;
  final double y;
  final double z;
  final double w;

  static const VrPose identity = VrPose(0.0, 0.0, 0.0, 1.0);
}

/// Flutter-side coordinator for VR mode.
///
/// Rendering happens entirely in Flutter: both eyes sample the same media_kit
/// [VideoController], so no EGL context sharing is needed. The native side is a
/// thin bridge that only streams sensor poses and raw gamepad events.
///
/// The public method names are kept compatible with the settings page and the
/// player panel, which were written before the renderer was moved to Flutter.
class VRService {
  VRService._();
  static final VRService instance = VRService._();

  static const MethodChannel _channel = MethodChannel('com.predidit.kazumi/vr');
  static const EventChannel _poseChannel =
      EventChannel('com.predidit.kazumi/vr/pose');
  static const EventChannel _inputChannel =
      EventChannel('com.predidit.kazumi/vr/input');

  final ValueNotifier<bool> enabled = ValueNotifier<bool>(false);
  final ValueNotifier<VrPose> pose = ValueNotifier<VrPose>(VrPose.identity);
  final ValueNotifier<Map<String, dynamic>> settings =
      ValueNotifier<Map<String, dynamic>>(<String, dynamic>{});
  final ValueNotifier<double> videoAspect = ValueNotifier<double>(16.0 / 9.0);

  final StreamController<Map<String, dynamic>> _inputController =
      StreamController<Map<String, dynamic>>.broadcast();

  /// Raw gamepad events: {type, keyCode, action, axisX, axisY, hatX, hatY, ...}.
  Stream<Map<String, dynamic>> get inputStream => _inputController.stream;

  bool _initialized = false;
  StreamSubscription<dynamic>? _poseSub;
  StreamSubscription<dynamic>? _inputSub;
  VrPose _center = VrPose.identity;

  VrPose get center => _center;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    settings.value = _readSettings();
    enabled.value = GStorage.getSetting<bool>(SettingsKeys.vrEnabled);
    try {
      _poseSub = _poseChannel.receiveBroadcastStream().listen(
            _onPose,
            onError: (Object error) {},
          );
      _inputSub = _inputChannel.receiveBroadcastStream().listen(
            _onInput,
            onError: (Object error) {},
          );
    } catch (_) {
      // EventChannels are Android-only. On other platforms sensors are absent.
    }
  }

  /// Cancels the native event subscriptions (teardown / hot restart).
  /// Keeping these subscriptions in fields also prevents duplicate listeners
  /// when [initialize] is called again after a dispose.
  Future<void> dispose() async {
    final poseSub = _poseSub;
    final inputSub = _inputSub;
    _poseSub = null;
    _inputSub = null;
    await poseSub?.cancel();
    await inputSub?.cancel();
    _initialized = false;
  }

  void _onPose(dynamic event) {
    if (event is! List || event.length < 4) return;
    pose.value = VrPose(
      (event[0] as num).toDouble(),
      (event[1] as num).toDouble(),
      (event[2] as num).toDouble(),
      (event[3] as num).toDouble(),
    );
  }

  void _onInput(dynamic event) {
    if (event is Map) {
      _inputController.add(Map<String, dynamic>.from(event));
    }
  }

  Map<String, dynamic> _readSettings() {
    return <String, dynamic>{
      'screenScale': GStorage.getSetting<double>(SettingsKeys.vrScreenScale),
      'screenDistanceMeters':
          GStorage.getSetting<double>(SettingsKeys.vrScreenDistanceMeters),
      'screenVerticalOffset':
          GStorage.getSetting<double>(SettingsKeys.vrScreenVerticalOffset),
      'curvature': GStorage.getSetting<double>(SettingsKeys.vrCurvature),
      'viewerMode':
          GStorage.getSetting<int>(SettingsKeys.vrViewerMode) == 0
              ? 'CARDBOARD'
              : 'MONO',
      'headTrackingEnabled':
          GStorage.getSetting<bool>(SettingsKeys.vrHeadTrackingEnabled),
      'headTrackingSensitivity':
          GStorage.getSetting<double>(SettingsKeys.vrHeadTrackingSensitivity),
      'ipdScale': GStorage.getSetting<double>(SettingsKeys.vrIpdScale),
      'screenFillMode':
          GStorage.getSetting<int>(SettingsKeys.vrScreenFillMode) == 0
              ? 'DISTORTED'
              : 'FILL',
      'zoomScale': GStorage.getSetting<double>(SettingsKeys.vrZoomScale),
      'backgroundEnabled':
          GStorage.getSetting<bool>(SettingsKeys.vrBackgroundEnabled),
      'backgroundImageUri':
          GStorage.getSetting<String>(SettingsKeys.vrBackgroundImageUri),
      'backgroundBrightness':
          GStorage.getSetting<double>(SettingsKeys.vrBackgroundBrightness),
      'brightness': GStorage.getSetting<double>(SettingsKeys.vrBrightness),
      'horizontalMirror':
          GStorage.getSetting<bool>(SettingsKeys.vrHorizontalMirror),
      'verticalFlip': GStorage.getSetting<bool>(SettingsKeys.vrVerticalFlip),
      'showStatus': GStorage.getSetting<bool>(SettingsKeys.vrShowStatus),
      'showCenterAlignmentLine':
          GStorage.getSetting<bool>(SettingsKeys.vrShowCenterAlignmentLine),
      'showDebugOverlay':
          GStorage.getSetting<bool>(SettingsKeys.vrShowDebugOverlay),
      'gamepadDeadZone':
          GStorage.getSetting<double>(SettingsKeys.vrGamepadDeadZone),
      'menuRepeatMs': GStorage.getSetting<int>(SettingsKeys.vrMenuRepeatMs),
    };
  }

  Future<void> setVREnabled(bool value) async {
    await initialize();
    await GStorage.putSetting<bool>(SettingsKeys.vrEnabled, value);
    enabled.value = value;
    try {
      await _channel.invokeMethod<void>('setVrActive', <String, dynamic>{
        'active': value,
      });
    } catch (_) {
      // Missing plugin on non-Android platforms; VR rendering still works.
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

  /// Retained for compatibility. The overlay now reads the VideoController
  /// directly, so the aspect ratio is only kept as local state.
  Future<void> setVideoAspect(int width, int height) async {
    if (width <= 0 || height <= 0) return;
    final aspect = width / height;
    if (aspect.isNaN || aspect.isInfinite) return;
    videoAspect.value = aspect.clamp(0.25, 8.0).toDouble();
  }

  Future<void> recenter() async {
    _center = pose.value;
  }

  /// Import Cardboard viewer parameters. See [VrQrImportDialog].
  Future<void> scanQrCode(BuildContext context) async {
    final raw = await showDialog<String>(
      context: context,
      builder: (BuildContext ctx) => const VrQrImportDialog(),
    );
    if (raw == null || raw.isEmpty) return;
    final patch = VrQrParser.parse(raw);
    if (patch.isNotEmpty) await updateSettings(patch);
  }

  /// Apply a settings patch locally and mirror it into GStorage.
  /// No native call is needed: the Flutter overlay listens to [settings].
  Future<void> updateSettings(Map<String, dynamic> patch) async {
    await initialize();
    final next = Map<String, dynamic>.from(settings.value)..addAll(patch);
    settings.value = next;
    for (final entry in patch.entries) {
      try {
        await _persist(entry.key, entry.value);
      } catch (_) {
        // Unknown key (e.g. viewerName) is display-only and need not persist.
      }
    }
  }

  Future<Map<String, dynamic>> getSettings() async {
    await initialize();
    return Map<String, dynamic>.from(settings.value);
  }

  /// Name kept for compatibility; settings now live in Flutter only.
  Future<void> syncSettingsToNative() async {
    await initialize();
    settings.value = _readSettings();
  }

  Future<void> _persist(String key, dynamic v) async {
    switch (key) {
      case 'screenScale':
        await GStorage.putSetting<double>(
            SettingsKeys.vrScreenScale, _d(v));
        break;
      case 'screenDistanceMeters':
        await GStorage.putSetting<double>(
            SettingsKeys.vrScreenDistanceMeters, _d(v));
        break;
      case 'screenVerticalOffset':
        await GStorage.putSetting<double>(
            SettingsKeys.vrScreenVerticalOffset, _d(v));
        break;
      case 'curvature':
        await GStorage.putSetting<double>(SettingsKeys.vrCurvature, _d(v));
        break;
      case 'viewerMode':
        await GStorage.putSetting<int>(
            SettingsKeys.vrViewerMode, v == 'MONO' ? 1 : 0);
        break;
      case 'headTrackingEnabled':
        await GStorage.putSetting<bool>(
            SettingsKeys.vrHeadTrackingEnabled, v as bool);
        break;
      case 'headTrackingSensitivity':
        await GStorage.putSetting<double>(
            SettingsKeys.vrHeadTrackingSensitivity, _d(v));
        break;
      case 'ipdScale':
        await GStorage.putSetting<double>(SettingsKeys.vrIpdScale, _d(v));
        break;
      case 'screenFillMode':
        await GStorage.putSetting<int>(
            SettingsKeys.vrScreenFillMode, v == 'FILL' ? 1 : 0);
        break;
      case 'zoomScale':
        await GStorage.putSetting<double>(SettingsKeys.vrZoomScale, _d(v));
        break;
      case 'backgroundEnabled':
        await GStorage.putSetting<bool>(
            SettingsKeys.vrBackgroundEnabled, v as bool);
        break;
      case 'backgroundImageUri':
        await GStorage.putSetting<String>(
            SettingsKeys.vrBackgroundImageUri, v as String);
        break;
      case 'backgroundBrightness':
        await GStorage.putSetting<double>(
            SettingsKeys.vrBackgroundBrightness, _d(v));
        break;
      case 'brightness':
        await GStorage.putSetting<double>(SettingsKeys.vrBrightness, _d(v));
        break;
      case 'horizontalMirror':
        await GStorage.putSetting<bool>(
            SettingsKeys.vrHorizontalMirror, v as bool);
        break;
      case 'verticalFlip':
        await GStorage.putSetting<bool>(SettingsKeys.vrVerticalFlip, v as bool);
        break;
      case 'showStatus':
        await GStorage.putSetting<bool>(SettingsKeys.vrShowStatus, v as bool);
        break;
      case 'showCenterAlignmentLine':
        await GStorage.putSetting<bool>(
            SettingsKeys.vrShowCenterAlignmentLine, v as bool);
        break;
      case 'showDebugOverlay':
        await GStorage.putSetting<bool>(
            SettingsKeys.vrShowDebugOverlay, v as bool);
        break;
      case 'gamepadDeadZone':
        await GStorage.putSetting<double>(
            SettingsKeys.vrGamepadDeadZone, _d(v));
        break;
      case 'menuRepeatMs':
        await GStorage.putSetting<int>(
            SettingsKeys.vrMenuRepeatMs, (v as num).toInt());
        break;
    }
  }

  static double _d(dynamic v) => (v as num).toDouble();
}
