# Changelog

## 2026-02-28 — Flutter + Kotlin CameraX Integration

Converted from a standalone native `CameraActivity` to a Flutter app. Flutter handles all UI; Kotlin handles all camera logic accessed via platform channels. `flutter run` now works.

### Architecture

- **`CameraManager.kt`** — All CameraX logic (`Preview`, `ImageCapture` at 4K, `ImageAnalysis` at 640×480, Camera2 interop). `ImageAnalysis` frames JPEG-compressed and pushed to Dart over `EventChannel("com.example.camerax/frames")`. `startCamera()` defers safely if the `PreviewView` PlatformView hasn't been created yet.
- **`CameraPreviewView.kt` / `CameraPreviewFactory.kt`** — `PlatformView` wrapping CameraX `PreviewView`, registered as `"camerax-preview"`. Uses Hybrid Composition (`initExpensiveAndroidView`) so Flutter overlays render above the native camera surface.
- **`MainActivity.kt`** — Registers `MethodChannel("com.example.camerax/control")` with methods: `requestPermission`, `startCamera`, `stopCamera`, `saveFrame`, `toggleAwb`, `getResolution`. Registers the PlatformView factory and EventChannel stream handler.
- **`lib/main.dart`** — Flutter UI: full-screen `PlatformViewLink` for preview, right-edge toolbar (AWB toggle), bottom bar (ImageAnalysis thumbnail, resolution readouts, status, Save Frame button). `CameraControl` class wraps all channel calls.

### Removed

- `CameraActivity.kt`, `activity_camera.xml` — replaced by `CameraManager` + Flutter UI
- `camera` and `camera_android_camerax` pub packages — using native CameraX directly instead
- `run.sh` — no longer needed

### Docs

- **`docs/architecture-decisions.md`** — rationale for platform channel design, widget choices, and Kotlin patterns.

---

## 2026-02-28 — Initial CameraX Native App

Standalone native Android app using CameraX. No Flutter UI.

### Camera

- `ImageAnalysis` (640×480 RGBA_8888) — continuous frame loop with reusable `Bitmap` pool, thumbnail displayed in UI
- `ImageCapture` (4K, `ResolutionStrategy(3840×2160)`) — in-memory JPEG capture, saved to `Pictures/CameraXDemo` via MediaStore with `IS_PENDING` and EXIF orientation
- AF, AE, AWB disabled via `Camera2CameraControl` interop; manual exposure/ISO clamped to `CameraCharacteristics` ranges
- AWB toggle button (Camera2 interop, `CONTROL_AWB_MODE_OFF` ↔ `CONTROL_AWB_MODE_AUTO`)
- `WRITE_EXTERNAL_STORAGE` declared with `maxSdkVersion="28"` for pre-Q devices

### Docs

- **`docs/camerax-api-reference.md`** — CameraX & Camera2 API reference (use cases, resolution strategy, interop, in-memory capture, ZSL).


### Changed

