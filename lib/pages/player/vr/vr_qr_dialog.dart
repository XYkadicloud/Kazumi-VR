import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Import dialog for Cardboard viewer parameters.
///
/// A phone in a Cardboard holder cannot reliably scan a QR code with the
/// camera, so this dialog accepts the QR payload as text (paste from
/// clipboard), a Cardboard profile URL, or plain key=value parameters such as
/// `ipd=0.064&fov=60`. [VrQrParser] turns that into a settings patch that
/// [VRService.updateSettings] can persist.
///
/// Note: the official Cardboard QR payload is a binary protobuf. This parser
/// reads its embedded viewer name and applies well-known preset values; it
/// does not claim to reconstruct every distortion coefficient. Plain-text
/// parameters are applied exactly.
class VrQrImportDialog extends StatefulWidget {
  const VrQrImportDialog({super.key});

  @override
  State<VrQrImportDialog> createState() => _VrQrImportDialogState();
}

class _VrQrImportDialogState extends State<VrQrImportDialog> {
  final TextEditingController _controller = TextEditingController();
  String? _message;
  bool _isWarning = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _pasteFromClipboard() async {
    final ClipboardData? data =
        await Clipboard.getData(Clipboard.kTextPlain);
    final String text = (data?.text ?? '').trim();
    if (text.isEmpty) {
      setState(() {
        _isWarning = true;
        _message = '剪贴板为空，请手动粘贴二维码内容';
      });
      return;
    }
    _controller.text = text;
    setState(() {
      _isWarning = false;
      _message = null;
    });
  }

  void _submit() {
    final String raw = _controller.text.trim();
    if (raw.isEmpty) {
      setState(() {
        _isWarning = true;
        _message = '请先粘贴二维码内容或输入参数';
      });
      return;
    }
    final Map<String, dynamic> patch = VrQrParser.parse(raw);
    if (patch.isEmpty) {
      setState(() {
        _isWarning = true;
        _message = '未能识别该内容。可输入明文参数，例如 ipd=0.064&fov=60';
      });
      return;
    }
    Navigator.of(context).pop(raw);
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    return AlertDialog(
      title: const Text('导入 Cardboard 设备参数'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              '粘贴 Cardboard 二维码内容、设备参数网址，或直接输入明文参数。',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _controller,
              maxLines: 4,
              minLines: 2,
              autofocus: false,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: '二维码内容 / 参数',
                hintText: 'ipd=0.064&fov=60',
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: _pasteFromClipboard,
              icon: const Icon(Icons.content_paste_rounded),
              label: const Text('从剪贴板粘贴'),
            ),
            const SizedBox(height: 8),
            Text(
              '支持：Cardboard V1/V2、Daydream、Homido、BoboVR、VR Box 等型号，'
              '以及 ipd / fov / scale / distance / brightness / mode 参数。',
              style: theme.textTheme.bodySmall,
            ),
            if (_message != null) ...<Widget>[
              const SizedBox(height: 12),
              Text(
                _message!,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: _isWarning
                      ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                ),
              ),
            ],
          ],
        ),
      ),
      actions: <Widget>[
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _submit,
          child: const Text('导入'),
        ),
      ],
    );
  }
}

/// Stateless parser shared by the import dialog and [VRService].
class VrQrParser {
  VrQrParser._();

  /// Presets for the most common viewers. Values map directly onto VR setting
  /// keys understood by VRService so an import has an immediate visible effect.
  static const Map<String, Map<String, dynamic>> presets =
      <String, Map<String, dynamic>>{
    'cardboard v2': <String, dynamic>{
      'ipdScale': 1.0,
      'zoomScale': 1.15,
      'screenScale': 1.05,
    },
    'cardboard v1': <String, dynamic>{
      'ipdScale': 1.0,
      'zoomScale': 1.2,
      'screenScale': 1.0,
    },
    'daydream': <String, dynamic>{
      'ipdScale': 1.05,
      'zoomScale': 1.1,
      'screenScale': 1.0,
    },
    'homido': <String, dynamic>{
      'ipdScale': 1.12,
      'zoomScale': 1.0,
      'screenScale': 1.0,
    },
    'bobovr': <String, dynamic>{
      'ipdScale': 1.08,
      'zoomScale': 1.0,
      'screenScale': 1.1,
    },
    'vr box': <String, dynamic>{
      'ipdScale': 1.05,
      'zoomScale': 1.05,
      'screenScale': 1.05,
    },
  };

