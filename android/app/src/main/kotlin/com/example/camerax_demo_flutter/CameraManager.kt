package com.example.camerax_demo_flutter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var isAwbEnabled = false

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
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        val exposureRange: Range<Long>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val sensitivityRange: Range<Int>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )

        val defaultExposure = 33_333_333L  // ~33 ms (1/30 s)
        val exposureTime = exposureRange?.clamp(defaultExposure) ?: defaultExposure

        val defaultSensitivity = 200  // ISO 200
        val sensitivity = sensitivityRange?.clamp(defaultSensitivity) ?: defaultSensitivity

        Log.i(TAG, "Device exposure range : $exposureRange → using $exposureTime")
        Log.i(TAG, "Device sensitivity range: $sensitivityRange → using $sensitivity")

        val result = camera2Control.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    exposureTime
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    sensitivity
                )
                .build()
        )
        result.addListener({
            try {
                result.get()
                Log.i(TAG, "Camera2 interop: AF, AE, AWB all set to OFF")
                pushEvent(
                    type = "status",
                    tag = "cameraSettings",
                    message = "AF: OFF | AE: OFF | AWB: OFF",
                    data = mapOf("afEnabled" to false, "aeEnabled" to false, "awbEnabled" to false)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable auto controls", e)
                pushEvent(
                    type = "warning",
                    tag = "cameraSettings",
                    message = "Failed to disable auto controls: ${e.message ?: "unknown"}"
                )
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

    // ── AWB toggle ──────────────────────────────────────────────────────

    /**
     * Toggle auto-white-balance between OFF and AUTO.
     * Returns the new AWB state (true = AUTO, false = OFF).
     */
    @OptIn(ExperimentalCamera2Interop::class)
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
        val awbMode = if (desiredEnabled) {
            CameraMetadata.CONTROL_AWB_MODE_AUTO
        } else {
            CameraMetadata.CONTROL_AWB_MODE_OFF
        }

        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val result = camera2Control.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                .build()
        )
        result.addListener({
            try {
                result.get()
                // Commit only after the hardware confirms the change.
                isAwbEnabled = desiredEnabled
                val label = if (desiredEnabled) "AUTO" else "OFF"
                Log.i(TAG, "AWB set to $label")
                // Listener runs on getMainExecutor — call directly, no post needed.
                callback(desiredEnabled, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle AWB", e)
                // Revert to the captured previous state, not a blind flip.
                isAwbEnabled = previousEnabled
                callback(null, e)
            }
        }, ContextCompat.getMainExecutor(context))
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
        return result
    }
}
