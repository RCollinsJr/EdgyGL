package com.example.edgygl.opengl

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import java.io.BufferedReader
import java.io.IOException
import timber.log.Timber

/**
 * Some OpenGL utility functions adapted from Grafika
 */
class GlUtil {

    companion object {

        private var INSTANCE: GlUtil? = null
        val instance: GlUtil
            get() {
                if (INSTANCE == null) {
                    INSTANCE = GlUtil()
                }
                return INSTANCE as GlUtil
            }

        /** Identity matrix for general use */
        private val IDENTITY_MATRIX: FloatArray = FloatArray(16)
    }

    // Kotlin class init() function
    init {
        Matrix.setIdentityM(IDENTITY_MATRIX, 0)
    }

    /**
     * Creates a new program from the supplied vertex and fragment shaders
     * @param context Application [Context]
     * @param vertexAssetFile Asset file containing the vertex shader code
     * @param fragmentAssetFile Asset file containing the fragment shader code
     * @return A handle to the program, or 0 on failure
     */
    fun createProgram(context: Context, vertexAssetFile: String, fragmentAssetFile: String): Int {
        Timber.d("createProgram() called...")
        val vertexSource = getStringFromFileInAssets(context, "shaders/$vertexAssetFile")
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) { return 0 }

        val fragmentSource = getStringFromFileInAssets(context, "shaders/$fragmentAssetFile")
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) { return 0 }

        var program = GLES30.glCreateProgram()
        checkGLError("glCreateProgram")
        if (program == 0) {
            Timber.e("Could not create program from %s, %s", "shaders/$fragmentAssetFile", "shaders/$vertexAssetFile")
        } else {

            GLES30.glAttachShader(program, vertexShader)
            checkGLError("glAttachShader")
            GLES30.glAttachShader(program, fragmentShader)
            checkGLError("glAttachShader")
            GLES30.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES30.GL_TRUE) {
                Timber.e("Could not link program: %s", GLES30.glGetProgramInfoLog(program))
                GLES30.glDeleteProgram(program)
                program = 0
            }
        }

        return program
    }

    /**
     * Compiles the provided shader source
     * @return A handle to the shader, or 0 on failure
     */
    private fun compileShader(shaderType: Int, source: String): Int {

        val shader = GLES30.glCreateShader(shaderType)
        checkGLError("glCreateShader type=$shaderType")
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Timber.e("Could not compile shader %d", shaderType)
            Timber.e(GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    /**
     * Convert an asset file into a [String]
     * @param ctx The application [Context]
     * @param filename The asset file name as a [String]
     * @return A [String] built from the shader file found in the options_menu.assets folder
     */
    private fun getStringFromFileInAssets(ctx: Context, filename: String): String {

        try {
            val inputStream = ctx.assets.open(filename)
            val line = inputStream.bufferedReader().use(BufferedReader::readText)
            inputStream.close()

            return line
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    /**
     * Checks to see if a GLES error has been raised.
     * @param glOperation A [String] representing the OpenGL operation
     */
    fun checkGLError(glOperation: String) {
        val error = GLES30.glGetError()
        val msg = glOperation + ": glError 0x" + Integer.toHexString(error)
        if (error != GLES30.GL_NO_ERROR) {
            Timber.e(msg)
            throw RuntimeException(msg) // TODO do something more graceful...
        } else {
            Timber.d("Success: $msg")
        }
    }
}