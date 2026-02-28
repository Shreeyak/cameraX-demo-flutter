# Changelog

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
