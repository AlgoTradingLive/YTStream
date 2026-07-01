import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:image_picker/image_picker.dart';
import 'package:url_launcher/url_launcher.dart';

void main() => runApp(const YTStreamApp());

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
  Widget build(BuildContext context) => MaterialApp(
    title: 'YT Stream',
    debugShowCheckedModeBanner: false,
    theme: isDark ? _dark() : _light(),
    home: StreamPage(isDark: isDark),
  );
  ThemeData _dark() => ThemeData(brightness: Brightness.dark,
    scaffoldBackgroundColor: const Color(0xFF0D0D0D),
    colorScheme: const ColorScheme.dark(primary: Color(0xFFFF1A1A), surface: Color(0xFF161B22)),
    useMaterial3: true);
  ThemeData _light() => ThemeData(brightness: Brightness.light,
    scaffoldBackgroundColor: const Color(0xFFF5F5F5),
    colorScheme: const ColorScheme.light(primary: Color(0xFFFF1A1A), surface: Colors.white),
    useMaterial3: true);
}

// ── Overlay data model ────────────────────────────────────────────────────────
class OverlayItem {
  String type; // 'text' | 'image' | 'ticker'
  String content;
  double x, y;
  bool bold;
  String textSize;    // small | medium | large
  String textColor;   // white | yellow | red | black
  String textFont;    // roboto_bold | roboto_condensed | bangers
  String textBgColor; // black | white | none
  double textBgOpacity;
  String imageScale;
  OverlayItem({
    required this.type, required this.content,
    this.x = 0.05, this.y = 0.05,
    this.bold = false, this.textSize = 'medium',
    this.textColor = 'white', this.textFont = 'roboto_bold',
    this.textBgColor = 'black', this.textBgOpacity = 0.6,
    this.imageScale = 'medium',
  });
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
  bool _cameraEnabled = true;
  String _cameraFacing = 'back';
  String _cameraMode = 'pip';
  String _faceFilter = 'none';  // ← नवीन: सध्याचा face filter
  int _bitrate = 2000;
  bool _showAdvanced = false;

  // Overlay
  bool _showOverlayPanel = false;
  bool _showPreview = false;
  OverlayItem? _textOverlay;
  OverlayItem? _imageOverlay;
  OverlayItem? _tickerOverlay;
  final _textCtrl = TextEditingController();
  final _tickerCtrl = TextEditingController();
  String _draftTextColor = 'white';
  String _draftTextSize = 'medium';
  bool _draftBold = false;
  String _draftTextFont = 'roboto_bold';
  String _draftTextBgColor = 'black';
  double _draftTextBgOpacity = 0.6;
  String _draftImageScale = 'medium';
  String _draftTickerColor = 'white';
  String _draftTickerFont = 'roboto_bold';
  String _draftTickerBgColor = 'black';
  double _draftTickerBgOpacity = 0.6;

  // Timer
  Timer? _timer;
  int _seconds = 0;

