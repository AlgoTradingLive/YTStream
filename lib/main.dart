import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:image_picker/image_picker.dart';

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
  String _cameraFacing = 'back';
  String _cameraMode = 'pip';
  int _bitrate = 2000; // kbps

  // Overlay
  String _overlayText = '';
  String _overlayImagePath = '';
  String _tickerText = '';
  bool _showOverlayPanel = false;
  final _overlayTextCtrl = TextEditingController();
  final _tickerCtrl = TextEditingController();

  // Stream timer
  Timer? _timer;
  int _seconds = 0;

  // Live viewer count
  int _viewerCount = 0;
  Timer? _viewerTimer;
  final _ytApiKeyCtrl = TextEditingController();
  final _videoIdCtrl = TextEditingController();
  String _ytApiKey = '';
  String _videoId = '';

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
      _bitrate = p.getInt('bitrate') ?? 2000;
      _ytApiKey = p.getString('yt_api_key') ?? '';
      _videoId = p.getString('video_id') ?? '';
      _ytApiKeyCtrl.text = _ytApiKey;
      _videoIdCtrl.text = _videoId;
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
    await p.setInt('bitrate', _bitrate);
    await p.setString('yt_api_key', _ytApiKey);
    await p.setString('video_id', _videoId);
  }

  Future<dynamic> _handleCallback(MethodCall call) async {
    switch (call.method) {
      case 'onStreamStarted':
        setState(() { _isStreaming = true; _isLoading = false; _status = 'LIVE'; });
        _startTimer();
        _startViewerFetch();
        break;
      case 'onStreamError':
        final msg = call.arguments ?? 'Error';
        setState(() { _isLoading = false; _status = msg; });
        // Pause/resume messages → streaming state बदलू नको
        if (msg != '⏸ Paused' && msg != '🔇 Muted' && !msg.toString().startsWith('📷')) {
          setState(() => _isStreaming = false);
          _stopTimer();
          _stopViewerFetch();
        }
        break;
      case 'onStreamStopped':
        setState(() { _isStreaming = false; _isLoading = false; _status = 'Ready'; });
        _stopTimer();
        _stopViewerFetch();
        break;
      case 'onBitrateUpdate':
        if (_isStreaming) setState(() => _status = '${call.arguments} kbps');
        break;
    }
  }

  // ── Timer ────────────────────────────────────────────────────────────────
  void _startTimer() {
    _seconds = 0;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      setState(() => _seconds++);
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
    _seconds = 0;
  }

  String get _timerStr {
    final h = _seconds ~/ 3600;
    final m = (_seconds % 3600) ~/ 60;
    final s = _seconds % 60;
    if (h > 0) return '${h.toString().padLeft(2,'0')}:${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
    return '${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
  }

  // ── Viewer Count (YouTube Data API v3) ──────────────────────────────────
  void _startViewerFetch() {
    if (_ytApiKey.isEmpty || _videoId.isEmpty) return;
    _fetchViewers();
    _viewerTimer = Timer.periodic(const Duration(seconds: 30), (_) => _fetchViewers());
  }

  void _stopViewerFetch() {
    _viewerTimer?.cancel();
    _viewerTimer = null;
    setState(() => _viewerCount = 0);
  }

  Future<void> _fetchViewers() async {
    try {
      final url = 'https://www.googleapis.com/youtube/v3/videos'
          '?part=liveStreamingDetails&id=$_videoId&key=$_ytApiKey';
      final req = await HttpClient().getUrl(Uri.parse(url));
      final res = await req.close();
      final body = await res.transform(const SystemEncoding().decoder).join();
      // concurrent viewers parse करणे
      final match = RegExp(r'"concurrentViewers":"(\d+)"').firstMatch(body);
      if (match != null) {
        setState(() => _viewerCount = int.tryParse(match.group(1)!) ?? 0);
      }
    } catch (_) {}
  }

  // ── Overlay ──────────────────────────────────────────────────────────────
  void _sendOverlay() {
    platform.invokeMethod('updateOverlay', {
      'overlayText': _overlayText,
      'overlayImagePath': _overlayImagePath,
      'textX': 0.05, 'textY': 0.05,
      'imageX': 0.7, 'imageY': 0.05,
    });
  }

  Future<void> _pickImage() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(source: ImageSource.gallery);
    if (picked != null) {
      setState(() => _overlayImagePath = picked.path);
      if (_isStreaming) _sendOverlay();
    }
  }

  // ── Start / Stop ─────────────────────────────────────────────────────────
  Future<void> _start() async {
    final key = _keyCtrl.text.trim();
    if (key.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: const Text('Enter Stream Key!'), backgroundColor: red),
      );
      return;
    }
    _ytApiKey = _ytApiKeyCtrl.text.trim();
    _videoId = _videoIdCtrl.text.trim();
    await _save();
    setState(() { _isLoading = true; _status = 'Starting...'; });
    try {
      await platform.invokeMethod('startStream', {
        'rtmpUrl': _rtmpUrl,
        'streamKey': key,
        'audioMode': _audioMode,
        'orientation': _orientation,
        'cameraEnabled': _cameraEnabled,
        'cameraFacing': _cameraFacing,
        'cameraMode': _cameraMode,
        'bitrate': _bitrate * 1000, // kbps → bps
        'overlayText': _overlayText,
        'overlayImagePath': _overlayImagePath,
      });
    } on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = e.message ?? 'Error'; });
    }
  }

  Future<void> _stop() async {
    setState(() { _isLoading = true; _status = 'Stopping...'; });
    try { await platform.invokeMethod('stopStream'); }
    on PlatformException catch (e) {
      setState(() { _isLoading = false; _status = e.message ?? 'Error'; });
    }
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

  // ── Widgets ──────────────────────────────────────────────────────────────
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

  // Bitrate selector
  Widget _bitrateSection() {
    final options = [1000, 2000, 4000, 6000];
    final labels = ['1 Mbps', '2 Mbps', '4 Mbps', '6 Mbps'];
    final subs = ['Low', 'Medium', 'High', 'Ultra'];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Stream Quality', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
        const SizedBox(height: 6),
        Row(
          children: List.generate(options.length, (i) {
            final sel = _bitrate == options[i];
            return Expanded(
              child: GestureDetector(
                onTap: _isStreaming ? null : () => setState(() => _bitrate = options[i]),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  margin: EdgeInsets.only(right: i < options.length - 1 ? 8 : 0),
                  padding: const EdgeInsets.symmetric(vertical: 10),
                  decoration: BoxDecoration(
                    color: sel ? red : card,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: sel ? red : border),
                  ),
                  child: Column(children: [
                    Text(labels[i], style: TextStyle(color: sel ? Colors.white : text, fontSize: 11, fontWeight: FontWeight.w600)),
                    Text(subs[i], style: TextStyle(color: sel ? Colors.white70 : subtext, fontSize: 9)),
                  ]),
                ),
              ),
            );
          }),
        ),
      ],
    );
  }

  // Live stats bar (timer + viewers) — streaming मध्ये दाखव
  Widget _liveStatsBar() {
    return _card(
      color: widget.isDark ? const Color(0xFF1A0000) : const Color(0xFFFFF0F0),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          // Red dot
          Container(width: 8, height: 8,
            decoration: BoxDecoration(color: red, shape: BoxShape.circle,
              boxShadow: [BoxShadow(color: red.withOpacity(0.6), blurRadius: 6)])),
          const SizedBox(width: 8),
          Text('LIVE', style: TextStyle(color: red, fontSize: 12, fontWeight: FontWeight.bold)),
          const SizedBox(width: 12),
          // Timer
          Icon(Icons.timer_outlined, color: subtext, size: 14),
          const SizedBox(width: 4),
          Text(_timerStr, style: TextStyle(color: text, fontSize: 13, fontWeight: FontWeight.w500)),
          const Spacer(),
          // Viewer count
          if (_viewerCount > 0) ...[
            Icon(Icons.remove_red_eye_outlined, color: subtext, size: 14),
            const SizedBox(width: 4),
            Text('$_viewerCount', style: TextStyle(color: text, fontSize: 13, fontWeight: FontWeight.w500)),
          ] else if (_ytApiKey.isNotEmpty && _videoId.isNotEmpty) ...[
            Icon(Icons.remove_red_eye_outlined, color: subtext, size: 14),
            const SizedBox(width: 4),
            Text('—', style: TextStyle(color: subtext, fontSize: 13)),
          ],
          // Bitrate status
          const SizedBox(width: 12),
          Text(_status, style: TextStyle(color: subtext, fontSize: 11)),
        ],
      ),
    );
  }

  // Overlay panel
  Widget _overlayPanel() {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header
          GestureDetector(
            onTap: () => setState(() => _showOverlayPanel = !_showOverlayPanel),
            child: Row(
              children: [
                Icon(Icons.layers_outlined, color: _showOverlayPanel ? red : subtext, size: 20),
                const SizedBox(width: 8),
                Text('Overlay', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
                const Spacer(),
                Icon(_showOverlayPanel ? Icons.expand_less : Icons.expand_more, color: subtext),
              ],
            ),
          ),

          if (_showOverlayPanel) ...[
            const SizedBox(height: 14),

            // Text overlay
            Text('Text', style: TextStyle(color: subtext, fontSize: 12)),
            const SizedBox(height: 6),
            Row(children: [
              Expanded(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  decoration: BoxDecoration(
                    color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: border),
                  ),
                  child: TextField(
                    controller: _overlayTextCtrl,
                    style: TextStyle(color: text, fontSize: 13),
                    decoration: InputDecoration(
                      border: InputBorder.none,
                      hintText: 'Add Text in Stream  ...',
                      hintStyle: TextStyle(color: subtext, fontSize: 12),
                    ),
                    onChanged: (v) => _overlayText = v,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: () {
                  setState(() => _overlayText = _overlayTextCtrl.text.trim());
                  if (_isStreaming) _sendOverlay();
                },
                child: Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: red, borderRadius: BorderRadius.circular(8)),
                  child: const Icon(Icons.check, color: Colors.white, size: 18),
                ),
              ),
            ]),

            const SizedBox(height: 12),

            // Ticker
            Text('Ticker (Scrolling Text)', style: TextStyle(color: subtext, fontSize: 12)),
            const SizedBox(height: 6),
            Row(children: [
              Expanded(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  decoration: BoxDecoration(
                    color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: border),
                  ),
                  child: TextField(
                    controller: _tickerCtrl,
                    style: TextStyle(color: text, fontSize: 13),
                    decoration: InputDecoration(
                      border: InputBorder.none,
                      hintText: 'Breaking News: ...',
                      hintStyle: TextStyle(color: subtext, fontSize: 12),
                    ),
                    onChanged: (v) => _tickerText = v,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: () {
                  // Ticker text overlay म्हणून पाठव (खाली position)
                  platform.invokeMethod('updateOverlay', {
                    'overlayText': _tickerCtrl.text.trim(),
                    'overlayImagePath': _overlayImagePath,
                    'textX': 0.0, 'textY': 0.85,
                    'imageX': 0.7, 'imageY': 0.05,
                  });
                },
                child: Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: red, borderRadius: BorderRadius.circular(8)),
                  child: const Icon(Icons.send, color: Colors.white, size: 18),
                ),
              ),
            ]),

            const SizedBox(height: 12),

            // Image overlay
            Text('Image / Logo', style: TextStyle(color: subtext, fontSize: 12)),
            const SizedBox(height: 6),
            GestureDetector(
              onTap: _pickImage,
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 12),
                decoration: BoxDecoration(
                  color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: _overlayImagePath.isNotEmpty ? red : border),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      _overlayImagePath.isNotEmpty ? Icons.image : Icons.add_photo_alternate_outlined,
                      color: _overlayImagePath.isNotEmpty ? red : subtext, size: 20),
                    const SizedBox(width: 8),
                    Text(
                      _overlayImagePath.isNotEmpty ? 'Image selected ✓' : 'Select from Gallery ',
                      style: TextStyle(color: _overlayImagePath.isNotEmpty ? red : subtext, fontSize: 12)),
                    if (_overlayImagePath.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      GestureDetector(
                        onTap: () {
                          setState(() => _overlayImagePath = '');
                          if (_isStreaming) _sendOverlay();
                        },
                        child: Icon(Icons.close, color: subtext, size: 16),
                      ),
                    ],
                  ],
                ),
              ),
            ),

            // Clear all overlay
            if (_overlayText.isNotEmpty || _overlayImagePath.isNotEmpty) ...[
              const SizedBox(height: 10),
              GestureDetector(
                onTap: () {
                  setState(() { _overlayText = ''; _overlayImagePath = ''; _overlayTextCtrl.clear(); });
                  if (_isStreaming) _sendOverlay();
                },
                child: Row(children: [
                  Icon(Icons.clear_all, color: subtext, size: 16),
                  const SizedBox(width: 4),
                  Text('Clear All overlay text img etc..', style: TextStyle(color: subtext, fontSize: 11)),
                ]),
              ),
            ],
          ],
        ],
      ),
    );
  }

  // YouTube API settings
  Widget _ytApiSection() {
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Icon(Icons.people_outline, color: subtext, size: 20),
            const SizedBox(width: 8),
            Text('Live Viewer Count', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 4),
          Text('Add YouTube API key And Video ID', style: TextStyle(color: subtext, fontSize: 11)),
          const SizedBox(height: 10),
          _inputField(_ytApiKeyCtrl, 'YouTube API Key', obscure: true),
          const SizedBox(height: 8),
          _inputField(_videoIdCtrl, 'Video ID (Live stream )'),
          const SizedBox(height: 6),
          Text('Video ID: youtube.com/watch?v=VIDEO_ID_HERE', style: TextStyle(color: subtext, fontSize: 10)),
        ],
      ),
    );
  }

  Widget _inputField(TextEditingController ctrl, String hint, {bool obscure = false}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      decoration: BoxDecoration(
        color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: border),
      ),
      child: TextField(
        controller: ctrl,
        obscureText: obscure,
        style: TextStyle(color: text, fontSize: 13),
        decoration: InputDecoration(
          border: InputBorder.none,
          hintText: hint,
          hintStyle: TextStyle(color: subtext, fontSize: 12),
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

            // ── Live Stats Bar (streaming असताना) ──
            if (_isStreaming) ...[
              _liveStatsBar(),
              const SizedBox(height: 14),
            ],

            // ── Status card (streaming नसताना) ──
            if (!_isStreaming)
              _card(
                child: Row(children: [
                  Container(width: 10, height: 10,
                    decoration: BoxDecoration(color: green, shape: BoxShape.circle)),
                  const SizedBox(width: 10),
                  if (_isLoading) ...[
                    SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: red)),
                    const SizedBox(width: 8),
                  ],
                  Text(_status, style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w500)),
                ]),
              ),

            const SizedBox(height: 14),

            // ── Stream Key ──
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

            // ── Audio ──
            Text('Audio', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
            const SizedBox(height: 6),
            Row(children: [
              _selectBtn('internal', _audioMode, '🔊', 'Only Internal', 'Game/App sound', (v) => _audioMode = v),
              const SizedBox(width: 10),
              _selectBtn('mic_internal', _audioMode, '🎤', 'Mic + Internal', 'Commentary + Sound', (v) => _audioMode = v),
            ]),
            const SizedBox(height: 16),

            // ── Orientation ──
            Text('Orientation', style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
            const SizedBox(height: 6),
            Row(children: [
              _selectBtn('landscape', _orientation, '🖥️', 'Landscape', '16:9', (v) => _orientation = v),
              const SizedBox(width: 10),
              _selectBtn('portrait', _orientation, '📱', 'Portrait (Shorts)', '9:16', (v) => _orientation = v),
            ]),
            const SizedBox(height: 16),

            // ── Stream Quality ──
            _bitrateSection(),
            const SizedBox(height: 16),

            // ── Camera ──
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
                    Text('Mode', style: TextStyle(color: subtext, fontSize: 12)),
                    const SizedBox(height: 6),
                    Row(children: [
                      _selectBtn('pip', _cameraMode, '🎮', 'PIP', 'Corner overlay', (v) => _cameraMode = v),
                      const SizedBox(width: 10),
                      _selectBtn('split', _cameraMode, '📱', 'Split', '70/30 vertical', (v) => _cameraMode = v),
                    ]),
                    const SizedBox(height: 12),
                    Text('Camera', style: TextStyle(color: subtext, fontSize: 12)),
                    const SizedBox(height: 6),
                    Row(children: [
                      _selectBtn('back', _cameraFacing, '📷', 'Back', 'Main camera', (v) => _cameraFacing = v),
                      const SizedBox(width: 10),
                      _selectBtn('front', _cameraFacing, '🤳', 'Front', 'Selfie camera', (v) => _cameraFacing = v),
                    ]),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 16),

            // ── Overlay ──
            _overlayPanel(),
            const SizedBox(height: 16),

            // ── YouTube API (viewer count) ──
            _ytApiSection(),
            const SizedBox(height: 24),

            // ── Start/Stop Button ──
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
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _keyCtrl.dispose();
    _overlayTextCtrl.dispose();
    _tickerCtrl.dispose();
    _ytApiKeyCtrl.dispose();
    _videoIdCtrl.dispose();
    _timer?.cancel();
    _viewerTimer?.cancel();
    super.dispose();
  }
}
