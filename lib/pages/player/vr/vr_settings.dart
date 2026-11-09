import 'dart:math' as math;

import 'package:kazumi/services/platform/vr_service.dart';

/// Typed, read-only view over the VR settings map held by [VRService].
/// Every getter has a safe default so a missing key can never throw.
class VrSettingsView {
  const VrSettingsView(this._m);

  final Map<String, dynamic> _m;

  static const Map<String, dynamic> defaults = <String, dynamic>{
    'screenScale': 1.0,
    'screenDistanceMeters': 2.0,
    'screenVerticalOffset': 0.0,
    'curvature': 0.0,
    'viewerMode': 'CARDBOARD',
    'headTrackingEnabled': true,
    'headTrackingSensitivity': 1.0,
    'ipdScale': 1.0,
    'screenFillMode': 'DISTORTED',
    'zoomScale': 1.2,
    'backgroundEnabled': false,
    'backgroundImageUri': '',
    'backgroundBrightness': 0.5,
    'brightness': 1.0,
    'horizontalMirror': false,
    'verticalFlip': false,
    'showStatus': true,
    'showCenterAlignmentLine': true,
    'showDebugOverlay': false,
    'gamepadDeadZone': 0.15,
    'menuRepeatMs': 180,
  };

  double _d(String k) =>
      (_m[k] as num?)?.toDouble() ?? (defaults[k]! as num).toDouble();

  bool _b(String k) => (_m[k] as bool?) ?? (defaults[k]! as bool);

  String _s(String k) => (_m[k] as String?) ?? (defaults[k]! as String);

  int _i(String k) => (_m[k] as num?)?.toInt() ?? (defaults[k]! as int);

  double get screenScale => _d('screenScale');
  double get screenDistanceMeters => _d('screenDistanceMeters');
  double get screenVerticalOffset => _d('screenVerticalOffset');
  double get curvature => _d('curvature');
  String get viewerMode => _s('viewerMode');
  bool get headTrackingEnabled => _b('headTrackingEnabled');
  double get headTrackingSensitivity => _d('headTrackingSensitivity');
  double get ipdScale => _d('ipdScale');
  String get screenFillMode => _s('screenFillMode');
  double get zoomScale => _d('zoomScale');
  bool get backgroundEnabled => _b('backgroundEnabled');
  String get backgroundImageUri => _s('backgroundImageUri');
  double get backgroundBrightness => _d('backgroundBrightness');
  double get brightness => _d('brightness');
  bool get horizontalMirror => _b('horizontalMirror');
  bool get verticalFlip => _b('verticalFlip');
  bool get showStatus => _b('showStatus');
  bool get showCenterAlignmentLine => _b('showCenterAlignmentLine');
  bool get showDebugOverlay => _b('showDebugOverlay');
  double get gamepadDeadZone => _d('gamepadDeadZone');
  int get menuRepeatMs => _i('menuRepeatMs');

  bool get stereo => viewerMode != 'MONO';
}

/// Yaw / pitch of a quaternion, in radians.
class VrHeadAngles {
  const VrHeadAngles(this.yaw, this.pitch);

  final double yaw;
  final double pitch;
}

/// Relative head rotation with respect to the last recenter, scaled by the
/// user's sensitivity setting. This drives the parallax of the theatre screen.
VrHeadAngles vrRelativeAngles(
  VrPose current,
  VrPose center,
  double sensitivity,
) {
  double yawOf(VrPose q) => math.atan2(
        2.0 * (q.w * q.z + q.x * q.y),
        1.0 - 2.0 * (q.y * q.y + q.z * q.z),
      );

  double pitchOf(VrPose q) {
    final s = 2.0 * (q.w * q.y - q.z * q.x);
    return math.asin(s.clamp(-1.0, 1.0).toDouble());
  }

  return VrHeadAngles(
    (yawOf(current) - yawOf(center)) * sensitivity,
    (pitchOf(current) - pitchOf(center)) * sensitivity,
  );
}
