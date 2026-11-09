import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:kazumi/services/platform/vr_service.dart';

/// Semantic commands produced from the raw Android gamepad events forwarded
/// by VrNativeBridge. Keeping this translation in Dart means no native menu
/// logic is needed and VR can be switched on/off without touching the input
/// pipeline used by normal (non-VR) playback.
enum VrInputCommand {
  none,
  up,
  down,
  left,
  right,
  confirm,
  back,
  playPause,
  seekBack,
  seekForward,
  menu,
  recenter,
}

extension VrInputCommandLabel on VrInputCommand {
  String get label {
    switch (this) {
      case VrInputCommand.up:
        return '上';
      case VrInputCommand.down:
        return '下';
      case VrInputCommand.left:
        return '左';
      case VrInputCommand.right:
        return '右';
      case VrInputCommand.confirm:
        return '确认';
      case VrInputCommand.back:
        return '返回';
      case VrInputCommand.playPause:
        return '播放/暂停';
      case VrInputCommand.seekBack:
        return '快退';
      case VrInputCommand.seekForward:
        return '快进';
      case VrInputCommand.menu:
        return '菜单';
      case VrInputCommand.recenter:
        return '重置中心';
      case VrInputCommand.none:
        return '';
    }
  }
}

/// Android key codes (mirrored from android.view.KeyEvent). We cannot import
/// Android framework classes from Dart, so the numeric constants are declared
/// here and kept next to the mapping that uses them.
class _Key {
  static const int back = 4;
  static const int dpadUp = 19;
  static const int dpadDown = 20;
  static const int dpadLeft = 21;
  static const int dpadRight = 22;
  static const int dpadCenter = 23;
  static const int enter = 66;
  static const int space = 62;
  static const int mediaPlayPause = 85;
  static const int mediaRewind = 89;
  static const int mediaFastForward = 90;
  static const int buttonA = 96;
  static const int buttonB = 97;
  static const int buttonX = 99;
  static const int buttonY = 100;
  static const int buttonL1 = 102;
  static const int buttonR1 = 103;
  static const int buttonL2 = 104;
  static const int buttonR2 = 105;
  static const int buttonStart = 108;
  static const int buttonSelect = 109;
  static const int buttonMode = 110;
  static const int buttonThumbl = 106;
  static const int buttonThumbr = 107;
}

/// Translates raw input events into [VrInputCommand]s and implements
/// dead-zone filtering plus long-press repeat for D-pad / analog stick.
///
/// Attach it while the VR overlay is visible and detach in dispose(). When VR
/// is inactive the native bridge does not consume any key events, so a gamepad
/// keeps its normal behaviour for the rest of Kazumi.
class VrGamepad {
  VrGamepad({required this.onCommand});

  final ValueChanged<VrInputCommand> onCommand;

  double deadZone = 0.15;
  int repeatMs = 180;

  StreamSubscription<Map<String, dynamic>>? _subscription;
  Timer? _repeatTimer;
  VrInputCommand _heldDirection = VrInputCommand.none;

  void attach() {
    detach();
    _subscription = VRService.instance.inputStream.listen(_onEvent);
  }

  void detach() {
    _stopRepeat();
    _subscription?.cancel();
    _subscription = null;
  }

  void updateSettings({double? deadZone, int? repeatMs}) {
    if (deadZone != null) {
      this.deadZone = deadZone.clamp(0.0, 0.5).toDouble();
    }
    if (repeatMs != null) {
      this.repeatMs = repeatMs.clamp(50, 500).toInt();
    }
    if (_heldDirection != VrInputCommand.none) {
      // Restart the timer so a changed repeat interval takes effect at once.
      final VrInputCommand held = _heldDirection;
      _stopRepeat();
      _startRepeat(held);
    }
  }

  void _onEvent(Map<String, dynamic> event) {
    final String? type = event['type'] as String?;
    switch (type) {
      case 'back':
        _emit(VrInputCommand.back);
        break;
      case 'key':
        _onKey(event);
        break;
      case 'motion':
        _onMotion(event);
        break;
    }
  }

