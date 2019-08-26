package com.example.edgygl.opengl

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import org.jetbrains.anko.windowManager
import timber.log.Timber
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.*
import kotlin.collections.ArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs


/**
 * Camera class for rendering output to GL surface
 * @param context The application [Context]
 */
class CameraRenderer(private val context: Context) : GLRendererBase(context),
    GLRendererBase.EGLSurfaceTextureListener, CoroutineScope {

    /** Kotlin co-routines */
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    /** Conversion from screen rotation to JPEG orientation */
    private val mORIENTATIONS = SparseIntArray()

    private var isPreviewStarted = false

    /** A [CameraCaptureSession] for camera preview */
    private var mCaptureSession: CameraCaptureSession? = null

    /** A [CameraManager] for, well, managing the camera! */
    private var mCameraManager: CameraManager? = null

    /** [CameraManager] will be used to get [CameraCharacteristics] */
    private var mCameraCharacteristics: CameraCharacteristics? = null

    /** A reference to the opened [CameraDevice]
     * NOTE: In this app, this will always be using
     * the rear-facing camera. No selfies please!
     */
    private var mCameraDevice: CameraDevice? = null

    /** The [Size] of camera preview */
    private var mPreviewSize: Size = Size(0, 0)

    /** The aspect ration of the device's screen  */
    private var mAspectRatio: Float = 0f

    /** A list of supported preview sizes with aspect ratios that match the native display ratio */
    private var mPreviewSizes = arrayListOf<Size>()

    private var mSupportedPreviewSizes: MutableList<Size> = mutableListOf()

    init {

        mORIENTATIONS.append(Surface.ROTATION_0, 90)
        mORIENTATIONS.append(Surface.ROTATION_90, 0)
        mORIENTATIONS.append(Surface.ROTATION_180, 270)
        mORIENTATIONS.append(Surface.ROTATION_270, 180)

        // CameraManager and CameraCharacteristics get used a lot
        mCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mCameraId = getRearFacingCamera()
        mCameraId?.let { mCameraCharacteristics = mCameraManager?.getCameraCharacteristics(it) }

        // get the device display size
        val displaySize = Point()
        context.windowManager.defaultDisplay.getRealSize(displaySize)

        // calculate the display aspect ration
        mAspectRatio = displaySize.y.toFloat() / displaySize.x

        // get a list of supported sizes that match the aspect ratio
        mPreviewSizes = ArrayList(getGoodAspectRatios(mAspectRatio))

        startBackgroundThread()
    }

    public override fun doStart() {
        Timber.d("Start Camera Renderer thread")
        super.doStart()
    }

    public override fun doStop() {
        Timber.d("Stop Camera Renderer thread")
        super.doStop()
        stopBackgroundThread()
    }

    override fun onEGLSurfaceTextureReady(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Timber.d("onEGLSurfaceTextureReady() called with width: $width and height: $height")
        hasSurface = true

        // sets mPreviewSize and changes size of surface to match
        setCameraPreviewSize(width, height)
    }

    override fun onEGLSurfaceTextureChanged(width: Int, height: Int) {
        Timber.d("onEGLSurfaceTextureChanged() called with width: $width and height: $height")

        // sets mPreviewSize and changes size of surface to match
        setCameraPreviewSize(width, height)
    }

    override fun onEGLSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) {
    }

    /**
     * Calculate a preview size to fit into a given view matching the "exact" aspect ratio
     * of the device display. Any of the sizes in the list will work, but full HD 1920x1080
     * is a heavy load for the opencv jni code. Use with caution... Some cameras go MUCH higher
     * @param width The width of the window to fit into
     * @param height The height of the window to fit into
     */
    private fun calculatePreviewSize(width: Int, height: Int): Boolean {

        var bestWidth = 0
        var bestHeight = 0

        // re-sort list so we start at the largest size and find one that fits
        // into the MAX_PREVIEW_HEIGHT and MAX_PREVIEW_WIDTH
        var sizes = mPreviewSizes.sortedByDescending { size -> size.height }

        // if empty fall back on complete list regardless of aspect ratio
        if (sizes.isEmpty()) {
            sizes = mSupportedPreviewSizes.sortedByDescending { size -> size.height }
            mPreviewSizes = ArrayList(sizes)
        }

        // small enough to fit max dimensions
        for (size in sizes) {
            if (size.height <= MAX_PREVIEW_HEIGHT && size.width <= MAX_PREVIEW_WIDTH) {
                bestWidth = size.width
                bestHeight = size.height
                break
            }
        }

        return if (bestWidth == 0 || bestHeight == 0) {
            Timber.i("Best Found Size Not Found")
            false
        }
        else {
            // this is the live preview size (basically just using the native screen resolution...)
            mPreviewSize = Size(bestWidth, bestHeight)
            Timber.i("Best Found Size: $bestWidth x $bestHeight")
            true
        }
    }

    /**
     * This [CameraDevice.StateCallback] is called when the [CameraDevice] changes it's state
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            Timber.d("onOpened() called...")
            mCameraOpenCloseLock.release()
            mCameraDevice = cameraDevice

            //
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            isPreviewStarted = false
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Timber.e("onError() called")
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }
    }


    /**
     * Get a list of supported camera preview resolutions that match a given aspect ratio
     * @param aspectRatio The intrinsic device aspect ratio
     * @return A [List] of [Size]s that are supported by the camera device
     */
    private fun getGoodAspectRatios(aspectRatio: Float): List<Size> {

        val goodAspectRatios: ArrayList<Size> = arrayListOf()

        // map of allowable stream configurations supported by the camera
        val streamConfigMap = mCameraCharacteristics
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // list of available preview sizes supported by the camera
        mSupportedPreviewSizes = Arrays.asList(*streamConfigMap?.getOutputSizes(SurfaceTexture::class.java))
        if (mSupportedPreviewSizes.isNotEmpty()) {
            for (previewSize in mSupportedPreviewSizes) {
                val w = previewSize.width
                val h = previewSize.height

                // get all the "exact" aspect ratio combinations for preview
                if (abs(aspectRatio - w.toFloat() / h) <= 0.01f) {
                    goodAspectRatios.add(previewSize)
                }
            }
        }

        // return a sorted list of supported sizes
        return goodAspectRatios.sortedBy { size -> size.height }
    }

    /** An additional thread for running tasks that shouldn't block the UI */
    private var mBackgroundThread: HandlerThread? = null

    /** A [Handler] for running tasks in the background */
    private var mBackgroundHandler: Handler? = null

    /** An [ImageReader] that handles still image capture */
    private var mImageReader: ImageReader? = null

    private var glPreviewSurface: Surface? = null

    /** This is the output file for our picture */
    private var mFile: File? = null

    /** [CaptureRequest.Builder] for the camera preview */
    private var mPreviewRequestBuilder: CaptureRequest.Builder? = null

    /** [CaptureRequest] generated by [.mPreviewRequestBuilder] */
    private var mPreviewRequest: CaptureRequest? = null

    /** A [Semaphore] to prevent the app from exiting before closing the camera */
    private val mCameraOpenCloseLock = Semaphore(1)

    /** Whether the current camera device supports Flash or not */
    private var mFlashSupported: Boolean = false

    /** Orientation of the camera sensor */
    private var mSensorOrientation: Int = 0

    /**
     * Calculate and populate mPreviewSize from a list of supported camera output sizes
     * @param parentSize  The [Size] of the parent container for camera preview
     */
    private fun calculatePreviewSize(parentSize: Size): Size? {

        try {

            // is the flash is supported (we will want to turn it OFF)
            mFlashSupported = mCameraCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    ?: false

            // we won't be using a front facing camera - No Selfies!
            val facing = mCameraCharacteristics?.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return null
            }

            val map = mCameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // see if we need to swap dimension to get the preview size relative to sensor coordinate
            val displayRotation = context.windowManager.defaultDisplay.rotation
            mSensorOrientation = mCameraCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    ?: Surface.ROTATION_0

            var swappedDimensions = false
            when (displayRotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 ->
                    if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                    swappedDimensions = true
                }
                Surface.ROTATION_90, Surface.ROTATION_270 ->
                    if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                    swappedDimensions = true
                }
                else -> Timber.e("Display rotation is invalid: $displayRotation")
            }

            // get the device display size
            val displaySize = Point()
            context.windowManager.defaultDisplay.getSize(displaySize)

            var rotatedPreviewWidth = parentSize.width
            var rotatedPreviewHeight = parentSize.height

            if (swappedDimensions) {
                rotatedPreviewWidth = parentSize.height
                rotatedPreviewHeight = parentSize.width
            }

            calculatePreviewSize(rotatedPreviewWidth, rotatedPreviewHeight)

            // just return if dimensions are 0
            if (mPreviewSize.width <= 0 || mPreviewSize.height <= 0) {
                Timber.e("calculatePreviewSize() is returning a Size of 0!")
                return Size(0, 0)
            }

            mPreviewSize.also {
                mCameraWidth = it.width
                mCameraHeight = it.height
            }

            return mPreviewSize

        } catch (e: CameraAccessException) {
            Timber.e("CameraAccessException: $e")
            e.printStackTrace()
        } catch (e: NullPointerException) {
            Timber.e("NullPointerException: $e")
        }

        return null
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
     *
     * @param width
     * @param height
     */
    public override fun setCameraPreviewSize(width: Int, height: Int) {

        var newWidth = width
        var newHeight = height

        if (mMaxCameraWidth in 1 until newWidth) {
            newWidth = mMaxCameraWidth
        }
        if (mMaxCameraHeight in 1 until newHeight) {
            newHeight = mMaxCameraHeight
        }

        // if there is no size just return
        if (newWidth <= 0 || newHeight <= 0) { return }

        try {
            // get lock
            mCameraOpenCloseLock.acquire()

            // calculate the new texture view size
            val newSize = Size(newWidth, newHeight)
            calculatePreviewSize(newSize)

            mCameraWidth = mPreviewSize.width
            mCameraHeight = mPreviewSize.height

            if (null != mCaptureSession) {
                mCaptureSession?.close()
                mCaptureSession = null
            }

            // release the lock
            mCameraOpenCloseLock.release()

        } catch (e: InterruptedException) {
            mCameraOpenCloseLock.release()
            throw RuntimeException("Interrupted while setCameraPreviewSize.", e)
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        Timber.d("createCameraPreviewSession() called...")
        try {
            mCameraOpenCloseLock.acquire()
            if (mCameraDevice == null) {
                mCameraOpenCloseLock.release()
                Timber.e("createCameraPreviewSession: camera isn't opened")
                return
            }
            if ( mCaptureSession != null) {
                mCameraOpenCloseLock.release()
                Timber.e("createCameraPreviewSession: mCaptureSession is already started")
                return
            }
            if (surfaceTexture == null) {
                mCameraOpenCloseLock.release()
                Timber.e("createCameraPreviewSession: preview SurfaceTexture is null")
                return
            }

            // set the buffer size of the SurfaceTexture and create the GL surface
            surfaceTexture?.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
            glPreviewSurface = Surface(surfaceTexture)

            // We set up a CaptureRequest.Builder with the output Surface
            mPreviewRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            glPreviewSurface?.let { mPreviewRequestBuilder?.addTarget(it) }

            val surfaceList = listOf(glPreviewSurface)
            if (surfaceList.isEmpty()) {
                Timber.e("ERROR: cannot create Preview Session - surface list is empty")
                return
            }

            /** create a [CameraCaptureSession] for camera preview */
            mCameraDevice?.createCaptureSession(surfaceList, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    Timber.d("onConfigured() called")

                    // The camera is already closed
                    if (mCameraDevice == null) {
                        return
                    }

                    // When the session is ready, we start displaying the preview.
                    mCaptureSession = cameraCaptureSession

                    // Auto focus should be continuous for camera preview.
                    mPreviewRequestBuilder?.
                        set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                    // Auto Exposure and Auto ISO settings
                    mPreviewRequestBuilder?.
                        set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON)

                    // NO flash EVER !!
                    setAutoFlashOff(mPreviewRequestBuilder)

                    // Finally, we start displaying the camera preview.
                    mPreviewRequest = mPreviewRequestBuilder?.build()
                    mPreviewRequest?.let {
                        mCaptureSession?.setRepeatingRequest(it, null, mBackgroundHandler)
                    }

                    mCameraOpenCloseLock.release()
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Timber.e("CameraCaptureSession onConfigureFailed() called...")
                    mCameraOpenCloseLock.release()
                }

            }, mBackgroundHandler)

        } catch (e: CameraAccessException) {
            Timber.e("EXCEPTION: createCameraPreviewSession()")
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while createCameraPreviewSession", e)
        } finally {
            mCameraOpenCloseLock.release()
        }

        return
    }

    // NO flash EVER !!
    private fun setAutoFlashOff(requestBuilder: CaptureRequest.Builder?) {
        Timber.d("setAutoFlashOff() called...")
        if (mFlashSupported) {
            requestBuilder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    /**
     * Opens the camera
     * @param width
     * @param height
     */
     public override fun openCamera(width: Int, height: Int) {
        Timber.d("openCamera() called...")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                // requestCameraPermission()
            return
        }

        if (glSurfaceView == null) return
        if (mCameraDevice != null) return

        try {
            // Wait for camera to open - 2.5 seconds is "sufficient"
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            checkPermission()
            // OPEN the camera
            mCameraId?.also {
                this.launch(this.coroutineContext) {
                    Timber.i("Opening Camera with ID = $it")
                    mCameraManager?.openCamera(it, mStateCallback, mBackgroundHandler)
                }

            }
        } catch (e: CameraAccessException) {
            Timber.e(e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    public override fun closeCamera() {
        Timber.d("closeCamera() called...")

        try {
            mCameraOpenCloseLock.acquire()
            mCaptureSession?.stopRepeating()
            mCaptureSession?.abortCaptures()
            mCaptureSession?.close()
            mCaptureSession = null
            mCameraDevice?.close()
            mCameraDevice = null
            mImageReader?.close()
            mImageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            mCameraOpenCloseLock.release()
            isPreviewStarted = false
        }
    }

    /**
     * Finds the ID of the Rear facing camera
     * @return the ID of the Rear facing camera as a [String]
     */
    private fun getRearFacingCamera(): String? {

        var backCameraId: String? = null
        if (mCameraManager != null) {
            mCameraCharacteristics = mCameraManager?.getCameraCharacteristics("0")
            for (cameraId in (mCameraManager as CameraManager).cameraIdList) {
                val facing = mCameraCharacteristics?.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraMetadata.LENS_FACING_FRONT) {
                    backCameraId = cameraId
                    break
                }
            }
        }

        return backCameraId
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }



}