import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_mobx/flutter_mobx.dart';
import 'package:kazumi/pages/player/player_controller.dart';
import 'package:kazumi/pages/player/vr/vr_gamepad.dart';
import 'package:kazumi/pages/player/vr/vr_settings.dart';
import 'package:kazumi/services/platform/vr_service.dart';
import 'package:media_kit_video/media_kit_video.dart';

/// Full-screen VR theatre rendered entirely with Flutter / Material widgets.
///
/// The video is NOT re-opened and NOT redirected to another surface: both eyes
/// draw the very same media_kit texture via [Texture] on
/// [VideoController.id]. Playback, subtitles and audio therefore keep working
/// exactly as in normal (non-VR) viewing, and switching VR off simply removes
/// this overlay.
///
/// Cardboard barrel distortion requires a GLSL/fragment shader and lens
/// coefficients; with plain Flutter widgets this overlay provides a faithful
/// stereo layout (two eye viewports, IPD offset, head-tracking parallax,
/// screen scale/distance, mirror/flip) rather than claiming true optical
/// distortion correction. The "原版畸变 / 单纯放大" setting selects the
/// corresponding scale profile.
class VrOverlay extends StatefulWidget {
  const VrOverlay({super.key, required this.playerController});

  final PlayerController playerController;

  @override
  State<VrOverlay> createState() => _VrOverlayState();
}

enum _ItemKind { range, toggle, action }

class _MenuItem {
  const _MenuItem({
    required this.label,
    required this.icon,
    required this.kind,
    this.key,
    this.min = 0.0,
    this.max = 1.0,
    this.step = 0.1,
    this.action,
  });

  final String label;
  final IconData icon;
  final _ItemKind kind;
  final String? key;
  final double min;
  final double max;
  final double step;
  final String? action;
}

/// Settings that can be changed while wearing the headset. Every row is a
/// large focusable button; the gamepad moves the focus and A/X/Y adjust it.
const List<_MenuItem> _menuItems = <_MenuItem>[
  _MenuItem(
    label: '屏幕大小',
    icon: Icons.zoom_out_map_rounded,
    kind: _ItemKind.range,
    key: 'screenScale',
    min: 0.4,
    max: 3.0,
    step: 0.1,
  ),
  _MenuItem(
    label: '观看距离',
    icon: Icons.straighten_rounded,
    kind: _ItemKind.range,
    key: 'screenDistanceMeters',
    min: 0.5,
    max: 5.0,
    step: 0.2,
  ),
  _MenuItem(
    label: '画面亮度',
    icon: Icons.brightness_6_rounded,
    kind: _ItemKind.range,
    key: 'brightness',
    min: 0.25,
    max: 2.0,
    step: 0.1,
  ),
  _MenuItem(
    label: '瞳距缩放',
    icon: Icons.remove_red_eye_rounded,
    kind: _ItemKind.range,
    key: 'ipdScale',
    min: 0.5,
    max: 1.5,
    step: 0.05,
  ),
  _MenuItem(
    label: '追踪灵敏度',
    icon: Icons.speed_rounded,
    kind: _ItemKind.range,
    key: 'headTrackingSensitivity',
    min: 0.25,
    max: 3.0,
    step: 0.1,
  ),
  _MenuItem(
    label: '立体模式',
    icon: Icons.view_in_ar_rounded,
    kind: _ItemKind.toggle,
    key: 'viewerMode',
  ),
  _MenuItem(
    label: '头部追踪',
    icon: Icons.screen_rotation_alt_rounded,
    kind: _ItemKind.toggle,
    key: 'headTrackingEnabled',
  ),
  _MenuItem(
    label: '显示状态栏',
    icon: Icons.info_outline_rounded,
    kind: _ItemKind.toggle,
    key: 'showStatus',
  ),
  _MenuItem(
    label: '中心辅助线',
    icon: Icons.center_focus_strong_rounded,
    kind: _ItemKind.toggle,
    key: 'showCenterAlignmentLine',
  ),
  _MenuItem(
    label: '重置中心点',
    icon: Icons.refresh_rounded,
    kind: _ItemKind.action,
    action: 'recenter',
  ),
  _MenuItem(
    label: '退出 VR',
    icon: Icons.fullscreen_exit_rounded,
    kind: _ItemKind.action,
    action: 'exit',
  ),
];