  /// Returns a settings patch. An empty map means the input was not usable.
  static Map<String, dynamic> parse(String raw) {
    final Map<String, dynamic> out = <String, dynamic>{};
    final String text = raw.trim();
    if (text.isEmpty) return out;

    final String lower = text.toLowerCase();

    // 1) Recognise a known viewer by name.
    for (final MapEntry<String, Map<String, dynamic>> entry
        in presets.entries) {
      if (lower.contains(entry.key)) {
        out.addAll(entry.value);
        out['viewerName'] = _labelFor(entry.key);
        break;
      }
    }

    // 2) Collect key=value pairs from the raw text, URL query and fragment.
    final Map<String, String> params = <String, String>{};
    Uri? uri;
    try {
      uri = Uri.tryParse(text);
    } catch (_) {
      uri = null;
    }
    if (uri != null) {
      try {
        uri.queryParameters.forEach((String k, String v) {
          params[k.toLowerCase()] = v;
        });
      } catch (_) {
        // Malformed percent-encoding in a pasted string: ignore query.
      }
      if (uri.fragment.isNotEmpty) {
        _collectPairs(uri.fragment, params);
      }
    }
    _collectPairs(text, params);

    _applyPlainParams(params, out);

    // 3) Official Cardboard QR payloads carry a base64 protobuf in `p=`.
    // Read the human-readable viewer name out of it; binary distortion
    // coefficients are intentionally not guessed.
    final String? p = params['p'];
    if (p != null && p.isNotEmpty) {
      final String? decoded = _tryBase64(p);
      if (decoded != null) {
        final String? name = _readableName(decoded);
        out['viewerName'] ??= name ?? 'Cardboard 自定义参数';
      }
    }

    // A pure URL with no recognisable content is not a usable import.
    if (out.isEmpty && p == null) return out;
    return out;
  }

  static void _collectPairs(String source, Map<String, String> params) {
    for (final String part in source.split(RegExp(r'[&\s,;]+'))) {
      final List<String> kv = part.split('=');
      if (kv.length != 2) continue;
      final String key = kv[0].trim().toLowerCase();
      final String value = kv[1].trim();
      if (key.isEmpty || value.isEmpty) continue;
      params[key] = value;
    }
  }

  static void _applyPlainParams(
    Map<String, String> params,
    Map<String, dynamic> out,
  ) {
    double? number(String key) => double.tryParse(params[key] ?? '');

    final double? ipd = number('ipd') ?? number('inter_lens_distance');
    if (ipd != null && ipd > 0.02 && ipd < 0.12) {
      out['ipdScale'] = (ipd / 0.064).clamp(0.5, 1.5).toDouble();
    }

    final double? fov = number('fov') ?? number('field_of_view');
    if (fov != null && fov > 20 && fov < 140) {
      out['zoomScale'] = (60.0 / fov).clamp(1.0, 2.0).toDouble();
    }

    final double? scale = number('scale') ?? number('screenscale');
    if (scale != null) {
      out['screenScale'] = scale.clamp(0.4, 3.0).toDouble();
    }

    final double? distance =
        number('distance') ?? number('dist') ?? number('screen_distance');
    if (distance != null) {
      out['screenDistanceMeters'] = distance.clamp(0.5, 5.0).toDouble();
    }

    final double? brightness = number('brightness');
    if (brightness != null) {
      out['brightness'] = brightness.clamp(0.25, 2.0).toDouble();
    }

    final String mode = (params['mode'] ?? params['viewer'] ?? '').toLowerCase();
    if (mode == 'mono' || mode == '2d') {
      out['viewerMode'] = 'MONO';
    } else if (mode == 'cardboard' || mode == 'stereo' || mode == 'vr') {
      out['viewerMode'] = 'CARDBOARD';
    }
  }

  static String? _tryBase64(String input) {
    try {
      String normalized = input.replaceAll('-', '+').replaceAll('_', '/');
      while (normalized.length % 4 != 0) {
        normalized += '=';
      }
      return utf8.decode(base64.decode(normalized), allowMalformed: true);
    } catch (_) {
      return null;
    }
  }

  static String? _readableName(String decoded) {
    final Iterable<RegExpMatch> matches =
        RegExp(r'[A-Za-z][A-Za-z0-9 ._-]{2,30}').allMatches(decoded);
    for (final RegExpMatch m in matches) {
      final String s = (m.group(0) ?? '').trim();
      if (s.length >= 3 && !s.toLowerCase().startsWith('http')) {
        return s;
      }
    }
    return null;
  }

  static String _labelFor(String key) {
    switch (key) {
      case 'cardboard v1':
        return 'Cardboard V1';
      case 'cardboard v2':
        return 'Cardboard V2';
      case 'daydream':
        return 'Daydream View';
      case 'homido':
        return 'Homido';
      case 'bobovr':
        return 'BoboVR';
      case 'vr box':
        return 'VR Box';
      default:
        return key;
    }
  }
}
