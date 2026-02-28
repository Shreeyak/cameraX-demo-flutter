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
  static Future<bool> toggleAwb() async {
    final enabled = await _method.invokeMethod<bool>('toggleAwb');
    return enabled ?? false;
  }

  /// Set a manual color temperature in Kelvin (AWB must be OFF).
  static Future<void> setColorTemperature(int kelvin) =>
      _method.invokeMethod('setColorTemperature', {'kelvin': kelvin});

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
  bool _awbEnabled = false;
  bool _awbPending = false; // true while a toggleAwb call is in flight
  int _colorTempK = 5500; // current manual color temperature (Kelvin)
  bool _tempPending =
      false; // true while a setColorTemperature request is in flight
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
        // Optimistic default assuming defaults; confirmed state arrives via event stream.
        _statusText = 'AF: ON | AE: ON | AWB: ON';
      });

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

  Future<void> _toggleAwb() async {
    // Ignore taps while a previous request is still in flight.
    if (_awbPending) return;
    setState(() => _awbPending = true);
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
    } finally {
      if (mounted) setState(() => _awbPending = false);
    }
  }

  /// Set manual color temperature. Updates the UI immediately (optimistic) and
  /// sends requests to the camera, looping until the latest value is applied.
  Future<void> _setColorTemperature(int kelvin) async {
    if (_awbEnabled) return; // AWB must be OFF for manual temperature
    // Immediate UI feedback — do not wait for the camera round-trip.
    setState(() => _colorTempK = kelvin);
    // Gate actual camera requests: if one is already in flight, the while-loop
    // below will catch any value changes that happen while we wait.
    if (_tempPending) return;
    setState(() => _tempPending = true);
    try {
      while (true) {
        final targetK = _colorTempK;
        await CameraControl.setColorTemperature(targetK);
        if (!mounted) return;
        // If the user kept dragging while we waited, apply the new value.
        if (_colorTempK == targetK) break;
      }
    } catch (e) {
      if (!mounted) return;
      debugPrint('Set color temperature failed: $e');
    } finally {
      if (mounted) setState(() => _tempPending = false);
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
                isPending: _awbPending,
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
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Color temperature slider (visible when camera is running) ──
            if (_cameraStarted) ...[
              Row(
                children: [
                  const Icon(Icons.thermostat, size: 14, color: Colors.white54),
                  const SizedBox(width: 4),
                  Text(
                    'Color Temp${_awbEnabled ? ' (AWB ON)' : ''}',
                    style: TextStyle(
                      color: _awbEnabled ? Colors.white38 : Colors.white70,
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              _TemperatureSlider(
                value: _colorTempK,
                enabled: !_awbEnabled,
                onChanged: _setColorTemperature,
              ),
              const SizedBox(height: 10),
            ],

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

// ── Reusable toolbar button widget ──────────────────────────────────────

class _ToolbarButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool isActive;

  /// When true the button is visually dimmed and taps are ignored.
  final bool isPending;
  final VoidCallback onTap;

  const _ToolbarButton({
    required this.icon,
    required this.label,
    required this.isActive,
    required this.onTap,
    this.isPending = false,
  });

  @override
  Widget build(BuildContext context) {
    final color = isPending
        ? Colors.grey
        : (isActive ? Colors.greenAccent : Colors.redAccent);
    return GestureDetector(
      onTap: isPending ? null : onTap,
      child: Opacity(
        opacity: isPending ? 0.4 : 1.0,
        child: Container(
          width: 48,
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: isActive && !isPending
                ? Colors.green.withValues(alpha: 0.2)
                : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 28, color: color),
              const SizedBox(height: 2),
              Text(
                label,
                style: TextStyle(
                  fontSize: 9,
                  fontWeight: FontWeight.bold,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
// ── Temperature slider ─────────────────────────────────────────────────

/// A horizontal touch-controlled color-temperature slider.
///
/// Displays a warm→cool gradient strip with tick marks at every [step] Kelvin
/// and labels at every 1000 K. The user can tap or drag anywhere on the strip
/// to set the value; the current position is shown by a white marker line.
class _TemperatureSlider extends StatefulWidget {
  const _TemperatureSlider({
    required this.value,
    required this.onChanged,
    this.enabled = true,
  });

  final int value;
  final ValueChanged<int> onChanged;
  final bool enabled;

  static const int kMin = 2000;
  static const int kMax = 10000;
  static const int kStep = 250;

  @override
  State<_TemperatureSlider> createState() => _TemperatureSliderState();
}

class _TemperatureSliderState extends State<_TemperatureSlider> {
  double? _dragStartX;
  int? _dragStartValue;

  int _snap(double rawK) {
    const min = _TemperatureSlider.kMin;
    const max = _TemperatureSlider.kMax;
    const step = _TemperatureSlider.kStep;
    final snapped = ((rawK - min) / step).round() * step + min;
    return snapped.clamp(min, max);
  }

  void _onPanStart(DragStartDetails d) {
    _dragStartX = d.localPosition.dx;
    _dragStartValue = widget.value;
  }

  void _onPanUpdate(DragUpdateDetails d, double width) {
    if (_dragStartX == null || _dragStartValue == null) return;
    final dx = d.localPosition.dx - _dragStartX!;
    final deltaK =
        (dx / width) * (_TemperatureSlider.kMax - _TemperatureSlider.kMin);
    widget.onChanged(_snap(_dragStartValue! + deltaK));
  }

  void _onTapDown(TapDownDetails d, double width) {
    final frac = (d.localPosition.dx / width).clamp(0.0, 1.0);
    widget.onChanged(
      _snap(
        _TemperatureSlider.kMin +
            frac * (_TemperatureSlider.kMax - _TemperatureSlider.kMin),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        return Opacity(
          opacity: widget.enabled ? 1.0 : 0.35,
          child: GestureDetector(
            onPanStart: widget.enabled ? _onPanStart : null,
            onPanUpdate: widget.enabled ? (d) => _onPanUpdate(d, w) : null,
            onTapDown: widget.enabled ? (d) => _onTapDown(d, w) : null,
            child: CustomPaint(
              size: Size(w, 72),
              painter: _TemperatureScalePainter(value: widget.value),
            ),
          ),
        );
      },
    );
  }
}

// ── Temperature scale painter ──────────────────────────────────────────

class _TemperatureScalePainter extends CustomPainter {
  const _TemperatureScalePainter({required this.value});

  final int value;

  static const _min = _TemperatureSlider.kMin;
  static const _max = _TemperatureSlider.kMax;
  static const _step = _TemperatureSlider.kStep;

  // Warm-to-cool gradient stops matching the 2000–10000 K range.
  static const _gradientColors = [
    Color(0xFFFF4500), // 2000 K – deep orange
    Color(0xFFFFAA00), // 3000 K – amber
    Color(0xFFFFE4B0), // 4000 K – warm yellow-white
    Color(0xFFFFFFFF), // 5500 K – neutral white
    Color(0xFFD0E8FF), // 6500 K – slightly blue
    Color(0xFF90B8FF), // 8000 K – cool blue
    Color(0xFF5888F5), // 10000 K – deep blue
  ];
  // stops = (K - 2000) / (10000 - 2000)
  static const _gradientStops = [0.0, 0.125, 0.25, 0.4375, 0.5625, 0.75, 1.0];

  @override
  void paint(Canvas canvas, Size size) {
    final double range = (_max - _min).toDouble();
    final rrect = RRect.fromRectAndRadius(
      Rect.fromLTWH(0, 0, size.width, size.height),
      const Radius.circular(10),
    );

    // ── Gradient background ──
    final gradient = const LinearGradient(
      colors: _gradientColors,
      stops: _gradientStops,
    );
    canvas.drawRRect(
      rrect,
      Paint()
        ..shader = gradient.createShader(
          Rect.fromLTWH(0, 0, size.width, size.height),
        ),
    );
    // Darken overlay for tick/label contrast
    canvas.drawRRect(rrect, Paint()..color = const Color(0x40000000));

    // ── Tick marks ──
    final tickPaint = Paint()
      ..color = const Color(0xBFFFFFFF)
      ..strokeWidth = 1.0;
    final tp = TextPainter(textDirection: TextDirection.ltr);

    for (int k = _min; k <= _max; k += _step) {
      final x = (k - _min) / range * size.width;
      final isMajor = k % 1000 == 0;
      final tickTop = size.height - (isMajor ? 22.0 : 11.0);
      canvas.drawLine(Offset(x, tickTop), Offset(x, size.height), tickPaint);

      if (isMajor) {
        tp.text = TextSpan(
          text: '${k ~/ 1000}K',
          style: const TextStyle(
            color: Color(0xFFFFFFFF),
            fontSize: 9,
            fontWeight: FontWeight.w600,
            shadows: [Shadow(blurRadius: 2, color: Color(0x88000000))],
          ),
        );
        tp.layout();
        final lx = (x - tp.width / 2).clamp(0.0, size.width - tp.width);
        tp.paint(canvas, Offset(lx, tickTop - tp.height - 1));
      }
    }

    // ── Current value indicator line ──
    final cx = (value - _min) / range * size.width;
    canvas.drawLine(
      Offset(cx, 0),
      Offset(cx, size.height),
      Paint()
        ..color = const Color(0xFFFFFFFF)
        ..strokeWidth = 2.5,
    );

    // Triangle pointer at top of indicator
    final tri = Path()
      ..moveTo(cx - 6, 0)
      ..lineTo(cx + 6, 0)
      ..lineTo(cx, 9)
      ..close();
    canvas.drawPath(tri, Paint()..color = const Color(0xFFFFFFFF));
    canvas.drawPath(
      tri,
      Paint()
        ..color = const Color(0x66000000)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 0.5,
    );

    // ── Value label pill ──
    tp.text = TextSpan(
      text: '${value}K',
      style: const TextStyle(
        color: Color(0xFFFFFFFF),
        fontSize: 11,
        fontWeight: FontWeight.bold,
        shadows: [Shadow(blurRadius: 3, color: Color(0xFF000000))],
      ),
    );
    tp.layout();
    final pillW = tp.width + 10;
    final pillX = (cx - pillW / 2).clamp(0.0, size.width - pillW);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(pillX, 10, pillW, 18),
        const Radius.circular(4),
      ),
      Paint()..color = const Color(0x88000000),
    );
    tp.paint(canvas, Offset(pillX + 5, 13));
  }

  @override
  bool shouldRepaint(_TemperatureScalePainter old) => old.value != value;
}