class _VrOverlayState extends State<VrOverlay> {
  static const int _barCount = 6;
  static const double _barHeight = 88.0;
  static const double _orbSize = 88.0;

  late final VrGamepad _gamepad;
  bool _menuOpen = false;
  int _barFocus = 0;
  int _menuFocus = 0;
  String? _toast;
  Timer? _toastTimer;

  @override
  void initState() {
    super.initState();
    unawaited(VRService.instance.initialize());
    _gamepad = VrGamepad(onCommand: _handleCommand);
    _gamepad.attach();
    final VrSettingsView s =
        VrSettingsView(VRService.instance.settings.value);
    _gamepad.updateSettings(
      deadZone: s.gamepadDeadZone,
      repeatMs: s.menuRepeatMs,
    );
  }

  @override
  void dispose() {
    _gamepad.detach();
    _toastTimer?.cancel();
    super.dispose();
  }

  // ---------------------------------------------------------------- input ---

  void _handleCommand(VrInputCommand command) {
    if (!mounted) return;
    if (_menuOpen) {
      _handleMenuCommand(command);
    } else {
      _handleBarCommand(command);
    }
  }

  void _handleBarCommand(VrInputCommand command) {
    switch (command) {
      case VrInputCommand.left:
      case VrInputCommand.up:
        setState(() => _barFocus = (_barFocus - 1 + _barCount) % _barCount);
        break;
      case VrInputCommand.right:
      case VrInputCommand.down:
        setState(() => _barFocus = (_barFocus + 1) % _barCount);
        break;
      case VrInputCommand.confirm:
        _runBarAction(_barFocus);
        break;
      case VrInputCommand.playPause:
        _runBarAction(0);
        break;
      case VrInputCommand.seekBack:
        _runBarAction(1);
        break;
      case VrInputCommand.seekForward:
        _runBarAction(2);
        break;
      case VrInputCommand.menu:
        _openMenu();
        break;
      case VrInputCommand.recenter:
        _runBarAction(4);
        break;
      case VrInputCommand.back:
        _runBarAction(5);
        break;
      case VrInputCommand.none:
        break;
    }
  }

  void _handleMenuCommand(VrInputCommand command) {
    final int n = _menuItems.length;
    switch (command) {
      case VrInputCommand.up:
        setState(() => _menuFocus = (_menuFocus - 2 + n) % n);
        break;
      case VrInputCommand.down:
        setState(() => _menuFocus = (_menuFocus + 2) % n);
        break;
      case VrInputCommand.left:
        setState(() => _menuFocus = (_menuFocus - 1 + n) % n);
        break;
      case VrInputCommand.right:
        setState(() => _menuFocus = (_menuFocus + 1) % n);
        break;
      case VrInputCommand.confirm:
        _applyMenuDelta(_menuFocus, 1);
        break;
      case VrInputCommand.seekBack:
        _applyMenuDelta(_menuFocus, -1);
        break;
      case VrInputCommand.seekForward:
        _applyMenuDelta(_menuFocus, 1);
        break;
      case VrInputCommand.back:
      case VrInputCommand.menu:
        setState(() => _menuOpen = false);
        break;
      case VrInputCommand.recenter:
        unawaited(VRService.instance.recenter());
        _showToast('已重置中心点');
        break;
      case VrInputCommand.playPause:
      case VrInputCommand.none:
        break;
    }
  }

  void _openMenu() {
    setState(() {
      _menuOpen = true;
      _menuFocus = 0;
    });
  }

