# Architecture & Widget Decisions

This document explains the architecture of the Flutter + Kotlin CameraX app and why each widget and pattern was chosen.

---

## High-level architecture

```
┌─────────────────────────────────────────────────┐
│  Flutter (Dart) — lib/main.dart                 │
│                                                 │
│  PlatformViewLink  ← live camera preview        │
│  MethodChannel     ← commands (start, save, …)  │
│  EventChannel      ← analysis frame stream      │
│  Flutter widgets   ← buttons, overlays, text    │
└──────────────┬──────────────────────────────────┘
               │ Platform Channels
┌──────────────▼──────────────────────────────────┐
│  Android (Kotlin) — MainActivity.kt             │
│                                                 │
│  CameraPreviewFactory → camerax-preview view    │
│  MethodChannel handler → dispatches to manager  │
│  EventChannel handler  → wires frame sink       │
│                                                 │
│  CameraManager.kt:                              │
│    ProcessCameraProvider                         │
│    Preview → PreviewView (PlatformView)          │
│    ImageCapture (4K)                             │
│    ImageAnalysis → JPEG → EventSink             │
│    Camera2 interop (AF/AE/AWB control)           │
└─────────────────────────────────────────────────┘
```

All camera logic lives in Kotlin. Flutter only handles UI rendering and user interaction.

---

## Platform channel choices

### MethodChannel (`com.example.camerax/control`)

**What it does**: Request/response communication. Dart calls a method, Kotlin executes it, returns a result.

**Why MethodChannel, not EventChannel**: Every camera command (`startCamera`, `saveFrame`, `toggleAwb`, `requestPermission`, `getResolution`) is a discrete request that returns a single result. MethodChannel is designed for exactly this — one call, one reply. EventChannel would be wrong here because these are not continuous streams.

**Why a single channel for all commands**: Keeps the API surface small. The `call.method` string acts as a router. Adding new commands (e.g., `setExposure`, `toggleAf`) requires only adding a `when` branch in Kotlin and a static method in the Dart `CameraControl` class.

### EventChannel (`com.example.camerax/frames`)

**What it does**: Streams ImageAnalysis frames (JPEG-compressed) from Kotlin to Dart continuously.

**Why EventChannel, not MethodChannel**: Frame data is a continuous stream at ~10-15 fps. MethodChannel would require Dart to poll, which is wasteful and introduces latency. EventChannel provides a native push-based stream: Kotlin pushes frames into `EventSink`, Dart receives them via `Stream.listen()`.

**Why JPEG over raw RGBA**: Raw RGBA at 640×480 = ~1.2 MB per frame. JPEG at quality 80 = ~30–50 KB per frame. That's a 25–40× reduction in data crossing the platform boundary. `Image.memory()` in Flutter can decode JPEG natively, so no extra parsing code is needed on the Dart side.

**Why not use the PlatformView for thumbnails too**: The thumbnail needs to be rendered inside a Flutter widget (the bottom bar overlay), where Flutter controls layout, sizing, and positioning. A PlatformView is a heavyweight native view — embedding a second one just for a thumbnail would be wasteful and hard to style consistently with Flutter widgets.

---

## Widget choices (Dart side)

### `PlatformViewLink` + `AndroidViewSurface` + `initExpensiveAndroidView` (camera preview)

```dart
PlatformViewLink(
  viewType: 'camerax-preview',
  surfaceFactory: (context, controller) {
    return AndroidViewSurface(
      controller: controller as AndroidViewController,
      hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      gestureRecognizers: const {},
    );
  },
  onCreatePlatformView: (params) {
    return PlatformViewsService.initExpensiveAndroidView(...)
      ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
      ..create();
  },
)
```

**Why not plain `AndroidView`**: `AndroidView` uses **Virtual Display** mode by default. In Virtual Display mode, the native view is rendered into an off-screen buffer (virtual display), then composited as a texture in Flutter. This creates two problems for camera:

