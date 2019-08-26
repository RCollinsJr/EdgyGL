package com.example.edgygl.activities

import android.content.pm.ActivityInfo
import android.graphics.Point
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.edgygl.R
import com.example.edgygl.opengl.CameraRenderer
import com.example.edgygl.opengl.EdgyGLSurfaceView
import kotlinx.android.synthetic.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import org.opencv.android.CameraGLSurfaceView
import org.opencv.android.OpenCVLoader
import timber.log.Timber
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
    private external fun processImage(textureIN: Int,
                                      textureOUT: Int,
                                      textureWidth: Int,
                                      textureHeight: Int,
                                      lowThreshold: Int)

    private var mTextureWidth: Int = -1
    private var mTextureHeight: Int = -1

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

    /** Run all co-routines  on Main  */
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCameraViewStarted(width: Int, height: Int) {
        frameCounter = 0
        lastNanoTime = System.nanoTime()
    }

    override fun onCameraViewStopped() {}

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

        // using Timber for logging
        Timber.plant(Timber.DebugTree())

        // portrait mode only for simplicity - do this BEFORE setContentView()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // set application theme and layout
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)

        // FULL screen mode to keep the aspect ratio of the GLSurfaceView correct
        window.decorView.apply {
            systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        // initialize OpenCV library
        this.launch(this.coroutineContext) {
            if (!OpenCVLoader.initDebug()) {
                Timber.e("OpenCV not loaded")
            } else {
                Timber.i("OpenCV loaded")
            }
        }

        // load the jni library with the astronomy specific classes
        this.launch(this.coroutineContext) {
            System.loadLibrary("processGLImage")
        }

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
     * Calls doProcessing() if not already performing edge detection
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
     *
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
