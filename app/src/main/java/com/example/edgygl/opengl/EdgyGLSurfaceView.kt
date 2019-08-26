package com.example.edgygl.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.SurfaceHolder
import org.opencv.android.CameraGLSurfaceView
import timber.log.Timber

class EdgyGLSurfaceView(context: Context, attrs: AttributeSet) : GLSurfaceView(context, attrs) {

    var cameraTextureListener: CameraGLSurfaceView.CameraTextureListener? = null
    private var mRenderer: CameraRenderer? = null

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        Timber.d("surfaceCreated() called")
        mRenderer?.onSurfaceCreated(null, null)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        Timber.d("surfaceChanged() called")
        mRenderer?.onSurfaceChanged(null, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Timber.d("surfaceDestroyed() called")
        super.surfaceDestroyed(holder)
    }

}
