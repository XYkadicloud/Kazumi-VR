import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show FilteringTextInputFormatter;
import 'package:flutter_modular/flutter_modular.dart';
import 'package:kazumi/bean/dialog/dialog_helper.dart';
import 'package:kazumi/bean/settings/settings_detail_scaffold.dart';
import 'package:kazumi/bean/settings/settings_dropdown_tile.dart';
import 'package:kazumi/pages/player/controller/player_aspect_ratio.dart';
import 'package:kazumi/pages/settings/low_memory_mode_settings.dart';
import 'package:kazumi/utils/constants.dart';
import 'package:kazumi/services/storage/storage.dart';
import 'package:kazumi/services/player/pip_utils.dart';
import 'package:kazumi/bean/settings/settings_list.dart';
import 'package:kazumi/utils/device.dart';
import 'package:kazumi/services/platform/vr_service.dart';

class PlayerSettingsPage extends StatefulWidget {
  const PlayerSettingsPage({super.key});

  @override
  State<PlayerSettingsPage> createState() => _PlayerSettingsPageState();
}

class _PlayerSettingsPageState extends State<PlayerSettingsPage> {
  static const double _minPlayerControllerLayerDisappearSeconds = 1;
  static const double _maxPlayerControllerLayerDisappearSeconds = 10;
  static const int _playerControllerLayerDisappearDivisions = 18;

  late double defaultPlaySpeed;
  late double defaultShortcutForwardPlaySpeed;
  late PlayerAspectRatio defaultAspectRatioMode;
  late bool hAenable;
  late bool androidEnableOpenSLES;
  late bool androidAutoEnterPIP;
  late bool playResume;
  late bool showPlayerError;
  late bool privateMode;
  late bool playerDebugMode;
  late bool playerDisableAnimations;
  late bool forceAdBlocker;
  late bool autoPlayNext;
  late bool backgroundPlayback;
  late bool brightnessVolumeGesture;
  late int playerButtonSkipTime;
  late int playerArrowKeySkipTime;
  late int playerLogLevel;
  late int playerControllerLayerDisappearTime;

  @override
  void initState() {
    super.initState();
    _loadSettingsFromStorage();
  }

  void _loadSettingsFromStorage() {
    defaultPlaySpeed =
        GStorage.getSetting<double>(SettingsKeys.defaultPlaySpeed);
    defaultShortcutForwardPlaySpeed = GStorage.getSetting<double>(
        SettingsKeys.defaultShortcutForwardPlaySpeed);
    defaultAspectRatioMode = PlayerAspectRatio.fromStorageValue(
      GStorage.getSetting<int>(SettingsKeys.defaultAspectRatioType),
    );
    hAenable = GStorage.getSetting<bool>(SettingsKeys.hAenable);
    androidEnableOpenSLES =
        GStorage.getSetting<bool>(SettingsKeys.androidEnableOpenSLES);
    androidAutoEnterPIP =
        GStorage.getSetting<bool>(SettingsKeys.androidAutoEnterPIP);
    playResume = GStorage.getSetting<bool>(SettingsKeys.playResume);
    privateMode = GStorage.getSetting<bool>(SettingsKeys.privateMode);
    showPlayerError = GStorage.getSetting<bool>(SettingsKeys.showPlayerError);
    playerDebugMode = GStorage.getSetting<bool>(SettingsKeys.playerDebugMode);
    autoPlayNext = GStorage.getSetting<bool>(SettingsKeys.autoPlayNext);
    backgroundPlayback =
        GStorage.getSetting<bool>(SettingsKeys.backgroundPlayback);
    playerDisableAnimations =
        GStorage.getSetting<bool>(SettingsKeys.playerDisableAnimations);
    forceAdBlocker = GStorage.getSetting<bool>(SettingsKeys.forceAdBlocker);
    playerLogLevel = GStorage.getSetting<int>(SettingsKeys.playerLogLevel);

    brightnessVolumeGesture =
        GStorage.getSetting<bool>(SettingsKeys.brightnessVolumeGesture);

    playerButtonSkipTime =
        GStorage.getSetting<int>(SettingsKeys.buttonSkipTime);
    playerArrowKeySkipTime =
        GStorage.getSetting<int>(SettingsKeys.arrowKeySkipTime);

    playerControllerLayerDisappearTime = GStorage.getSetting<int>(
        SettingsKeys.playerControllerLayerDisappearTime);
  }

