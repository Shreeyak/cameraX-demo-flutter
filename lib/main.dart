import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Lock to portrait for consistent camera orientation
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const CameraXDemoApp());
}

// ── App root ────────────────────────────────────────────────────────────

class CameraXDemoApp extends StatelessWidget {
  const CameraXDemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CameraX Demo',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        colorScheme: ColorScheme.dark(
          primary: Colors.blueAccent,
          surface: Colors.black,
        ),
      ),
      home: const CameraScreen(),
    );
  }
}

// ── Platform channel helper ─────────────────────────────────────────────

class CameraControl {
  static const _method = MethodChannel('com.example.camerax/control');
  static const _frames = EventChannel('com.example.camerax/frames');
  static const _events = EventChannel('com.example.camerax/kotlinEvents');

  /// Request camera permission. Returns true if granted.
  static Future<bool> requestPermission() async {
    final granted = await _method.invokeMethod<bool>('requestPermission');
    return granted ?? false;
  }

  /// Start the camera. Returns resolution info map.
  static Future<Map<String, dynamic>> startCamera() async {
    final result = await _method.invokeMethod<Map>('startCamera');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// Stop the camera and release resources.
  static Future<void> stopCamera() => _method.invokeMethod('stopCamera');

  /// Save a 4K frame to disk. Returns the file path.
  static Future<String> saveFrame() async {
    final path = await _method.invokeMethod<String>('saveFrame');
    return path ?? '';
  }

  /// Toggle AWB between OFF and AUTO. Returns new state (true = AUTO).
  /// @deprecated Use setWhiteBalancePreset instead.
  static Future<bool> toggleAwb() async {
    final enabled = await _method.invokeMethod<bool>('toggleAwb');
    return enabled ?? false;
  }

  /// Set a Camera2 CONTROL_AWB_MODE_* preset (mode int constant).
  static Future<void> setWhiteBalancePreset(int mode) =>
      _method.invokeMethod('setWhiteBalancePreset', {'mode': mode});

  /// Returns the list of AWB mode ints this device supports.
  /// Empty list if camera not yet started.
  static Future<List<int>> getAvailableWhiteBalanceModes() async {
    final result = await _method.invokeMethod<List>(
      'getAvailableWhiteBalanceModes',
    );
    return result?.cast<int>() ?? [1];
  }

  /// @deprecated Use setWhiteBalancePreset instead.
  static Future<void> setAfEnabled(bool enabled) =>
      _method.invokeMethod('setAfEnabled', {'enabled': enabled});

  /// Get current resolution info.
  static Future<Map<String, dynamic>> getResolution() async {
    final result = await _method.invokeMethod<Map>('getResolution');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// Stream of analysis frames as maps with 'bytes', 'width', 'height'.
  static Stream<Map<dynamic, dynamic>> get frameStream => _frames
      .receiveBroadcastStream()
      .map((event) => event as Map<dynamic, dynamic>);

  /// Stream of generic events from Kotlin: status updates, warnings, errors.
  /// Each event map contains: 'type' ("status"|"warning"|"error"),
  /// 'tag' (source identifier), 'message' (string), and optional 'data' (map).
  static Stream<Map<dynamic, dynamic>> get eventStream => _events
      .receiveBroadcastStream()
      .map((event) => event as Map<dynamic, dynamic>);
}

// ── Camera screen ───────────────────────────────────────────────────────

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  bool _permissionGranted = false;
  bool _cameraStarted = false;
  bool _saving = false;
  // WB preset selection
  int _selectedWbMode = 1; // AUTO by default
  bool _wbPending = false;
  List<int> _availableWbModes = []; // populated after camera starts
  bool _isAwbDrawerOpen = false;

  bool _afEnabled = false;

  String _captureResolution = '--';
  String _analysisResolution = '--';
  String _statusText = 'Initializing...';

  // Analysis frame thumbnail
  Uint8List? _thumbnailBytes;
  StreamSubscription<Map<dynamic, dynamic>>? _frameSub;
  StreamSubscription<Map<dynamic, dynamic>>? _eventSub;

  @override
  void initState() {
    super.initState();
    _initCamera();
  }

  Future<void> _initCamera() async {
    // Request camera permission
    final granted = await CameraControl.requestPermission();
    if (!mounted) return;
    setState(() {
      _permissionGranted = granted;
      _statusText = granted ? 'Permission granted' : 'Permission denied';
    });

    if (!granted) return;

    // startCamera can be called immediately — CameraManager will defer
    // until the PlatformView's PreviewView is created on the native side
    await _startCamera();
  }

  Future<void> _startCamera() async {
    try {
      setState(() => _statusText = 'Starting camera...');
      final info = await CameraControl.startCamera();
      if (!mounted) return;

      setState(() {
        _cameraStarted = true;
        final cw = info['captureWidth'] ?? '--';
        final ch = info['captureHeight'] ?? '--';
        final aw = info['analysisWidth'] ?? '--';
        final ah = info['analysisHeight'] ?? '--';
        _captureResolution = '${cw}x$ch';
        _analysisResolution = '${aw}x$ah';
        _statusText = 'AF: ON | AE: ON | AWB: AUTO';
      });

      // Fetch supported WB modes and update the preset bar
      final modes = await CameraControl.getAvailableWhiteBalanceModes();
      if (mounted && modes.isNotEmpty) {
        setState(() => _availableWbModes = modes);
      }

      // Start listening to analysis frames and Kotlin events
      _listenToFrames();
      _listenToEvents();
    } catch (e) {
      if (!mounted) return;
      setState(() => _statusText = 'Camera error: $e');
    }
  }

  void _listenToFrames() {
    _frameSub = CameraControl.frameStream.listen(
      (frame) {
        if (!mounted) return;
        final bytes = frame['bytes'];

        setState(() {
          _thumbnailBytes = bytes is Uint8List
              ? bytes
              : Uint8List.fromList(List<int>.from(bytes));
        });
      },
      onError: (e) {
        debugPrint('Frame stream error: $e');
      },
    );
  }

  void _listenToEvents() {
    _eventSub = CameraControl.eventStream.listen(
      (event) {
        if (!mounted) return;
        final type = event['type'] as String? ?? 'status';
        final message = event['message'] as String? ?? '';

        switch (type) {
          case 'status':
            setState(() => _statusText = message);
          case 'warning':
            setState(() => _statusText = '⚠ $message');
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(message),
                backgroundColor: Colors.orange[800],
                duration: const Duration(seconds: 4),
              ),
            );
          case 'error':
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text(message),
                backgroundColor: Colors.red,
                duration: const Duration(seconds: 4),
              ),
            );
        }
      },
      onError: (e) {
        debugPrint('Event stream error: $e');
      },
    );
  }

  @override
  void dispose() {
    _frameSub?.cancel();
    _eventSub?.cancel();
    CameraControl.stopCamera();
    super.dispose();
  }

  Future<void> _saveFrame() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      final path = await CameraControl.saveFrame();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Saved: $path'),
          duration: const Duration(seconds: 3),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Save failed: $e'), backgroundColor: Colors.red),
      );
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _setWbPreset(int mode) async {
    if (_wbPending) return;
    final previousMode = _selectedWbMode;
    setState(() {
      _wbPending = true;
      _selectedWbMode = mode; // optimistic UI update
    });
    try {
      await CameraControl.setWhiteBalancePreset(mode);
    } catch (e) {
      // Revert optimistic update on failure
      if (!mounted) return;
      setState(() => _selectedWbMode = previousMode);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('WB preset failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      if (mounted) setState(() => _wbPending = false);
    }
  }

  Future<void> _toggleAf() async {
    final previousAf = _afEnabled;
    setState(() {
      _afEnabled = !_afEnabled;
    });
    try {
      // Optimistically update
      await CameraControl.setAfEnabled(_afEnabled);
    } catch (e) {
      if (!mounted) return;
      setState(() => _afEnabled = previousAf);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('AF toggle failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // ── Full-screen camera preview ──
          if (_permissionGranted)
            Positioned.fill(
              child: PlatformViewLink(
                viewType: 'camerax-preview',
                surfaceFactory: (context, controller) {
                  return AndroidViewSurface(
                    controller: controller as AndroidViewController,
                    hitTestBehavior: PlatformViewHitTestBehavior.opaque,
                    gestureRecognizers: const {},
                  );
                },
                onCreatePlatformView: (params) {
                  return PlatformViewsService.initExpensiveAndroidView(
                      id: params.id,
                      viewType: 'camerax-preview',
                      layoutDirection: TextDirection.ltr,
                      creationParamsCodec: const StandardMessageCodec(),
                    )
                    ..addOnPlatformViewCreatedListener(
                      params.onPlatformViewCreated,
                    )
                    ..create();
                },
              ),
            )
          else
            const Center(
              child: Text(
                'Camera permission required',
                style: TextStyle(color: Colors.white, fontSize: 18),
              ),
            ),

          // ── Right-edge toolbar ──
          Positioned(right: 0, top: 0, bottom: 0, child: _buildToolbar()),

          // ── Background tap to close drawer ──
          if (_isAwbDrawerOpen)
            Positioned.fill(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () {
                  setState(() {
                    _isAwbDrawerOpen = false;
                  });
                },
                child: const SizedBox.expand(),
              ),
            ),

          // ── Left-edge AWB drawer ──
          Positioned(left: 0, top: 0, bottom: 0, child: _buildLeftBar()),

          // ── Bottom overlay ──
          Positioned(left: 0, right: 0, bottom: 0, child: _buildBottomBar()),
        ],
      ),
    );
  }

  /// Right-edge vertical toolbar. Currently empty; reserved for future buttons.
  Widget _buildToolbar() => const SizedBox.shrink();

  Widget _buildLeftBar() {
    if (!_cameraStarted) return const SizedBox.shrink();

    final selectedPreset = _kWbPresets.firstWhere(
      (p) => p.mode == _selectedWbMode,
      orElse: () => _kWbPresets.first,
    );

    final visiblePresets = _availableWbModes.isEmpty
        ? _kWbPresets
        : _kWbPresets.where((p) => _availableWbModes.contains(p.mode)).toList();

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // The Bar (Full-height on left)
        Container(
          width: 65,
          height: double.infinity,
          color: Colors.black.withValues(alpha: 0.6),
          child: SafeArea(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // AF Toggle Button (above WB)
                GestureDetector(
                  onTap: _toggleAf,
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    color: Colors.transparent,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          _afEnabled
                              ? Icons.center_focus_strong
                              : Icons.center_focus_weak,
                          size: 24,
                          color: _afEnabled
                              ? Colors.greenAccent
                              : Colors.white54,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          _afEnabled ? 'AF ON' : 'AF OFF',
                          style: TextStyle(
                            color: _afEnabled
                                ? Colors.greenAccent
                                : Colors.white54,
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                // WB Drawer Toggle
                GestureDetector(
                  onTap: () {
                    setState(() {
                      _isAwbDrawerOpen = !_isAwbDrawerOpen;
                    });
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    color: Colors.transparent, // Ensure gesture hits
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          selectedPreset.icon,
                          size: 24,
                          color: Colors.blueAccent,
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'WB', // Text below the icon
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),

        // The Drawer
        if (_isAwbDrawerOpen)
          Align(
            alignment: Alignment.center,
            child: Container(
              width:
                  290, // constrain width to safely allow ~4 items per row (60px each + spacing)
              margin: const EdgeInsets.only(
                left: 8,
              ), // Some breathing room from the bar
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
              decoration: BoxDecoration(
                color: Colors.black.withValues(
                  alpha: 0.9,
                ), // Darker shade than the bar
                borderRadius: BorderRadius.circular(12),
              ),
              child: Wrap(
                direction: Axis.horizontal,
                spacing: 8, // space between elements horizontally
                runSpacing: 8, // space between rows vertically
                children: visiblePresets.map((preset) {
                  final isSelected = preset.mode == _selectedWbMode;
                  return SizedBox(
                    width: 60,
                    height: 54, // fixed height for rows
                    child: _WbChip(
                      preset: preset,
                      isSelected: isSelected,
                      isPending: _wbPending && isSelected,
                      onTap: () {
                        _setWbPreset(preset.mode);
                        setState(() {
                          _isAwbDrawerOpen = false;
                        });
                      },
                    ),
                  );
                }).toList(),
              ),
            ),
          ),
      ],
    );
  }

  /// Bottom overlay bar with thumbnail, resolution, status, and save button.
  Widget _buildBottomBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
      decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.8)),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Thumbnail + info + save ──
            Row(
              children: [
                // ── Thumbnail from ImageAnalysis ──
                Container(
                  width: 160,
                  height: 120,
                  decoration: BoxDecoration(
                    color: Colors.grey[900],
                    border: Border.all(color: Colors.grey[700]!, width: 1),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: _thumbnailBytes != null
                      ? ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: Image.memory(
                            _thumbnailBytes!,
                            width: 160,
                            height: 120,
                            fit: BoxFit.contain,
                            gaplessPlayback: true, // avoid flicker on updates
                          ),
                        )
                      : const Center(
                          child: Text(
                            'No frames',
                            style: TextStyle(color: Colors.grey, fontSize: 12),
                          ),
                        ),
                ),
                const SizedBox(width: 16),

                // ── Info + save ──
                Expanded(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      // Resolution info
                      Text(
                        'Capture: $_captureResolution',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13,
                          fontFamily: 'monospace',
                        ),
                      ),
                      Text(
                        'Analysis: $_analysisResolution',
                        style: const TextStyle(
                          color: Colors.white70,
                          fontSize: 12,
                          fontFamily: 'monospace',
                        ),
                      ),
                      const SizedBox(height: 4),

                      // Status text
                      Text(
                        _statusText,
                        style: const TextStyle(
                          color: Colors.amber,
                          fontSize: 12,
                        ),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),

                      // Save button
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: _cameraStarted && !_saving
                              ? _saveFrame
                              : null,
                          icon: _saving
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                )
                              : const Icon(Icons.save, size: 18),
                          label: Text(_saving ? 'Capturing…' : 'Save Frame'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.blueAccent,
                            foregroundColor: Colors.white,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ── White balance preset bar ─────────────────────────────────────────

/// Preset descriptor — mode value matches CameraMetadata.CONTROL_AWB_MODE_*
class _WbPreset {
  const _WbPreset(this.label, this.mode, this.icon, this.indicatorColor);
  final String label;
  final int mode;
  final IconData icon;

  /// Small color dot shown below the label to hint at the light color.
  final Color indicatorColor;
}

// Ordered based on user request: auto, tungsten, warm fl, fluor, sunny, cloudy, twilight, shade.
// Inverted colors: Tungsten (blue) to Shade (orange).
const _kWbPresets = [
  _WbPreset('Auto', 1, Icons.wb_auto, Color(0xFFFFFFFF)),
  _WbPreset('Tungsten', 2, Icons.wb_incandescent, Color(0xFF99AAFF)), // Blue
  _WbPreset('Warm Fl.', 4, Icons.light_mode, Color(0xFFBBCCFF)), // Light blue
  _WbPreset('Fluor.', 3, Icons.fluorescent, Color(0xFFDDEEFF)), // Pale blue
  _WbPreset('Sunny', 5, Icons.wb_sunny, Color(0xFFFFF5C0)), // Middle pale
  _WbPreset('Cloudy', 6, Icons.wb_cloudy, Color(0xFFFFFFAA)), // Yellowish
  _WbPreset(
    'Twilight',
    7,
    Icons.nights_stay,
    Color(0xFFFFCC66),
  ), // Light Orange
  _WbPreset('Shade', 8, Icons.filter_drama, Color(0xFFFF8800)), // Orange
];

class _WbChip extends StatelessWidget {
  const _WbChip({
    required this.preset,
    required this.isSelected,
    required this.isPending,
    required this.onTap,
  });

  final _WbPreset preset;
  final bool isSelected;
  final bool isPending;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = isSelected ? Colors.blueAccent : Colors.white38;
    return GestureDetector(
      onTap: isPending ? null : onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        width: 60,
        decoration: BoxDecoration(
          color: isSelected
              ? Colors.blueAccent.withValues(alpha: 0.25)
              : Colors.white.withValues(alpha: 0.04),
          border: Border.all(
            color: isSelected ? Colors.blueAccent : Colors.white24,
            width: isSelected ? 1.5 : 0.5,
          ),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Opacity(
          opacity: isPending ? 0.5 : 1.0,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(preset.icon, size: 18, color: color),
              const SizedBox(height: 2),
              Text(
                preset.label,
                style: TextStyle(
                  color: color,
                  fontSize: 9,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                ),
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 3),
              Container(
                width: 10,
                height: 4,
                decoration: BoxDecoration(
                  color: preset.indicatorColor.withValues(
                    alpha: isSelected ? 1.0 : 0.45,
                  ),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
