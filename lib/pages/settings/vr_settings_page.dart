import 'dart:async' show unawaited;

import 'package:flutter/material.dart';
import 'package:kazumi/bean/settings/settings_detail_scaffold.dart';
import 'package:kazumi/bean/settings/settings_dropdown_tile.dart';
import 'package:kazumi/bean/settings/settings_list.dart';
import 'package:kazumi/pages/player/vr/vr_settings.dart';
import 'package:kazumi/services/platform/vr_service.dart';

/// 「内容与偏好 → VR 设置」.
///
/// This page and the in-headset GL menu edit the SAME native store
/// (`VrSettingsStore`). Nothing here is a separate Flutter copy: every write
/// goes through `VRService.updateSettings`, and every value shown comes from
/// the map mirrored back by MainActivity's `onSettingsChanged` callback.
class VrSettingsPage extends StatefulWidget {
  const VrSettingsPage({super.key});

  @override
  State<VrSettingsPage> createState() => _VrSettingsPageState();
}

class _VrSettingsPageState extends State<VrSettingsPage> {
  @override
  void initState() {
    super.initState();
    unawaited(VRService.instance.initialize());
  }

  Future<void> _patch(String key, Object value) =>
      VRService.instance.updateSettings(<String, dynamic>{key: value});

  Widget _slider(
    VrSettingsView s, {
    required IconData icon,
    required String title,
    required String key,
    required double min,
    required double max,
    required int divisions,
    String? description,
    String? suffix,
    int decimals = 2,
    bool integer = false,
  }) {
    final double value = integer ? s.integer(key).toDouble() : s.number(key);
    return SettingsSliderTile(
      leading: icon,
      title: Text(title),
      description: description == null ? null : Text(description),
      value: value.clamp(min, max).toDouble(),
      min: min,
      max: max,
      divisions: divisions,
      valueLabel:
          '${value.toStringAsFixed(decimals)}${suffix ?? ''}',
      onChanged: (double next) async {
        await _patch(key, integer ? next.round() : next);
      },
    );
  }

  Widget _toggle(
    VrSettingsView s, {
    required IconData icon,
    required String title,
    required String key,
    String? description,
  }) =>
      SettingsTile.switchTile(
        leading: icon,
        title: Text(title),
        description: description == null ? null : Text(description),
        initialValue: s.flag(key),
        onToggle: (bool? value) async {
          await _patch(key, value ?? !s.flag(key));
        },
      );

  Widget _dropdown(
    VrSettingsView s, {
    required IconData icon,
    required String title,
    required String key,
    required Map<String, String> options,
    String? description,
  }) =>
      SettingsDropdownTile<String>(
        leading: icon,
        title: Text(title),
        description: description == null ? null : Text(description),
        value: options.containsKey(s.text(key)) ? s.text(key) : options.keys.first,
        options: options,
        onChanged: (String value) async {
          await _patch(key, value);
        },
      );