1. **Z-ordering is broken** — the texture sits at a fixed layer. Flutter widgets (our toolbar, bottom bar, buttons) cannot reliably render *on top of* the virtual display texture. This is why the first version showed only the camera preview with no visible overlay UI.
2. **SurfaceView issues** — CameraX's `PreviewView` uses `SurfaceView` internally, which has special compositing behavior that conflicts with Virtual Display rendering on some devices (black screen, flickering).

**Hybrid Composition** (`initExpensiveAndroidView`) solves both: the native view is inserted directly into the view hierarchy, composited by the Android window manager alongside Flutter's surface. Flutter overlays render correctly on top.

The "expensive" in the name refers to the per-frame compositing cost (the GPU must composite two surfaces). For a camera app this cost is negligible since the camera preview is always full-screen and always being redrawn anyway.

**Why `hitTestBehavior: PlatformViewHitTestBehavior.opaque`**: The camera preview fills the screen. We don't need tap/gesture events to pass *through* the preview to widgets behind it. `opaque` tells Flutter not to bother hit-testing below this view, which is a slight optimization.

### `Stack` with `Positioned` children (layout)

```dart
Stack(
  children: [
    Positioned.fill(child: cameraPreview),  // layer 0: full-screen preview
    Positioned(right: 0, ..., child: toolbar),  // layer 1: right toolbar
    Positioned(bottom: 0, ..., child: bottomBar),  // layer 2: bottom bar
  ],
)
```

**Why Stack**: The UI is a camera viewfinder with overlay controls — a classic stack-based composition. The camera preview occupies the full screen, and the toolbar + bottom bar float on top of it. `Stack` is the natural widget for this: children are painted in order, with later children on top.

**Why not Column or Row**: These would force the camera preview to share space with the controls (e.g., preview takes 70% height, bar takes 30%), leaving black bars. A camera app should use the full screen for the viewfinder with controls overlaid transparently.

### `SafeArea` (notch/navigation bar avoidance)

```dart
SafeArea(
  child: Center(child: toolbarContent),
)
```

**Why**: On modern phones with notches, punch-holes, and gesture navigation bars, content placed at screen edges can be obscured. `SafeArea` insets its child to avoid these system UI elements. The toolbar uses it to avoid the status bar / notch, and the bottom bar uses `SafeArea(top: false)` to avoid the gesture navigation bar without adding unnecessary top padding.

### `Image.memory` with `gaplessPlayback: true` (thumbnail)

```dart
Image.memory(
  _thumbnailBytes!,
  fit: BoxFit.contain,
  gaplessPlayback: true,
)
```

**Why `Image.memory`**: The analysis frames arrive as `Uint8List` (JPEG bytes) via EventChannel. `Image.memory` decodes raw bytes directly without needing a file path, asset reference, or network URL. It's the most direct way to display in-memory image data.

**Why `gaplessPlayback: true`**: Without this, every time `_thumbnailBytes` changes (every frame), Flutter briefly shows nothing while decoding the new image, causing visible flicker. `gaplessPlayback: true` tells Flutter to keep displaying the previous frame until the new one is decoded. This produces smooth, flicker-free updates.

**Why `BoxFit.contain`**: The analysis frames are 640×480 (4:3) but the thumbnail container is 160×120 (also 4:3). `contain` scales the image to fit entirely within the container while preserving aspect ratio. If the aspect ratios ever diverge (e.g., different camera sensor), `contain` avoids cropping — you always see the full frame.

### `ElevatedButton.icon` (save button)

```dart
ElevatedButton.icon(
  onPressed: _cameraStarted && !_saving ? _saveFrame : null,
  icon: _saving ? CircularProgressIndicator(...) : Icon(Icons.save),
  label: Text(_saving ? 'Capturing…' : 'Save Frame'),
)
```

**Why `ElevatedButton.icon`**: Provides both an icon and text label, making the button's purpose immediately clear. The `.icon` constructor is a Material Design pattern for primary actions.

**Why swap icon to `CircularProgressIndicator` while saving**: Gives immediate visual feedback that the capture is in progress. The button is also disabled (`onPressed: null`) during save to prevent double-taps. This is a common UX pattern for async actions.