  void _onKey(Map<String, dynamic> event) {
    final int action = _int(event['action']);
    final int keyCode = _int(event['keyCode']);
    final int repeatCount = _int(event['repeatCount']);

    if (action == 1) {
      // ACTION_UP
      if (_isDirectionKey(keyCode)) _stopRepeat();
      return;
    }
    if (action != 0 || repeatCount > 0) return; // ACTION_DOWN only.

    switch (keyCode) {
      case _Key.dpadUp:
        _startRepeat(VrInputCommand.up);
        return;
      case _Key.dpadDown:
        _startRepeat(VrInputCommand.down);
        return;
      case _Key.dpadLeft:
        _startRepeat(VrInputCommand.left);
        return;
      case _Key.dpadRight:
        _startRepeat(VrInputCommand.right);
        return;
      case _Key.buttonA:
      case _Key.dpadCenter:
      case _Key.enter:
      case _Key.space:
        _emit(VrInputCommand.confirm);
        return;
      case _Key.buttonB:
      case _Key.back:
        _emit(VrInputCommand.back);
        return;
      case _Key.buttonX:
      case _Key.buttonL1:
      case _Key.buttonL2:
      case _Key.mediaRewind:
        _emit(VrInputCommand.seekBack);
        return;
      case _Key.buttonY:
      case _Key.buttonR1:
      case _Key.buttonR2:
      case _Key.mediaFastForward:
        _emit(VrInputCommand.seekForward);
        return;
      case _Key.mediaPlayPause:
        _emit(VrInputCommand.playPause);
        return;
      case _Key.buttonStart:
      case _Key.buttonSelect:
      case _Key.buttonMode:
        _emit(VrInputCommand.menu);
        return;
      case _Key.buttonThumbl:
        _emit(VrInputCommand.recenter);
        return;
      case _Key.buttonThumbr:
        _emit(VrInputCommand.playPause);
        return;
    }
  }

  void _onMotion(Map<String, dynamic> event) {
    final double hatX = _double(event['hatX']);
    final double hatY = _double(event['hatY']);
    final double axisX = _double(event['axisX']);
    final double axisY = _double(event['axisY']);

    VrInputCommand direction = VrInputCommand.none;
    if (hatX <= -0.5) {
      direction = VrInputCommand.left;
    } else if (hatX >= 0.5) {
      direction = VrInputCommand.right;
    } else if (hatY <= -0.5) {
      direction = VrInputCommand.up;
    } else if (hatY >= 0.5) {
      direction = VrInputCommand.down;
    } else if (axisX.abs() > deadZone || axisY.abs() > deadZone) {
      if (axisX.abs() > axisY.abs()) {
        direction = axisX < 0 ? VrInputCommand.left : VrInputCommand.right;
      } else {
        direction = axisY < 0 ? VrInputCommand.up : VrInputCommand.down;
      }
    }

    if (direction == VrInputCommand.none) {
      _stopRepeat();
    } else {
      _startRepeat(direction);
    }
  }

  void _startRepeat(VrInputCommand direction) {
    if (_heldDirection == direction && (_repeatTimer?.isActive ?? false)) {
      return;
    }
    _stopRepeat();
    _heldDirection = direction;
    _emit(direction);
    _repeatTimer = Timer.periodic(
      Duration(milliseconds: repeatMs.clamp(50, 500).toInt()),
      (Timer _) {
        if (_heldDirection != VrInputCommand.none) {
          _emit(_heldDirection);
        }
      },
    );
  }

  void _stopRepeat() {
    _repeatTimer?.cancel();
    _repeatTimer = null;
    _heldDirection = VrInputCommand.none;
  }

  void _emit(VrInputCommand command) {
    if (command != VrInputCommand.none) onCommand(command);
  }

  static bool _isDirectionKey(int keyCode) {
    return keyCode == _Key.dpadUp ||
        keyCode == _Key.dpadDown ||
        keyCode == _Key.dpadLeft ||
        keyCode == _Key.dpadRight;
  }

  static int _int(dynamic value) => (value as num?)?.toInt() ?? 0;

  static double _double(dynamic value) =>
      (value as num?)?.toDouble() ?? 0.0;
}
