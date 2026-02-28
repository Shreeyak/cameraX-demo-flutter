package com.example.camera_x_demo

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
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
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.camera_x_demo.databinding.ActivityCameraBinding
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Native Android CameraX activity that:
 * - Captures frames continuously into in-memory buffers via ImageAnalysis
 * - Displays a small preview thumbnail of the latest frame
 * - Provides a Save button that captures a 4K JPEG to disk via ImageCapture
 * - Disables auto-focus, auto-exposure, and auto-white-balance via Camera2 interop
 * - Targets 4K (3840x2160) resolution and verifies it by reading image dimensions
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    @Volatile private var latestBitmap: Bitmap? = null
    private var reusableBitmap: Bitmap? = null  // pre-allocated bitmap for analysis frames
    private lateinit var cameraExecutor: ExecutorService

    // ── Permission handling ─────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.saveButton.setOnClickListener { saveFrameToDisk() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear ImageView before recycling to avoid "Canvas: trying to use a recycled bitmap"
        binding.thumbnailView.setImageBitmap(null)
        latestBitmap?.recycle()
        latestBitmap = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        // Shut down executor and wait for in-flight work to finish
        cameraExecutor.shutdown()
        try {
            if (!cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cameraExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            cameraExecutor.shutdownNow()
        }
    }

    // ── Camera setup ────────────────────────────────────────────────────
    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // ── Resolution selector: target 4K for ImageCapture ──
            val resolution4K = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(3840, 2160),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // ── Resolution selector: smaller for continuous analysis thumbnails ──
            val resolutionAnalysis = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // ── Preview (live viewfinder) ──
            val preview = Preview.Builder()
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // ── ImageCapture at 4K, in-memory buffer mode ──
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolution4K)
                .setTargetRotation(Surface.ROTATION_0)
                .build()

            // ── ImageAnalysis: continuous frame capture loop ──
            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionAnalysis)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture!!, imageAnalysis
                )

                // Disable AF, AE, AWB via Camera2 interop
                disableAutoControls(camera)

                // Verify and display the resolved capture resolution
                val captureRes = imageCapture!!.resolutionInfo?.resolution
                val analysisRes = imageAnalysis.resolutionInfo?.resolution
                Log.i(TAG, "Capture resolution : $captureRes")
                Log.i(TAG, "Analysis resolution: $analysisRes")

                runOnUiThread {
                    binding.resolutionText.text =
                        "Capture: ${captureRes?.width}x${captureRes?.height}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Disable AF / AE / AWB ───────────────────────────────────────────
    @OptIn(ExperimentalCamera2Interop::class)
    private fun disableAutoControls(camera: Camera) {
        val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
        val camera2Control = Camera2CameraControl.from(camera.cameraControl)

        // Query device-supported ranges for manual exposure parameters
        val exposureRange: Range<Long>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val sensitivityRange: Range<Int>? = camera2Info.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )

        // Clamp defaults to device-supported ranges
        val defaultExposure = 33_333_333L  // ~33 ms (1/30 s)
        val exposureTime = exposureRange?.clamp(defaultExposure) ?: defaultExposure

        val defaultSensitivity = 200  // ISO 200
        val sensitivity = sensitivityRange?.clamp(defaultSensitivity) ?: defaultSensitivity

        Log.i(TAG, "Device exposure range : $exposureRange → using $exposureTime")
        Log.i(TAG, "Device sensitivity range: $sensitivityRange → using $sensitivity")

        val result = camera2Control.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                // Auto-focus OFF
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF
                )
                // Auto-exposure OFF
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF
                )
                // Auto-white-balance OFF  (critical for microscopy / scientific imaging)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF
                )
                // Manual exposure — clamped to device-supported range
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
                result.get()  // throws if the request was rejected
                Log.i(TAG, "Camera2 interop: AF, AE, AWB all set to OFF")
                runOnUiThread {
                    binding.statusText.text = "AF: OFF | AE: OFF | AWB: OFF"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable auto controls", e)
                runOnUiThread {
                    binding.statusText.text = "AF/AE/AWB: FAILED — ${e.message}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Continuous frame capture loop (ImageAnalysis) ───────────────────
    private fun processFrame(imageProxy: ImageProxy) {
        val w = imageProxy.width
        val h = imageProxy.height

        try {
            val bitmap = imageProxyToBitmap(imageProxy, w, h)

            runOnUiThread {
                // Recycle the old bitmap AFTER removing it from the ImageView
                val old = latestBitmap
                latestBitmap = bitmap
                binding.thumbnailView.setImageBitmap(bitmap)
                // Safe to recycle now — ImageView no longer references old
                if (old != null && old !== bitmap) {
                    old.recycle()
                }

                val captureRes = imageCapture?.resolutionInfo?.resolution
                binding.resolutionText.text = buildString {
                    append("Loop: ${w}x${h}")
                    captureRes?.let { append("  |  Capture: ${it.width}x${it.height}") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()  // MUST close to receive next frame
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
            // No padding — copy directly into a reusable bitmap
            val bitmap = getOrCreateBitmap(w, h)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }

        // Row-stride padding present — need padded-width bitmap, then crop
        val bitmapWidth = w + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        if (cropped !== padded) padded.recycle()
        return cropped
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

    // ── Save current frame to disk ──────────────────────────────────────
    private fun saveFrameToDisk() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        binding.saveButton.isEnabled = false
        binding.saveButton.text = "Capturing…"

        // Use in-memory takePicture to verify 4K resolution, then write bytes to MediaStore
        capture.takePicture(cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val w = imageProxy.width
                    val h = imageProxy.height
                    Log.i(TAG, "Captured in-memory image: ${w}x${h}")

                    // Read JPEG bytes from buffer
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    imageProxy.close()

                    // Write to MediaStore with IS_PENDING for atomic visibility
                    val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(System.currentTimeMillis())
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "CXD_$name")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                "Pictures/CameraXDemo"
                            )
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    try {
                        val uri = contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                        )
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { os ->
                                // Write JPEG bytes with EXIF orientation
                                os.write(bytes)
                                os.flush()
                            }
                            // Write EXIF rotation so gallery apps display correctly
                            writeExifRotation(bytes, uri, rotation)

                            // Clear IS_PENDING — file is now visible to other apps
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val update = ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                }
                                contentResolver.update(it, update, null, null)
                            }
                        }
                        Log.i(TAG, "Saved ${w}x${h} to Pictures/CameraXDemo")

                        runOnUiThread {
                            binding.saveButton.isEnabled = true
                            binding.saveButton.text = "Save Frame"
                            Toast.makeText(
                                this@CameraActivity,
                                "Saved: ${w}x${h} (rot ${rotation}°) → Pictures/CameraXDemo",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Save failed", e)
                        runOnUiThread {
                            binding.saveButton.isEnabled = true
                            binding.saveButton.text = "Save Frame"
                            Toast.makeText(
                                this@CameraActivity,
                                "Save failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    runOnUiThread {
                        binding.saveButton.isEnabled = true
                        binding.saveButton.text = "Save Frame"
                        Toast.makeText(
                            this@CameraActivity,
                            "Capture failed: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    /**
     * Write EXIF orientation tag to the saved JPEG via its MediaStore URI.
     * Falls back gracefully if the content resolver doesn't support file descriptors.
     */
    private fun writeExifRotation(jpegBytes: ByteArray, uri: android.net.Uri, rotationDegrees: Int) {
        val exifOrientation = when (rotationDegrees) {
            90  -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write EXIF orientation", e)
        }
    }

    companion object {
        private const val TAG = "CameraXDemo"
    }
}
