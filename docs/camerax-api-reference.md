# CameraX & Camera2 API Reference for Android Camera Integration

> **Purpose**: This document is a structured reference for LLMs generating Android camera code. It consolidates official Android documentation, API signatures, sample code patterns, and architectural decisions for CameraX and Camera2. All information is sourced from `developer.android.com` and `github.com/android/camera-samples`.

---

## Table of Contents

1. [Documentation Sources](#1-documentation-sources)
2. [CameraX Architecture Overview](#2-camerax-architecture-overview)
3. [CameraX Use Cases](#3-camerax-use-cases)
4. [Image Capture — In-Memory Buffer](#4-image-capture--in-memory-buffer)
5. [Zero-Shutter Lag (ZSL)](#5-zero-shutter-lag-zsl)
6. [Capture Mode Configuration](#6-capture-mode-configuration)
7. [Rotation Control](#7-rotation-control)
8. [Resolution Strategy — Exact 4K](#8-resolution-strategy--exact-4k)
9. [CameraX Configuration (CameraXConfig)](#9-camerax-configuration-cameraxconfig)
10. [Camera2 Interoperability](#10-camera2-interoperability)
11. [Camera2 Basic — JPEG, RAW, DEPTH Capture](#11-camera2-basic--jpeg-raw-depth-capture)
12. [Camera2 Extensions — Live Preview & Still Capture](#12-camera2-extensions--live-preview--still-capture)
13. [CameraX Basic — Full Sample Walkthrough](#13-camerax-basic--full-sample-walkthrough)
14. [Output Formats Reference](#14-output-formats-reference)
15. [Camera Controls (Zoom, Torch, Focus, Exposure)](#15-camera-controls-zoom-torch-focus-exposure)
16. [Sample Comparison Table](#16-sample-comparison-table)

---

## 1. Documentation Sources

### Official API References

| Topic | URL |
|---|---|
| CameraX Architecture | https://developer.android.com/media/camera/camerax/architecture |
| CameraX Configuration | https://developer.android.com/media/camera/camerax/configuration |
| Image Capture Guide | https://developer.android.com/media/camera/camerax/take-photo |
| Capture Options (mode, flash, format) | https://developer.android.com/media/camera/camerax/take-photo/options |
| Zero-Shutter Lag | https://developer.android.com/media/camera/camerax/take-photo/zsl |
| `ImageCapture` class | https://developer.android.com/reference/androidx/camera/core/ImageCapture |
| `ImageAnalysis` class | https://developer.android.com/reference/androidx/camera/core/ImageAnalysis |
| `ResolutionStrategy` class | https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionStrategy |
| `ResolutionSelector` class | https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionSelector |
| Camera2 Interop package | https://developer.android.com/reference/androidx/camera/camera2/interop/package-summary |

### Official Code Samples

| Sample | Description | URL |
|---|---|---|
| CameraXBasic | CameraX preview, capture, analysis | https://github.com/android/camera-samples/tree/main/CameraXBasic |
| Camera2Basic | JPEG, RAW, DEPTH capture via Camera2 | https://github.com/android/camera-samples/tree/main/Camera2Basic |
| Camera2Extensions | Extension live preview & still capture | https://github.com/android/camera-samples/tree/main/Camera2Extensions |
| CameraXExtensions | CameraX extension preview & capture | https://github.com/android/camera-samples/tree/main/CameraXExtensions |
| CameraXVideo | CameraX VideoCapture API | https://github.com/android/camera-samples/tree/main/CameraXVideo |

### Dependencies (latest version: 1.6.0-rc01)

```groovy
dependencies {
    def camerax_version = "1.6.0-rc01"
    implementation "androidx.camera:camera-core:${camerax_version}"
    implementation "androidx.camera:camera-camera2:${camerax_version}"
    implementation "androidx.camera:camera-lifecycle:${camerax_version}"
    implementation "androidx.camera:camera-video:${camerax_version}"
    implementation "androidx.camera:camera-view:${camerax_version}"
    implementation "androidx.camera:camera-mlkit-vision:${camerax_version}"
    implementation "androidx.camera:camera-extensions:${camerax_version}"
}
```

Minimum requirements: Android API level 21, Android Architecture Components 1.1.1.

---

## 2. CameraX Architecture Overview

CameraX abstracts camera operations into **use cases**:

| Use Case | Purpose |
|---|---|
| `Preview` | Displays camera viewfinder |
| `ImageCapture` | Takes still photos |
| `ImageAnalysis` | Provides CPU-accessible frames for processing |
| `VideoCapture` | Records video and audio |

### Two API Models

**CameraController** (simpler):
```kotlin
val cameraController = LifecycleCameraController(baseContext)
cameraController.bindToLifecycle(this)
cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
previewView.controller = cameraController
```
- Auto-handles initialization, tap-to-focus, pinch-to-zoom
- Default use cases: Preview, ImageCapture, ImageAnalysis
- Requires `PreviewView`

**CameraProvider** (more flexible):
```kotlin
val cameraProvider = ProcessCameraProvider.getInstance(context).await()
val camera = cameraProvider.bindToLifecycle(
    lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalyzer
)
preview.setSurfaceProvider(viewFinder.surfaceProvider)
```
- Allows custom `Surface` for preview
- More control over configuration
- App manages use case setup explicitly

### Lifecycle Binding

All use cases are bound to an Android `LifecycleOwner`. CameraX handles opening/closing the camera automatically based on lifecycle state. Use a single `bindToLifecycle()` call for all concurrent use cases:

```kotlin
val camera = cameraProvider.bindToLifecycle(
    this as LifecycleOwner,
    cameraSelector,
    preview,
    imageCapture,
    imageAnalyzer
)
```

For advanced cases, a custom `LifecycleOwner` can be created to manually control camera start/stop.

### Concurrent Use Case Rules

- One instance each of `Preview`, `VideoCapture`, `ImageAnalysis`, `ImageCapture` can run simultaneously
- With extensions enabled, only `ImageCapture` + `Preview` is guaranteed
- On `FULL` or lower hardware level, combining `Preview` + `VideoCapture` + (`ImageCapture` or `ImageAnalysis`) may trigger stream sharing (higher latency)

---

## 3. CameraX Use Cases

### Preview

```kotlin
val preview = Preview.Builder()
    .setTargetAspectRatio(screenAspectRatio)
    .setTargetRotation(rotation)
    .build()
preview.setSurfaceProvider(viewFinder.surfaceProvider)
```

### ImageCapture

```kotlin
val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setTargetAspectRatio(screenAspectRatio)
    .setTargetRotation(rotation)
    .build()
```

### ImageAnalysis

```kotlin
val imageAnalyzer = ImageAnalysis.Builder()
    .setTargetAspectRatio(screenAspectRatio)
    .setTargetRotation(rotation)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .build()
    .also {
        it.setAnalyzer(executor) { imageProxy ->
            // Process imageProxy
            imageProxy.close() // MUST close to receive next frame
        }
    }
```

---

## 4. Image Capture — In-Memory Buffer

### Two `takePicture` Variants

**Variant 1: Save to file (disk I/O)**
```kotlin
imageCapture.takePicture(outputFileOptions, executor,
    object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val savedUri = output.savedUri
        }
        override fun onError(exc: ImageCaptureException) { }
    }
)
```

**Variant 2: In-memory buffer (no disk I/O)**
```kotlin
imageCapture.takePicture(executor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            // imageProxy contains JPEG data in memory
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // Process bytes directly — no file read needed
            imageProxy.close() // MUST close
        }
        override fun onError(exception: ImageCaptureException) { }
    }
)
```

**Key characteristics of in-memory capture:**
- Returns an `ImageProxy` wrapping the captured image
- Default format is JPEG (encoded but in-memory — no disk round-trip)
- Caller **must** call `imageProxy.close()` when done
- `ImageProxy.getRotationDegrees()` provides the rotation to apply for correct orientation
- `ImageProxy.getCropRect()` provides the crop region if viewport/aspect ratio cropping was applied

### Kotlin Coroutine Extension (suspend function)

```kotlin
val imageProxy: ImageProxy = imageCapture.takePicture(
    onCaptureStarted = { /* shutter animation */ },
    onCaptureProcessProgressed = { progress -> /* 0-100 */ },
    onPostviewBitmapAvailable = { bitmap -> /* low-res preview */ }
)
```

### Simultaneous RAW + JPEG Capture (CameraX 1.5+)

```kotlin
val imageCapture = ImageCapture.Builder()
    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
    .build()

// With OUTPUT_FORMAT_RAW_JPEG, onCaptureSuccess fires TWICE:
// once for RAW_SENSOR, once for JPEG (order not guaranteed)
imageCapture.takePicture(executor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            when (imageProxy.format) {
                ImageFormat.RAW_SENSOR -> { /* raw data */ }
                ImageFormat.JPEG -> { /* jpeg data */ }
            }
            imageProxy.close()
        }
    }
)
```

---

## 5. Zero-Shutter Lag (ZSL)

**Source**: https://developer.android.com/media/camera/camerax/take-photo/zsl

### How It Works

ZSL uses a **ring buffer that stores the 3 most recent capture frames**. When `takePicture()` is called, CameraX retrieves the frame with the timestamp closest to the button press. It then **reprocesses** the capture session to generate a JPEG image from that frame.

### Enabling ZSL

```kotlin
val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
    .build()
```

Falls back to `CAPTURE_MODE_MINIMIZE_LATENCY` if unsupported.

### Prerequisites

| Requirement | Detail |
|---|---|
| API level | Android 6.0+ (API 23) |
| Reprocessing | Device must support `PRIVATE` reprocessing |
| Check support | `CameraInfo.isZslSupported()` |
| Flash | Does **not** work with flash ON or AUTO |
| VideoCapture | Cannot be used simultaneously |
| Extensions | Cannot be used with OEM camera extensions |

### Constants

```
CAPTURE_MODE_ZERO_SHUTTER_LAG = 2  // @ExperimentalZeroShutterLag, added in 1.2.0
```

> **When to use**: Ideal for instant-capture scenarios (e.g., microscope snapshots) where minimizing the delay between trigger and capture is critical. The image quality remains good since it reprocesses a full-resolution frame.

---

## 6. Capture Mode Configuration

**Source**: https://developer.android.com/media/camera/camerax/take-photo/options

```kotlin
ImageCapture.Builder().setCaptureMode(mode)
```

| Mode | Constant | Value | JPEG Quality | Behavior |
|---|---|---|---|---|
| Minimize latency | `CAPTURE_MODE_MINIMIZE_LATENCY` | 1 | 95 | Faster capture, slightly reduced quality (default) |
| Maximize quality | `CAPTURE_MODE_MAXIMIZE_QUALITY` | 0 | 100 | Higher quality, may take longer |
| Zero-shutter lag | `CAPTURE_MODE_ZERO_SHUTTER_LAG` | 2 | — | Ring buffer, experimental, best latency |

Custom JPEG quality can be set explicitly:
```kotlin
ImageCapture.Builder().setJpegQuality(90)
```

---

## 7. Rotation Control

### Default Behavior

By default, CameraX sets rotation to match the default display's rotation at use case creation time.

### Dynamic Rotation for ImageCapture

```kotlin
// Set during construction
val imageCapture = ImageCapture.Builder()
    .setTargetRotation(Surface.ROTATION_0)
    .build()

// Or set dynamically (e.g., for a fixed microscope orientation)
imageCapture.targetRotation = Surface.ROTATION_0
```

**Effect**: Sets the EXIF rotation metadata in saved images, and the `getRotationDegrees()` value of `ImageProxy` returned by `OnImageCapturedCallback`. The value indicates "what rotation to apply to make the image match the target rotation."

### Dynamic Rotation for ImageAnalysis

```kotlin
// Set during construction
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetRotation(Surface.ROTATION_0)
    .build()

// Or set dynamically
imageAnalysis.setTargetRotation(Surface.ROTATION_0)
```

**Effect**: Adjusts `ImageInfo.getRotationDegrees()` on the `ImageProxy` passed to `analyze()`. The rotation value tells the analyzer how to orient the image data to match the target rotation.

### Using OrientationEventListener (for device rotation tracking)

```kotlin
val orientationEventListener = object : OrientationEventListener(context) {
    override fun onOrientationChanged(orientation: Int) {
        val rotation = when (orientation) {
            in 45..134 -> Surface.ROTATION_270
            in 135..224 -> Surface.ROTATION_180
            in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        imageCapture.targetRotation = rotation
        imageAnalysis.setTargetRotation(rotation)
    }
}
orientationEventListener.enable()
```

### Fixed Rotation for Microscope

For a microscope (or any device with a fixed camera orientation), set a constant rotation and skip the OrientationEventListener:

```kotlin
// Lock to no rotation — image data matches sensor orientation directly
imageCapture.targetRotation = Surface.ROTATION_0
imageAnalysis.setTargetRotation(Surface.ROTATION_0)
```

---

## 8. Resolution Strategy — Exact 4K

**Source**: https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionStrategy

### ResolutionStrategy Class (CameraX 1.3+)

Defines a bound size and fallback behavior:

```kotlin
ResolutionStrategy(boundSize: Size, fallbackRule: Int)
```

#### Fallback Rules

| Constant | Value | Behavior |
|---|---|---|
| `FALLBACK_RULE_NONE` | 0 | **Exact match only** — throws `IllegalArgumentException` if unavailable |
| `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` | 1 | Try higher first, then lower (**recommended**) |
| `FALLBACK_RULE_CLOSEST_HIGHER` | 2 | Only fall back to higher resolutions |
| `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` | 3 | Try lower first, then higher |
| `FALLBACK_RULE_CLOSEST_LOWER` | 4 | Only fall back to lower resolutions |
| `HIGHEST_AVAILABLE_STRATEGY` | — | Static instance, selects highest available (no bound) |

### Exact 4K Configuration

```kotlin
val resolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(3840, 2160),                                    // 4K UHD
            ResolutionStrategy.FALLBACK_RULE_NONE                // Exact match required
        )
    )
    .setAspectRatioStrategy(
        AspectRatioStrategy(
            AspectRatio.RATIO_16_9,
            AspectRatioStrategy.FALLBACK_RULE_NONE
        )
    )
    .build()

val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setResolutionSelector(resolutionSelector)
    .build()
```

### Safer 4K Configuration (with fallback)

```kotlin
val resolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(3840, 2160),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )
    )
    .setAspectRatioStrategy(
        AspectRatioStrategy(
            AspectRatio.RATIO_16_9,
            AspectRatioStrategy.FALLBACK_RULE_AUTO
        )
    )
    .build()
```

### ResolutionSelector Pipeline

The `ResolutionSelector` selects resolutions through a 3-step pipeline:

1. **Collect** supported output sizes from the device
2. **Filter and sort** using:
   - `setAspectRatioStrategy()` — aspect ratio preference (default: 4:3 with auto fallback)
   - `setResolutionStrategy()` — bound size + fallback rule
   - `setResolutionFilter()` — custom `ResolutionFilter` implementation for advanced filtering
3. **Final selection** — CameraX considers hardware level, capabilities, and bound use case combination

Aspect ratio strategy has **higher priority** than resolution strategy in sorting.

### Allowed Resolution Modes

| Mode | Behavior |
|---|---|
| `PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION` (default) | Uses sizes from `getOutputSizes()` — normal performance |
| `PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE` | Also includes `getHighResolutionOutputSizes()` — may be slower |

### Checking Selected Resolution

After binding, verify what was actually selected:

```kotlin
val camera = cameraProvider.bindToLifecycle(owner, selector, imageCapture)
val resolutionInfo = imageCapture.resolutionInfo
// resolutionInfo?.resolution -> actual Size selected
// resolutionInfo?.cropRect -> crop rectangle applied
```

---

## 9. CameraX Configuration (CameraXConfig)

**Source**: https://developer.android.com/media/camera/camerax/configuration

Implement `CameraXConfig.Provider` in your `Application` class:

```kotlin
class MainApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
    }
}
```

### Configuration Options

| Method | Purpose |
|---|---|
| `setMinimumLoggingLevel(int)` | `Log.DEBUG` (default), `Log.INFO`, `Log.WARN`, `Log.ERROR` |
| `setAvailableCamerasLimiter(CameraSelector)` | Reduce startup latency by ignoring unused cameras |
| `setCameraExecutor(Executor)` | Custom executor for Camera2 API calls |
| `setSchedulerHandler(Handler)` | Custom handler for internal scheduling |

> **Important**: Don't use an executor that runs on the main thread. Camera operations involve blocking IPC.

---

## 10. Camera2 Interoperability

**Source**: https://developer.android.com/media/camera/camerax/architecture#camerax-interoperability

CameraX is built on Camera2. Interop APIs allow reading/writing Camera2 properties:

### Reading Camera2 Properties

```kotlin
val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
val hardwareLevel = camera2Info.getCameraCharacteristic(
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
)
```

### Writing Camera2 Properties (two pathways)

**1. Camera2CameraControl** — sets properties on the CaptureRequest:
```kotlin
val camera2Control = Camera2CameraControl.from(camera.cameraControl)
camera2Control.setCaptureRequestOptions(
    CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, ...)
        .build()
)
```

**2. Camera2Interop.Extender** — sets properties on a use case builder:
```kotlin
val previewBuilder = Preview.Builder()
Camera2Interop.Extender(previewBuilder)
    .setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL.toLong())

val preview = previewBuilder.build()
```

> **Warning**: Setting Camera2 properties is `@Experimental`. Values override CameraX defaults. Only use when absolutely necessary and test thoroughly.

---

## 11. Camera2 Basic — JPEG, RAW, DEPTH Capture

**Source**: https://github.com/android/camera-samples/tree/main/Camera2Basic

This sample uses the **raw Camera2 API** (no CameraX). Key patterns:

### Opening Camera (coroutine wrapper)

```kotlin
private suspend fun openCamera(
    manager: CameraManager, cameraId: String, handler: Handler?
): CameraDevice = suspendCancellableCoroutine { cont ->
    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) = cont.resume(device)
        override fun onDisconnected(device: CameraDevice) { requireActivity().finish() }
        override fun onError(device: CameraDevice, error: Int) {
            cont.resumeWithException(RuntimeException("Camera error: $error"))
        }
    }, handler)
}
```

### Creating Capture Session

```kotlin
val targets = listOf(viewFinder.holder.surface, imageReader.surface)
session = createCaptureSession(camera, targets, cameraHandler)

// Start preview
val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    .apply { addTarget(viewFinder.holder.surface) }
session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
```

### ImageReader Setup per Format

The pixel format (`JPEG`, `RAW_SENSOR`, `DEPTH_JPEG`) is selected at startup:

```kotlin
val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    .getOutputSizes(pixelFormat).maxByOrNull { it.height * it.width }!!
imageReader = ImageReader.newInstance(size.width, size.height, pixelFormat, IMAGE_BUFFER_SIZE)
```

### Still Capture with Timestamp Synchronization

```kotlin
val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
    .apply { addTarget(imageReader.surface) }

session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(session, request, result: TotalCaptureResult) {
        val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        // Match against Image.timestamp from ImageReader queue
    }
}, cameraHandler)
```

### Saving by Format

```kotlin
when (result.format) {
    ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
        val buffer = result.image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
        FileOutputStream(output).use { it.write(bytes) }
        // Write EXIF orientation for JPEG
        val exif = ExifInterface(output.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
    }
    ImageFormat.RAW_SENSOR -> {
        val dngCreator = DngCreator(characteristics, result.metadata)
        FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
    }
}
```

---

## 12. Camera2 Extensions — Live Preview & Still Capture

**Source**: https://github.com/android/camera-samples/tree/main/Camera2Extensions

Requires **Android SDK 31+**. Uses `CameraExtensionSession` instead of `CameraCaptureSession`.

### Supported Extensions

| Constant | Label |
|---|---|
| `EXTENSION_HDR` | HDR |
| `EXTENSION_NIGHT` | Night mode |
| `EXTENSION_BOKEH` | Bokeh (portrait) |
| `EXTENSION_FACE_RETOUCH` | Face retouch |
| `EXTENSION_AUTO` | Auto |

### Querying Extension Support

```kotlin
val extensionCharacteristics = cameraManager.getCameraExtensionCharacteristics(cameraId)
val supportedExtensions = extensionCharacteristics.supportedExtensions
// Filter by format support:
for (extension in supportedExtensions) {
    val jpegRSizes = extensionCharacteristics.getExtensionSupportedSizes(
        extension, ImageFormat.JPEG_R
    )
}
```

### Creating Extension Session

```kotlin
val outputConfig = listOf(
    OutputConfiguration(stillImageReader.surface),
    OutputConfiguration(previewSurface)
)
val extensionConfig = ExtensionSessionConfiguration(
    currentExtension,
    outputConfig,
    Dispatchers.IO.asExecutor(),
    object : CameraExtensionSession.StateCallback() {
        override fun onConfigured(session: CameraExtensionSession) {
            cameraExtensionSession = session
            // Start repeating preview
            submitRequest(CameraDevice.TEMPLATE_PREVIEW, previewSurface, isRepeating = true)
        }
        override fun onConfigureFailed(session: CameraExtensionSession) { finish() }
    }
)
cameraDevice.createExtensionSession(extensionConfig)
```

### Still Image Format Selection Priority

```kotlin
// 1. JPEG_R (API 35+, if supported)
// 2. JPEG (default)
// 3. YUV_420_888 (fallback)
val jpegSizes = extensionCharacteristics.getExtensionSupportedSizes(extension, ImageFormat.JPEG)
stillFormat = if (jpegSizes.isEmpty()) ImageFormat.YUV_420_888 else ImageFormat.JPEG
```

### Advanced Features (API 34+)

- **Postview**: Low-res preview shown during processing — `extensionConfig.postviewOutputConfiguration`
- **Capture progress**: `StillCaptureLatency` provides estimated durations, UI shows "Hold Still" → "Processing"
- **Extension strength**: `CaptureRequest.EXTENSION_STRENGTH` controls effect intensity
- **Tap-to-focus**: Translate touch coords → sensor `MeteringRectangle`, submit AF trigger
- **Pinch-to-zoom**: `CaptureRequest.CONTROL_ZOOM_RATIO`

---

## 13. CameraX Basic — Full Sample Walkthrough

**Source**: https://github.com/android/camera-samples/tree/main/CameraXBasic

### Application Class — CameraXConfig

```kotlin
class MainApplication : Application(), CameraXConfig.Provider {
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }
}
```

### Camera Setup Flow

```kotlin
private suspend fun setUpCamera() {
    cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
    lensFacing = when {
        hasBackCamera() -> CameraSelector.LENS_FACING_BACK
        hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
        else -> throw IllegalStateException("No camera available")
    }
    bindCameraUseCases()
}
```

### Binding Use Cases

```kotlin
private fun bindCameraUseCases() {
    val metrics = windowManager.getCurrentWindowMetrics().bounds
    val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
    val rotation = viewFinder.display.rotation

    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing).build()

    preview = Preview.Builder()
        .setTargetAspectRatio(screenAspectRatio)
        .setTargetRotation(rotation)
        .build()

    imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setTargetAspectRatio(screenAspectRatio)
        .setTargetRotation(rotation)
        .build()

    imageAnalyzer = ImageAnalysis.Builder()
        .setTargetAspectRatio(screenAspectRatio)
        .setTargetRotation(rotation)
        .build()
        .also {
            it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                Log.d(TAG, "Average luminosity: $luma")
            })
        }

    cameraProvider.unbindAll()
    camera = cameraProvider.bindToLifecycle(
        this, cameraSelector, preview, imageCapture, imageAnalyzer
    )
    preview?.setSurfaceProvider(viewFinder.surfaceProvider)
}
```

### Taking a Picture (save to MediaStore)

```kotlin
val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
    .format(System.currentTimeMillis())
val contentValues = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
    }
}
val outputOptions = ImageCapture.OutputFileOptions.Builder(
    contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
).build()

imageCapture.takePicture(outputOptions, cameraExecutor,
    object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) { Log.e(TAG, "Capture failed", exc) }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            val savedUri = output.savedUri
            Log.d(TAG, "Photo saved: $savedUri")
        }
    }
)
```

### Image Analysis — Luminosity Analyzer

```kotlin
private class LuminosityAnalyzer(listener: LumaListener?) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer      // Y plane of YUV
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val pixels = data.map { it.toInt() and 0xFF }
        val luma = pixels.average()
        listeners.forEach { it(luma) }
        image.close()  // MUST close
    }
}
```

### Aspect Ratio Calculation

```kotlin
private fun aspectRatio(width: Int, height: Int): Int {
    val previewRatio = max(width, height).toDouble() / min(width, height)
    return if (abs(previewRatio - 4.0/3.0) <= abs(previewRatio - 16.0/9.0)) {
        AspectRatio.RATIO_4_3
    } else {
        AspectRatio.RATIO_16_9
    }
}
```

### Display Rotation Listener

```kotlin
private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayChanged(displayId: Int) = view?.let { view ->
        if (displayId == this@CameraFragment.displayId) {
            imageCapture?.targetRotation = view.display.rotation
            imageAnalyzer?.targetRotation = view.display.rotation
        }
    } ?: Unit
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
}
```

---

## 14. Output Formats Reference

### CameraX ImageCapture Output Formats (1.4+)

| Constant | Value | Format | Since |
|---|---|---|---|
| `OUTPUT_FORMAT_JPEG` | 0 | 8-bit SDR JPEG | 1.4.0 |
| `OUTPUT_FORMAT_JPEG_ULTRA_HDR` | 1 | JPEG_R (HDR, backward-compatible) | 1.4.0 |
| `OUTPUT_FORMAT_RAW` | 2 | RAW_SENSOR | 1.5.0 |
| `OUTPUT_FORMAT_RAW_JPEG` | 3 | Simultaneous RAW_SENSOR + JPEG | 1.5.0 |

### ImageAnalysis Output Formats

| Constant | Value | Description |
|---|---|---|
| `OUTPUT_IMAGE_FORMAT_YUV_420_888` | 1 | YUV (default) |
| `OUTPUT_IMAGE_FORMAT_RGBA_8888` | 2 | RGBA — single plane, R,G,B,A byte order |
| `OUTPUT_IMAGE_FORMAT_NV21` | 3 | NV21 — YUV with interleaved VU plane |

### Camera2 ImageFormat Constants

| Format | Use |
|---|---|
| `ImageFormat.JPEG` | Standard compressed |
| `ImageFormat.RAW_SENSOR` | Unprocessed sensor data → save as DNG via `DngCreator` |
| `ImageFormat.DEPTH_JPEG` | Depth data embedded in JPEG |
| `ImageFormat.YUV_420_888` | Flexible YUV for analysis |
| `ImageFormat.JPEG_R` | Ultra HDR (API 35+) |

---

## 15. Camera Controls (Zoom, Torch, Focus, Exposure)

**Source**: https://developer.android.com/media/camera/camerax/configuration

Accessed via `Camera` object returned from `bindToLifecycle()`:

```kotlin
val camera = cameraProvider.bindToLifecycle(...)
val cameraControl = camera.cameraControl
val cameraInfo = camera.cameraInfo
```

### Zoom

```kotlin
// By ratio (within min..max range)
cameraControl.setZoomRatio(2.0f)

// By linear value (0.0 to 1.0) — ideal for slider UI
cameraControl.setLinearZoom(0.5f)

// Observe zoom state
cameraInfo.zoomState.observe(lifecycleOwner) { state ->
    val ratio = state.zoomRatio
    val linear = state.linearZoom
    val minRatio = state.minZoomRatio
    val maxRatio = state.maxZoomRatio
}
```

### Torch

```kotlin
cameraControl.enableTorch(true)  // on
cameraControl.enableTorch(false) // off

// Check availability and state
cameraInfo.hasFlashUnit()  // Boolean
cameraInfo.torchState.observe(lifecycleOwner) { state ->
    // TorchState.ON or TorchState.OFF
}
```

### Focus and Metering (Tap-to-Focus)

```kotlin
val meteringPoint = previewView.meteringPointFactory
    .createPoint(touchEvent.x, touchEvent.y)

val action = FocusMeteringAction.Builder(meteringPoint)         // AF|AE|AWB
    .addPoint(secondPoint, FLAG_AF or FLAG_AE)                  // optional
    .setAutoCancelDuration(3, TimeUnit.SECONDS)                 // default 5s
    .build()

val result = cameraControl.startFocusAndMetering(action)
result.addListener({
    val isFocused = result.get().isFocusSuccessful
}, ContextCompat.getMainExecutor(context))
```

### Exposure Compensation

```kotlin
// Query supported range
val exposureState = cameraInfo.exposureState
val range = exposureState.exposureCompensationRange     // e.g., -12..12
val step = exposureState.exposureCompensationStep       // e.g., Rational(1,6)
val isSupported = exposureState.isExposureCompensationSupported

// Set exposure index
cameraControl.setExposureCompensationIndex(3).addListener({
    val currentIndex = cameraInfo.exposureState.exposureCompensationIndex
}, mainExecutor)
```

---

## 16. Sample Comparison Table

| Feature | CameraXBasic | Camera2Basic | Camera2Extensions |
|---|---|---|---|
| **API** | CameraX (`ProcessCameraProvider`) | Camera2 (`CameraDevice`) | Camera2 Extensions (`CameraExtensionSession`) |
| **Preview surface** | `PreviewView` + `SurfaceProvider` | `SurfaceView` | `TextureView` |
| **Capture formats** | JPEG only | JPEG, RAW (DNG), DEPTH_JPEG | JPEG, YUV, JPEG_R |
| **Image analysis** | Yes (luminosity) | No | No |
| **Camera extensions** | No | No | HDR, Night, Bokeh, Face Retouch |
| **Tap-to-focus** | No (requires manual impl) | No | Yes |
| **Pinch-to-zoom** | No | No | Yes |
| **Min SDK** | 21 | 29 | 31 |
| **Lifecycle mgmt** | Automatic via LifecycleOwner | Manual open/close | Manual open/close |
| **Threading** | `Executors.newSingleThreadExecutor()` | `HandlerThread` + `Handler` | `Dispatchers.IO.asExecutor()` |
| **Capture sync** | Callback-based (simple) | Timestamp matching with timeout | Callback-based |

---

## Quick Reference: Building an ImageCapture Pipeline

For an optimized CameraX image capture pipeline targeting 4K with in-memory buffers and minimal latency:

```kotlin
// 1. Resolution strategy — exact 4K
val resolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(3840, 2160),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )
    )
    .build()

// 2. ImageCapture — minimize latency, fixed rotation
val imageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
    .setResolutionSelector(resolutionSelector)
    .setTargetRotation(Surface.ROTATION_0)   // fixed for microscope
    .build()

// 3. Bind to lifecycle
val camera = cameraProvider.bindToLifecycle(
    lifecycleOwner, cameraSelector, preview, imageCapture
)

// 4. Capture to in-memory buffer
imageCapture.takePicture(executor,
    object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(imageProxy: ImageProxy) {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // bytes contains JPEG data — process directly, no disk I/O
            imageProxy.close()
        }
        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "Capture failed: ${exception.message}")
        }
    }
)
```

For ZSL (if supported):
```kotlin
// Check support first
if (camera.cameraInfo.isZslSupported) {
    val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
        .setResolutionSelector(resolutionSelector)
        .setTargetRotation(Surface.ROTATION_0)
        .build()
}
```
