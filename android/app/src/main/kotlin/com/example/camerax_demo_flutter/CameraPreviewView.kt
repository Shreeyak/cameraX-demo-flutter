package com.example.camerax_demo_flutter

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import io.flutter.plugin.platform.PlatformView

/**
 * Wraps a CameraX PreviewView as a Flutter PlatformView.
 * The PreviewView is created programmatically and its surface provider
 * is used by CameraManager to display the camera preview.
 */
class CameraPreviewView(
    context: Context,
    private val viewId: Int,
    creationParams: Map<String, Any>?,
    private val onViewCreated: (PreviewView) -> Unit
) : PlatformView {

    private val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
    }

    init {
        // Notify the factory/activity that the PreviewView is ready
        onViewCreated(previewView)
    }

    override fun getView(): View = previewView

    override fun dispose() {
        // PreviewView cleanup is handled by CameraManager.stopCamera()
    }
}
