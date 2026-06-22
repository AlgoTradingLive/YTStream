import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const YTStreamApp());
}

class YTStreamApp extends StatelessWidget {
  const YTStreamApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YT Stream',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1565C0), brightness: Brightness.dark),
        useMaterial3: true,
      ),
      home: const StreamPage(),
    );
  }
}

class StreamPage extends StatefulWidget {
  const StreamPage({super.key});
  @override
  State<StreamPage> createState() => _StreamPageState();
}

class _StreamPageState extends State<StreamPage> {
  static const platform = MethodChannel('com.mango.ytstream/stream');
  final _streamKeyController = TextEditingController();
  bool _isStreaming = false;
  bool _isLoading = false;
  String _status = 'Ready';
  String _rtmpUrl = 'rtmps://a.rtmps.youtube.com/live2';
  String _audioMode = 'internal';
  String _orientation = 'landscape'; // landscape or portrait

  @override
  void initState() {
    super.initState();
    _loadSaved();
    platform.setMethodCallHandler(_handleNativeCallback);
  }

  Future<void> _loadSaved() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _streamKeyController.text = prefs.getString('stream_key') ?? '';
      _rtmpUrl = prefs.getString('rtmp_url') ?? 'rtmps://a.rtmps.youtube.com/live2';
      _audioMode = prefs.getString('audio_mode') ?? 'internal';
      _orientation = prefs.getString('orientation') ?? 'landscape';
    });
  }

  Future<void> _savePref() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('stream_key', _streamKeyController.text.trim());
    await prefs.setString('rtmp_url', _rtmpUrl);
    await prefs.setString('audio_mode', _audioMode);
    await prefs.setString('orientation', _orientation);
  }

  Future<dynamic> _handleNativeCallback(MethodCall call) async {
    switch (call.method) {
      case 'onStreamStarted':
        setState(() { _isStreaming = true; _isLoading = false; _status = '🔴 LIVE - Streaming...'; });
        break;
      case 'onStreamError':
        setState(() { _isStreaming = false; _isLoading = false; _status = '❌ ${call.arguments}'; });
        break;
      case 'onStreamStopped':
        setState(() { _isStreaming = false; _isLoading = false; _status = 'Stopped'; });
        break;
      case 'onBitrateUpdate':
        if (_isStreaming) setState(() { _status = '🔴 LIVE — ${call.arguments} kbps'; });
        break;
    }
  }

  Future<void> _startStream() async {
    final key = _streamKeyController.text.trim();
    if (key.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Stream Key enter karo!'), backgroundColor: Colors.red));
      return;
    }
    await _savePref();
    setState(() { _isLoading = true; _status = 'Starting...'; });
    try {
      await platform.invokeMethod('startStream', {
        'rtmpUrl': _rtmpUrl,
        'streamKey': key,
        'audioMode': _audioMode,
        'orientation': _orientation,
      });
    } on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = '❌ ${e.message}'; });
    }
  }

  Future<void> _stopStream() async {
    setState(() { _isLoading = true; _status = 'Stopping...'; });
    try { await platform.invokeMethod('stopStream'); }
    on PlatformException catch (e) { setState(() { _isLoading = false; _status = '❌ ${e.message}'; }); }
  }

  void _showRtmpDialog() {
    final c = TextEditingController(text: _rtmpUrl);
    showDialog(context: context, builder: (ctx) => AlertDialog(
      title: const Text('RTMP URL'),
      content: TextField(controller: c),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        TextButton(onPressed: () { setState(() => _rtmpUrl = c.text.trim()); Navigator.pop(ctx); }, child: const Text('Save')),
      ],
    ));
  }

  Widget _modeBtn(String mode, String emoji, String title, String sub) {
    final selected = _audioMode == mode;
    return Expanded(
      child: GestureDetector(
        onTap: _isStreaming ? null : () => setState(() => _audioMode = mode),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFF1565C0) : const Color(0xFF161B22),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: selected ? const Color(0xFF1565C0) : Colors.white12),
          ),
          child: Column(children: [
            Text(emoji, style: const TextStyle(fontSize: 22)),
            const SizedBox(height: 4),
            Text(title, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500)),
            Text(sub, style: const TextStyle(color: Colors.white54, fontSize: 10)),
          ]),
        ),
      ),
    );
  }

  Widget _orientBtn(String mode, String emoji, String title) {
    final selected = _orientation == mode;
    return Expanded(
      child: GestureDetector(
        onTap: _isStreaming ? null : () => setState(() => _orientation = mode),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFF0D6B3C) : const Color(0xFF161B22),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: selected ? Colors.green : Colors.white12),
          ),
          child: Column(children: [
            Text(emoji, style: const TextStyle(fontSize: 22)),
            const SizedBox(height: 4),
            Text(title, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w500)),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      appBar: AppBar(
        backgroundColor: const Color(0xFF161B22),
        title: const Text('YT Stream', style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        actions: [IconButton(icon: const Icon(Icons.settings, color: Colors.white70), onPressed: _showRtmpDialog)],
      ),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: _isStreaming ? const Color(0xFF1A0000) : const Color(0xFF161B22),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: _isStreaming ? Colors.red : Colors.white12),
              ),
              child: Row(children: [
                if (_isLoading) const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                else Icon(_isStreaming ? Icons.circle : Icons.circle_outlined, color: _isStreaming ? Colors.red : Colors.white38, size: 16),
                const SizedBox(width: 12),
                Expanded(child: Text(_status, style: TextStyle(color: _isStreaming ? Colors.red[300] : Colors.white70, fontSize: 13))),
              ]),
            ),
            const SizedBox(height: 18),

            // Stream Key
            const Text('YouTube Stream Key', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 6),
            TextField(
              controller: _streamKeyController,
              obscureText: true,
              enabled: !_isStreaming,
              style: const TextStyle(color: Colors.white),
              decoration: InputDecoration(
                hintText: 'xxxx-xxxx-xxxx-xxxx-xxxx',
                hintStyle: const TextStyle(color: Colors.white24),
                filled: true, fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Colors.white12)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Colors.white12)),
                focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Color(0xFF1565C0))),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.paste, color: Colors.white38),
                  onPressed: _isStreaming ? null : () async {
                    final d = await Clipboard.getData('text/plain');
                    if (d?.text != null) _streamKeyController.text = d!.text!.trim();
                  },
                ),
              ),
            ),
            const SizedBox(height: 18),

            // Audio Mode
            const Text('Audio', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 8),
            Row(children: [
              _modeBtn('internal', '🔊', 'Only Internal', 'Game/App sound'),
              const SizedBox(width: 10),
              _modeBtn('mic_internal', '🎤', 'Mic + Internal', 'Commentary + Sound'),
            ]),
            const SizedBox(height: 16),

            // Orientation
            const Text('Orientation', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 8),
            Row(children: [
              _orientBtn('landscape', '🖥️', 'Landscape'),
              const SizedBox(width: 10),
              _orientBtn('portrait', '📱', 'Portrait (Shorts)'),
            ]),

            const Spacer(),

            // Start/Stop
            SizedBox(
              height: 56,
              child: ElevatedButton(
                onPressed: _isLoading ? null : (_isStreaming ? _stopStream : _startStream),
                style: ElevatedButton.styleFrom(
                  backgroundColor: _isStreaming ? Colors.red[800] : const Color(0xFF1565C0),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                ),
                child: Text(
                  _isStreaming ? '⏹  STOP STREAM' : '▶  START STREAM',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, letterSpacing: 1),
                ),
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() { _streamKeyController.dispose(); super.dispose(); }
}
