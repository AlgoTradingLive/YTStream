import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const YTStreamApp());
}

class YTStreamApp extends StatefulWidget {
  const YTStreamApp({super.key});
  static _YTStreamAppState? of(BuildContext context) =>
      context.findAncestorStateOfType<_YTStreamAppState>();
  @override
  State<YTStreamApp> createState() => _YTStreamAppState();
}

class _YTStreamAppState extends State<YTStreamApp> {
  bool isDark = true;
  void toggleTheme() => setState(() => isDark = !isDark);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YT Stream',
      debugShowCheckedModeBanner: false,
      theme: isDark ? _darkTheme() : _lightTheme(),
      home: StreamPage(isDark: isDark),
    );
  }

  ThemeData _darkTheme() => ThemeData(
    brightness: Brightness.dark,
    scaffoldBackgroundColor: const Color(0xFF0D0D0D),
    colorScheme: const ColorScheme.dark(
      primary: Color(0xFFFF1A1A),
      surface: Color(0xFF161B22),
    ),
    useMaterial3: true,
  );

  ThemeData _lightTheme() => ThemeData(
    brightness: Brightness.light,
    scaffoldBackgroundColor: const Color(0xFFF5F5F5),
    colorScheme: const ColorScheme.light(
      primary: Color(0xFFFF1A1A),
      surface: Colors.white,
    ),
    useMaterial3: true,
  );
}

class StreamPage extends StatefulWidget {
  final bool isDark;
  const StreamPage({super.key, required this.isDark});
  @override
  State<StreamPage> createState() => _StreamPageState();
}

class _StreamPageState extends State<StreamPage> {
  static const platform = MethodChannel('com.mango.ytstream/stream');
  final _keyCtrl = TextEditingController();
  bool _isStreaming = false;
  bool _isLoading = false;
  String _status = 'Ready';
  String _rtmpUrl = 'rtmps://a.rtmps.youtube.com/live2';
  String _audioMode = 'internal';
  String _orientation = 'landscape';
  bool _cameraEnabled = false;
String _cameraFacing = 'back'; // back or front
String _cameraMode = 'pip'; // pip or split

  // Colors based on theme
  Color get bg => widget.isDark ? const Color(0xFF0D0D0D) : const Color(0xFFF5F5F5);
  Color get card => widget.isDark ? const Color(0xFF161B22) : Colors.white;
  Color get text => widget.isDark ? Colors.white : const Color(0xFF222222);
  Color get subtext => widget.isDark ? Colors.white54 : const Color(0xFF888888);
  Color get border => widget.isDark ? Colors.white12 : const Color(0xFFE0E0E0);
  Color get red => const Color(0xFFFF1A1A);
  Color get green => const Color(0xFF22C55E);

  @override
  void initState() {
    super.initState();
    _loadSaved();
    platform.setMethodCallHandler(_handleCallback);
  }

  Future<void> _loadSaved() async {
    final p = await SharedPreferences.getInstance();
    setState(() {
      _keyCtrl.text = p.getString('stream_key') ?? '';
      _rtmpUrl = p.getString('rtmp_url') ?? 'rtmps://a.rtmps.youtube.com/live2';
      _audioMode = p.getString('audio_mode') ?? 'internal';
      _orientation = p.getString('orientation') ?? 'landscape';
      _cameraEnabled = p.getBool('camera_enabled') ?? false;
_cameraFacing = p.getString('camera_facing') ?? 'back';
_cameraMode = p.getString('camera_mode') ?? 'pip';
    });
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString('stream_key', _keyCtrl.text.trim());
    await p.setString('rtmp_url', _rtmpUrl);
    await p.setString('audio_mode', _audioMode);
    await p.setString('orientation', _orientation);
    await p.setBool('camera_enabled', _cameraEnabled);
await p.setString('camera_facing', _cameraFacing);
await p.setString('camera_mode', _cameraMode);
  }

  Future<dynamic> _handleCallback(MethodCall call) async {
    switch (call.method) {
      case 'onStreamStarted':
        setState(() { _isStreaming = true; _isLoading = false; _status = 'LIVE'; });
        break;
      case 'onStreamError':
        setState(() { _isStreaming = false; _isLoading = false; _status = call.arguments ?? 'Error'; });
        break;
      case 'onStreamStopped':
        setState(() { _isStreaming = false; _isLoading = false; _status = 'Ready'; });
        break;
      case 'onBitrateUpdate':
        if (_isStreaming) setState(() { _status = '${call.arguments} kbps'; });
        break;
    }
  }

