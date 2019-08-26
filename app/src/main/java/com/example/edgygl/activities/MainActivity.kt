package com.example.edgygl.activities

import android.graphics.Point
import android.opengl.GLSurfaceView
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.edgygl.R
import com.example.edgygl.opengl.CameraRenderer
import com.example.edgygl.opengl.EdgyGLSurfaceView
import kotlinx.android.synthetic.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import org.opencv.android.CameraGLSurfaceView
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import java.util.*
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(),
    CameraGLSurfaceView.CameraTextureListener, CoroutineScope {

    /**
     * This is the JNI method to call when frame data is available
     *
     * @param textureIN A handle to the input texture as an [Int]
     * @param textureOUT A handle to the output texture as an [Int]
     * @param textureWidth An [Int] representing the gl texture's width
     * @param textureHeight An [Int] representing the GL texture's height
     * @param lowThreshold Canny Threshold low value
     */
    external fun processImage(textureIN: Int,
                                      textureOUT: Int,
                                      textureWidth: Int,
                                      textureHeight: Int,
                                      lowThreshold: Int)

    /**
     *
     */
    private val mSurfaceHolderCallback = object : SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder?) {
            mSurfaceHolder = holder
        }

        override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            mSurfaceHolder = holder
        }

        override fun surfaceDestroyed(holder: SurfaceHolder?) {
            mSurfaceHolder = null
        }
    }


    private var mTextureWidth: Int = -1
    private var mTextureHeight: Int = -1

    private var mSurfaceHolder: SurfaceHolder? = null

    /** An additional thread for running tasks that shouldn't block the UI */
    private var mBackgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background */
    private var mBackgroundHandler: Handler? = null

    private var mGLSurfaceView: EdgyGLSurfaceView? = null
    private var mGLRenderer: CameraRenderer? = null

    private var mThresholdValue: Int = 0

    private var frameCounter: Int = 0
    private var lastNanoTime: Long = 0

    private var mFpsText: TextView? = null

    /**
     * Flag for indicating the jni code is still processing a previous frame
     */
    private var mIsProcessing = false
    var isProcessing: Boolean
        get() = mIsProcessing
        set(value) {
            mIsProcessing = value
        }

    /** Compares two `Size`s based on their areas  */
    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

    /** Run all co-routines  on Main  */
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCameraViewStarted(width: Int, height: Int) {
        runOnUiThread { Toast.makeText(this, "onCameraViewStarted", Toast.LENGTH_SHORT).show() }
        frameCounter = 0
        lastNanoTime = System.nanoTime()
    }

    override fun onCameraViewStopped() {
        runOnUiThread { Toast.makeText(this, "onCameraViewStopped", Toast.LENGTH_SHORT).show() }
    }

    override fun onCameraTexture(textureIn: Int, textureOut: Int, width: Int, height: Int): Boolean {
        // FPS
        frameCounter++
        if (frameCounter >= 30) {
            val fps = (frameCounter * 1e9 / (System.nanoTime() - lastNanoTime)).toInt()
            Timber.i("drawFrame() FPS: $fps")
            if (mFpsText != null) {
                val fpsUpdater = Runnable { mFpsText?.text = "FPS: $fps" }
                Handler(Looper.getMainLooper()).post(fpsUpdater)
            } else {
                Timber.d("mFpsText == null")
            }
            frameCounter = 0
            lastNanoTime = System.nanoTime()
        }

        doImageProcessing(textureIn, textureOut, width, height)

        return true
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // using Timber for logging
        Timber.plant(Timber.DebugTree())

        if (!OpenCVLoader.initDebug()) {
            Timber.e("OpenCV not loaded")
        } else {
            Timber.i("OpenCV loaded")
        }

        // load the jni library with the astronomy specific classes
        this.launch(this.coroutineContext) { System.loadLibrary("processGLImage") }

        // create GL renderer passing in the app context so it can access the shader glsl files
        mGLRenderer = CameraRenderer(this)

        // set up the GLSurfaceView
        mGLSurfaceView = gl_surface_view
        mGLSurfaceView?.setEGLContextClientVersion(3) // do this BEFORE setRenderer()
        mGLSurfaceView?.setRenderer(mGLRenderer)
        mGLSurfaceView?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        mGLRenderer?.glSurfaceView = mGLSurfaceView

        mGLSurfaceView?.cameraTextureListener = this

        // a seekbar to change canny threshold settings
        mThresholdValue = cannyThresholdSeekBar.progress
        cannyThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                mThresholdValue = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            // pop up a toast showing the slider value
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                showSeekBarToast()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        mGLSurfaceView?.onResume()

        val displaySize = Point(0, 0)
        windowManager.defaultDisplay.getRealSize(displaySize)
        mGLRenderer?.setCameraPreviewSize(displaySize.x, displaySize.y)
    }

    override fun onPause() {
        super.onPause()
        mGLSurfaceView?.onPause()
        mGLRenderer?.doStop()
    }

    override fun onDestroy() {
        stopBackgroundThread()
        coroutineContext.cancelChildren()
        this.clearFindViewByIdCache()
        super.onDestroy()
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        stopBackgroundThread()
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread?.start()
        mBackgroundThread?.let { mBackgroundHandler = Handler(it.looper) }
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        mBackgroundThread?.quitSafely()
        try {
            mBackgroundThread?.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /**
     * Shows a [Toast] on the UI thread.
     */
    private fun showSeekBarToast() {
        runOnUiThread {
            val toast = Toast.makeText(applicationContext,
                "Low Threshold: $mThresholdValue", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        }
    }

    /**
     *
     * @param textIn
     * @param textOut
     * @param width
     * @param height
     */
    private fun doImageProcessing(textIn: Int, textOut: Int, width: Int, height: Int) {

        mTextureWidth = width
        mTextureHeight = height

        // call routine specific to this measurement type
        if (!mIsProcessing) {
            doProcessing(textIn, textOut)
        }
    }

    /**
     * Calls the jni c++ processImage() function to perform the edge detection
     * This function does nothing if the jni code has not returned
     * from a previous call
     * @param texture_in
     * @param texture_out
     */
    private fun doProcessing(texture_in: Int, texture_out: Int) {

        // set 'processing flag' true
        mIsProcessing = true

        processImage(
            texture_in,
            texture_out,
            mTextureWidth,
            mTextureHeight,
            mThresholdValue)

        // done processing
        mIsProcessing = false
    }

}
