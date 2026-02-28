package com.example.camerax_demo_flutter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter activity that wires up:
 * - PlatformView factory for CameraX PreviewView ("camerax-preview")
 * - MethodChannel for camera control commands
 * - EventChannel for streaming analysis frame thumbnails
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "CameraXDemo"
        private const val METHOD_CHANNEL = "com.example.camerax/control"
        private const val EVENT_CHANNEL = "com.example.camerax/frames"
        private const val CAMERA_PERMISSION_CODE = 1001
    }

    private var cameraManager: CameraManager? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Create camera manager bound to this activity's lifecycle
        val manager = CameraManager(this, this)
        cameraManager = manager

        // ── Register PlatformView factory ──
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "camerax-preview",
            CameraPreviewFactory { previewView ->
                // Called when the PlatformView creates the PreviewView
                manager.setPreviewView(previewView)
            }
        )

        // ── MethodChannel for camera commands ──
        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestPermission" -> {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            result.success(true)
                        } else {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.CAMERA),
                                CAMERA_PERMISSION_CODE
                            )
                            // Store result to answer when permission callback fires
                            pendingPermissionResult = result
                        }
                    }

                    "startCamera" -> {
                        manager.startCamera { info, error ->
                            if (error != null) {
                                result.error("CAMERA_START_FAILED", error.message, null)
                            } else {
                                result.success(info)
                            }
                        }
                    }

                    "stopCamera" -> {
                        manager.stopCamera()
                        result.success(null)
                    }

                    "saveFrame" -> {
                        manager.saveFrame { path, error ->
                            if (error != null) {
                                result.error("SAVE_FAILED", error.message, null)
                            } else {
                                result.success(path)
                            }
                        }
                    }

                    "toggleAwb" -> {
                        manager.toggleAwb { enabled, error ->
                            if (error != null) {
                                result.error("AWB_FAILED", error.message, null)
                            } else {
                                result.success(enabled)
                            }
                        }
                    }

                    "getResolution" -> {
                        result.success(manager.getResolutionInfo())
                    }

                    else -> result.notImplemented()
                }
            }
        }

        // ── EventChannel for analysis frame streaming ──
        eventChannel = EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).also { channel ->
            channel.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    Log.i(TAG, "Frame stream: Dart listener attached")
                    manager.setFrameSink(events)
                }

                override fun onCancel(arguments: Any?) {
                    Log.i(TAG, "Frame stream: Dart listener detached")
                    manager.setFrameSink(null)
                }
            })
        }
    }

    // ── Permission handling ─────────────────────────────────────────────
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
        }
    }

    override fun onDestroy() {
        cameraManager?.stopCamera()
        cameraManager = null
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        super.onDestroy()
    }
}