  Future<void> _start() async {
    final key = _keyCtrl.text.trim();
    if (key.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: const Text('Stream Key enter karo!'), backgroundColor: red),
        'cameraEnabled': _cameraEnabled,
'cameraFacing': _cameraFacing,
'cameraMode': _cameraMode,
      );
      return;
    }
    await _save();
    setState(() { _isLoading = true; _status = 'Starting...'; });
    try {
      await platform.invokeMethod('startStream', {
        'rtmpUrl': _rtmpUrl, 'streamKey': key,
        'audioMode': _audioMode, 'orientation': _orientation,
      });
    } on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = e.message ?? 'Error'; });
    }
  }

  Future<void> _stop() async {
    setState(() { _isLoading = true; _status = 'Stopping...'; });
    try { await platform.invokeMethod('stopStream'); }
    on PlatformException catch (e) { setState(() { _isLoading = false; _status = e.message ?? 'Error'; }); }
  }

  void _showRtmpDialog() {
    final c = TextEditingController(text: _rtmpUrl);
    showDialog(context: context, builder: (ctx) => AlertDialog(
      backgroundColor: card,
      title: Text('RTMP URL', style: TextStyle(color: text)),
      content: TextField(controller: c, style: TextStyle(color: text),
        decoration: InputDecoration(enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: border)))),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: Text('Cancel', style: TextStyle(color: subtext))),
        TextButton(onPressed: () { setState(() => _rtmpUrl = c.text.trim()); Navigator.pop(ctx); },
          child: Text('Save', style: TextStyle(color: red))),
      ],
    ));
  }

  Widget _card({required Widget child, Color? color, EdgeInsets? padding}) {
    return Container(
      padding: padding ?? const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color ?? card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: border),
        boxShadow: widget.isDark ? [] : [BoxShadow(color: Colors.black.withOpacity(0.06), blurRadius: 8, offset: const Offset(0, 2))],
      ),
      child: child,
    );
  }

  Widget _selectBtn(String value, String current, String emoji, String title, String sub, Function(String) onTap) {
    final sel = value == current;
    return Expanded(
      child: GestureDetector(
        onTap: _isStreaming ? null : () => setState(() => onTap(value)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: BoxDecoration(
            color: sel ? red : card,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: sel ? red : border, width: sel ? 0 : 1),
          ),
          child: Column(children: [
            Text(emoji, style: const TextStyle(fontSize: 22)),
            const SizedBox(height: 4),
            Text(title, style: TextStyle(color: sel ? Colors.white : text, fontSize: 12, fontWeight: FontWeight.w600)),
            Text(sub, style: TextStyle(color: sel ? Colors.white70 : subtext, fontSize: 10)),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isDark = widget.isDark;

    return Scaffold(
      backgroundColor: bg,
      appBar: AppBar(
        backgroundColor: isDark ? const Color(0xFF0D0D0D) : Colors.white,
        elevation: 0,
        titleSpacing: 16,
        title: Row(children: [
          Image.asset('assets/logo.png', width: 38, height: 38),
          const SizedBox(width: 8),
          RichText(text: TextSpan(children: [
            TextSpan(text: 'Yt', style: TextStyle(color: red, fontSize: 20, fontWeight: FontWeight.bold)),
            TextSpan(text: 'Stream', style: TextStyle(color: text, fontSize: 20, fontWeight: FontWeight.bold)),
          ])),
        ]),
        actions: [
          // Theme toggle
          GestureDetector(
            onTap: () => YTStreamApp.of(context)?.toggleTheme(),
            child: Container(
              margin: const EdgeInsets.only(right: 8),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: isDark ? Colors.white12 : Colors.black12,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(isDark ? '☀️' : '🌙', style: const TextStyle(fontSize: 16)),
            ),
          ),
          IconButton(
            icon: Icon(Icons.settings_outlined, color: isDark ? Colors.white54 : Colors.black54),
            onPressed: _showRtmpDialog,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status card
            _card(
              color: _isStreaming
                  ? (isDark ? const Color(0xFF1A0000) : const Color(0xFFFFF0F0))
                  : card,
              child: Row(children: [
                Container(
                  width: 10, height: 10,
                  decoration: BoxDecoration(
                    color: _isStreaming ? red : green,
                    shape: BoxShape.circle,
                    boxShadow: _isStreaming ? [BoxShadow(color: red.withOpacity(0.5), blurRadius: 6)] : [],
                  ),
                ),
                const SizedBox(width: 10),
                if (_isLoading) ...[
                  SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: red)),
                  const SizedBox(width: 8),
                ],
                Text(_isStreaming ? '🔴  $_status' : _status,
                  style: TextStyle(color: _isStreaming ? red : text, fontSize: 14, fontWeight: FontWeight.w500)),
              ]),
            ),
            const SizedBox(height: 14),

            // Stream Key
            Text('YouTube Stream Key', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
            const SizedBox(height: 6),
            _card(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    controller: _keyCtrl,
                    obscureText: true,
                    enabled: !_isStreaming,
                    style: TextStyle(color: text, fontSize: 14),
                    decoration: InputDecoration(
                      border: InputBorder.none,
                      hintText: '••••-••••-••••-••••-••••',
                      hintStyle: TextStyle(color: border, fontSize: 14),
                    ),
                  ),
                ),
                IconButton(
                  icon: Icon(Icons.content_paste_rounded, color: subtext, size: 20),
                  onPressed: _isStreaming ? null : () async {
                    final d = await Clipboard.getData('text/plain');
                    if (d?.text != null) _keyCtrl.text = d!.text!.trim();
                  },
                ),
              ]),
            ),
            const SizedBox(height: 16),

            // Audio
            Text('Audio', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
            const SizedBox(height: 6),
            Row(children: [
              _selectBtn('internal', _audioMode, '🔊', 'Only Internal', 'Game/App sound',
                  (v) => _audioMode = v),
              const SizedBox(width: 10),
              _selectBtn('mic_internal', _audioMode, '🎤', 'Mic + Internal', 'Commentary + Sound',
                  (v) => _audioMode = v),
            ]),
            const SizedBox(height: 16),

            // Orientation
            Text('Orientation', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
            const SizedBox(height: 6),
            Row(children: [
              _selectBtn('landscape', _orientation, '🖥️', 'Landscape', '16:9',
                  (v) => _orientation = v),
              const SizedBox(width: 10),
              _selectBtn('portrait', _orientation, '📱', 'Portrait (Shorts)', '9:16',
                  (v) => _orientation = v),
            ]),
            const SizedBox(height: 24),
const SizedBox(height: 16),

// Camera Section
_card(
  child: Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(children: [
            Icon(Icons.videocam_rounded, color: _cameraEnabled ? red : subtext, size: 20),
            const SizedBox(width: 8),
            Text('Camera', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
          ]),
          Switch(
            value: _cameraEnabled,
            onChanged: _isStreaming ? null : (v) => setState(() => _cameraEnabled = v),
            activeColor: red,
          ),
        ],
      ),

      if (_cameraEnabled) ...[
        const SizedBox(height: 12),

        // Camera Mode
        Text('Mode', style: TextStyle(color: subtext, fontSize: 12)),
        const SizedBox(height: 6),
        Row(children: [
          _selectBtn('pip', _cameraMode, '🎮', 'PIP', 'Corner overlay',
              (v) => _cameraMode = v),
          const SizedBox(width: 10),
          _selectBtn('split', _cameraMode, '📱', 'Split', '70/30 vertical',
              (v) => _cameraMode = v),
        ]),
        const SizedBox(height: 12),

        // Front/Back
        Text('Camera', style: TextStyle(color: subtext, fontSize: 12)),
        const SizedBox(height: 6),
        Row(children: [
          _selectBtn('back', _cameraFacing, '📷', 'Back', 'Main camera',
              (v) => _cameraFacing = v),
          const SizedBox(width: 10),
          _selectBtn('front', _cameraFacing, '🤳', 'Front', 'Selfie camera',
              (v) => _cameraFacing = v),
        ]),
      ],
    ],
  ),
),
            // Start/Stop Button
            SizedBox(
              height: 54,
              child: ElevatedButton(
                onPressed: _isLoading ? null : (_isStreaming ? _stop : _start),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isStreaming ? const Color(0xFF8B0000) : red,
                  foregroundColor: Colors.white,
                  elevation: 0,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(_isStreaming ? '⏹' : '▶', style: const TextStyle(fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(
                      _isStreaming ? 'STOP STREAM' : 'START STREAM',
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 1.5),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() { _keyCtrl.dispose(); super.dispose(); }
}