- **Architecture converted to Flutter app with Kotlin CameraX backend** — All camera logic remains in Kotlin; Flutter handles the full UI. The app now launches via `MainActivity` (Flutter's `FlutterActivity`) and runs with `flutter run`.

- **`CameraManager.kt`** — New class encapsulating all CameraX logic extracted from the old `CameraActivity`:
  - Supports `startCamera()`, `stopCamera()`, `saveFrame()`, `toggleAwb()`, `getResolutionInfo()`
  - `ImageAnalysis` frames JPEG-compressed (quality 80) and streamed to Dart via `EventChannel`
  - Deferred-start mechanism: if `startCamera()` is called before the native `PreviewView` exists, the call is queued and executed automatically once the `PlatformView` is ready
  - All callbacks delivered on the main thread for safe channel dispatch

- **`CameraPreviewView.kt` and `CameraPreviewFactory.kt`** — Flutter `PlatformView` wrapping CameraX's `PreviewView`. Registered under viewType `"camerax-preview"`. Uses Hybrid Composition so Flutter overlays render correctly on top of the native camera surface.

- **`MainActivity.kt`** — Wires Flutter engine to Kotlin camera backend:
  - `MethodChannel("com.example.camerax/control")` — handles `startCamera`, `stopCamera`, `saveFrame`, `toggleAwb`, `getResolution`, `requestPermission`
  - `EventChannel("com.example.camerax/frames")` — streams JPEG analysis frames to Dart continuously
  - `PlatformViewFactory` registered for the camera preview

- **`lib/main.dart`** — Full Flutter UI replacing the old counter template:
  - Full-screen camera preview via `PlatformViewLink` (Hybrid Composition)
  - Right-edge vertical toolbar with AWB toggle button (red = OFF, green = AUTO), extensible for future controls
  - Bottom overlay bar with ImageAnalysis thumbnail, capture and analysis resolution readouts, AF/AE/AWB status, and Save Frame button
  - `SnackBar` feedback on save success/failure
  - `CameraControl` static helper class wrapping all `MethodChannel` and `EventChannel` calls

- **`pubspec.yaml`** — Removed `camera` and `camera_android_camerax` Flutter plugins (replaced by native CameraX via platform channels).

- **`build.gradle.kts`** — Removed `viewBinding` and `activity-ktx` (no longer required without the native Activity layout).

- **`AndroidManifest.xml`** — `MainActivity` is now the launcher activity.

### Removed

- `CameraActivity.kt` — replaced by `CameraManager` + Flutter UI
- `activity_camera.xml` — replaced by Flutter widget layout
- `CameraTheme` — no longer needed; Flutter handles theming
- `run.sh` — no longer needed; `flutter run` works directly

### Added

- **`docs/architecture-decisions.md`** — Documents platform channel design choices, widget selections with rationale, Kotlin-side patterns, and discovered gotchas (PlatformView compositing modes, bitmap lifecycle, deferred camera initialization).

---

## 2026-02-28 — Initial CameraX Capture App

### Added

- **`CameraActivity`** — Native Android activity (bypasses Flutter) that launches as the main entry point.
  - Live camera preview via CameraX `PreviewView`
  - Continuous in-memory frame capture loop using `ImageAnalysis` (RGBA_8888, 640×480)
  - Small thumbnail of the latest analysis frame displayed in the UI
  - 4K still image capture via `ImageCapture` with `ResolutionStrategy(3840×2160)`
  - Resolution verification: capture and analysis dimensions displayed on-screen and logged
  - **Save Frame** button writes JPEG to `Pictures/CameraXDemo` via MediaStore
  - AF, AE, and AWB disabled via `Camera2CameraControl` interop (`CONTROL_AF_MODE_OFF`, `CONTROL_AE_MODE_OFF`, `CONTROL_AWB_MODE_OFF`)
  - Manual exposure parameters (`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`) clamped to device-supported ranges queried from `CameraCharacteristics`
  - EXIF orientation tag written to saved files using `ExifInterface`
  - `IS_PENDING` flag used for atomic MediaStore writes on API 29+
  - `WRITE_EXTERNAL_STORAGE` permission declared with `maxSdkVersion="28"` for pre-Q devices
  - Reusable `Bitmap` pool for analysis frames to reduce GC pressure
  - Proper `onDestroy` cleanup: ImageView cleared before bitmap recycle, executor awaits termination
  - Error handling on Camera2 interop requests with UI feedback

- **Layout** (`activity_camera.xml`) — `PreviewView` + bottom overlay with thumbnail, resolution text, AF/AE/AWB status, and save button.

- **Theme** (`CameraTheme`) — Fullscreen `Theme.AppCompat.NoActionBar` with black background.

- **Dependencies** added to `build.gradle.kts`:
  - `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` (CameraX 1.4.0)
  - `appcompat`, `activity-ktx`, `core-ktx`
  - `exifinterface:1.3.7`
  - `guava:33.3.1-android` (for `ListenableFuture`)
  - `viewBinding` enabled

- **Manifest** — Camera permission, `CameraActivity` registered as launcher, Flutter `MainActivity` retained but not default.

- **`.gitignore`** — Expanded with comprehensive Flutter + Android ignores (Gradle, iOS/macOS, web, Linux/Windows generated files).

- **`docs/camerax-api-reference.md`** — CameraX & Camera2 API reference document covering architecture, use cases, in-memory capture, ZSL, resolution strategy, Camera2 interop, output formats, camera controls, and annotated sample code walkthroughs.
