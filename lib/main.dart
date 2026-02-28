import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
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
  static Future<bool> toggleAwb() async {
    final enabled = await _method.invokeMethod<bool>('toggleAwb');
    return enabled ?? false;
  }

  /// Get current resolution info.
  static Future<Map<String, dynamic>> getResolution() async {
    final result = await _method.invokeMethod<Map>('getResolution');
    return Map<String, dynamic>.from(result ?? {});
  }

  /// Stream of analysis frames as maps with 'bytes', 'width', 'height'.
  static Stream<Map<dynamic, dynamic>> get frameStream => _frames
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
  bool _awbEnabled = false;
  String _captureResolution = '--';
  String _analysisResolution = '--';
  String _statusText = 'Initializing...';

  // Analysis frame thumbnail
  Uint8List? _thumbnailBytes;
  StreamSubscription<Map<dynamic, dynamic>>? _frameSub;

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
        _statusText = 'AF: OFF | AE: OFF | AWB: OFF';
      });

      // Start listening to analysis frames
      _listenToFrames();
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

  @override
  void dispose() {
    _frameSub?.cancel();
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

  Future<void> _toggleAwb() async {
    try {
      final enabled = await CameraControl.toggleAwb();
      if (!mounted) return;
      setState(() {
        _awbEnabled = enabled;
        final awbLabel = enabled ? 'AUTO' : 'OFF';
        _statusText = 'AF: OFF | AE: OFF | AWB: $awbLabel';
      });
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('AWB toggle failed: $e'),
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

          // ── Bottom overlay ──
          Positioned(left: 0, right: 0, bottom: 0, child: _buildBottomBar()),
        ],
      ),
    );
  }

  /// Right-edge vertical toolbar with toggle buttons.
  Widget _buildToolbar() {
    return SafeArea(
      child: Center(
        child: Container(
          width: 56,
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.6),
            borderRadius: const BorderRadius.only(
              topLeft: Radius.circular(8),
              bottomLeft: Radius.circular(8),
            ),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // AWB toggle
              _ToolbarButton(
                icon: Icons.wb_auto,
                label: 'AWB',
                isActive: _awbEnabled,
                onTap: _toggleAwb,
              ),
              // Future buttons go here (AF, AE, zoom, etc.)
            ],
          ),
        ),
      ),
    );
  }

  /// Bottom overlay bar with thumbnail, resolution, status, and save button.
  Widget _buildBottomBar() {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
      decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.8)),
      child: SafeArea(
        top: false,
        child: Row(
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
                    style: const TextStyle(color: Colors.amber, fontSize: 12),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),

                  // Save button
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton.icon(
                      onPressed: _cameraStarted && !_saving ? _saveFrame : null,
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
      ),
    );
  }
}

// ── Reusable toolbar button widget ──────────────────────────────────────

class _ToolbarButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isActive;
  final VoidCallback onTap;

  const _ToolbarButton({
    required this.icon,
    required this.label,
    required this.isActive,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 48,
        padding: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(
          color: isActive
              ? Colors.green.withValues(alpha: 0.2)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              icon,
              size: 28,
              color: isActive ? Colors.greenAccent : Colors.redAccent,
            ),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 9,
                fontWeight: FontWeight.bold,
                color: isActive ? Colors.greenAccent : Colors.redAccent,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
