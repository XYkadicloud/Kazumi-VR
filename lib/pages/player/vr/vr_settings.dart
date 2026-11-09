import 'package:kazumi/services/platform/vr_service.dart';

/// Typed, read-only view over the settings map mirrored from the native
/// `VrSettingsStore`. Every getter falls back to [VRService.defaults], so a
/// missing key can never throw while the native channel is still connecting.
class VrSettingsView {
  const VrSettingsView(this._m);

  final Map<String, dynamic> _m;

  double _d(String k) =>
      (_m[k] as num?)?.toDouble() ??
      (VRService.defaults[k]! as num).toDouble();

  bool _b(String k) =>
      (_m[k] as bool?) ?? (VRService.defaults[k]! as bool);

  String _s(String k) =>
      (_m[k] as String?) ?? (VRService.defaults[k]! as String);

  int _i(String k) =>
      (_m[k] as num?)?.toInt() ?? (VRService.defaults[k]! as num).toInt();

  /// Generic accessors used by the settings page's tile builders.
  double number(String k) => _d(k);
  bool flag(String k) => _b(k);
  String text(String k) => _s(k);
  int integer(String k) => _i(k);

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
  double get gamepadDeadZone => _d('gamepadDeadZone');
  int get menuRepeatMs => _i('menuRepeatMs');

  bool get passthroughEnabled => _b('passthroughEnabled');
  String get passthroughCameraFacing => _s('passthroughCameraFacing');
  double get passthroughOpacity => _d('passthroughOpacity');
  double get passthroughWindowScale => _d('passthroughWindowScale');
  String get passthroughAspect => _s('passthroughAspect');
  String get passthroughMode => _s('passthroughMode');
  String get passthroughResolution => _s('passthroughResolution');
  String get passthroughFrameRate => _s('passthroughFrameRate');
  String get passthroughRotation => _s('passthroughRotation');
  bool get passthroughMirror => _b('passthroughMirror');
  bool get passthroughHeadLocked => _b('passthroughHeadLocked');

  bool get stereo => viewerMode != 'SIMULATION' && viewerMode != 'MONO';
}
