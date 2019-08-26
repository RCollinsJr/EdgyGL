package com.example.edgygl.opengl

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.renderscript.Matrix4f
import android.util.Size
import android.view.View
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A class to render the data
 * @param ctx Application [Context]
 */
abstract class GLRendererBase(private val ctx: Context)
    : SurfaceTexture.OnFrameAvailableListener,
    GLSurfaceView.Renderer {

    protected abstract fun openCamera(width: Int, height: Int)
    protected abstract fun closeCamera()
    protected abstract fun setCameraPreviewSize(width: Int, height: Int)

    interface EGLSurfaceTextureListener {

        /** EGL Context is ready */
        fun onEGLSurfaceTextureReady(surfaceTexture: SurfaceTexture, width: Int, height: Int)

        /** Surface texture size has changed */
        fun onEGLSurfaceTextureChanged(width: Int, height: Int)

        /** EGL Context is destroyed */
        fun onEGLSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture)
    }

    // the "Model View Projection Matrix"
    private var mProgramOES: Int = -1
    private var mProgram2D: Int = -1

    private var mCameraTextureMatrixHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    private val cameraTextureMatrix = Matrix4f()
    private val mvpMatrix = Matrix4f()

    private val mTexCamera = intArrayOf(0)
    private val mTexFBO = intArrayOf(0)
    private val mTexDraw = intArrayOf(0)

    private var vPositionHandleOES: Int = 0
    private var vTexturePositionHandle: Int = 0
    private var vPositionHandle2D: Int = 0
    private var vTexturePositionHandle2D: Int = 0

    private val mFBO = intArrayOf(0)

    private val mVerticesOES = floatArrayOf(
        -1f, -1f,
        -1f, +1f,
        +1f, -1f,
        +1f, +1f
    )

    private val mTexCoordOES = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private val mTexCoord2D = floatArrayOf(
        1f, 0f,
        1f, 1f,
        0f, 0f,
        0f, 1f
    )

    private var vertexByteBuffer: FloatBuffer? = null
    private var texOESByteBuffer: FloatBuffer? = null
    private var tex2DByteBuffer: FloatBuffer? = null

    init {

        val bytes = mVerticesOES.size * (java.lang.Float.SIZE / java.lang.Byte.SIZE) /* 32/8 = 4 */

        vertexByteBuffer = ByteBuffer
            .allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexByteBuffer?.put(mVerticesOES)?.position(0)

        vertexByteBuffer = ByteBuffer
            .allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexByteBuffer?.put(mVerticesOES)?.position(0)

        texOESByteBuffer = ByteBuffer
            .allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        texOESByteBuffer?.put(mTexCoordOES)?.position(0)

        tex2DByteBuffer = ByteBuffer
            .allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        tex2DByteBuffer?.put(mTexCoord2D)?.position(0)

    }

    private var mHaveFBO = false
    private var mUpdateSurfaceTexture = false
    private var mIsStarted = false
    private var mEnabled = true

    internal var mCameraWidth: Int = -1
    internal var mCameraHeight: Int = -1

    private var mFBOWidth = -1
    private var mFBOHeight = -1

    private var mSurfaceWidth: Int = 0
    private var mSurfaceHeight: Int = 0

    internal var mMaxCameraWidth = -1
    internal var mMaxCameraHeight = -1

    /** ID of the current [CameraDevice] */
    open var mCameraId: String? = null

    /** Size of the current [GLSurfaceView] */
    private var mSurfaceSize = Size(mSurfaceWidth, mSurfaceHeight)

    private var mGLSurfaceView: EdgyGLSurfaceView? = null
    var glSurfaceView: EdgyGLSurfaceView?
        get() = mGLSurfaceView
        set(value) {
            mGLSurfaceView = value
        }

    /** [Boolean] Flag to indicate the presence of a valid surface */
    private var mHasSurface = false
    var hasSurface: Boolean
        get() = mHasSurface
        set(value) {
            mHasSurface = value
        }

    /** The GLSurfaceView's [SurfaceTexture] */
    private var mSurfaceTexture: SurfaceTexture? = null
    var surfaceTexture: SurfaceTexture?
        get() = mSurfaceTexture
        set(value) {
            mSurfaceTexture = value
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // "compile" shader programs and init frame buffer objext
        initShaders()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.mSurfaceWidth = width
        this.mSurfaceHeight = height
        this.mSurfaceSize = (Size(this.mSurfaceWidth, this.mSurfaceHeight))

        mHasSurface = true
        updateStartState()
    }

    override fun onFrameAvailable(p0: SurfaceTexture?) {
        mUpdateSurfaceTexture = true
        glSurfaceView?.requestRender()
    }

    override fun onDrawFrame(gl: GL10) {

        // just return if frame buffer is null
        if (!mHaveFBO) { return }

        synchronized(this) {
            if (mUpdateSurfaceTexture) {
                mSurfaceTexture?.updateTexImage()
                mUpdateSurfaceTexture = false
            }

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val texListener = mGLSurfaceView?.cameraTextureListener
            if (texListener != null) {

                // mTexCamera(OES) to mTexFBO
                drawTexture(mTexCamera[0], true, mFBO[0])

                // call jni code (mTexFBO to mTexDraw)
                texListener.onCameraTexture(mTexFBO[0], mTexDraw[0], mCameraWidth, mCameraHeight)

                // mTexDraw to device screen (show output from jni code)
                drawTexture(mTexDraw[0], false, 0)
            }
        }
    }

    /**
     * Draw texture to FBO or to screen if fbo == 0
     * @param tex Texture handle
     * @param isOES Is texture an OES texture
     * @param fbo If 0 set viewport size to size of mGLSurfaceView
     */
    private fun drawTexture(tex: Int, isOES: Boolean, fbo: Int) {

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)

        if (fbo == -1) {
            mGLSurfaceView?.let { GLES30.glViewport(0, 0, it.width, it.height) }
        }
        if (fbo == 0) {
            mGLSurfaceView?.let { GLES30.glViewport(0, 0, it.width, it.height) }
        }
        else {
            GLES30.glViewport(0, 0, mFBOWidth, mFBOHeight)
        }

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // update the transform matrix
        mSurfaceTexture?.getTransformMatrix(cameraTextureMatrix.array)
        GLES30.glUniformMatrix4fv(mCameraTextureMatrixHandle, 1, false, cameraTextureMatrix.array, 0)

        if (isOES) {
            GLES30.glUseProgram(mProgramOES)
            GLES30.glVertexAttribPointer(vPositionHandleOES, 2, GLES30.GL_FLOAT, false, 4 * 2, vertexByteBuffer)
            GLES30.glVertexAttribPointer(vTexturePositionHandle, 2, GLES30.GL_FLOAT, false, 4 * 2, texOESByteBuffer)
        } else {
            GLES30.glUseProgram(mProgram2D)
            GLES30.glVertexAttribPointer(vPositionHandle2D, 2, GLES30.GL_FLOAT, false, 4 * 2, vertexByteBuffer)
            GLES30.glVertexAttribPointer(vTexturePositionHandle2D, 2, GLES30.GL_FLOAT, false, 4 * 2, tex2DByteBuffer)
        }

        // select active texture unit
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (isOES) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgramOES, UNIFORM_OES_CAMERA_TEXTURE), 0)

        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgram2D, UNIFORM_2D_TEXTURE), 0)
        }

        // fill the Mvp Matrix in the vertex shader
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix.array, 0)

        // And draw
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glFlush()
    }

    /**
     * Compile shader programs and assign some variables
     */
    private fun initShaders() {

        val version = GLES30.glGetString(GLES30.GL_VERSION)
        if (version != null) { Timber.d("OpenGL ES version: $version") }

        // Compile programOES fragment shader
        mProgramOES = ctx.let {
            GlUtil.instance.createProgram(it, VERTEX_SHADER, FRAGMENT_SHADER_OES)
        }
        check(mProgramOES != 0) { "Failed to create mProgramOES" }

        vPositionHandleOES = GLES30.glGetAttribLocation(mProgramOES, POSITION)
        vTexturePositionHandle = GLES30.glGetAttribLocation(mProgramOES, ATTR_TEXTURE_POSITION)
        mCameraTextureMatrixHandle = GLES30.glGetUniformLocation(mProgramOES, UNIFORM_CAMERA_MATRIX)
        mvpMatrixHandle = GLES30.glGetUniformLocation(mProgramOES, MVP_MATRIX)
        GLES30.glEnableVertexAttribArray(vPositionHandleOES)
        GLES30.glEnableVertexAttribArray(vTexturePositionHandle)

        // Compile program2D fragment shader
        mProgram2D = ctx.let {
            GlUtil.instance.createProgram(it, VERTEX_SHADER, FRAGMENT_SHADER_2D)
        }
        check(mProgram2D != 0) { "Failed to create mProgram2D" }

        vPositionHandle2D = GLES30.glGetAttribLocation(mProgram2D, POSITION)
        vTexturePositionHandle2D = GLES30.glGetAttribLocation(mProgram2D, ATTR_TEXTURE_POSITION)
        GLES30.glEnableVertexAttribArray(vPositionHandle2D)
        GLES30.glEnableVertexAttribArray(vTexturePositionHandle2D)
    }

    open fun initSurfaceTextures() {
        deleteSurfaceTextures()
        initTextureOES(mTexCamera)
        mSurfaceTexture = SurfaceTexture(mTexCamera[0])
        mSurfaceTexture?.setOnFrameAvailableListener(this)
    }

    open fun deleteSurfaceTextures() {
        // preview with overlays
        if (mSurfaceTexture != null) {
            mSurfaceTexture?.release()
            mSurfaceTexture = null
            deleteTexture(mTexCamera)
        }
    }

    private fun deleteTexture(tex: IntArray) {
        if (tex.size == 1) {
            GLES30.glDeleteTextures(1, tex, 0)
        }
    }

    open fun initTextureOES(tex: IntArray) {
        if (tex.size == 1) {
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        }
    }

    /**
     * Updates the "start" state of the GL thread doStart() and doStop() )
     * The following pre-conditions must be met for doStart() to be called:
     * mEnabled = true - gets set in enableView() or disableView()
     * mHasSurface= true - gets set in onEGLSurfaceTextureReady() in the AstroCamRenderer class
     * mAutoGLSurfaceView must be in the [View.VISIBLE] state
     *
     */
    private fun updateStartState() {

        // see if the startup requirements have all been met
        val canStart = mEnabled && mHasSurface && mGLSurfaceView?.visibility == View.VISIBLE

        // if already started don't start again
        if (canStart != mIsStarted) {
            when {
                canStart -> doStart()
                else -> doStop()
            }
        }
    }

    /**
     * Initializes the [GLSurfaceView]'s [SurfaceTexture], opens the camera
     * and creates the frame buffer object for the gl textures we will use
     */
    @Synchronized
    protected open fun doStart() {
        initSurfaceTextures()
        mIsStarted = true
        if (mCameraWidth > 0 && mCameraHeight > 0) {
            openCamera(mCameraWidth, mCameraHeight)
            createFBO(mCameraWidth, mCameraHeight)
        }
    }

    /**
     * Stops the camera preview and deletes the [GLSurfaceView]'s [SurfaceTexture]
     */
    protected open fun doStop() {
        synchronized(this) {
            mUpdateSurfaceTexture = false
            mIsStarted = false
            mHaveFBO = false
            closeCamera()
            deleteSurfaceTextures()
        }

        mGLSurfaceView?.cameraTextureListener?.onCameraViewStopped()
    }

    /**
     * Set the SurfaceView size, Camera Preview size and fbo size
     * @param width
     * @param height
     */
    private fun createFBO(width: Int, height: Int) {
        Timber.d("createFBO() called...")
        synchronized(this) {
            mHaveFBO = false
            initFBO(width, height)
            mHaveFBO = true
        }

        mGLSurfaceView?.cameraTextureListener?.onCameraViewStarted(mCameraWidth, mCameraHeight)
    }

    private fun deleteFBO() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, mFBO, 0)
        GlUtil.instance.checkGLError("deleteFBO() error status")

        deleteTexture(mTexFBO)
        deleteTexture(mTexDraw)

        mFBOHeight = 0
        mFBOWidth = mFBOHeight
    }

    /**
     * Init textures and add to FBO
     * @param width Width of the textures being added
     * @param height Height of the textures being added
     */
    private fun initFBO(width: Int, height: Int) {
        Timber.d("initFBO() called...")
        deleteFBO()

        GLES30.glGenTextures(1, mTexDraw, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexDraw[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GlUtil.instance.checkGLError("Bind mTexDraw error status")

        GLES30.glGenTextures(1, mTexFBO, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mTexFBO[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GlUtil.instance.checkGLError("Bind mTexFBO error status")

        GLES30.glGenFramebuffers(1, mFBO, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFBO[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, mTexFBO[0], 0)
        GlUtil.instance.checkGLError("initFBO error status")

        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) // GL_FRAMEBUFFER_COMPLETE = 36053 = 0x8CD5
            Timber.e("initFBO failed, status: $fboStatus")

        mFBOWidth = width
        mFBOHeight = height
    }

    // good place for constants
    companion object {

        /** Max preview width that is guaranteed by Camera2 API */
        internal const val MAX_PREVIEW_WIDTH = 1920

        /** Max preview height that is guaranteed by Camera2 API */
        internal const val MAX_PREVIEW_HEIGHT = 1080

        private const val VERTEX_SHADER = "vertex_shader.glsl"
        private const val FRAGMENT_SHADER_OES = "fragment_shader_oes.glsl"
        private const val FRAGMENT_SHADER_2D = "fragment_shader_2d.glsl"

        private const val POSITION = "position"
        private const val ATTR_TEXTURE_POSITION = "texturePosition"
        private const val UNIFORM_CAMERA_MATRIX = "cameraTextureMatrix"
        private const val MVP_MATRIX = "mvpMatrix"
        private const val UNIFORM_OES_CAMERA_TEXTURE = "cameraTexture"
        private const val UNIFORM_2D_TEXTURE = "sampler2DTexture"
    }

}