### `_ToolbarButton` with `GestureDetector` (AWB toggle)

```dart
GestureDetector(
  onTap: onTap,
  child: Container(
    decoration: BoxDecoration(
      color: isActive ? Colors.green.withValues(alpha: 0.2) : Colors.transparent,
    ),
    child: Column(children: [Icon(...), Text(...)]),
  ),
)
```

**Why a custom widget instead of `IconButton`**: `IconButton` has fixed padding (48×48 minimum touch target) and limited visual customization. Our toolbar needs:
- Color that changes with state (red OFF / green ON)
- A text label below the icon
- Background highlight when active
- Compact sizing to fit a narrow 56dp toolbar strip

A custom `GestureDetector` + `Container` + `Column` gives full control over all of these.

**Why `Column` with `Icon` + `Text`**: Vertically stacks the icon and its label in a narrow toolbar. This is a common pattern for camera app controls (see Google Camera, Open Camera) — small icons with short labels.

### `Container` with `BoxDecoration` (bottom bar, toolbar backgrounds)

```dart
Container(
  decoration: BoxDecoration(
    color: Colors.black.withValues(alpha: 0.8),
  ),
)
```

**Why semi-transparent black**: The overlays need to be readable against an arbitrary camera scene. A solid black background would hide too much of the preview. Full transparency would make text unreadable against bright scenes. 80% opacity for the bottom bar and 60% for the toolbar provide a good balance — text is always readable, but you can still see the camera feed through the overlay.

### `ScaffoldMessenger.showSnackBar` (save/error feedback)

**Why SnackBar**: Snackbars are the Material Design pattern for brief, non-blocking feedback messages. Save success ("Saved: Pictures/CameraXDemo/...") and errors ("Save failed: ...") are transient notifications that shouldn't interrupt the camera workflow. A dialog would require the user to dismiss it; a toast (Android-only) isn't available in Flutter's material library without plugins.

---

## Kotlin-side design choices

### `CameraManager` as a plain class (not an Activity, Service, or Fragment)

**Why**: CameraX binds to a `LifecycleOwner`. `FlutterActivity` already implements `LifecycleOwner`, so we pass `this` from `MainActivity`. Extracting camera logic into a standalone class (rather than extending `Activity`) makes it:
- **Testable** — can be instantiated with a mock context/lifecycle
- **Reusable** — could be used from a different activity or a service
- **Clean** — `MainActivity` is a thin wiring layer, `CameraManager` owns all camera logic

### `CameraPreviewView` / `CameraPreviewFactory` (PlatformView)

**Why a factory pattern**: Flutter's `PlatformViewsController` requires a `PlatformViewFactory` that creates `PlatformView` instances on demand. This is Flutter's standard extension point for embedding native views — there's no alternative pattern.

**Why the `onViewCreated` callback**: When Flutter's `AndroidView` creates the native `PreviewView`, `CameraManager` needs a reference to its `surfaceProvider` to bind the CameraX `Preview` use case. The callback bridges this: factory creates the view → callback fires → `CameraManager.setPreviewView()` stores the reference → `startCamera()` can bind to it.

### JPEG compression in `processFrame()` for EventChannel transfer

**Why compress before sending**: The platform channel boundary involves serializing data from Kotlin's JVM heap to Dart's isolate memory. Raw RGBA at 640×480 = 1,228,800 bytes per frame. JPEG at quality 80 = ~30,000–50,000 bytes. Compressing on the Kotlin side (where `Bitmap.compress` is hardware-accelerated on many devices) reduces:
- Memory allocation on both sides
- Serialization time across the channel
- GC pressure in Dart

### `mainHandler.post {}` for EventSink calls

**Why**: `EventChannel.EventSink.success()` must be called on the Android main thread (the thread the Flutter engine's `BinaryMessenger` is bound to). The ImageAnalysis analyzer runs on `cameraExecutor` (a background thread). `Handler(Looper.getMainLooper()).post {}` is the standard Android pattern for dispatching work to the main thread.

---

## Important Bugs encountered and fixes
