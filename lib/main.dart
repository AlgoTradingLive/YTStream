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
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1565C0),
          brightness: Brightness.dark,
        ),
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

  @override
  void initState() {
    super.initState();
    _loadSavedKey();
    platform.setMethodCallHandler(_handleNativeCallback);
  }

  Future<void> _loadSavedKey() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString('stream_key') ?? '';
    final savedUrl = prefs.getString('rtmp_url') ?? 'rtmps://a.rtmps.youtube.com/live2';
    setState(() {
      _streamKeyController.text = saved;
      _rtmpUrl = savedUrl;
    });
  }

  Future<void> _saveKey(String key) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('stream_key', key);
    await prefs.setString('rtmp_url', _rtmpUrl);
  }

  Future<dynamic> _handleNativeCallback(MethodCall call) async {
    switch (call.method) {
      case 'onStreamStarted':
        setState(() {
          _isStreaming = true;
          _isLoading = false;
          _status = '🔴 LIVE - Streaming...';
        });
        break;
      case 'onStreamError':
        setState(() {
          _isStreaming = false;
          _isLoading = false;
          _status = '❌ ${call.arguments}';
        });
        break;
      case 'onStreamStopped':
        setState(() {
          _isStreaming = false;
          _isLoading = false;
          _status = 'Stopped';
        });
        break;
      case 'onBitrateUpdate':
        if (_isStreaming) {
          setState(() {
            _status = '🔴 LIVE — ${call.arguments} kbps';
          });
        }
        break;
    }
  }

  Future<void> _startStream() async {
    final key = _streamKeyController.text.trim();
    if (key.isEmpty) {
      _showError('Stream Key enter karo!');
      return;
    }
    await _saveKey(key);
    setState(() { _isLoading = true; _status = 'Starting...'; });
    try {
      await platform.invokeMethod('startStream', {
        'rtmpUrl': _rtmpUrl,
        'streamKey': key,
      });
    } on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = '❌ ${e.message}'; });
    }
  }

  Future<void> _stopStream() async {
    setState(() { _isLoading = true; _status = 'Stopping...'; });
    try {
      await platform.invokeMethod('stopStream');
    } on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = '❌ ${e.message}'; });
    }
  }

  void _showError(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), backgroundColor: Colors.red),
    );
  }

  void _showRtmpDialog() {
    final controller = TextEditingController(text: _rtmpUrl);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('RTMP/RTMPS URL'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            hintText: 'rtmps://a.rtmps.youtube.com/live2',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          TextButton(
            onPressed: () {
              setState(() => _rtmpUrl = controller.text.trim());
              Navigator.pop(ctx);
            },
            child: const Text('Save'),
          ),
        ],
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
        actions: [
          IconButton(
            icon: const Icon(Icons.settings, color: Colors.white70),
            onPressed: _showRtmpDialog,
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: _isStreaming ? const Color(0xFF1A0000) : const Color(0xFF161B22),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: _isStreaming ? Colors.red : Colors.white12),
              ),
              child: Row(
                children: [
                  if (_isLoading)
                    const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  else
                    Icon(_isStreaming ? Icons.circle : Icons.circle_outlined,
                        color: _isStreaming ? Colors.red : Colors.white38, size: 16),
                  const SizedBox(width: 12),
                  Expanded(child: Text(_status,
                      style: TextStyle(color: _isStreaming ? Colors.red[300] : Colors.white70, fontSize: 13))),
                ],
              ),
            ),
            const SizedBox(height: 32),
            const Text('YouTube Stream Key', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 8),
            TextField(
              controller: _streamKeyController,
              obscureText: true,
              enabled: !_isStreaming,
              style: const TextStyle(color: Colors.white),
              decoration: InputDecoration(
                hintText: 'xxxx-xxxx-xxxx-xxxx-xxxx',
                hintStyle: const TextStyle(color: Colors.white24),
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Colors.white12)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Colors.white12)),
                focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Color(0xFF1565C0))),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.paste, color: Colors.white38),
                  onPressed: _isStreaming ? null : () async {
                    final data = await Clipboard.getData('text/plain');
                    if (data?.text != null) _streamKeyController.text = data!.text!.trim();
                  },
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text('URL: $_rtmpUrl', style: const TextStyle(color: Colors.white24, fontSize: 11)),
            const SizedBox(height: 24),
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: const Color(0xFF0D2137),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: Colors.blue.withOpacity(0.3)),
              ),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('ℹ️  Screen + Internal Audio → YT Live', style: TextStyle(color: Colors.lightBlue, fontSize: 13)),
                  SizedBox(height: 6),
                  Text('Stream Key: YouTube Studio → Go Live → Stream tab', style: TextStyle(color: Colors.white38, fontSize: 12)),
                ],
              ),
            ),
            const Spacer(),
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
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _streamKeyController.dispose();
    super.dispose();
  }
}
