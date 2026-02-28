package com.example.camerax_demo_flutter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow
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
    private var isAwbEnabled = false
    private var colorTemperatureK: Int = 5500

    // Stored manual sensor settings — kept in sync with the last successfully
    // applied capture options so that every setCaptureRequestOptions call can
    // write the FULL set (Camera2CameraControl replaces, it does not merge).
    private var storedExposureTimeNs: Long = 33_333_333L  // ~1/30 s
    private var storedSensitivityIso: Int = 200

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
                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionAnalysis)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
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

                // Disable AF, AE, AWB asynchronously. Result is pushed to Dart via
                // the events EventChannel, not coupled to the startCamera response.
                disableAutoControls(cam)
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

    // ── Disable AF / AE / AWB ───────────────────────────────────────────
    @OptIn(ExperimentalCamera2Interop::class)
    private fun disableAutoControls(camera: Camera) {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)

        val exposureRange: Range<Long>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val sensitivityRange: Range<Int>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )

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
                Log.i(TAG, "Camera2 interop: AF, AE, AWB all set to OFF")
                pushEvent(
                    type = "status",
                    tag = "cameraSettings",
                    message = "AF: OFF | AE: OFF | AWB: OFF",
                    data = mapOf("afEnabled" to false, "aeEnabled" to false, "awbEnabled" to false)
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
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,  CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,  CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, storedExposureTimeNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,   storedSensitivityIso)

        val awbMode = if (isAwbEnabled) CameraMetadata.CONTROL_AWB_MODE_AUTO
                      else             CameraMetadata.CONTROL_AWB_MODE_OFF
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        // Manual color correction is only active when AWB is OFF.
        // Per the Camera2 docs, COLOR_CORRECTION_GAINS are applied by the app ONLY
        // when COLOR_CORRECTION_MODE = TRANSFORM_MATRIX; in any other mode, the
        // camera device ignores the app-supplied gains and sets its own.
        // COLOR_CORRECTION_TRANSFORM must also be provided when using
        // TRANSFORM_MATRIX mode — we supply an identity matrix so the sensor's
        // native colour space is unchanged, and all WB is done via GAINS alone.
        if (!isAwbEnabled) {
            val gains = kelvinToRggbGains(colorTemperatureK)
            builder
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    IDENTITY_COLOR_TRANSFORM
                )
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    gains
                )
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

    // ── Manual color temperature ────────────────────────────────────────

    /**
     * Convert a color temperature in Kelvin to per-channel RGGB gains.
     *
     * Uses the Tanner-Helland blackbody approximation to get the natural R:G:B
     * ratios of a blackbody at that temperature, then applies them DIRECTLY
     * as sensor gains (colorimetric tint, not AWB compensation):
     *   - Low K  (2000K) → strong red/orange boost, blue cut  → warm image
     *   - High K (10000K) → blue boost, red cut                → cool/blue image
     * Green is kept at 1.0 as the reference channel.
     *
     * References: Tanner-Helland algorithm; validated against
     * https://andi-siess.de/rgb-to-color-temperature/ (Siess 2024)
     */
    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.coerceIn(1000, 20000).toFloat() / 100f

        // Natural red luminance for this temperature (Tanner-Helland)
        val red = if (temp <= 66f) 255f
        else (329.698727446f * (temp - 60f).pow(-0.1332047592f)).coerceIn(1f, 255f)

        // Natural green luminance
        val green = if (temp <= 66f) {
            (99.4708025861f * ln(temp) - 161.1195681661f).coerceIn(1f, 255f)
        } else {
            (288.1221695283f * (temp - 60f).pow(-0.0755148492f)).coerceIn(1f, 255f)
        }

        // Natural blue luminance
        val blue = when {
            temp >= 66f -> 255f
            temp <= 19f -> 1f
            else -> (138.5177312231f * ln(temp - 10f) - 305.0447927307f).coerceIn(1f, 255f)
        }

        // Direct tint: apply the blackbody ratios as sensor gains, normalised
        // to green = 1.0. At 2000K: R≈1.85, B≈0.13 (warm). At 10000K: R≈0.95, B≈1.17 (cool).
        val rGain = (red / green).coerceIn(0.1f, 4.0f)
        val bGain = (blue / green).coerceIn(0.1f, 4.0f)
        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }

    /**
     * Apply a manual color temperature (in Kelvin) via COLOR_CORRECTION_GAINS.
     * AWB will be forced OFF. Writes the FULL capture option set so that
     * AE=OFF / AF=OFF / exposure / sensitivity are preserved alongside the gains.
     */
    fun setColorTemperature(kelvin: Int, callback: (Exception?) -> Unit) {
        val cam = camera
        if (cam == null) {
            callback(IllegalStateException("Camera not ready"))
            return
        }

        val gains = kelvinToRggbGains(kelvin)
        Log.i(TAG, "Setting color temperature: ${kelvin}K → gains R=${"%.3f".format(gains.red)} G=${"%.3f".format(gains.greenEven)} B=${"%.3f".format(gains.blue)}")

        // Commit the desired temperature into state; isAwbEnabled stays as-is
        // (caller already guards against calling this when AWB is ON).
        colorTemperatureK = kelvin
        isAwbEnabled = false  // gains only apply when AWB is OFF

        applyAllCaptureOptions(cam) { e ->
            if (e != null) {
                Log.e(TAG, "Failed to set color temperature", e)
                callback(e)
            } else {
                Log.i(TAG, "Color temperature set to ${kelvin}K")
                callback(null)
            }
        }
    }

    // ── AWB toggle ──────────────────────────────────────────────────────

    /**
     * Toggle auto-white-balance between OFF and AUTO.
     * Returns the new AWB state (true = AUTO, false = OFF).
     */
    fun toggleAwb(callback: (Boolean?, Exception?) -> Unit) {
        val cam = camera
        if (cam == null) {
            callback(null, IllegalStateException("Camera not ready"))
            return
        }

        // Capture the before/after states as locals so that rapid successive
        // calls cannot corrupt each other's callbacks via isAwbEnabled mutations.
        val previousEnabled = isAwbEnabled
        val desiredEnabled = !previousEnabled
        isAwbEnabled = desiredEnabled  // optimistic — reverted on error

        applyAllCaptureOptions(cam) { e ->
            if (e != null) {
                Log.e(TAG, "Failed to toggle AWB", e)
                isAwbEnabled = previousEnabled  // revert
                callback(null, e)
            } else {
                val label = if (desiredEnabled) "AUTO" else "OFF"
                Log.i(TAG, "AWB set to $label")
                callback(desiredEnabled, null)
            }
        }
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
        result["awbEnabled"] = isAwbEnabled
        result["colorTemperatureK"] = colorTemperatureK
        return result
    }
}