  @override
  Widget build(BuildContext context) {
    return SettingsDetailScaffold(
      title: const Text('VR 设置'),
      body: ValueListenableBuilder<Map<String, dynamic>>(
        valueListenable: VRService.instance.settings,
        builder: (BuildContext context, Map<String, dynamic> map, Widget? _) {
          final VrSettingsView s = VrSettingsView(map);
          return SettingsList(
            sections: [
              SettingsSection(
                title: const Text('VR 模式'),
                bottomInfo: const Text(
                  '进入 VR 后手机 2D 界面会完全隐藏，全部操作由手柄完成：'
                  'L1 + R1 进入 · 长按 START + SELECT 或 HOME 打开菜单 · '
                  '长按 L2 + R2 开关摄像头透视 · BACK 或菜单里的「退出 VR」退出。'
                  '菜单中的每一项设置与本页面共用同一份配置，戴上纸盒也能直接修改。',
                ),
                tiles: [
                  ValueListenableBuilder<bool>(
                    valueListenable: VRService.instance.enabled,
                    builder: (BuildContext ctx, bool active, Widget? child) =>
                        SettingsTile.switchTile(
                      leading: Icons.view_in_ar_rounded,
                      title: const Text('启用 VR 模式'),
                      description: const Text(
                          '使用 Google Cardboard SDK 双目渲染与镜头畸变'),
                      initialValue: active,
                      onToggle: (bool? value) async {
                        await VRService.instance
                            .setVREnabled(value ?? !active);
                      },
                    ),
                  ),
                  _dropdown(
                    s,
                    icon: Icons.visibility_rounded,
                    title: '观看模式',
                    key: 'viewerMode',
                    description: 'Cardboard 立体模式或单眼平铺模式',
                    options: const <String, String>{
                      'CARDBOARD': 'Cardboard（立体 + 畸变）',
                      'SIMULATION': '单眼平铺（裸机预览）',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.crop_free_rounded,
                    title: '填充模式',
                    key: 'screenFillMode',
                    description: '原版畸变渲染或在合成后等比放大填满屏幕',
                    options: const <String, String>{
                      'FILL': '放大填满',
                      'DISTORTED': '原版畸变',
                    },
                  ),
                  _slider(
                    s,
                    icon: Icons.zoom_in_rounded,
                    title: '全屏缩放',
                    description: '仅在「放大填满」模式下生效',
                    key: 'zoomScale',
                    min: 1.0,
                    max: 2.5,
                    divisions: 75,
                    suffix: 'x',
                  ),
                  SettingsTile(
                    leading: Icons.qr_code_scanner_rounded,
                    onPressed: (_) async {
                      await VRService.instance.scanQrCode();
                    },
                    title: const Text('扫描 Cardboard 二维码'),
                    description: const Text('导入官方 viewer 参数（FOV / 畸变 / 瞳距）'),
                  ),
                  SettingsTile(
                    leading: Icons.refresh_rounded,
                    onPressed: (_) async {
                      await VRService.instance.recenter();
                    },
                    title: const Text('重置中心点'),
                    description: const Text('把当前头部朝向设为正前方'),
                  ),
                ],
              ),
              SettingsSection(
                title: const Text('影院画面'),
                tiles: [
                  _slider(
                    s,
                    icon: Icons.zoom_out_map_rounded,
                    title: '屏幕缩放',
                    key: 'screenScale',
                    min: 0.5,
                    max: 3.0,
                    divisions: 25,
                    suffix: 'x',
                  ),
                  _slider(
                    s,
                    icon: Icons.straighten_rounded,
                    title: '观看距离',
                    description: '虚拟幕布距离（米）',
                    key: 'screenDistanceMeters',
                    min: 0.8,
                    max: 6.0,
                    divisions: 26,
                    suffix: ' m',
                  ),
                  _slider(
                    s,
                    icon: Icons.vertical_align_center_rounded,
                    title: '垂直偏移',
                    key: 'screenVerticalOffset',
                    min: -1.0,
                    max: 1.0,
                    divisions: 20,
                  ),
                  _slider(
                    s,
                    icon: Icons.waves_rounded,
                    title: '屏幕曲率',
                    key: 'curvature',
                    min: 0.0,
                    max: 1.0,
                    divisions: 10,
                  ),
                  _slider(
                    s,
                    icon: Icons.brightness_7_rounded,
                    title: '画面亮度',
                    key: 'brightness',
                    min: 0.4,
                    max: 1.4,
                    divisions: 20,
                    suffix: 'x',
                  ),
                  _slider(
                    s,
                    icon: Icons.remove_red_eye_rounded,
                    title: '等效瞳距',
                    description: '相对 Cardboard 默认瞳距的倍率',
                    key: 'ipdScale',
                    min: 0.5,
                    max: 1.6,
                    divisions: 22,
                    suffix: 'x',
                  ),
                  _toggle(
                    s,
                    icon: Icons.flip_rounded,
                    title: '水平镜像',
                    key: 'horizontalMirror',
                  ),
                  _toggle(
                    s,
                    icon: Icons.flip_camera_android_rounded,
                    title: '垂直翻转',
                    key: 'verticalFlip',
                  ),
                  _toggle(
                    s,
                    icon: Icons.info_outline_rounded,
                    title: '显示 VR 状态栏',
                    key: 'showStatus',
                  ),
                  _toggle(
                    s,
                    icon: Icons.center_focus_strong_rounded,
                    title: '显示中心辅助线',
                    key: 'showCenterAlignmentLine',
                  ),
                  _toggle(
                    s,
                    icon: Icons.landscape_rounded,
                    title: '360° 环境背景',
                    key: 'backgroundEnabled',
                  ),
                  SettingsTile(
                    leading: Icons.image_rounded,
                    onPressed: (BuildContext ctx) async {
                      final TextEditingController controller =
                          TextEditingController(
                              text: s.text('backgroundImageUri'));
                      final String? uri;
                      try {
                        uri = await showDialog<String>(
                          context: ctx,
                          builder: (BuildContext dialogCtx) => AlertDialog(
                            title: const Text('360° 背景图片'),
                            content: TextField(
                              controller: controller,
                              decoration: const InputDecoration(
                                hintText: '图片路径或 content:// URI',
                              ),
                              maxLines: 1,
                            ),
                            actions: [
                              TextButton(
                                onPressed: () => Navigator.of(dialogCtx).pop(),
                                child: const Text('取消'),
                              ),
                              TextButton(
                                onPressed: () => Navigator.of(dialogCtx)
                                    .pop(controller.text.trim()),
                                child: const Text('保存'),
                              ),
                            ],
                          ),
                        );
                      } finally {
                        controller.dispose();
                      }
                      if (uri != null) {
                        await _patch('backgroundImageUri', uri);
                      }
                    },
                    title: const Text('360° 背景图片'),
                    description: const Text('留空则使用内置星空背景'),
                  ),
                  _slider(
                    s,
                    icon: Icons.brightness_6_rounded,
                    title: '背景亮度',
                    key: 'backgroundBrightness',
                    min: 0.2,
                    max: 1.5,
                    divisions: 26,
                    suffix: 'x',
                  ),
                ],
              ),
              SettingsSection(
                title: const Text('头部追踪与手柄'),
                tiles: [
                  _toggle(
                    s,
                    icon: Icons.screen_rotation_alt_rounded,
                    title: '头部追踪',
                    key: 'headTrackingEnabled',
                    description: '由 Cardboard SDK 的 HeadTransform 提供视角',
                  ),
                  _slider(
                    s,
                    icon: Icons.speed_rounded,
                    title: '追踪灵敏度',
                    key: 'headTrackingSensitivity',
                    min: 0.0,
                    max: 1.5,
                    divisions: 15,
                    suffix: 'x',
                  ),
                  _slider(
                    s,
                    icon: Icons.tune_rounded,
                    title: '摇杆死区',
                    key: 'gamepadDeadZone',
                    min: 0.05,
                    max: 0.5,
                    divisions: 9,
                  ),
                  _slider(
                    s,
                    icon: Icons.timer_rounded,
                    title: '菜单重复延迟',
                    description: '长按方向键 / 摇杆时的重复间隔',
                    key: 'menuRepeatMs',
                    min: 60.0,
                    max: 500.0,
                    divisions: 22,
                    suffix: ' ms',
                    decimals: 0,
                    integer: true,
                  ),
                ],
              ),
              SettingsSection(
                title: const Text('摄像头透视（Passthrough）'),
                bottomInfo: const Text(
                  '开启后真实环境会以独立的、保持正确比例的窗口出现在 VR 中。'
                  '首次开启需要授予相机权限；设备不支持时会自动回退到纯黑背景。',
                ),
                tiles: [
                  _toggle(
                    s,
                    icon: Icons.camera_alt_rounded,
                    title: '启用摄像头透视',
                    key: 'passthroughEnabled',
                  ),
                  _dropdown(
                    s,
                    icon: Icons.camera_front_rounded,
                    title: '使用镜头',
                    key: 'passthroughCameraFacing',
                    options: const <String, String>{
                      'BACK': '后置镜头',
                      'FRONT': '前置镜头',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.dashboard_rounded,
                    title: '画面布局',
                    key: 'passthroughMode',
                    options: const <String, String>{
                      'OVERLAY': '画中画（相机在幕布后）',
                      'CAMERA_MAIN': '相机为主画面',
                      'SIDE_BY_SIDE': '左右并排',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.aspect_ratio_rounded,
                    title: '窗口比例',
                    key: 'passthroughAspect',
                    description: '始终横向，预览缓冲会按此比例居中裁切而不是拉伸',
                    options: const <String, String>{
                      'FOUR_THREE': '4:3',
                      'SIXTEEN_NINE': '16:9',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.high_quality_rounded,
                    title: '预览分辨率',
                    key: 'passthroughResolution',
                    options: const <String, String>{
                      'P360': '360P',
                      'P480': '480P',
                      'P720': '720P',
                      'P1080': '1080P',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.slow_motion_video_rounded,
                    title: '预览帧率',
                    key: 'passthroughFrameRate',
                    options: const <String, String>{
                      'FPS_15': '15 FPS',
                      'FPS_30': '30 FPS',
                      'FPS_60': '60 FPS',
                    },
                  ),
                  _dropdown(
                    s,
                    icon: Icons.crop_rotate_rounded,
                    title: '预览旋转',
                    key: 'passthroughRotation',
                    description: 'AUTO 为横屏下的正立预览，其余为手动校正',
                    options: const <String, String>{
                      'AUTO': '自动',
                      'R0': '0°',
                      'R90': '90°',
                      'R180': '180°',
                      'R270': '270°',
                    },
                  ),
                  _slider(
                    s,
                    icon: Icons.opacity_rounded,
                    title: '透视可见度',
                    key: 'passthroughOpacity',
                    min: 0.0,
                    max: 1.0,
                    divisions: 20,
                  ),
                  _slider(
                    s,
                    icon: Icons.fit_screen_rounded,
                    title: '窗口大小',
                    key: 'passthroughWindowScale',
                    min: 0.30,
                    max: 1.50,
                    divisions: 24,
                    suffix: 'x',
                  ),
                  _toggle(
                    s,
                    icon: Icons.swap_horiz_rounded,
                    title: '预览镜像',
                    key: 'passthroughMirror',
                  ),
                  _toggle(
                    s,
                    icon: Icons.filter_center_focus_rounded,
                    title: '头部锁定窗口',
                    key: 'passthroughHeadLocked',
                    description: '关闭时相机窗口与幕布共用同一 MVP，转头不会相对滑动',
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}