  Future<void> resetPlayerSettings() async {
    final bool shouldReset = await KazumiDialog.show<bool>(
          builder: (context) => AlertDialog(
            title: const Text('恢复默认播放设置'),
            content: const Text('播放设置、硬件解码器、视频渲染器和超分辨率设置将恢复为默认值。'),
            actions: [
              TextButton(
                onPressed: () => KazumiDialog.dismiss(popWith: false),
                child: Text('取消'),
              ),
              TextButton(
                onPressed: () => KazumiDialog.dismiss(popWith: true),
                child: Text('恢复默认'),
              ),
            ],
          ),
        ) ??
        false;
    if (!shouldReset) return;

    await GStorage.resetPlayerSettings();
    if (Platform.isAndroid) {
      await PipUtils.setAndroidAutoEnterPIPEnabled(false);
    }
    if (!mounted) return;
    setState(_loadSettingsFromStorage);
    KazumiDialog.showToast(message: '已恢复默认播放设置');
  }

  void updateDefaultPlaySpeed(double speed) {
    GStorage.putSetting<double>(SettingsKeys.defaultPlaySpeed, speed);
    setState(() {
      defaultPlaySpeed = speed;
    });
  }

  void updateDefaultShortcutForwardPlaySpeed(double speed) {
    GStorage.putSetting<double>(
        SettingsKeys.defaultShortcutForwardPlaySpeed, speed);
    setState(() {
      defaultShortcutForwardPlaySpeed = speed;
    });
  }

  void updatePlayerLogLevel(int level) {
    GStorage.putSetting<int>(SettingsKeys.playerLogLevel, level);
    setState(() {
      playerLogLevel = level;
    });
  }

  void updateDefaultAspectRatioMode(PlayerAspectRatio mode) {
    GStorage.putSetting<int>(
      SettingsKeys.defaultAspectRatioType,
      mode.storageValue,
    );
    setState(() {
      defaultAspectRatioMode = mode;
    });
  }

  Future<void> updateButtonSkipTime() async {
    final int? newButtonSkipTime = await _showSkipTimeChangeDialog(
        title: '顶部按钮快进时长', initialValue: playerButtonSkipTime.toString());

    if (newButtonSkipTime != null &&
        newButtonSkipTime != playerButtonSkipTime) {
      GStorage.putSetting<int>(SettingsKeys.buttonSkipTime, newButtonSkipTime);
      setState(() {
        playerButtonSkipTime = newButtonSkipTime;
      });
    }
  }