  void _runBarAction(int index) {
    switch (index) {
      case 0:
        unawaited(widget.playerController.playOrPause());
        _showToast('播放 / 暂停');
        break;
      case 1:
        unawaited(widget.playerController.seekBy(const Duration(seconds: -10)));
        _showToast('快退 10 秒');
        break;
      case 2:
        unawaited(widget.playerController.seekBy(const Duration(seconds: 10)));
        _showToast('快进 10 秒');
        break;
      case 3:
        _openMenu();
        break;
      case 4:
        unawaited(VRService.instance.recenter());
        _showToast('已重置中心点');
        break;
      case 5:
        _exitVr();
        break;
    }
  }

  void _exitVr() {
    unawaited(VRService.instance.setVREnabled(false));
  }

  void _applyMenuDelta(int index, int delta) {
    final _MenuItem item = _menuItems[index];
    final Map<String, dynamic> current = VRService.instance.settings.value;

    if (item.kind == _ItemKind.range) {
      final String key = item.key ?? '';
      if (key.isEmpty) return;
      final double fallback =
          (VrSettingsView.defaults[key]! as num).toDouble();
      final double now = (current[key] as num?)?.toDouble() ?? fallback;
      final double next =
          (now + item.step * delta).clamp(item.min, item.max).toDouble();
      unawaited(VRService.instance.updateSettings(<String, dynamic>{key: next}));
      _showToast('${item.label} ${next.toStringAsFixed(2)}');
      return;
    }

    if (item.kind == _ItemKind.toggle) {
      final String key = item.key ?? '';
      if (key.isEmpty) return;
      if (key == 'viewerMode') {
        final String mode = current[key] as String? ?? 'CARDBOARD';
        unawaited(VRService.instance.updateSettings(<String, dynamic>{
          key: mode == 'MONO' ? 'CARDBOARD' : 'MONO',
        }));
      } else {
        final bool fallback = VrSettingsView.defaults[key]! as bool;
        final bool value = (current[key] as bool?) ?? fallback;
        unawaited(
          VRService.instance.updateSettings(<String, dynamic>{key: !value}),
        );
      }
      _showToast('${item.label} 已切换');
      return;
    }

    if (item.action == 'recenter') {
      unawaited(VRService.instance.recenter());
      _showToast('已重置中心点');
    } else if (item.action == 'exit') {
      _exitVr();
    }
  }

  void _showToast(String message) {
    if (!mounted) return;
    setState(() => _toast = message);
    _toastTimer?.cancel();
    _toastTimer = Timer(const Duration(milliseconds: 1400), () {
      if (mounted) setState(() => _toast = null);
    });
  }

  String _valueText(_MenuItem item, Map<String, dynamic> m) {
    final String key = item.key ?? '';
    if (item.kind == _ItemKind.range) {
      final double fallback =
          (VrSettingsView.defaults[key]! as num).toDouble();
      final double v = (m[key] as num?)?.toDouble() ?? fallback;
      return v.toStringAsFixed(2);
    }
    if (item.kind == _ItemKind.toggle) {
      if (key == 'viewerMode') {
        return (m[key] as String?) == 'MONO' ? '单眼平铺' : 'Cardboard 立体';
      }
      final bool fallback = VrSettingsView.defaults[key]! as bool;
      final bool v = (m[key] as bool?) ?? fallback;
      return v ? '开' : '关';
    }
    return '';
  }

