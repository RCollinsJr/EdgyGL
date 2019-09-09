package com.example.edgygl.activities

import android.Manifest.permission.CAMERA
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.edgygl.R
import com.example.edgygl.fragments.CameraPermissionsFragment
import com.example.edgygl.opengl.CameraRenderer
import com.example.edgygl.opengl.EdgyGLSurfaceView
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import org.opencv.android.CameraGLSurfaceView
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(),
    CameraGLSurfaceView.CameraTextureListener, CoroutineScope {

    // good place for constants...
    companion object {
        const val REQUEST_CAMERA_PERMISSIONS = 1
    }

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

    private var mHasPermission = false

    private var mCameraPermissionsFragment: CameraPermissionsFragment? = null
    private val isRationaleCamFragmentShown: Boolean
        get() = mCameraPermissionsFragment != null
                && (mCameraPermissionsFragment?.isVisible
            ?: false)

    /**
     * Flag for indicating the jni code is still processing a previous frame
     */
    private var mIsProcessing = false

    private var startTime: Long = 0
    private var endTime: Long = 0
    private var mElapedTimerList: MutableList<Long> = mutableListOf()

    /** Run all co-routines  on Main  */
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCameraViewStarted(width: Int, height: Int) {}

    override fun onCameraViewStopped() {}

    override fun onCameraTexture(textureIn: Int, textureOut: Int, width: Int, height: Int): Boolean {

        // use for determining performance
        startTime = System.nanoTime()

        // do the canny edge detect and contour following
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
                mElapedTimerList.clear()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            // pop up a toast showing the slider value
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                showSeekBarToast()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mHasPermission = if (checkSelfPermission(CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(CAMERA), REQUEST_CAMERA_PERMISSIONS)
                false
            } else {
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume() called...")

        val displaySize = Point(0, 0)
        windowManager.defaultDisplay.getRealSize(displaySize)

        startBackgroundThread()

        mGLSurfaceView?.onResume()
        mGLRenderer?.setCameraPreviewSize(displaySize.x, displaySize.y)
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause() called...")
        mGLSurfaceView?.onPause()
        mGLRenderer?.doStop()
    }

    override fun onDestroy() {
        Timber.d("onDestroy() called...")
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

        val formatter = DecimalFormat("###")
        formatter.roundingMode = RoundingMode.CEILING

        val totalTime = endTime - startTime
        if (totalTime > 0) {
            mElapedTimerList.add(totalTime)
        }

        // only keep 100 readings
        if (mElapedTimerList.size > 100) {
            mElapedTimerList.removeAt(0)
        }

        // use average of times and convert from nano seconds to fps
        val formattedTime =
            formatter.format(1 / (mElapedTimerList.average() * 1e-9)) +
                    " " +
                    getString(R.string.fps)
        this@MainActivity.runOnUiThread { fps_textview.text = formattedTime }
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

        // use for performance measurement
        endTime = System.nanoTime()
    }

    /**
     * Called when a user has selected 'Deny' or 'Allow' from a permissions dialog
     *
     * @param requestCode  Integer representing the 'Request Code'
     * @param permissions  String array containing the requested permissions
     * @param grantResults Integer array containing the permissions results
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {

        when (requestCode) {

            REQUEST_CAMERA_PERMISSIONS -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    return
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA)) {

                    if (mCameraPermissionsFragment == null) {
                        mCameraPermissionsFragment = CameraPermissionsFragment.newInstance("", "")
                    }

                    if (!isRationaleCamFragmentShown) {
                        mCameraPermissionsFragment?.show(supportFragmentManager, getString(R.string.permissions_title_camera))
                    }

                } else {
                    val snackbar = Snackbar.make(main_constraint_layout,
                        resources.getString(R.string.message_no_camera_permissions), Snackbar.LENGTH_LONG)
                    snackbar.setAction(resources.getString(R.string.settings)) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", packageName, null)
                        intent.data = uri
                        startActivity(intent)
                        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                    }

                    if (!snackbar.isShown) {
                        snackbar.show()
                    }
                }
            }
        }
    }

}
