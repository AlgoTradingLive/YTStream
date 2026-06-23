import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';
import 'package:image_picker/image_picker.dart';

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
  String _orientation = 'landscape';
  String _voiceMode = 'normal';

  // Overlay state
  String _overlayText = '';
  String? _overlayImagePath;
  double _textX = 0.05;
  double _textY = 0.05;
  double _imageX = 0.7;
  double _imageY = 0.05;
  bool _showOverlayPanel = false;

  final _overlayTextController = TextEditingController();

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
      _voiceMode = prefs.getString('voice_mode') ?? 'normal';
      _overlayText = prefs.getString('overlay_text') ?? '';
      _overlayImagePath = prefs.getString('overlay_image_path');
      _textX = prefs.getDouble('text_x') ?? 0.05;
      _textY = prefs.getDouble('text_y') ?? 0.05;
      _imageX = prefs.getDouble('image_x') ?? 0.7;
      _imageY = prefs.getDouble('image_y') ?? 0.05;
      _overlayTextController.text = _overlayText;
    });
  }

  Future<void> _savePref() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('stream_key', _streamKeyController.text.trim());
    await prefs.setString('rtmp_url', _rtmpUrl);
    await prefs.setString('audio_mode', _audioMode);
    await prefs.setString('orientation', _orientation);
    await prefs.setString('voice_mode', _voiceMode);
    await prefs.setString('overlay_text', _overlayText);
    if (_overlayImagePath != null) await prefs.setString('overlay_image_path', _overlayImagePath!);
    await prefs.setDouble('text_x', _textX);
    await prefs.setDouble('text_y', _textY);
    await prefs.setDouble('image_x', _imageX);
    await prefs.setDouble('image_y', _imageY);
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
        'voiceMode': _voiceMode,
        'overlayText': _overlayText,
        'overlayImagePath': _overlayImagePath ?? '',
        'textX': _textX,
        'textY': _textY,
        'imageX': _imageX,
        'imageY': _imageY,
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

  Future<void> _pickImage() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(source: ImageSource.gallery);
    if (picked != null) {
      setState(() => _overlayImagePath = picked.path);
      await _savePref();
      // Stream चालू असेल तर live update
      if (_isStreaming) {
        platform.invokeMethod('updateOverlay', {
          'overlayText': _overlayText,
          'overlayImagePath': _overlayImagePath ?? '',
          'textX': _textX, 'textY': _textY,
          'imageX': _imageX, 'imageY': _imageY,
        });
      }
    }
  }

  void _updateOverlayText(String text) {
    setState(() => _overlayText = text);
    _savePref();
    if (_isStreaming) {
      platform.invokeMethod('updateOverlay', {
        'overlayText': text,
        'overlayImagePath': _overlayImagePath ?? '',
        'textX': _textX, 'textY': _textY,
        'imageX': _imageX, 'imageY': _imageY,
      });
    }
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

  Widget _voiceBtn(String mode, String emoji, String title) {
    final selected = _voiceMode == mode;
    return Expanded(
      child: GestureDetector(
        onTap: () {
          setState(() => _voiceMode = mode);
          if (_isStreaming) platform.invokeMethod('setVoiceMode', {'voiceMode': mode});
        },
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFF6A1B9A) : const Color(0xFF161B22),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: selected ? Colors.purple : Colors.white12),
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

  // Overlay preview — user drag करून position ठरवेल
  Widget _overlayPreview() {
    return Container(
      height: 200,
      decoration: BoxDecoration(
        color: const Color(0xFF0A0A0A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.white12),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: Stack(
          children: [
            // Background hint
            const Center(
              child: Text('Screen Preview\n(Drag items to position)', textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white12, fontSize: 12)),
            ),

            // Text overlay — draggable
            if (_overlayText.isNotEmpty)
              _DraggableOverlayItem(
                x: _textX, y: _textY,
                onPositionChanged: (x, y) {
                  setState(() { _textX = x; _textY = y; });
                  _savePref();
                  if (_isStreaming) {
                    platform.invokeMethod('updateOverlay', {
                      'overlayText': _overlayText,
                      'overlayImagePath': _overlayImagePath ?? '',
                      'textX': _textX, 'textY': _textY,
                      'imageX': _imageX, 'imageY': _imageY,
                    });
                  }
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.black54,
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(_overlayText,
                    style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold)),
                ),
              ),

            // Image overlay — draggable
            if (_overlayImagePath != null)
              _DraggableOverlayItem(
                x: _imageX, y: _imageY,
                onPositionChanged: (x, y) {
                  setState(() { _imageX = x; _imageY = y; });
                  _savePref();
                  if (_isStreaming) {
                    platform.invokeMethod('updateOverlay', {
                      'overlayText': _overlayText,
                      'overlayImagePath': _overlayImagePath ?? '',
                      'textX': _textX, 'textY': _textY,
                      'imageX': _imageX, 'imageY': _imageY,
                    });
                  }
                },
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: Image.file(File(_overlayImagePath!), width: 50, height: 50, fit: BoxFit.contain),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _overlayPanel() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF161B22),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.white12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Text input
          const Text('Overlay Text', style: TextStyle(color: Colors.white70, fontSize: 13)),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _overlayTextController,
                style: const TextStyle(color: Colors.white, fontSize: 13),
                decoration: InputDecoration(
                  hintText: 'Channel name, watermark...',
                  hintStyle: const TextStyle(color: Colors.white24),
                  filled: true, fillColor: const Color(0xFF0D1117),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: Colors.white12)),
                  enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: Colors.white12)),
                ),
                onChanged: _updateOverlayText,
              ),
            ),
            if (_overlayText.isNotEmpty)
              IconButton(
                icon: const Icon(Icons.clear, color: Colors.white38, size: 20),
                onPressed: () {
                  _overlayTextController.clear();
                  _updateOverlayText('');
                },
              ),
          ]),

          const SizedBox(height: 12),

          // Image picker
          const Text('Overlay Image (Logo/Watermark)', style: TextStyle(color: Colors.white70, fontSize: 13)),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(
              child: GestureDetector(
                onTap: _pickImage,
                child: Container(
                  height: 44,
                  decoration: BoxDecoration(
                    color: const Color(0xFF0D1117),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.white12),
                  ),
                  child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    const Icon(Icons.image, color: Colors.white38, size: 18),
                    const SizedBox(width: 8),
                    Text(
                      _overlayImagePath != null ? _overlayImagePath!.split('/').last : 'Gallery मधून Image निवडा',
                      style: TextStyle(
                        color: _overlayImagePath != null ? Colors.white70 : Colors.white38,
                        fontSize: 12,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ]),
                ),
              ),
            ),
            if (_overlayImagePath != null)
              IconButton(
                icon: const Icon(Icons.clear, color: Colors.white38, size: 20),
                onPressed: () {
                  setState(() => _overlayImagePath = null);
                  _savePref();
                  if (_isStreaming) {
                    platform.invokeMethod('updateOverlay', {
                      'overlayText': _overlayText,
                      'overlayImagePath': '',
                      'textX': _textX, 'textY': _textY,
                      'imageX': _imageX, 'imageY': _imageY,
                    });
                  }
                },
              ),
          ]),

          // Preview
          if (_overlayText.isNotEmpty || _overlayImagePath != null) ...[
            const SizedBox(height: 12),
            const Text('Position (Drag करा)', style: TextStyle(color: Colors.white70, fontSize: 13)),
            const SizedBox(height: 6),
            _overlayPreview(),
          ],
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
        actions: [IconButton(icon: const Icon(Icons.settings, color: Colors.white70), onPressed: _showRtmpDialog)],
      ),
      body: SingleChildScrollView(
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

            // Voice Changer
            if (_audioMode == 'mic_internal') ...[
              const SizedBox(height: 16),
              const Text('Voice Changer', style: TextStyle(color: Colors.white70, fontSize: 13)),
              const SizedBox(height: 8),
              Row(children: [
                _voiceBtn('normal', '🎙️', 'Normal'),
                const SizedBox(width: 8),
                _voiceBtn('girl', '👧', 'Girl'),
                const SizedBox(width: 8),
                _voiceBtn('boy', '👦', 'Boy'),
              ]),
            ],

            const SizedBox(height: 16),

            // Overlay Section
            GestureDetector(
              onTap: () => setState(() => _showOverlayPanel = !_showOverlayPanel),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                decoration: BoxDecoration(
                  color: const Color(0xFF161B22),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: Colors.white12),
                ),
                child: Row(children: [
                  const Icon(Icons.layers, color: Colors.white54, size: 18),
                  const SizedBox(width: 10),
                  const Expanded(child: Text('Overlay (Text / Image)', style: TextStyle(color: Colors.white70, fontSize: 13))),
                  if (_overlayText.isNotEmpty || _overlayImagePath != null)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                      decoration: BoxDecoration(color: Colors.green[800], borderRadius: BorderRadius.circular(10)),
                      child: const Text('ON', style: TextStyle(color: Colors.white, fontSize: 10)),
                    ),
                  const SizedBox(width: 8),
                  Icon(_showOverlayPanel ? Icons.expand_less : Icons.expand_more, color: Colors.white38),
                ]),
              ),
            ),

            if (_showOverlayPanel) ...[
              const SizedBox(height: 8),
              _overlayPanel(),
            ],

            const SizedBox(height: 24),

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
  void dispose() {
    _streamKeyController.dispose();
    _overlayTextController.dispose();
    super.dispose();
  }
}

// Draggable overlay item widget
class _DraggableOverlayItem extends StatelessWidget {
  final double x, y;
  final Widget child;
  final void Function(double x, double y) onPositionChanged;

  const _DraggableOverlayItem({
    required this.x, required this.y,
    required this.child,
    required this.onPositionChanged,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (ctx, constraints) {
      return Stack(children: [
        Positioned(
          left: x * constraints.maxWidth,
          top: y * constraints.maxHeight,
          child: GestureDetector(
            onPanUpdate: (details) {
              double newX = (x + details.delta.dx / constraints.maxWidth).clamp(0.0, 0.9);
              double newY = (y + details.delta.dy / constraints.maxHeight).clamp(0.0, 0.9);
              onPositionChanged(newX, newY);
            },
            child: child,
          ),
        ),
      ]);
    });
  }
}
