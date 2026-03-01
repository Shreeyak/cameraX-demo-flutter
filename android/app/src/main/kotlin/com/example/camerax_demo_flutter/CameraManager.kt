package com.example.camerax_demo_flutter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import io.flutter.plugin.common.EventChannel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages all CameraX operations: preview, capture, analysis, and Camera2 interop.
 * Designed to be used from a FlutterActivity via MethodChannel.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraXDemo"

        /**
         * Identity COLOR_CORRECTION_TRANSFORM (sensor RGB → sRGB).
         *
         * When COLOR_CORRECTION_MODE = TRANSFORM_MATRIX, both GAINS and TRANSFORM
         * must be provided. This identity matrix leaves the sensor's native color
         * space unchanged; all white-balance work is done via the GAINS vector alone.
         *
         * The matrix is supplied as 9 rational numbers (numerator, denominator pairs):
         *   [ 1/1  0/1  0/1 ]
         *   [ 0/1  1/1  0/1 ]
         *   [ 0/1  0/1  1/1 ]
         */
        private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
            intArrayOf(
                1, 1,  0, 1,  0, 1,
                0, 1,  1, 1,  0, 1,
                0, 1,  0, 1,  1, 1
            )
        )
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    // Current AWB mode — one of the CameraMetadata.CONTROL_AWB_MODE_* constants.
    // Defaults to AUTO on startup; user can select any supported preset at runtime.
    private var currentAwbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var afEnabled: Boolean = false

    // Stored manual sensor settings — kept in sync with the last successfully
    // applied capture options so that every setCaptureRequestOptions call can
    // write the FULL set (Camera2CameraControl replaces, it does not merge).
    private var storedExposureTimeNs: Long = 33_333_333L  // ~1/30 s
    private var storedSensitivityIso: Int = 200
    @Volatile private var capturedFocusDistance: Float? = null

    // CCM captured from live AWB TotalCaptureResults. Updated whenever the
    // hardware reports CONTROL_AWB_MODE_AUTO in a CaptureResult, so it
    // always reflects the device's calibrated sensor→sRGB matrix for the
    // current scene. Used in place of an identity matrix when switching to
    // TRANSFORM_MATRIX mode, preventing the dull/desaturated appearance that
    // an identity CCM causes on non-sRGB sensors.
    @Volatile private var capturedColorTransform: ColorSpaceTransform? = null
    // Gains reported by AWB — purely diagnostic (logged for debugging).
    @Volatile private var capturedColorGains: RggbChannelVector? = null
    // Static CCM from CameraCharacteristics (read once at camera start).
    // Better OFF-mode fallback than identity when live CCM hasn't arrived yet.
    @Volatile private var staticColorTransform: ColorSpaceTransform? = null

    // Preview surface provider — set by the PlatformView when it's created
    private var previewView: PreviewView? = null
    // Pending startCamera callback — if startCamera is called before PreviewView exists
    private var pendingStartCallback: ((Map<String, Any>?, Exception?) -> Unit)? = null

    // EventChannel sink for streaming analysis frames to Dart
    private var frameSink: EventChannel.EventSink? = null
    // EventChannel sink for streaming events (status, warnings, errors) to Dart
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Analysis frame management ───────────────────────────────────────
    @Volatile private var reusableBitmap: Bitmap? = null
    // Reusable padded-width bitmap for devices where rowStride > pixelStride * width
    @Volatile private var reusablePaddedBitmap: Bitmap? = null

    /** Set the PreviewView whose surface provider will receive the camera feed. */
    fun setPreviewView(view: PreviewView) {
        previewView = view
        Log.i(TAG, "PreviewView set")
        // If startCamera was called before the view existed, execute it now
        pendingStartCallback?.let { callback ->
            pendingStartCallback = null
            Log.i(TAG, "Executing pending startCamera")
            startCamera(callback)
        }
    }

    /** Set the EventChannel sink for streaming analysis frames. */
    fun setFrameSink(sink: EventChannel.EventSink?) {
        frameSink = sink
    }

    /** Set the EventChannel sink for streaming events (status/warnings/errors). */
    fun setEventSink(sink: EventChannel.EventSink?) {
        eventSink = sink
    }

    /**
     * Push a typed event to Dart on the main thread.
     * @param type  "status" | "warning" | "error"
     * @param tag   identifies the source (e.g. "cameraSettings", "frameAnalysis")
     * @param message human-readable description
     * @param data  optional extra key-value pairs
     */
    private fun pushEvent(
        type: String,
        tag: String,
        message: String,
        data: Map<String, Any> = emptyMap()
    ) {
        val event = mutableMapOf<String, Any>(
            "type" to type,
            "tag" to tag,
            "message" to message
        )
        if (data.isNotEmpty()) event["data"] = data
        mainHandler.post { eventSink?.success(event) }
    }

    // ── Camera lifecycle ────────────────────────────────────────────────

    /**
     * Start the camera with Preview, ImageCapture (4K), and ImageAnalysis use cases.
     * Returns resolution info map via the callback on the main thread.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun startCamera(callback: (Map<String, Any>?, Exception?) -> Unit) {
        val pv = previewView
        if (pv == null) {
            // PreviewView not created yet — defer until setPreviewView is called
            Log.i(TAG, "PreviewView not ready, deferring startCamera")
            pendingStartCallback = callback
            return
        }

        if (cameraExecutor == null || cameraExecutor!!.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                this.cameraProvider = provider

                // ── Resolution selectors ──
                val resolution4K = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(3840, 2160),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val resolutionAnalysis = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // ── Preview ──
                val preview = Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                    .also { it.setSurfaceProvider(pv.surfaceProvider) }

                // ── ImageCapture at 4K ──
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolution4K)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()

                // ── ImageAnalysis: continuous frame loop ──
                // Also attaches a Camera2Interop capture callback to snoop
                // COLOR_CORRECTION_TRANSFORM from AWB TotalCaptureResults.
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionAnalysis)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(Surface.ROTATION_0)

                Camera2Interop.Extender(analysisBuilder)
                    .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            // Only store the CCM when hardware AWB is actually computing
                            // it.  Once we switch to TRANSFORM_MATRIX mode the result
                            // echoes back our own set value, so we stop updating.
                            val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
                            if (awbMode != null && awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let {
                                    capturedColorTransform = it
                                }
                                // Capture and log AWB gains for diagnostic visibility.
                                result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
                                    val prev = capturedColorGains
                                    capturedColorGains = gains
                                    // Log the first time, and whenever they change noticeably.
                                    if (prev == null ||
                                        Math.abs(gains.red - prev.red) > 0.05f ||
                                        Math.abs(gains.blue - prev.blue) > 0.05f
                                    ) {
                                        Log.i(TAG, "AWB gains (mode=$awbMode): " +
                                            "R=${"%.3f".format(gains.red)} " +
                                            "Ge=${"%.3f".format(gains.greenEven)} " +
                                            "Go=${"%.3f".format(gains.greenOdd)} " +
                                            "B=${"%.3f".format(gains.blue)}")
                                    }
                                }
                            }

                            // Capture last focus distance for restoring when MF is enabled
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { focus ->
                                capturedFocusDistance = focus
                            }
                        }
                    })

                imageAnalysis = analysisBuilder.build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                            processFrame(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture!!, imageAnalysis!!
                )
                this.camera = cam

                // Notify Dart that the camera is ready. Resolution info is available
                // immediately after bind; settings state will follow via the event stream.
                callback(gatherResolutionInfo(), null)

                // Wait for the first AWB TotalCaptureResult so we have the
                // device-calibrated COLOR_CORRECTION_TRANSFORM before we switch
                // to TRANSFORM_MATRIX mode. Polls every 50 ms, times out at 2 s.
                val handler = Handler(Looper.getMainLooper())
                var waited = 0L
                val waitForCcm = object : Runnable {
                    override fun run() {
                        when {
                            capturedColorTransform != null -> {
                                Log.i(TAG, "CCM ready after ${waited}ms — disabling auto controls")
                                disableAutoControls(cam)
                            }
                            waited >= 2000L -> {
                                Log.w(TAG, "Timed out waiting for CCM — using identity fallback")
                                disableAutoControls(cam)
                            }
                            else -> {
                                waited += 50L
                                handler.postDelayed(this, 50L)
                            }
                        }
                    }
                }
                handler.post(waitForCcm)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                callback(null, e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Stop the camera and release resources. */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        imageAnalysis = null

        reusableBitmap?.recycle()
        reusableBitmap = null
        reusablePaddedBitmap?.recycle()
        reusablePaddedBitmap = null

        cameraExecutor?.let { executor ->
            executor.shutdown()
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                executor.shutdownNow()
            }
        }
        cameraExecutor = null
    }

    // ── Disable AF / AE; keep AWB at current preset ────────────────────
    @OptIn(ExperimentalCamera2Interop::class)
    private fun disableAutoControls(camera: Camera) {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)

        val exposureRange: Range<Long>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val sensitivityRange: Range<Int>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )

        // Read static CCM from characteristics as a fallback for manual OFF mode.
        staticColorTransform = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM1
        ) ?: camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_COLOR_TRANSFORM2
        )
        if (staticColorTransform != null) {
            Log.i(TAG, "Static SENSOR_COLOR_TRANSFORM1 loaded")
        }

        // Log available AWB modes for diagnostic purposes.
        val availableModes = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
        )
        Log.i(TAG, "Available AWB modes: ${availableModes?.toList()}")

        storedExposureTimeNs = exposureRange?.clamp(33_333_333L) ?: 33_333_333L
        storedSensitivityIso = sensitivityRange?.clamp(200) ?: 200

        Log.i(TAG, "Device exposure range : $exposureRange → using $storedExposureTimeNs")
        Log.i(TAG, "Device sensitivity range: $sensitivityRange → using $storedSensitivityIso")

        applyAllCaptureOptions(camera) { e ->
            if (e != null) {
                Log.e(TAG, "Failed to disable auto controls", e)
                pushEvent(
                    type = "warning",
                    tag = "cameraSettings",
                    message = "Failed to disable auto controls: ${e.message ?: "unknown"}"
                )
            } else {
                val awbLabel = when (currentAwbMode) {
                    CameraMetadata.CONTROL_AWB_MODE_AUTO             -> "AUTO"
                    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT      -> "Tungsten"
                    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT       -> "Fluor."
                    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT  -> "Warm Fl."
                    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT          -> "Sunny"
                    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT   -> "Cloudy"
                    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT          -> "Twilight"
                    CameraMetadata.CONTROL_AWB_MODE_SHADE             -> "Shade"
                    CameraMetadata.CONTROL_AWB_MODE_OFF               -> "OFF"
                    else -> "mode $currentAwbMode"
                }
                Log.i(TAG, "Camera2 interop: AF=${if (afEnabled) "ON" else "OFF"}, AE=OFF, AWB=$awbLabel")
                pushEvent(
                    type = "status",
                    tag = "cameraSettings",
                    message = "AF: ${if (afEnabled) "ON" else "OFF"} | AE: OFF | AWB: $awbLabel",
                    data = mapOf("afEnabled" to afEnabled, "aeEnabled" to false,
                                 "awbMode" to currentAwbMode)
                )
            }
        }
    }

    /**
     * Apply the FULL set of capture request options from current state.
     *
     * Camera2CameraControl.setCaptureRequestOptions REPLACES all custom options —
     * it does not merge with the previous call. Every method that updates any
     * single option (AWB, color gains, etc.) must therefore re-write ALL options
     * together, or the previous settings will be silently cleared.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyAllCaptureOptions(cam: Camera, callback: (Exception?) -> Unit) {
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val afMode = if (afEnabled) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_OFF
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,  afMode)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,  CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, storedExposureTimeNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,   storedSensitivityIso)

        if (!afEnabled) {
            // Apply last known focus distance if we just disabled AF
            capturedFocusDistance?.let { focusDistance ->
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
        }

        // AWB: set selected preset mode.
        // For any mode other than OFF, the camera's 3A handles white balance —
        // we must NOT set COLOR_CORRECTION_MODE = TRANSFORM_MATRIX, which would
        // override the preset with our manual gains.
        // OFF mode requires explicit gains + transform (manual override).
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
        if (currentAwbMode == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            // Manual WB: neutral gains + device-calibrated (or static) CCM.
            val gains = RggbChannelVector(1.0f, 1.0f, 1.0f, 1.0f)
            val ccm = capturedColorTransform
                ?: staticColorTransform
                ?: IDENTITY_COLOR_TRANSFORM
            val ccmLabel = when {
                capturedColorTransform != null -> "live-AWB"
                staticColorTransform  != null -> "static-chars"
                else                          -> "identity"
            }
            Log.d(TAG, "AWB OFF — CCM source: $ccmLabel")
            builder
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ccm)
                .setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
        }

        val future = camera2Control.setCaptureRequestOptions(builder.build())
        future.addListener({
            try {
                future.get()
                callback(null)
            } catch (e: Exception) {
                callback(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Resolution info ─────────────────────────────────────────────────
    private fun gatherResolutionInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        imageCapture?.resolutionInfo?.resolution?.let {
            result["captureWidth"] = it.width
            result["captureHeight"] = it.height
        }
        imageAnalysis?.resolutionInfo?.resolution?.let {
            result["analysisWidth"] = it.width
            result["analysisHeight"] = it.height
        }
        Log.i(TAG, "Capture resolution : ${imageCapture?.resolutionInfo?.resolution}")
        Log.i(TAG, "Analysis resolution: ${imageAnalysis?.resolutionInfo?.resolution}")
        return result
    }

    // ── Continuous frame capture loop (ImageAnalysis) ───────────────────

    /**
     * Process an analysis frame: convert to JPEG, send via EventChannel to Dart.
     */
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Fast-path: skip conversion if no sink is listening
            if (frameSink == null) {
                imageProxy.close()
                return
            }

            val w = imageProxy.width
            val h = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxyToBitmap(imageProxy, w, h)

            // Apply rotation if needed
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap && bitmap !== reusableBitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }

            // Compress to JPEG for efficient transfer over EventChannel
            val stream = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val jpegBytes = stream.toByteArray()

            // Capture dimensions before any recycling
            val finalWidth = rotatedBitmap.width
            val finalHeight = rotatedBitmap.height

            // Clean up rotated bitmap if it's different from reusable
            if (rotatedBitmap !== bitmap && rotatedBitmap !== reusableBitmap) {
                rotatedBitmap.recycle()
            }

            // Send to Dart on main thread
            mainHandler.post {
                frameSink?.success(mapOf(
                    "bytes" to jpegBytes,
                    "width" to finalWidth,
                    "height" to finalHeight,
                    "rotation" to rotationDegrees
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
            pushEvent(
                type = "error",
                tag = "frameAnalysis",
                message = "Frame processing error: ${e.message ?: "unknown"}"
            )
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert an RGBA_8888 ImageProxy to a Bitmap.
     * Reuses a pre-allocated bitmap when dimensions match to reduce GC pressure.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy, w: Int, h: Int): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w

        if (rowPadding == 0) {
            val bitmap = getOrCreateBitmap(w, h)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // Row-stride padding present — copy into reusable padded bitmap,
        // then blit the valid region into the reusable output bitmap.
        val paddedWidth = w + rowPadding / pixelStride
        val padded = getOrCreatePaddedBitmap(paddedWidth, h)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val dst = getOrCreateBitmap(w, h)
        val canvas = android.graphics.Canvas(dst)
        canvas.drawBitmap(
            padded,
            android.graphics.Rect(0, 0, w, h),
            android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
            null
        )
        return dst
    }

    /** Returns a reusable padded-width bitmap, reallocating only when dimensions change. */
    private fun getOrCreatePaddedBitmap(w: Int, h: Int): Bitmap {
        val existing = reusablePaddedBitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        reusablePaddedBitmap = bitmap
        return bitmap
    }

    /** Returns a reusable bitmap if dimensions match, or allocates a new one. */
    private fun getOrCreateBitmap(w: Int, h: Int): Bitmap {
        val existing = reusableBitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        reusableBitmap = bitmap
        return bitmap
    }

    // ── Save frame to disk ──────────────────────────────────────────────

    /**
     * Capture a 4K JPEG and save to MediaStore.
     * Calls back with (filePath, error) on the main thread.
     */
    fun saveFrame(callback: (String?, Exception?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            callback(null, IllegalStateException("Camera not ready"))
            return
        }

        val executor = cameraExecutor
        if (executor == null || executor.isShutdown) {
            callback(null, IllegalStateException("Camera executor not available"))
            return
        }

        capture.takePicture(executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val w = imageProxy.width
                    val h = imageProxy.height
                    Log.i(TAG, "Captured in-memory image: ${w}x${h}")

                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    imageProxy.close()

                    val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(System.currentTimeMillis())
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "CXD_$name")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraXDemo")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    try {
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                        )
                        uri?.let {
                            context.contentResolver.openOutputStream(it)?.use { os ->
                                os.write(bytes)
                                os.flush()
                            }
                            writeExifRotation(uri, rotation)

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val update = ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                }
                                context.contentResolver.update(it, update, null, null)
                            }
                        }

                        val path = "Pictures/CameraXDemo/CXD_$name.jpg"
                        Log.i(TAG, "Saved ${w}x${h} to $path")

                        mainHandler.post {
                            callback(path, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Save failed", e)
                        mainHandler.post {
                            callback(null, e)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    mainHandler.post {
                        callback(null, exception)
                    }
                }
            }
        )
    }

    /**
     * Write EXIF orientation tag to the saved JPEG via its MediaStore URI.
     */
    private fun writeExifRotation(uri: android.net.Uri, rotationDegrees: Int) {
        val exifOrientation = when (rotationDegrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write EXIF orientation", e)
        }
    }

    // ── White balance preset control ─────────────────────────────────────

    /**
     * Set one of the Camera2 CONTROL_AWB_MODE_* presets at runtime.
     *
     * Supported mode constants (CameraMetadata):
     *   0  OFF              – manual (neutral 1.0 gains + live CCM applied)
     *   1  AUTO             – full auto white balance
     *   2  INCANDESCENT     – tungsten / candlelight (~2700 K)
     *   3  FLUORESCENT      – cool white fluorescent (~4000 K)
     *   4  WARM_FLUORESCENT – warm white fluorescent (~3000 K)
     *   5  DAYLIGHT         – direct sunlight (~5500 K)
     *   6  CLOUDY_DAYLIGHT  – overcast sky (~6500 K)
     *   7  TWILIGHT         – sunset / sunrise (~8000 K)
     *   8  SHADE            – open shade (~7000 K)
     *
     * For preset modes (≠ OFF) the camera's 3A handles all color correction;
     * we must NOT set COLOR_CORRECTION_MODE = TRANSFORM_MATRIX, which would
     * override the preset with manual gains.
     */
    fun setWhiteBalancePreset(mode: Int, callback: (Exception?) -> Unit) {
        val cam = camera
        if (cam == null) {
            callback(IllegalStateException("Camera not ready"))
            return
        }
        val previousMode = currentAwbMode
        currentAwbMode = mode
        applyAllCaptureOptions(cam) { e ->
            if (e != null) {
                Log.e(TAG, "Failed to set WB preset $mode", e)
                currentAwbMode = previousMode
                callback(e)
            } else {
                val label = when (mode) {
                    CameraMetadata.CONTROL_AWB_MODE_AUTO             -> "AUTO"
                    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT      -> "Tungsten"
                    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT       -> "Fluor."
                    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT  -> "Warm Fl."
                    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT          -> "Sunny"
                    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT   -> "Cloudy"
                    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT          -> "Twilight"
                    CameraMetadata.CONTROL_AWB_MODE_SHADE             -> "Shade"
                    CameraMetadata.CONTROL_AWB_MODE_OFF               -> "OFF"
                    else -> "mode $mode"
                }
                Log.i(TAG, "WB preset set to $label (mode $mode)")
                pushEvent(
                    type = "status",
                    tag = "whiteBalance",
                    message = "AF: ${if (afEnabled) "ON" else "OFF"} | AE: OFF | AWB: $label",
                    data = mapOf("awbMode" to mode)
                )
                callback(null)
            }
        }
    }

    fun setAfEnabled(enabled: Boolean, callback: (Exception?) -> Unit) {
        val cam = camera
        if (cam == null) {
            callback(IllegalStateException("Camera not ready"))
            return
        }
        val previousAf = afEnabled
        afEnabled = enabled
        applyAllCaptureOptions(cam) { e ->
            if (e != null) {
                Log.e(TAG, "Failed to set AF to $enabled", e)
                afEnabled = previousAf
                callback(e)
            } else {
                Log.i(TAG, "AF set to $enabled")
                val afLabel = if (enabled) "ON" else "OFF"
                val awbLabel = when (currentAwbMode) {
                    CameraMetadata.CONTROL_AWB_MODE_AUTO             -> "AUTO"
                    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT      -> "Tungsten"
                    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT       -> "Fluor."
                    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT  -> "Warm Fl."
                    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT          -> "Sunny"
                    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT   -> "Cloudy"
                    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT          -> "Twilight"
                    CameraMetadata.CONTROL_AWB_MODE_SHADE             -> "Shade"
                    CameraMetadata.CONTROL_AWB_MODE_OFF               -> "OFF"
                    else -> "mode $currentAwbMode"
                }

                pushEvent(
                    type = "status",
                    tag = "cameraSettings",
                    message = "AF: $afLabel | AE: OFF | AWB: $awbLabel",
                    data = mapOf("afEnabled" to enabled, "awbMode" to currentAwbMode)
                )
                callback(null)
            }
        }
    }

    /**
     * Query which AWB mode constants the device hardware supports.
     * Returns an empty list if the camera is not yet started.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun getAvailableWhiteBalanceModes(): List<Int> {
        val cam = camera ?: return emptyList()
        return Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toList() ?: listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    /** Get current resolution info as a map. */
    fun getResolutionInfo(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        imageCapture?.resolutionInfo?.resolution?.let {
            result["captureWidth"] = it.width
            result["captureHeight"] = it.height
        }
        imageAnalysis?.resolutionInfo?.resolution?.let {
            result["analysisWidth"] = it.width
            result["analysisHeight"] = it.height
        }
        result["currentAwbMode"] = currentAwbMode
        return result
    }
}