  // Viewer count
  int _viewerCount = 0;
  Timer? _viewerTimer;
  final _ytApiKeyCtrl = TextEditingController();
  final _videoIdCtrl = TextEditingController();
  String _ytApiKey = '';
  String _videoId = '';
  bool _showViewerPanel = false;

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
        _startTimer(); _startViewerFetch();
        break;
      case 'onStreamError':
        final msg = call.arguments ?? 'Error';
        setState(() { _isLoading = false; _status = msg; });
        if (msg != '⏸ Paused' && msg != '🔇 Muted' && !msg.toString().startsWith('📷')) {
          setState(() => _isStreaming = false);
          _stopTimer(); _stopViewerFetch();
        }
        break;
      case 'onStreamStopped':
        setState(() { _isStreaming = false; _isLoading = false; _status = 'Ready'; });
        _stopTimer(); _stopViewerFetch();
        break;
      case 'onBitrateUpdate':
        if (_isStreaming) setState(() => _status = '${call.arguments} kbps');
        break;
    }
  }

  void _startTimer() {
    _seconds = 0;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => setState(() => _seconds++));
  }
  void _stopTimer() { _timer?.cancel(); _timer = null; _seconds = 0; }
  String get _timerStr {
    final h = _seconds ~/ 3600;
    final m = (_seconds % 3600) ~/ 60;
    final s = _seconds % 60;
    if (h > 0) return '${h.toString().padLeft(2,'0')}:${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
    return '${m.toString().padLeft(2,'0')}:${s.toString().padLeft(2,'0')}';
  }

  void _startViewerFetch() {
    if (_ytApiKey.isEmpty || _videoId.isEmpty) return;
    _fetchViewers();
    _viewerTimer = Timer.periodic(const Duration(seconds: 30), (_) => _fetchViewers());
  }
  void _stopViewerFetch() {
    _viewerTimer?.cancel(); _viewerTimer = null;
    setState(() => _viewerCount = 0);
  }
  Future<void> _fetchViewers() async {
    try {
      final url = 'https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=$_videoId&key=$_ytApiKey';
      final req = await HttpClient().getUrl(Uri.parse(url));
      final res = await req.close();
      final body = await res.transform(const SystemEncoding().decoder).join();
      final match = RegExp(r'"concurrentViewers":"(\d+)"').firstMatch(body);
      if (match != null) setState(() => _viewerCount = int.tryParse(match.group(1)!) ?? 0);
    } catch (_) {}
  }

  // ── Overlay send to Kotlin ────────────────────────────────────────────────
  void _sendOverlay() {
    final t = _textOverlay;
    final img = _imageOverlay;
    platform.invokeMethod('updateOverlay', {
      'overlayText': t?.content ?? '',
      'overlayImagePath': img?.content ?? '',
      'textX': t?.x ?? 0.05,
      'textY': t?.y ?? 0.05,
      'imageX': img?.x ?? 0.7,
      'imageY': img?.y ?? 0.05,
      'textBold': t?.bold ?? false,
      'textSize': t?.textSize ?? 'medium',
      'textColor': t?.textColor ?? 'white',
      'textFont': t?.textFont ?? 'roboto_bold',
      'textBgColor': t?.textBgColor ?? 'black',
      'textBgOpacity': t?.textBgOpacity ?? 0.6,
      'imageScale': img?.imageScale ?? 'medium',
    });
  }

  void _sendTicker() {
    final tk = _tickerOverlay;
    if (tk == null || tk.content.isEmpty) {
      platform.invokeMethod('stopTicker');
    } else {
      platform.invokeMethod('updateTicker', {
        'tickerText': tk.content,
        'tickerColor': tk.textColor,
        'tickerFont': tk.textFont,
        'tickerBgColor': tk.textBgColor,
        'tickerBgOpacity': tk.textBgOpacity,
      });
    }
  }

  Future<void> _pickImage() async {
    final picker = ImagePicker();
    final picked = await picker.pickImage(source: ImageSource.gallery);
    if (picked != null) {
      setState(() {
        _imageOverlay = OverlayItem(
          type: 'image', content: picked.path,
          x: 0.7, y: 0.05, imageScale: _draftImageScale,
        );
      });
      if (_isStreaming) _sendOverlay();
    }
  }

  // ── Start / Stop ─────────────────────────────────────────────────────────
  Future<void> _start() async {
    final key = _keyCtrl.text.trim();
    if (key.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: const Text('Please enter Stream Key!'), backgroundColor: red));
      return;
    }
    _ytApiKey = _ytApiKeyCtrl.text.trim();
    _videoId = _videoIdCtrl.text.trim();
    await _save();
    setState(() { _isLoading = true; _status = 'Starting...'; });
    try {
      await platform.invokeMethod('startStream', {
        'rtmpUrl': _rtmpUrl, 'streamKey': key,
        'audioMode': _audioMode, 'orientation': _orientation,
        'cameraEnabled': _cameraEnabled, 'cameraFacing': _cameraFacing,
        'cameraMode': _cameraMode, 'bitrate': _bitrate * 1000,
        'overlayText': _textOverlay?.content ?? '',
        'overlayImagePath': _imageOverlay?.content ?? '',
        'textX': _textOverlay?.x ?? 0.05,
        'textY': _textOverlay?.y ?? 0.05,
        'imageX': _imageOverlay?.x ?? 0.7,
        'imageY': _imageOverlay?.y ?? 0.05,
        'textBold': _textOverlay?.bold ?? false,
        'textSize': _textOverlay?.textSize ?? 'medium',
        'textColor': _textOverlay?.textColor ?? 'white',
        'imageScale': _imageOverlay?.imageScale ?? 'medium',
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
      title: Text('Settings', style: TextStyle(color: text, fontSize: 16, fontWeight: FontWeight.bold)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // RTMP URL
          Text('RTMP URL', style: TextStyle(color: subtext, fontSize: 12)),
          const SizedBox(height: 6),
          TextField(
            controller: c,
            style: TextStyle(color: text, fontSize: 13),
            decoration: InputDecoration(
              enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: border)),
              focusedBorder: UnderlineInputBorder(borderSide: BorderSide(color: red)),
            ),
          ),
          const SizedBox(height: 20),
          Divider(color: border),
          const SizedBox(height: 8),
          // Links section
          Text('Links', style: TextStyle(color: subtext, fontSize: 12)),
          const SizedBox(height: 8),
          GestureDetector(
            onTap: () async {
              final url = Uri.parse('https://sites.google.com/view/ytstream-privacypolicy/home');
              if (await canLaunchUrl(url)) await launchUrl(url, mode: LaunchMode.externalApplication);
            },
            child: Row(children: [
              Icon(Icons.privacy_tip_outlined, color: red, size: 18),
              const SizedBox(width: 8),
              Text('Privacy Policy', style: TextStyle(color: red, fontSize: 13, fontWeight: FontWeight.w500)),
              const Spacer(),
              Icon(Icons.open_in_new, color: subtext, size: 14),
            ]),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: Text('Cancel', style: TextStyle(color: subtext))),
        TextButton(
          onPressed: () { setState(() => _rtmpUrl = c.text.trim()); Navigator.pop(ctx); },
          child: Text('Save', style: TextStyle(color: red))),
      ],
    ));
  }

  // ── Shared Widgets ────────────────────────────────────────────────────────
  Widget _card({required Widget child, Color? color, EdgeInsets? padding}) => Container(
    padding: padding ?? const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: color ?? card, borderRadius: BorderRadius.circular(12),
      border: Border.all(color: border),
      boxShadow: widget.isDark ? [] : [BoxShadow(color: Colors.black.withOpacity(0.06), blurRadius: 8, offset: const Offset(0,2))],
    ),
    child: child,
  );

  Widget _selectBtn(String value, String current, String emoji, String title, String sub, Function(String) onTap) {
    final sel = value == current;
    return Expanded(
      child: GestureDetector(
        onTap: _isStreaming ? null : () => setState(() => onTap(value)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: BoxDecoration(
            color: sel ? red : card, borderRadius: BorderRadius.circular(12),
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

  Widget _sectionLabel(String label) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(label, style: TextStyle(color: subtext, fontSize: 12, fontWeight: FontWeight.w500)),
  );

  Widget _inputField(TextEditingController ctrl, String hint,
      {bool obscure = false, Function(String)? onChanged}) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
    decoration: BoxDecoration(
      color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
      borderRadius: BorderRadius.circular(8), border: Border.all(color: border),
    ),
    child: TextField(
      controller: ctrl, obscureText: obscure,
      style: TextStyle(color: text, fontSize: 13), onChanged: onChanged,
      decoration: InputDecoration(border: InputBorder.none, hintText: hint,
        hintStyle: TextStyle(color: subtext, fontSize: 12)),
    ),
  );

  Widget _actionBtn(IconData icon, VoidCallback onTap, {Color? color}) => GestureDetector(
    onTap: onTap,
    child: Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: color ?? red, borderRadius: BorderRadius.circular(8)),
      child: Icon(icon, color: Colors.white, size: 18),
    ),
  );

  // ── Color picker row ──────────────────────────────────────────────────────
  Widget _colorRow(String current, Function(String) onSelect) {
    final colors = {'white': Colors.white, 'yellow': Colors.yellow, 'red': Colors.red, 'black': Colors.black};
    return Row(children: colors.entries.map((e) {
      final sel = e.key == current;
      return GestureDetector(
        onTap: () => setState(() => onSelect(e.key)),
        child: Container(
          width: 28, height: 28, margin: const EdgeInsets.only(right: 8),
          decoration: BoxDecoration(
            color: e.value, shape: BoxShape.circle,
            border: Border.all(color: sel ? red : Colors.grey.withOpacity(0.4), width: sel ? 2.5 : 1),
          ),
          child: sel ? Icon(Icons.check, size: 14, color: e.key == 'white' || e.key == 'yellow' ? Colors.black : Colors.white) : null,
        ),
      );
    }).toList());
  }

  // ── Size pills ────────────────────────────────────────────────────────────
  // Font name display
  String _fontLabel(String f) {
    switch (f) {
      case 'roboto_bold': return 'Roboto Bold';
      case 'roboto_condensed': return 'Condensed';
      case 'bangers': return 'Bangers';
      default: return 'Roboto Bold';
    }
  }

  Widget _fontPills(String current, Function(String) onSelect) {
    return Row(children: ['roboto_bold', 'roboto_condensed', 'bangers'].map((f) {
      final sel = f == current;
      return GestureDetector(
        onTap: () => setState(() => onSelect(f)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          margin: const EdgeInsets.only(right: 8),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: sel ? red : Colors.transparent,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: sel ? red : border),
          ),
          child: Text(_fontLabel(f),
            style: TextStyle(color: sel ? Colors.white : subtext, fontSize: 10, fontWeight: FontWeight.w500)),
        ),
      );
    }).toList());
  }

  // Background color + opacity row
  Widget _bgColorRow(String current, double opacity, Function(String) onColorSelect, Function(double) onOpacityChange) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          // None
          GestureDetector(
            onTap: () => setState(() => onColorSelect('none')),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              margin: const EdgeInsets.only(right: 8),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: current == 'none' ? red : Colors.transparent,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: current == 'none' ? red : border),
              ),
              child: Text('None', style: TextStyle(color: current == 'none' ? Colors.white : subtext, fontSize: 10)),
            ),
          ),
          // Black
          GestureDetector(
            onTap: () => setState(() => onColorSelect('black')),
            child: Container(
              width: 28, height: 28, margin: const EdgeInsets.only(right: 8),
              decoration: BoxDecoration(
                color: Colors.black, shape: BoxShape.circle,
                border: Border.all(color: current == 'black' ? red : Colors.grey.withOpacity(0.4), width: current == 'black' ? 2.5 : 1),
              ),
              child: current == 'black' ? const Icon(Icons.check, size: 14, color: Colors.white) : null,
            ),
          ),
          // White
          GestureDetector(
            onTap: () => setState(() => onColorSelect('white')),
            child: Container(
              width: 28, height: 28, margin: const EdgeInsets.only(right: 8),
              decoration: BoxDecoration(
                color: Colors.white, shape: BoxShape.circle,
                border: Border.all(color: current == 'white' ? red : Colors.grey.withOpacity(0.4), width: current == 'white' ? 2.5 : 1),
              ),
              child: current == 'white' ? const Icon(Icons.check, size: 14, color: Colors.black) : null,
            ),
          ),
        ]),
        if (current != 'none') ...[
          const SizedBox(height: 6),
          Row(children: [
            Text('Opacity', style: TextStyle(color: subtext, fontSize: 10)),
            Expanded(
              child: SliderTheme(
                data: SliderThemeData(
                  trackHeight: 2,
                  thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
                  overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
                  activeTrackColor: red,
                  inactiveTrackColor: border,
                  thumbColor: red,
                ),
                child: Slider(
                  value: opacity, min: 0.1, max: 1.0, divisions: 9,
                  onChanged: (v) => setState(() => onOpacityChange(v)),
                ),
              ),
            ),
            Text('${(opacity * 100).toInt()}%', style: TextStyle(color: subtext, fontSize: 10)),
          ]),
        ],
      ],
    );
  }

  Widget _sizePills(String current, Function(String) onSelect) {
    return Row(children: ['small', 'medium', 'large'].map((s) {
      final sel = s == current;
      return GestureDetector(
        onTap: () => setState(() => onSelect(s)),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          margin: const EdgeInsets.only(right: 8),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          decoration: BoxDecoration(
            color: sel ? red : Colors.transparent,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: sel ? red : border),
          ),
          child: Text(s[0].toUpperCase() + s.substring(1),
            style: TextStyle(color: sel ? Colors.white : subtext, fontSize: 11, fontWeight: FontWeight.w500)),
        ),
      );
    }).toList());
  }

  // ── Preview widget with drag ──────────────────────────────────────────────
  Widget _previewWidget() {
    final isPortrait = _orientation == 'portrait';
    final aspect = isPortrait ? 9.0 / 16.0 : 16.0 / 9.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Text('Preview', style: TextStyle(color: text, fontSize: 13, fontWeight: FontWeight.w600)),
          Text('Drag to position', style: TextStyle(color: subtext, fontSize: 11)),
        ]),
        const SizedBox(height: 8),
        AspectRatio(
          aspectRatio: aspect,
          child: LayoutBuilder(builder: (ctx, constraints) {
            final w = constraints.maxWidth;
            final h = constraints.maxHeight;
            return Container(
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: border),
              ),
              child: Stack(children: [
                // Grid lines
                CustomPaint(painter: _GridPainter(), size: Size(w, h)),

                // Text overlay drag
                if (_textOverlay != null)
                  _DraggableOverlayItem(
                    x: _textOverlay!.x, y: _textOverlay!.y,
                    containerW: w, containerH: h,
                    onDragEnd: (nx, ny) {
                      setState(() { _textOverlay!.x = nx; _textOverlay!.y = ny; });
                      if (_isStreaming) _sendOverlay();
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                      decoration: BoxDecoration(
                        color: Colors.black.withOpacity(0.5),
                        borderRadius: BorderRadius.circular(4),
                        border: Border.all(color: red.withOpacity(0.7)),
                      ),
                      child: Text(
                        _textOverlay!.content,
                        style: TextStyle(
                          color: _colorFromString(_textOverlay!.textColor),
                          fontSize: _textOverlay!.textSize == 'small' ? 9 : _textOverlay!.textSize == 'large' ? 15 : 12,
                          fontWeight: _textOverlay!.bold ? FontWeight.bold : FontWeight.normal,
                        ),
                        maxLines: 2, overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ),

                // Image overlay drag
                if (_imageOverlay != null)
                  _DraggableOverlayItem(
                    x: _imageOverlay!.x, y: _imageOverlay!.y,
                    containerW: w, containerH: h,
                    onDragEnd: (nx, ny) {
                      setState(() { _imageOverlay!.x = nx; _imageOverlay!.y = ny; });
                      if (_isStreaming) _sendOverlay();
                    },
                    child: Container(
                      width: _imageOverlay!.imageScale == 'small' ? 40 : _imageOverlay!.imageScale == 'large' ? 80 : 56,
                      height: _imageOverlay!.imageScale == 'small' ? 40 : _imageOverlay!.imageScale == 'large' ? 80 : 56,
                      decoration: BoxDecoration(
                        border: Border.all(color: red.withOpacity(0.7)),
                        borderRadius: BorderRadius.circular(4),
                        image: DecorationImage(
                          image: FileImage(File(_imageOverlay!.content)),
                          fit: BoxFit.contain,
                        ),
                      ),
                    ),
                  ),

                // Ticker at bottom
                if (_tickerOverlay != null && _tickerOverlay!.content.isNotEmpty)
                  Positioned(
                    bottom: 8, left: 0, right: 0,
                    child: Container(
                      color: Colors.black.withOpacity(0.6),
                      padding: const EdgeInsets.symmetric(vertical: 3, horizontal: 6),
                      child: Text(
                        '▶ ${_tickerOverlay!.content}',
                        style: TextStyle(
                          color: _colorFromString(_tickerOverlay!.textColor),
                          fontSize: 9, fontWeight: FontWeight.w500,
                        ),
                        maxLines: 1, overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ),

                // No overlays hint
                if (_textOverlay == null && _imageOverlay == null && (_tickerOverlay == null || _tickerOverlay!.content.isEmpty))
                  Center(child: Text('Add overlays below to see preview',
                    style: TextStyle(color: Colors.white30, fontSize: 11))),
              ]),
            );
          }),
        ),
      ],
    );
  }

  Color _colorFromString(String c) {
    switch (c) {
      case 'yellow': return Colors.yellow;
      case 'red': return Colors.red;
      case 'black': return Colors.black;
      default: return Colors.white;
    }
  }

  // ── Overlay panel ─────────────────────────────────────────────────────────
  Widget _overlayPanel() => _card(
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      GestureDetector(
        onTap: () => setState(() => _showOverlayPanel = !_showOverlayPanel),
        child: Row(children: [
          Icon(Icons.layers_outlined, color: _showOverlayPanel ? red : subtext, size: 20),
          const SizedBox(width: 8),
          Text('Overlay', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
          const Spacer(),
          Icon(_showOverlayPanel ? Icons.expand_less : Icons.expand_more, color: subtext),
        ]),
      ),
      if (_showOverlayPanel) ...[
        const SizedBox(height: 14),

        // Preview toggle
        GestureDetector(
          onTap: () => setState(() => _showPreview = !_showPreview),
          child: Row(children: [
            Icon(_showPreview ? Icons.visibility : Icons.visibility_outlined, color: _showPreview ? red : subtext, size: 16),
            const SizedBox(width: 6),
            Text(_showPreview ? 'Hide Preview' : 'Show Preview',
              style: TextStyle(color: _showPreview ? red : subtext, fontSize: 12)),
          ]),
        ),
        if (_showPreview) ...[
          const SizedBox(height: 12),
          _previewWidget(),
        ],
        const Divider(height: 24),

        // ── Text overlay ──
        _sectionLabel('Text Overlay'),
        Row(children: [
          Expanded(child: _inputField(_textCtrl, 'Enter text to show on stream',
            onChanged: (v) {
              if (_textOverlay != null) setState(() => _textOverlay!.content = v);
            })),
          const SizedBox(width: 8),
          _actionBtn(Icons.check, () {
            final t = _textCtrl.text.trim();
            if (t.isEmpty) return;
            setState(() {
              _textOverlay = OverlayItem(
                type: 'text', content: t,
                x: _textOverlay?.x ?? 0.05, y: _textOverlay?.y ?? 0.05,
                bold: _draftBold, textSize: _draftTextSize, textColor: _draftTextColor,
                textFont: _draftTextFont, textBgColor: _draftTextBgColor, textBgOpacity: _draftTextBgOpacity,
              );
            });
            if (_isStreaming) _sendOverlay();
          }),
          if (_textOverlay != null) ...[
            const SizedBox(width: 6),
            _actionBtn(Icons.close, () {
              setState(() { _textOverlay = null; _textCtrl.clear(); });
              if (_isStreaming) _sendOverlay();
            }, color: Colors.grey.shade700),
          ],
        ]),
        const SizedBox(height: 10),

        // Text style
        Row(children: [
          GestureDetector(
            onTap: () {
              setState(() {
                _draftBold = !_draftBold;
                if (_textOverlay != null) _textOverlay!.bold = _draftBold;
              });
              if (_isStreaming && _textOverlay != null) _sendOverlay();
            },
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
              decoration: BoxDecoration(
                color: _draftBold ? red : Colors.transparent,
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: _draftBold ? red : border),
              ),
              child: Text('B', style: TextStyle(
                color: _draftBold ? Colors.white : subtext,
                fontSize: 13, fontWeight: FontWeight.bold)),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(child: _sizePills(_draftTextSize, (v) {
            _draftTextSize = v;
            if (_textOverlay != null) { _textOverlay!.textSize = v; if (_isStreaming) _sendOverlay(); }
          })),
        ]),
        const SizedBox(height: 8),
        _sectionLabel('Color'),
        _colorRow(_draftTextColor, (v) {
          _draftTextColor = v;
          if (_textOverlay != null) { _textOverlay!.textColor = v; if (_isStreaming) _sendOverlay(); }
        }),
        const SizedBox(height: 8),
        _sectionLabel('Font'),
        _fontPills(_draftTextFont, (v) {
          _draftTextFont = v;
          if (_textOverlay != null) { _textOverlay!.textFont = v; if (_isStreaming) _sendOverlay(); }
        }),
        const SizedBox(height: 8),
        _sectionLabel('Background'),
        _bgColorRow(_draftTextBgColor, _draftTextBgOpacity,
          (v) {
            _draftTextBgColor = v;
            if (_textOverlay != null) { _textOverlay!.textBgColor = v; if (_isStreaming) _sendOverlay(); }
          },
          (v) {
            _draftTextBgOpacity = v;
            if (_textOverlay != null) { _textOverlay!.textBgOpacity = v; if (_isStreaming) _sendOverlay(); }
          },
        ),

        const Divider(height: 24),

        // ── Ticker ──
        _sectionLabel('Ticker (Scrolling text at bottom)'),
        Row(children: [
          Expanded(child: _inputField(_tickerCtrl, 'Breaking News: ...')),
          const SizedBox(width: 8),
          _actionBtn(Icons.send, () {
            final t = _tickerCtrl.text.trim();
            setState(() {
              _tickerOverlay = OverlayItem(
                type: 'ticker', content: t,
                textColor: _draftTickerColor,
                textFont: _draftTickerFont,
                textBgColor: _draftTickerBgColor,
                textBgOpacity: _draftTickerBgOpacity,
              );
            });
            if (_isStreaming) _sendTicker();
          }),
          if (_tickerOverlay != null && _tickerOverlay!.content.isNotEmpty) ...[
            const SizedBox(width: 6),
            _actionBtn(Icons.stop, () {
              setState(() => _tickerOverlay = null);
              platform.invokeMethod('stopTicker');
            }, color: Colors.grey.shade700),
          ],
        ]),
        const SizedBox(height: 8),
        _sectionLabel('Ticker Color'),
        _colorRow(_draftTickerColor, (v) {
          setState(() {
            _draftTickerColor = v;
            if (_tickerOverlay != null) _tickerOverlay!.textColor = v;
          });
          if (_isStreaming && _tickerOverlay != null) _sendTicker();
        }),
        const SizedBox(height: 8),
        _sectionLabel('Ticker Font'),
        _fontPills(_draftTickerFont, (v) {
          setState(() {
            _draftTickerFont = v;
            if (_tickerOverlay != null) _tickerOverlay!.textFont = v;
          });
          if (_isStreaming && _tickerOverlay != null) _sendTicker();
        }),
        const SizedBox(height: 8),
        _sectionLabel('Ticker Background'),
        _bgColorRow(_draftTickerBgColor, _draftTickerBgOpacity,
          (v) {
            setState(() {
              _draftTickerBgColor = v;
              if (_tickerOverlay != null) _tickerOverlay!.textBgColor = v;
            });
            if (_isStreaming && _tickerOverlay != null) _sendTicker();
          },
          (v) {
            setState(() {
              _draftTickerBgOpacity = v;
              if (_tickerOverlay != null) _tickerOverlay!.textBgOpacity = v;
            });
            if (_isStreaming && _tickerOverlay != null) _sendTicker();
          },
        ),

        const Divider(height: 24),

        // ── Image ──
        _sectionLabel('Image / Logo'),
        GestureDetector(
          onTap: _pickImage,
          child: Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(vertical: 12),
            decoration: BoxDecoration(
              color: widget.isDark ? Colors.white.withOpacity(0.05) : Colors.black.withOpacity(0.04),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: _imageOverlay != null ? red : border),
            ),
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              Icon(_imageOverlay != null ? Icons.image : Icons.add_photo_alternate_outlined,
                color: _imageOverlay != null ? red : subtext, size: 20),
              const SizedBox(width: 8),
              Text(_imageOverlay != null ? 'Image selected ✓' : 'Pick from Gallery',
                style: TextStyle(color: _imageOverlay != null ? red : subtext, fontSize: 12)),
              if (_imageOverlay != null) ...[
                const SizedBox(width: 8),
                GestureDetector(
                  onTap: () {
                    setState(() => _imageOverlay = null);
                    if (_isStreaming) _sendOverlay();
                  },
                  child: Icon(Icons.close, color: subtext, size: 16)),
              ],
            ]),
          ),
        ),
        if (_imageOverlay != null) ...[
          const SizedBox(height: 8),
          _sectionLabel('Image Size'),
          _sizePills(_draftImageScale, (v) {
            setState(() {
              _draftImageScale = v;
              if (_imageOverlay != null) _imageOverlay!.imageScale = v;
            });
            if (_isStreaming) _sendOverlay();
          }),
        ],
      ],
    ]),
  );

  // ── Live stats bar ────────────────────────────────────────────────────────
  Widget _liveStatsBar() => _card(
    color: widget.isDark ? const Color(0xFF1A0000) : const Color(0xFFFFF0F0),
    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
    child: Row(children: [
      Container(width: 8, height: 8, decoration: BoxDecoration(color: red, shape: BoxShape.circle,
        boxShadow: [BoxShadow(color: red.withOpacity(0.6), blurRadius: 6)])),
      const SizedBox(width: 8),
      Text('LIVE', style: TextStyle(color: red, fontSize: 12, fontWeight: FontWeight.bold)),
      const SizedBox(width: 12),
      Icon(Icons.timer_outlined, color: subtext, size: 14),
      const SizedBox(width: 4),
      Text(_timerStr, style: TextStyle(color: text, fontSize: 13, fontWeight: FontWeight.w500)),
      const Spacer(),
      if (_viewerCount > 0) ...[
        Icon(Icons.remove_red_eye_outlined, color: subtext, size: 14),
        const SizedBox(width: 4),
        Text('$_viewerCount', style: TextStyle(color: text, fontSize: 13, fontWeight: FontWeight.w500)),
        const SizedBox(width: 12),
      ],
      Text(_status, style: TextStyle(color: subtext, fontSize: 11)),
    ]),
  );

  // ── Advanced section ──────────────────────────────────────────────────────
  Widget _advancedSection() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      GestureDetector(
        onTap: () => setState(() => _showAdvanced = !_showAdvanced),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          decoration: BoxDecoration(
            color: card, borderRadius: BorderRadius.circular(12),
            border: Border.all(color: _showAdvanced ? red.withOpacity(0.5) : border),
          ),
          child: Row(children: [
            Icon(Icons.tune_rounded, color: _showAdvanced ? red : subtext, size: 18),
            const SizedBox(width: 10),
            Text('Advanced Settings', style: TextStyle(color: _showAdvanced ? red : text,
              fontSize: 14, fontWeight: FontWeight.w600)),
            const Spacer(),
            Text('Optional', style: TextStyle(color: subtext, fontSize: 11)),
            const SizedBox(width: 6),
            Icon(_showAdvanced ? Icons.keyboard_arrow_up : Icons.keyboard_arrow_down, color: subtext, size: 20),
          ]),
        ),
      ),
      if (_showAdvanced) ...[
        const SizedBox(height: 14),

        // Camera
        _card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Row(children: [
              Icon(Icons.videocam_rounded, color: _cameraEnabled ? red : subtext, size: 20),
              const SizedBox(width: 8),
              Text('Camera', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
            ]),
            Switch(value: _cameraEnabled, activeColor: red,
              onChanged: _isStreaming ? null : (v) => setState(() => _cameraEnabled = v)),
          ]),
          if (_cameraEnabled) ...[
            const SizedBox(height: 12),
            _sectionLabel('Mode'),
            Row(children: [
              _selectBtn('pip', _cameraMode, '🎮', 'PIP', 'Corner overlay', (v) => _cameraMode = v),
              const SizedBox(width: 10),
              _selectBtn('split', _cameraMode, '📱', 'Split', '70/30 view', (v) => _cameraMode = v),
            ]),
            const SizedBox(height: 12),
            _sectionLabel('Camera'),
            Row(children: [
              _selectBtn('back', _cameraFacing, '📷', 'Back', 'Main camera', (v) => _cameraFacing = v),
              const SizedBox(width: 10),
              _selectBtn('front', _cameraFacing, '🤳', 'Front', 'Selfie camera', (v) => _cameraFacing = v),
            ]),
          ],
        ])),
        const SizedBox(height: 12),

        // Stream Quality
        _card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.high_quality_outlined, color: subtext, size: 20),
            const SizedBox(width: 8),
            Text('Stream Quality', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
          ]),
          const SizedBox(height: 12),
          Row(children: [
            [1000, '1 Mbps', 'Low'],
            [2000, '2 Mbps', 'Medium'],
            [4000, '4 Mbps', 'High'],
            [6000, '6 Mbps', 'Ultra'],
          ].asMap().entries.map((e) {
            final i = e.key; final opt = e.value;
            final val = opt[0] as int; final label = opt[1] as String; final sub = opt[2] as String;
            final sel = _bitrate == val;
            return Expanded(child: GestureDetector(
              onTap: _isStreaming ? null : () => setState(() => _bitrate = val),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                margin: EdgeInsets.only(right: i < 3 ? 8 : 0),
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: BoxDecoration(
                  color: sel ? red : card, borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: sel ? red : border),
                ),
                child: Column(children: [
                  Text(label, style: TextStyle(color: sel ? Colors.white : text, fontSize: 11, fontWeight: FontWeight.w600)),
                  Text(sub, style: TextStyle(color: sel ? Colors.white70 : subtext, fontSize: 9)),
                ]),
              ),
            ));
          }).toList(),
          ),
        ])),
        const SizedBox(height: 12),

        // Overlay
        _overlayPanel(),
        const SizedBox(height: 12),

        // Viewer count
        _card(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          GestureDetector(
            onTap: () => setState(() => _showViewerPanel = !_showViewerPanel),
            child: Row(children: [
              Icon(Icons.people_outline, color: _showViewerPanel ? red : subtext, size: 20),
              const SizedBox(width: 8),
              Text('Live Viewer Count', style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w600)),
              const Spacer(),
              Icon(_showViewerPanel ? Icons.expand_less : Icons.expand_more, color: subtext),
            ]),
          ),
          if (_showViewerPanel) ...[
            const SizedBox(height: 12),
            Text('Requires YouTube Data API v3 key', style: TextStyle(color: subtext, fontSize: 11)),
            const SizedBox(height: 10),
            _inputField(_ytApiKeyCtrl, 'YouTube API Key', obscure: true),
            const SizedBox(height: 8),
            _inputField(_videoIdCtrl, 'Video ID  (youtube.com/watch?v=VIDEO_ID)'),
          ],
        ])),
        const SizedBox(height: 12),
      ],
    ],
  );

  Widget _faceFilterBtn(String value, String emoji, String label) {
    final bool isSelected = _faceFilter == value;
    return GestureDetector(
      onTap: () async {
        setState(() => _faceFilter = value);
        try {
          await platform.invokeMethod('setFaceFilter', {'filterName': value});
        } catch (e) {
          // channel fail झाला तरी app crash होऊ नये
        }
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: isSelected ? red : card,
          borderRadius: BorderRadius.circular(9),
          border: Border.all(color: isSelected ? red : border),
        ),
        child: Column(children: [
          Text(emoji, style: const TextStyle(fontSize: 16)),
          const SizedBox(height: 1),
          Text(label, style: TextStyle(
            color: isSelected ? Colors.white : text,
            fontSize: 9, fontWeight: FontWeight.w600,
          )),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: bg,
      appBar: AppBar(
        backgroundColor: widget.isDark ? const Color(0xFF0D0D0D) : Colors.white,
        elevation: 0, titleSpacing: 16,
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
                color: widget.isDark ? Colors.white12 : Colors.black12,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Text(widget.isDark ? '☀️' : '🌙', style: const TextStyle(fontSize: 16)),
            ),
          ),
          IconButton(
            icon: Icon(Icons.settings_outlined, color: widget.isDark ? Colors.white54 : Colors.black54),
            onPressed: _showRtmpDialog,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [

          // 1. Status / Live bar
          if (_isStreaming) _liveStatsBar()
          else _card(child: Row(children: [
            Container(width: 10, height: 10, decoration: BoxDecoration(color: green, shape: BoxShape.circle)),
            const SizedBox(width: 10),
            if (_isLoading) ...[
              SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: red)),
              const SizedBox(width: 8),
            ],
            Text(_status, style: TextStyle(color: text, fontSize: 14, fontWeight: FontWeight.w500)),
          ])),
          const SizedBox(height: 14),

          // 2. Stream Key
          _sectionLabel('YouTube Stream Key'),
          _card(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
            child: Row(children: [
              Expanded(child: TextField(
                controller: _keyCtrl, obscureText: true, enabled: !_isStreaming,
                style: TextStyle(color: text, fontSize: 14),
                decoration: InputDecoration(border: InputBorder.none,
                  hintText: '••••-••••-••••-••••-••••',
                  hintStyle: TextStyle(color: border, fontSize: 14)),
              )),
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

          // 3. Audio
          _sectionLabel('Audio'),
          Row(children: [
            _selectBtn('internal', _audioMode, '🔊', 'Only Internal', 'Game/App sound', (v) => _audioMode = v),
            const SizedBox(width: 10),
            _selectBtn('mic_internal', _audioMode, '🎤', 'Mic + Internal', 'Commentary + Sound', (v) => _audioMode = v),
          ]),
          const SizedBox(height: 16),

          // 4. Orientation
          _sectionLabel('Orientation'),
          Row(children: [
            _selectBtn('landscape', _orientation, '🖥️', 'Landscape', '16:9', (v) => _orientation = v),
            const SizedBox(width: 10),
            _selectBtn('portrait', _orientation, '📱', 'Portrait (Shorts)', '9:16', (v) => _orientation = v),
          ]),
          const SizedBox(height: 24),

          // 5. Start / Stop
          SizedBox(
            height: 54,
            child: ElevatedButton(
              onPressed: _isLoading ? null : (_isStreaming ? _stop : _start),
              style: ElevatedButton.styleFrom(
                backgroundColor: _isStreaming ? const Color(0xFF8B0000) : red,
                foregroundColor: Colors.white, elevation: 0,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Text(_isStreaming ? '⏹' : '▶', style: const TextStyle(fontSize: 18)),
                const SizedBox(width: 8),
                Text(_isStreaming ? 'STOP STREAM' : 'START STREAM',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 1.5)),
              ]),
            ),
          ),
          const SizedBox(height: 20),

          // 5.5 Face Filters
          _sectionLabel('Face Filter'),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(children: [
              _faceFilterBtn('galaxy_blue', '🔵', 'Galaxy Blue'),
              const SizedBox(width: 6),
              _faceFilterBtn('galaxy_green', '🟢', 'Galaxy Green'),
              const SizedBox(width: 6),
              _faceFilterBtn('galaxy_red', '🔴', 'Galaxy Red'),
              const SizedBox(width: 6),
              _faceFilterBtn('anonymous', '🎭', 'Anonymous'),
              const SizedBox(width: 6),
              _faceFilterBtn('robot', '🤖', 'Robot'),
              const SizedBox(width: 6),
              _faceFilterBtn('tribal', '✨', 'Tribal'),
              const SizedBox(width: 6),
              _faceFilterBtn('webslinger', '🕸️', 'Web'),
              const SizedBox(width: 6),
              _faceFilterBtn('tiger', '🐯', 'Tiger'),
              const SizedBox(width: 6),
              _faceFilterBtn('holi', '🎨', 'Holi'),
              const SizedBox(width: 6),
              _faceFilterBtn('galaxy', '🌌', 'Galaxy'),
              const SizedBox(width: 6),
              _faceFilterBtn('none', '🚫', 'None'),
            ]),
          ),
          const SizedBox(height: 20),

          // Advanced
          _advancedSection(),
          const SizedBox(height: 24),
        ]),
      ),
    );
  }

  @override
  void dispose() {
    _keyCtrl.dispose(); _textCtrl.dispose(); _tickerCtrl.dispose();
    _ytApiKeyCtrl.dispose(); _videoIdCtrl.dispose();
    _timer?.cancel(); _viewerTimer?.cancel();
    super.dispose();
  }
}