  Future<int?> _showSkipTimeChangeDialog(
      {required String title, required String initialValue}) async {
    return KazumiDialog.show<int>(builder: (context) {
      String input = "";
      return AlertDialog(
        title: Text(title),
        content: StatefulBuilder(
            builder: (BuildContext context, StateSetter setState) {
          return TextField(
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
            ],
            decoration: InputDecoration(
              floatingLabelBehavior: FloatingLabelBehavior.never,
              labelText: initialValue,
            ),
            onChanged: (value) {
              input = value;
            },
          );
        }),
        actions: <Widget>[
          TextButton(
            onPressed: () => KazumiDialog.dismiss(),
            child: Text(
              '取消',
              style: TextStyle(color: Theme.of(context).colorScheme.outline),
            ),
          ),
          TextButton(
            onPressed: () async {
              final int? newValue = int.tryParse(input);

              if (newValue == null) {
                KazumiDialog.showToast(message: '请输入数字');
                return;
              }

              if (newValue <= 0) {
                KazumiDialog.showToast(message: '请输入大于0的数字');
                return;
              }
              KazumiDialog.dismiss(popWith: newValue);
            },
            child: const Text('确定'),
          ),
        ],
      );
    });
  }

  double get playerControllerLayerDisappearSeconds =>
      (playerControllerLayerDisappearTime / Duration.millisecondsPerSecond)
          .clamp(_minPlayerControllerLayerDisappearSeconds,
              _maxPlayerControllerLayerDisappearSeconds)
          .toDouble();

  String formatPlayerControllerLayerDisappearSeconds(double seconds) {
    if (seconds == seconds.roundToDouble()) {
      return '${seconds.toInt()} 秒';
    }
    return '${seconds.toStringAsFixed(1)} 秒';
  }

  void updatePlayerControllerLayerDisappearSeconds(double seconds) {
    final int newDisappearTime =
        (seconds * Duration.millisecondsPerSecond).round();
    if (newDisappearTime == playerControllerLayerDisappearTime) {
      return;
    }
    GStorage.putSetting<int>(
        SettingsKeys.playerControllerLayerDisappearTime, newDisappearTime);
    setState(() {
      playerControllerLayerDisappearTime = newDisappearTime;
    });
  }

  @override
  Widget build(BuildContext context) {
    return SettingsDetailScaffold(
      title: const Text('播放设置'),
      body: SettingsList(
        sections: [
          SettingsSection(
            title: Text('解码与渲染'),
            tiles: [
              SettingsTile.switchTile(
                leading: Icons.memory_rounded,
                onToggle: (value) async {
                  hAenable = value ?? !hAenable;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.hAenable, hAenable);
                  setState(() {});
                },
                title: Text('硬件解码'),
                initialValue: hAenable,
              ),
              SettingsTile(
                leading: Icons.developer_board_rounded,
                onPressed: (_) async {
                  await context.pushNamed('/settings/player/decoder');
                },
                title: Text('硬件解码器'),
                description: Text('仅在硬件解码启用时生效'),
              ),
              if (Platform.isAndroid) ...[
                SettingsTile(
                  leading: Icons.tv_rounded,
                  onPressed: (_) async {
                    await context.pushNamed('/settings/player/renderer');
                  },
                  title: Text('视频渲染器'),
                  description: Text('选择视频输出方式'),
                ),
              ],
              const LowMemoryModeSettingsTile(),
              if (Platform.isAndroid) ...[
                SettingsTile.switchTile(
                  leading: Icons.graphic_eq_rounded,
                  onToggle: (value) async {
                    androidEnableOpenSLES = value ?? !androidEnableOpenSLES;
                    await GStorage.putSetting<bool>(
                        SettingsKeys.androidEnableOpenSLES,
                        androidEnableOpenSLES);
                    setState(() {});
                  },
                  title: Text('低延迟音频'),
                  description: Text('启用OpenSLES音频输出以降低延时'),
                  initialValue: androidEnableOpenSLES,
                ),
              ],
              SettingsTile(
                leading: Icons.auto_awesome_rounded,
                onPressed: (_) async {
                  context.pushNamed('/settings/player/super');
                },
                title: Text('超分辨率'),
              ),
            ],
          ),
          SettingsSection(
            title: Text('播放行为'),
            tiles: [
              SettingsTile.switchTile(
                leading: Icons.headphones_rounded,
                onToggle: (value) async {
                  backgroundPlayback = value ?? !backgroundPlayback;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.backgroundPlayback, backgroundPlayback);
                  setState(() {});
                },
                title: Text('后台播放'),
                description: Text('应用退到后台或熄屏时继续播放音频'),
                initialValue: backgroundPlayback,
              ),
              SettingsTile.switchTile(
                leading: Icons.history_rounded,
                onToggle: (value) async {
                  playResume = value ?? !playResume;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.playResume, playResume);
                  setState(() {});
                },
                title: Text('自动跳转'),
                description: Text('跳转到上次播放位置'),
                initialValue: playResume,
              ),
              SettingsTile.switchTile(
                leading: Icons.playlist_play_rounded,
                onToggle: (value) async {
                  autoPlayNext = value ?? !autoPlayNext;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.autoPlayNext, autoPlayNext);
                  setState(() {});
                },
                title: Text('自动连播'),
                description: Text('当前视频播放完毕后自动播放下一集'),
                initialValue: autoPlayNext,
              ),
              if (Platform.isAndroid)
                SettingsTile.switchTile(
                  leading: Icons.picture_in_picture_alt_rounded,
                  onToggle: (value) async {
                    androidAutoEnterPIP = value ?? !androidAutoEnterPIP;
                    await GStorage.putSetting<bool>(
                        SettingsKeys.androidAutoEnterPIP, androidAutoEnterPIP);
                    await PipUtils.setAndroidAutoEnterPIPEnabled(
                        androidAutoEnterPIP);
                    setState(() {});
                  },
                  title: Text('自动进入画中画'),
                  description: Text('切到后台时，自动进入画中画'),
                  initialValue: androidAutoEnterPIP,
                ),
              SettingsTile.switchTile(
                leading: Icons.block_rounded,
                onToggle: (value) async {
                  forceAdBlocker = value ?? !forceAdBlocker;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.forceAdBlocker, forceAdBlocker);
                  setState(() {});
                },
                title: Text('广告过滤'),
                description: Text('强制启用HLS广告过滤，忽略规则设置'),
                initialValue: forceAdBlocker,
              ),
              SettingsTile.switchTile(
                leading: Icons.animation_rounded,
                onToggle: (value) async {
                  playerDisableAnimations = value ?? !playerDisableAnimations;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.playerDisableAnimations,
                      playerDisableAnimations);
                  setState(() {});
                },
                title: Text('禁用动画'),
                description: Text('禁用播放器内的过渡动画'),
                initialValue: playerDisableAnimations,
              ),
              if (!isDesktop())
                SettingsTile.switchTile(
                  leading: Icons.swipe_vertical_rounded,
                  onToggle: (value) async {
                    brightnessVolumeGesture = value ?? !brightnessVolumeGesture;
                    await GStorage.putSetting<bool>(
                        SettingsKeys.brightnessVolumeGesture,
                        brightnessVolumeGesture);
                    setState(() {});
                  },
                  title: Text('滑动手势'),
                  description: Text('竖向滑动调节音量和亮度'),
                  initialValue: brightnessVolumeGesture,
                ),
              SettingsTile.switchTile(
                leading: Icons.visibility_off_rounded,
                onToggle: (value) async {
                  privateMode = value ?? !privateMode;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.privateMode, privateMode);
                  setState(() {});
                },
                title: Text('隐身模式'),
                description: Text('不保留观看记录'),
                initialValue: privateMode,
              ),
            ],
          ),
          SettingsSection(
            title: Text('诊断'),
            tiles: [
              SettingsTile.switchTile(
                leading: Icons.error_outline_rounded,
                onToggle: (value) async {
                  showPlayerError = value ?? !showPlayerError;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.showPlayerError, showPlayerError);
                  setState(() {});
                },
                title: Text('错误提示'),
                description: Text('显示播放器内部错误提示'),
                initialValue: showPlayerError,
              ),
              SettingsTile.switchTile(
                leading: Icons.bug_report_rounded,
                onToggle: (value) async {
                  playerDebugMode = value ?? !playerDebugMode;
                  await GStorage.putSetting<bool>(
                      SettingsKeys.playerDebugMode, playerDebugMode);
                  setState(() {});
                },
                title: Text('调试模式'),
                description: Text('记录播放器内部日志'),
                initialValue: playerDebugMode,
              ),
              SettingsDropdownTile<int>(
                leading: Icons.receipt_long_rounded,
                title: const Text('日志等级'),
                description: const Text('播放器内部日志等级'),
                value: playerLogLevel,
                options: playerLogLevelMap,
                fallbackLabel: '???',
                onChanged: updatePlayerLogLevel,
              ),
            ],
          ),
          SettingsSection(
            title: Text('播放参数'),
            tiles: [
              SettingsSliderTile(
                leading: Icons.speed_rounded,
                title: Text('默认倍速'),
                value: defaultPlaySpeed,
                min: 0.25,
                max: 3,
                divisions: 11,
                valueLabel: '${defaultPlaySpeed}x',
                onChanged: (value) => updateDefaultPlaySpeed(
                    double.parse(value.toStringAsFixed(2))),
              ),
              SettingsSliderTile(
                leading: Icons.fast_forward_rounded,
                title: Text('长按倍速'),
                description: Text('长按屏幕或按住方向键时的倍速'),
                value: defaultShortcutForwardPlaySpeed,
                min: 1.25,
                max: 3,
                divisions: 7,
                valueLabel: '${defaultShortcutForwardPlaySpeed}x',
                onChanged: (value) => updateDefaultShortcutForwardPlaySpeed(
                    double.parse(value.toStringAsFixed(2))),
              ),
              SettingsSliderTile(
                leading: Icons.swap_horiz_rounded,
                title: Text('方向键跳转'),
                description: Text('左右方向键的快进/快退秒数'),
                value: playerArrowKeySkipTime.toDouble(),
                min: 0,
                max: 15,
                divisions: 15,
                valueLabel: '$playerArrowKeySkipTime 秒',
                onChanged: (value) {
                  final newArrowKeySkipTime = value.toInt();
                  if (newArrowKeySkipTime == playerArrowKeySkipTime) {
                    return;
                  }
                  GStorage.putSetting<int>(
                      SettingsKeys.arrowKeySkipTime, newArrowKeySkipTime);
                  setState(() {
                    playerArrowKeySkipTime = newArrowKeySkipTime;
                  });
                },
              ),
              SettingsTile(
                leading: Icons.skip_next_rounded,
                onPressed: (_) async {
                  await updateButtonSkipTime();
                },
                title: Text('跳过时长'),
                description: Text('顶栏跳过按钮的秒数'),
                value: Text('$playerButtonSkipTime 秒'),
              ),
              SettingsSliderTile(
                leading: Icons.timer_rounded,
                title: Text('控制栏消失时间'),
                description: Text('播放控制器自动隐藏前的停留时长'),
                value: playerControllerLayerDisappearSeconds,
                min: _minPlayerControllerLayerDisappearSeconds,
                max: _maxPlayerControllerLayerDisappearSeconds,
                divisions: _playerControllerLayerDisappearDivisions,
                valueLabel: formatPlayerControllerLayerDisappearSeconds(
                    playerControllerLayerDisappearSeconds),
                onChanged: updatePlayerControllerLayerDisappearSeconds,
              ),
              SettingsDropdownTile<PlayerAspectRatio>(
                leading: Icons.aspect_ratio_rounded,
                title: const Text('默认视频比例'),
                value: defaultAspectRatioMode,
                options: {
                  for (final mode in PlayerAspectRatio.values) mode: mode.label,
                },
                onChanged: updateDefaultAspectRatioMode,
              ),
            ],
          ),
          SettingsSection(
            title: Text('VR 设置'),
            tiles: [
              SettingsTile.switchTile(
                leading: Icons.view_in_ar_rounded,
                onToggle: (value) async {
                  final enabled = value ?? false;
                  await GStorage.putSetting<bool>(SettingsKeys.vrEnabled, enabled);
                  if (enabled) {
                    await VRService.instance.setVREnabled(true);
                  } else {
                    await VRService.instance.setVREnabled(false);
                  }
                  setState(() {});
                },
                title: Text('启用 VR 模式'),
                description: Text('使用 Google Cardboard 模拟 VR 观看'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrEnabled),
              ),
              SettingsSliderTile(
                leading: Icons.zoom_out_map_rounded,
                title: Text('屏幕缩放'),
                value: GStorage.getSetting<double>(SettingsKeys.vrScreenScale),
                min: 0.4,
                max: 3.0,
                divisions: 26,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrScreenScale).toStringAsFixed(2)}x',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrScreenScale, value);
                  await VRService.instance.updateSettings({'screenScale': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.straighten_rounded,
                title: Text('观看距离'),
                description: Text('虚拟屏幕距离（米）'),
                value: GStorage.getSetting<double>(SettingsKeys.vrScreenDistanceMeters),
                min: 0.5,
                max: 5.0,
                divisions: 45,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrScreenDistanceMeters).toStringAsFixed(2)} m',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrScreenDistanceMeters, value);
                  await VRService.instance.updateSettings({'screenDistanceMeters': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.vertical_align_center_rounded,
                title: Text('垂直偏移'),
                value: GStorage.getSetting<double>(SettingsKeys.vrScreenVerticalOffset),
                min: -1.0,
                max: 1.0,
                divisions: 40,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrScreenVerticalOffset).toStringAsFixed(2)}',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrScreenVerticalOffset, value);
                  await VRService.instance.updateSettings({'screenVerticalOffset': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.waves_rounded,
                title: Text('屏幕曲率'),
                value: GStorage.getSetting<double>(SettingsKeys.vrCurvature),
                min: 0.0,
                max: 2.0,
                divisions: 40,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrCurvature).toStringAsFixed(2)}',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrCurvature, value);
                  await VRService.instance.updateSettings({'curvature': value});
                  setState(() {});
                },
              ),
              SettingsDropdownTile<int>(
                leading: Icons.view_in_ar_rounded,
                title: const Text('观看模式'),
                description: const Text('Cardboard 立体模式或单眼平铺模式'),
                value: GStorage.getSetting<int>(SettingsKeys.vrViewerMode),
                options: const {
                  0: 'Cardboard (立体)',
                  1: '单眼平铺',
                },
                onChanged: (value) async {
                  await GStorage.putSetting<int>(SettingsKeys.vrViewerMode, value);
                  await VRService.instance.updateSettings({'viewerMode': value == 0 ? 'CARDBOARD' : 'MONO'});
                  setState(() {});
                },
              ),
              SettingsTile.switchTile(
                leading: Icons.screen_rotation_alt_rounded,
                onToggle: (value) async {
                  final enabled = value ?? true;
                  await GStorage.putSetting<bool>(SettingsKeys.vrHeadTrackingEnabled, enabled);
                  await VRService.instance.updateSettings({'headTrackingEnabled': enabled});
                  setState(() {});
                },
                title: Text('头部追踪'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrHeadTrackingEnabled),
              ),
              SettingsSliderTile(
                leading: Icons.speed_rounded,
                title: Text('追踪灵敏度'),
                value: GStorage.getSetting<double>(SettingsKeys.vrHeadTrackingSensitivity),
                min: 0.25,
                max: 3.0,
                divisions: 27,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrHeadTrackingSensitivity).toStringAsFixed(2)}x',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrHeadTrackingSensitivity, value);
                  await VRService.instance.updateSettings({'headTrackingSensitivity': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.remove_red_eye_rounded,
                title: Text('瞳距缩放'),
                value: GStorage.getSetting<double>(SettingsKeys.vrIpdScale),
                min: 0.5,
                max: 1.5,
                divisions: 20,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrIpdScale).toStringAsFixed(2)}x',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrIpdScale, value);
                  await VRService.instance.updateSettings({'ipdScale': value});
                  setState(() {});
                },
              ),
              SettingsDropdownTile<int>(
                leading: Icons.crop_free_rounded,
                title: const Text('填充模式'),
                description: const Text('原版畸变渲染或单纯放大'),
                value: GStorage.getSetting<int>(SettingsKeys.vrScreenFillMode),
                options: const {
                  0: '原版畸变',
                  1: '单纯放大',
                },
                onChanged: (value) async {
                  await GStorage.putSetting<int>(SettingsKeys.vrScreenFillMode, value);
                  await VRService.instance.updateSettings({'screenFillMode': value == 0 ? 'DISTORTED' : 'FILL'});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.zoom_in_rounded,
                title: Text('全屏缩放'),
                description: Text('仅在"单纯放大"模式下生效'),
                value: GStorage.getSetting<double>(SettingsKeys.vrZoomScale),
                min: 1.0,
                max: 2.0,
                divisions: 20,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrZoomScale).toStringAsFixed(2)}x',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrZoomScale, value);
                  await VRService.instance.updateSettings({'zoomScale': value});
                  setState(() {});
                },
              ),
              SettingsTile.switchTile(
                leading: Icons.landscape_rounded,
                onToggle: (value) async {
                  final enabled = value ?? false;
                  await GStorage.putSetting<bool>(SettingsKeys.vrBackgroundEnabled, enabled);
                  await VRService.instance.updateSettings({'backgroundEnabled': enabled});
                  setState(() {});
                },
                title: Text('360° 环境背景'),
                description: Text('启用后显示纯色或自定义图片背景'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrBackgroundEnabled),
              ),
              SettingsSliderTile(
                leading: Icons.brightness_6_rounded,
                title: Text('背景亮度'),
                value: GStorage.getSetting<double>(SettingsKeys.vrBackgroundBrightness),
                min: 0.0,
                max: 1.0,
                divisions: 20,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrBackgroundBrightness).toStringAsFixed(2)}',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrBackgroundBrightness, value);
                  await VRService.instance.updateSettings({'backgroundBrightness': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.brightness_7_rounded,
                title: Text('画面亮度'),
                value: GStorage.getSetting<double>(SettingsKeys.vrBrightness),
                min: 0.25,
                max: 2.0,
                divisions: 35,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrBrightness).toStringAsFixed(2)}x',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrBrightness, value);
                  await VRService.instance.updateSettings({'brightness': value});
                  setState(() {});
                },
              ),
              SettingsTile.switchTile(
                leading: Icons.flip_rounded,
                onToggle: (value) async {
                  final enabled = value ?? false;
                  await GStorage.putSetting<bool>(SettingsKeys.vrHorizontalMirror, enabled);
                  await VRService.instance.updateSettings({'horizontalMirror': enabled});
                  setState(() {});
                },
                title: Text('水平镜像'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrHorizontalMirror),
              ),
              SettingsTile.switchTile(
                leading: Icons.flip_camera_android_rounded,
                onToggle: (value) async {
                  final enabled = value ?? false;
                  await GStorage.putSetting<bool>(SettingsKeys.vrVerticalFlip, enabled);
                  await VRService.instance.updateSettings({'verticalFlip': enabled});
                  setState(() {});
                },
                title: Text('垂直翻转'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrVerticalFlip),
              ),
              SettingsTile.switchTile(
                leading: Icons.info_outline_rounded,
                onToggle: (value) async {
                  final enabled = value ?? true;
                  await GStorage.putSetting<bool>(SettingsKeys.vrShowStatus, enabled);
                  await VRService.instance.updateSettings({'showStatus': enabled});
                  setState(() {});
                },
                title: Text('显示状态栏'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrShowStatus),
              ),
              SettingsTile.switchTile(
                leading: Icons.center_focus_strong_rounded,
                onToggle: (value) async {
                  final enabled = value ?? true;
                  await GStorage.putSetting<bool>(SettingsKeys.vrShowCenterAlignmentLine, enabled);
                  await VRService.instance.updateSettings({'showCenterAlignmentLine': enabled});
                  setState(() {});
                },
                title: Text('显示中心辅助线'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrShowCenterAlignmentLine),
              ),
              SettingsTile.switchTile(
                leading: Icons.bug_report_rounded,
                onToggle: (value) async {
                  final enabled = value ?? false;
                  await GStorage.putSetting<bool>(SettingsKeys.vrShowDebugOverlay, enabled);
                  await VRService.instance.updateSettings({'showDebugOverlay': enabled});
                  setState(() {});
                },
                title: Text('调试覆盖层'),
                initialValue: GStorage.getSetting<bool>(SettingsKeys.vrShowDebugOverlay),
              ),
              SettingsSliderTile(
                leading: Icons.tune_rounded,
                title: Text('摇杆死区'),
                value: GStorage.getSetting<double>(SettingsKeys.vrGamepadDeadZone),
                min: 0.0,
                max: 0.5,
                divisions: 50,
                valueLabel: '${GStorage.getSetting<double>(SettingsKeys.vrGamepadDeadZone).toStringAsFixed(2)}',
                onChanged: (value) async {
                  await GStorage.putSetting<double>(SettingsKeys.vrGamepadDeadZone, value);
                  await VRService.instance.updateSettings({'gamepadDeadZone': value});
                  setState(() {});
                },
              ),
              SettingsSliderTile(
                leading: Icons.timer_rounded,
                title: Text('菜单重复延迟'),
                description: Text('长按手柄方向键/摇杆时的重复间隔 (ms)'),
                value: GStorage.getSetting<int>(SettingsKeys.vrMenuRepeatMs).toDouble(),
                min: 50,
                max: 500,
                divisions: 45,
                valueLabel: '${GStorage.getSetting<int>(SettingsKeys.vrMenuRepeatMs)} ms',
                onChanged: (value) async {
                  final intVal = value.round();
                  await GStorage.putSetting<int>(SettingsKeys.vrMenuRepeatMs, intVal);
                  await VRService.instance.updateSettings({'menuRepeatMs': intVal});
                  setState(() {});
                },
              ),
              SettingsTile(
                leading: Icons.refresh_rounded,
                onPressed: (_) async {
                  await VRService.instance.recenter();
                },
                title: Text('重置中心点'),
                description: Text('将当前头部朝向设为正前方'),
              ),
              SettingsTile(
                leading: Icons.qr_code_scanner_rounded,
                onPressed: (ctx) async {
                  await VRService.instance.scanQrCode(ctx);
                },
                title: Text('扫描 Cardboard 二维码'),
                description: Text('导入官方 Cardboard 设备参数'),
              ),
            ],
          ),
          SettingsSection(
            tiles: [
              SettingsTile(
                leading: Icons.settings_backup_restore_rounded,
                onPressed: (_) => resetPlayerSettings(),
                title: Text('恢复默认设置'),
                description: Text('将播放相关设置恢复为默认值'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
