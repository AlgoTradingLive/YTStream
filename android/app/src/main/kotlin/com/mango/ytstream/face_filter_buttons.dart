// face_filter_buttons.dart
//
// हे widget कुठेही वापरता येतं — उदा. तुमच्या stream control screen मध्ये.
// बटण दाबल्यावर MethodChannel द्वारे थेट Android (Kotlin) ला signal जातो.
//
// वापर:
//   import 'face_filter_buttons.dart';
//   ...
//   FaceFilterButtons()   // तुमच्या widget tree मध्ये कुठेही टाका
//
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class FaceFilterButtons extends StatefulWidget {
  const FaceFilterButtons({super.key});

  @override
  State<FaceFilterButtons> createState() => _FaceFilterButtonsState();
}

class _FaceFilterButtonsState extends State<FaceFilterButtons> {
  // ── हेच channel name MainActivity.kt मध्ये वापरलं आहे ──────────────────
  // तुमच्या existing code मध्ये हे आधीच असेल (StreamService साठी वापरलेलं)
  static const MethodChannel _channel = MethodChannel('com.mango.ytstream/stream');

  String _selectedFilter = 'none';

  Future<void> _setFilter(String filterName) async {
    try {
      await _channel.invokeMethod('setFaceFilter', {'filterName': filterName});
      setState(() => _selectedFilter = filterName);
    } catch (e) {
      // channel error झाला तरी app crash होऊ नये
      debugPrint('Face filter error: $e');
    }
  }

  Widget _filterButton(String label, String value, IconData icon) {
    final bool isSelected = _selectedFilter == value;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: ElevatedButton.icon(
        onPressed: () => _setFilter(value),
        icon: Icon(icon, size: 18),
        label: Text(label),
        style: ElevatedButton.styleFrom(
          backgroundColor: isSelected ? Colors.deepPurple : Colors.grey[800],
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          _filterButton('Batman', 'batman', Icons.dark_mode),
          _filterButton('Superman', 'superman', Icons.bolt),
          _filterButton('Dog', 'dog', Icons.pets),
          _filterButton('None', 'none', Icons.block),
        ],
      ),
    );
  }
}