  // ---------------------------------------------------------------- build ---

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {},
      child: ValueListenableBuilder<Map<String, dynamic>>(
        valueListenable: VRService.instance.settings,
        builder: (BuildContext context, Map<String, dynamic> settingsMap,
            Widget? _) {
          final VrSettingsView s = VrSettingsView(settingsMap);
          _gamepad.updateSettings(
            deadZone: s.gamepadDeadZone,
            repeatMs: s.menuRepeatMs,
          );
          return _buildShell(context, s, settingsMap);
        },
      ),
    );
  }

  Widget _buildShell(
    BuildContext context,
    VrSettingsView s,
    Map<String, dynamic> m,
  ) {
    final Size size = MediaQuery.sizeOf(context);
    final bool portrait = size.height > size.width;

    return Container(
      width: double.infinity,
      height: double.infinity,
      color: Colors.black,
      child: Observer(builder: (BuildContext ctx) {
        final bool loading = widget.playerController.playback.loading;
        final VideoController? vc =
            widget.playerController.playback.videoController;
        final bool videoReady = !loading && vc != null;

        return Stack(
          children: <Widget>[
            Positioned.fill(child: _buildBackground(s)),
            if (videoReady)
              Positioned.fill(
                child: ValueListenableBuilder<VrPose>(
                  valueListenable: VRService.instance.pose,
                  builder: (BuildContext c, VrPose pose, Widget? __) =>
                      _buildPicture(c, s, vc, pose),
                ),
              )
            else
              const Positioned.fill(
                child: Center(
                  child: Text(
                    '等待视频加载…',
                    style: TextStyle(color: Colors.white70, fontSize: 26),
                  ),
                ),
              ),
            if (s.showCenterAlignmentLine)
              Positioned.fill(
                child: IgnorePointer(
                  child: Center(
                    child: SizedBox(
                      width: 48,
                      height: 48,
                      child: Stack(
                        children: <Widget>[
                          Center(
                            child: Container(
                              width: 48,
                              height: 2,
                              color: Color.fromRGBO(255, 255, 255, 0.30),
                            ),
                          ),
                          Center(
                            child: Container(
                              width: 2,
                              height: 48,
                              color: Color.fromRGBO(255, 255, 255, 0.30),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            if (s.showStatus)
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: _buildTopStatus(context, s, portrait, m),
              ),
            if (s.showDebugOverlay)
              Positioned(
                right: 14,
                top: 160,
                child: IgnorePointer(
                  child: Container(
                    padding: const EdgeInsets.all(10),
                    color: Color.fromRGBO(0, 0, 0, 0.65),
                    child: Text(
                      'scale=${s.screenScale.toStringAsFixed(2)}  '
                      'ipd=${s.ipdScale.toStringAsFixed(2)}  '
                      'dz=${s.gamepadDeadZone.toStringAsFixed(2)}',
                      style: const TextStyle(
                        color: Color.fromRGBO(120, 255, 160, 1.0),
                        fontSize: 16,
                      ),
                    ),
                  ),
                ),
              ),
            if (_toast != null)
              Positioned(
                top: portrait ? 200 : 140,
                left: 0,
                right: 0,
                child: IgnorePointer(
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 26,
                        vertical: 12,
                      ),
                      decoration: BoxDecoration(
                        color: Color.fromRGBO(0, 0, 0, 0.82),
                        borderRadius: BorderRadius.circular(30),
                      ),
                      child: Text(
                        _toast!,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 26,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            if (!_menuOpen)
              Positioned(
                right: 22,
                bottom: _barHeight + 36,
                child: _buildOrb(),
              ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _buildBottomBar(context),
            ),
            if (_menuOpen)
              Positioned.fill(child: _buildMenu(context, s, m)),
          ],
        );
      }),
    );
  }

  Widget _buildBackground(VrSettingsView s) {
    if (!s.backgroundEnabled) {
      return const ColoredBox(color: Colors.black);
    }
    final double b = s.backgroundBrightness.clamp(0.0, 1.0).toDouble();
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: <Color>[
            Color.fromRGBO((36 * b).round(), (40 * b).round(),
                (62 * b).round(), 1.0),
            Color.fromRGBO((10 * b).round(), (12 * b).round(),
                (20 * b).round(), 1.0),
          ],
        ),
      ),
    );
  }

  Widget _buildPicture(
    BuildContext context,
    VrSettingsView s,
    VideoController vc,
    VrPose pose,
  ) {
    if (!s.stereo) {
      return _eyeView(vc, s, pose, true);
    }
    return Row(
      children: <Widget>[
        Expanded(child: _eyeView(vc, s, pose, true)),
        Container(width: 3.0, color: Color.fromRGBO(42, 42, 46, 1.0)),
        Expanded(child: _eyeView(vc, s, pose, false)),
      ],
    );
  }

  Widget _eyeView(
    VideoController vc,
    VrSettingsView s,
    VrPose pose,
    bool leftEye,
  ) {
    final VrHeadAngles angles = s.headTrackingEnabled
        ? vrRelativeAngles(
            pose,
            VRService.instance.center,
            s.headTrackingSensitivity,
          )
        : const VrHeadAngles(0.0, 0.0);
    final double parallaxX =
        (-angles.yaw * 140.0).clamp(-160.0, 160.0).toDouble();
    final double parallaxY =
        (angles.pitch * 110.0).clamp(-120.0, 120.0).toDouble();
    final double distanceScale =
        (2.0 / s.screenDistanceMeters).clamp(0.55, 1.8).toDouble();
    final double fillScale =
        s.screenFillMode == 'FILL' ? s.zoomScale : 1.0;
    final double scale =
        (s.screenScale * distanceScale * fillScale).clamp(0.3, 3.0).toDouble();
    final double ipdShift = (leftEye ? -1.0 : 1.0) * s.ipdScale * 10.0;
    final double offsetX = parallaxX + ipdShift;
    final double offsetY = parallaxY + s.screenVerticalOffset * 40.0;

    return ClipRect(
      child: ColoredBox(
        color: Colors.black,
        child: ValueListenableBuilder<int?>(
          valueListenable: vc.id,
          builder: (BuildContext context, int? id, Widget? _) {
            if (id == null) {
              return const Center(
                child: Text(
                  '视频纹理准备中…',
                  style: TextStyle(color: Colors.white54, fontSize: 20),
                ),
              );
            }
            return ValueListenableBuilder<Rect?>(
              valueListenable: vc.rect,
              builder: (BuildContext context, Rect? rect, Widget? __) {
                final double aspect = (rect != null && rect.height > 1.0)
                    ? (rect.width / rect.height).clamp(0.25, 8.0).toDouble()
                    : 16.0 / 9.0;
                Widget picture = AspectRatio(
                  aspectRatio: aspect,
                  child: Texture(textureId: id),
                );
                if (s.brightness < 1.0) {
                  picture = Stack(
                    children: <Widget>[
                      picture,
                      Positioned.fill(
                        child: ColoredBox(
                          color: Color.fromRGBO(
                            0,
                            0,
                            0,
                            (1.0 - s.brightness).clamp(0.0, 0.75).toDouble(),
                          ),
                        ),
                      ),
                    ],
                  );
                } else if (s.brightness > 1.0) {
                  picture = Stack(
                    children: <Widget>[
                      picture,
                      Positioned.fill(
                        child: ColoredBox(
                          color: Color.fromRGBO(
                            255,
                            255,
                            255,
                            ((s.brightness - 1.0) * 0.35)
                                .clamp(0.0, 0.35)
                                .toDouble(),
                          ),
                        ),
                      ),
                    ],
                  );
                }
                return Transform.translate(
                  offset: Offset(offsetX, offsetY),
                  child: Transform.scale(
                    scale: scale,
                    child: Transform(
                      alignment: Alignment.center,
                      transform: Matrix4.diagonal3Values(
                        s.horizontalMirror ? -1.0 : 1.0,
                        s.verticalFlip ? -1.0 : 1.0,
                        1.0,
                      ),
                      child: FittedBox(
                        fit: BoxFit.contain,
                        child: SizedBox(
                          width: 320.0,
                          height: 320.0 / aspect,
                          child: picture,
                        ),
                      ),
                    ),
                  ),
                );
              },
            );
          },
        ),
      ),
    );
  }

  Widget _buildTopStatus(
    BuildContext context,
    VrSettingsView s,
    bool portrait,
    Map<String, dynamic> m,
  ) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
      color: Color.fromRGBO(0, 0, 0, 0.58),
      child: Observer(builder: (BuildContext ctx) {
        final playback = widget.playerController.playback;
        final String pos = _formatDuration(playback.currentPosition);
        final String dur = _formatDuration(playback.duration);
        final String viewer =
            (m['viewerName'] as String?)?.isNotEmpty == true
                ? m['viewerName'] as String
                : (s.stereo ? 'Cardboard 立体' : '单眼平铺');
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                const Icon(Icons.view_in_ar_rounded,
                    color: Colors.white, size: 28),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'VR 影院 · $viewer',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 23,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                Text(
                  playback.playing ? '播放中' : '已暂停',
                  style: const TextStyle(color: Colors.white, fontSize: 20),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              '$pos / $dur',
              style: const TextStyle(color: Colors.white70, fontSize: 20),
            ),
            const SizedBox(height: 4),
            const Text(
              '方向键选择 · A 确认 · B 返回 · START 菜单 · X 减 / Y 加',
              style: TextStyle(color: Colors.white54, fontSize: 16),
            ),
            if (portrait)
              const Padding(
                padding: EdgeInsets.only(top: 6),
                child: Text(
                  '建议横屏使用 Cardboard',
                  style: TextStyle(
                    color: Color.fromRGBO(255, 190, 90, 1.0),
                    fontSize: 18,
                  ),
                ),
              ),
          ],
        );
      }),
    );
  }

  Widget _buildBottomBar(BuildContext context) {
    return Observer(builder: (BuildContext ctx) {
      final bool playing = widget.playerController.playback.playing;
      final List<IconData> icons = <IconData>[
        playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
        Icons.replay_10_rounded,
        Icons.forward_10_rounded,
        Icons.settings_rounded,
        Icons.center_focus_strong_rounded,
        Icons.fullscreen_exit_rounded,
      ];
      const List<String> labels = <String>[
        '播放/暂停',
        '快退 10s',
        '快进 10s',
        'VR 设置',
        '重置中心',
        '退出 VR',
      ];
      return Container(
        width: double.infinity,
        color: Color.fromRGBO(0, 0, 0, 0.74),
        padding: const EdgeInsets.fromLTRB(10, 8, 10, 12),
        child: SafeArea(
          top: false,
          child: Row(
            children: List<Widget>.generate(_barCount, (int i) {
              return Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: _buildBarButton(
                    icons[i],
                    labels[i],
                    i,
                    i == _barFocus,
                  ),
                ),
              );
            }),
          ),
        ),
      );
    });
  }

  Widget _buildBarButton(
    IconData icon,
    String label,
    int index,
    bool focused,
  ) {
    final ColorScheme cs = Theme.of(context).colorScheme;
    final Color foreground = focused ? cs.onPrimaryContainer : Colors.white;
    return Semantics(
      button: true,
      label: label,
      child: Material(
        color: focused
            ? cs.primaryContainer
            : Color.fromRGBO(30, 30, 36, 0.94),
        borderRadius: BorderRadius.circular(18),
        child: InkWell(
          borderRadius: BorderRadius.circular(18),
          onTap: () => _runBarAction(index),
          child: Container(
            width: double.infinity,
            height: _barHeight,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(18),
              border: Border.all(
                color: focused ? cs.primary : Colors.white24,
                width: focused ? 4.0 : 2.0,
              ),
            ),
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Icon(icon, size: 36, color: foreground),
                const SizedBox(height: 2),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    label,
                    style: TextStyle(fontSize: 16, color: foreground),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildOrb() {
    return Semantics(
      button: true,
      label: '打开 VR 设置菜单',
      child: Material(
        color: Color.fromRGBO(32, 34, 46, 0.58),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: _openMenu,
          child: SizedBox(
            width: _orbSize,
            height: _orbSize,
            child: const Center(
              child: Icon(
                Icons.tune_rounded,
                size: 42,
                color: Colors.white70,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMenu(
    BuildContext context,
    VrSettingsView s,
    Map<String, dynamic> m,
  ) {
    final Size size = MediaQuery.sizeOf(context);
    final double panelW =
        (size.width * 0.92).clamp(320.0, 940.0).toDouble();
    final double panelH =
        (size.height * 0.84).clamp(280.0, 620.0).toDouble();
    final double cellW = (panelW - 32 - 12) / 2;
    return GestureDetector(
      onTap: () => setState(() => _menuOpen = false),
      child: Container(
        color: Color.fromRGBO(0, 0, 0, 0.84),
        alignment: Alignment.center,
        child: GestureDetector(
          onTap: () {},
          child: Material(
            color: Color.fromRGBO(24, 24, 30, 0.98),
            borderRadius: BorderRadius.circular(24),
            child: SizedBox(
              width: panelW,
              height: panelH,
              child: Column(
                children: <Widget>[
                  _buildMenuHeader(),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        children: List<Widget>.generate(
                          _menuItems.length,
                          (int i) => SizedBox(
                            width: cellW,
                            child: _buildMenuCell(_menuItems[i], i, m),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildMenuHeader() {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 14, 12, 6),
      child: Row(
        children: <Widget>[
          const Icon(Icons.tune_rounded, color: Colors.white, size: 30),
          const SizedBox(width: 12),
          const Expanded(
            child: Text(
              'VR 设置（观看中可调整）',
              style: TextStyle(
                color: Colors.white,
                fontSize: 24,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          IconButton(
            onPressed: () => setState(() => _menuOpen = false),
            icon: const Icon(Icons.close_rounded,
                color: Colors.white, size: 32),
            iconSize: 32,
            tooltip: '关闭',
            constraints: const BoxConstraints(minWidth: 60, minHeight: 60),
          ),
        ],
      ),
    );
  }

  Widget _buildMenuCell(_MenuItem item, int index, Map<String, dynamic> m) {
    final bool focused = index == _menuFocus;
    final ColorScheme cs = Theme.of(context).colorScheme;
    final Color foreground = focused ? cs.onPrimaryContainer : Colors.white;
    final String value = _valueText(item, m);
    return Material(
      color: focused ? cs.primaryContainer : Color.fromRGBO(40, 40, 48, 1.0),
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () => _applyMenuDelta(index, 1),
        child: Container(
          height: 104,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            border: Border.all(
              color: focused ? cs.primary : Colors.white24,
              width: focused ? 4.0 : 2.0,
            ),
          ),
          child: Row(
            children: <Widget>[
              Icon(item.icon, size: 34, color: foreground),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: Text(
                        item.label,
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                          color: foreground,
                        ),
                      ),
                    ),
                    if (value.isNotEmpty)
                      Text(
                        value,
                        style: TextStyle(
                          fontSize: 18,
                          color: focused
                              ? cs.onPrimaryContainer
                              : Colors.white70,
                        ),
                      ),
                  ],
                ),
              ),
              IconButton(
                onPressed: () => _applyMenuDelta(index, -1),
                icon: const Icon(Icons.remove_rounded, size: 28),
                iconSize: 28,
                color: foreground,
                tooltip: '减小',
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(
                  minWidth: 52,
                  minHeight: 52,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _formatDuration(Duration d) {
    final int total = d.inSeconds < 0 ? 0 : d.inSeconds;
    final int h = total ~/ 3600;
    final int minutes = (total % 3600) ~/ 60;
    final int sec = total % 60;
    final String mm = minutes.toString().padLeft(2, '0');
    final String ss = sec.toString().padLeft(2, '0');
    return h > 0 ? '$h:$mm:$ss' : '$mm:$ss';
  }
}