// ── Draggable overlay item ────────────────────────────────────────────────────
class _DraggableOverlayItem extends StatefulWidget {
  final double x, y, containerW, containerH;
  final Widget child;
  final Function(double, double) onDragEnd;
  const _DraggableOverlayItem({
    required this.x, required this.y,
    required this.containerW, required this.containerH,
    required this.child, required this.onDragEnd,
  });
  @override
  State<_DraggableOverlayItem> createState() => _DraggableOverlayItemState();
}

class _DraggableOverlayItemState extends State<_DraggableOverlayItem> {
  late double _x, _y;
  @override
  void initState() {
    super.initState();
    _x = widget.x; _y = widget.y;
  }
  @override
  void didUpdateWidget(_DraggableOverlayItem old) {
    super.didUpdateWidget(old);
    if (old.x != widget.x || old.y != widget.y) { _x = widget.x; _y = widget.y; }
  }
  @override
  Widget build(BuildContext context) {
    return Positioned(
      left: _x * widget.containerW,
      top: _y * widget.containerH,
      child: GestureDetector(
        onPanUpdate: (d) {
          setState(() {
            _x = (_x + d.delta.dx / widget.containerW).clamp(0.0, 0.9);
            _y = (_y + d.delta.dy / widget.containerH).clamp(0.0, 0.9);
          });
        },
        onPanEnd: (_) => widget.onDragEnd(_x, _y),
        child: widget.child,
      ),
    );
  }
}

// ── Grid painter for preview ──────────────────────────────────────────────────
class _GridPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withOpacity(0.07)
      ..strokeWidth = 0.5;
    for (int i = 1; i < 3; i++) {
      canvas.drawLine(Offset(size.width * i / 3, 0), Offset(size.width * i / 3, size.height), paint);
      canvas.drawLine(Offset(0, size.height * i / 3), Offset(size.width, size.height * i / 3), paint);
    }
  }
  @override
  bool shouldRepaint(_) => false;
}
