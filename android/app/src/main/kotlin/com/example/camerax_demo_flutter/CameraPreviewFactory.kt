package com.example.camerax_demo_flutter

import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Factory that creates CameraPreviewView instances for Flutter's AndroidView widget.
 * Registered with viewType "camerax-preview" in MainActivity.
 */
class CameraPreviewFactory(
    private val onViewCreated: (androidx.camera.view.PreviewView) -> Unit
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        @Suppress("UNCHECKED_CAST")
        val params = args as? Map<String, Any>
        return CameraPreviewView(context, viewId, params, onViewCreated)
    }
}
