package com.horizoncam

import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class GlRenderer(private val stabilizer: HorizonStabilizer) {

    companion object {
        private const val TAG = "GlRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // -----------------------------------------------------------------
        // Pass 1 shaders: OES camera texture → FBO
        // Applies texMatrix to handle sensor orientation + Y-flip.
        // Produces a clean, correctly-oriented RGBA image in the FBO.
        // -----------------------------------------------------------------
        private val PASS1_VERT = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uTexMatrix;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        private val PASS1_FRAG = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        // -----------------------------------------------------------------
        // Pass 2 shaders: FBO texture → display / recorder
        // Applies counter-rotation and aspect-correct crop.
        // Works on a standard sampler2D with clean (0,0)→(1,1) coords.
        //
        // Coordinate system (isotropic / physical space):
        //   y spans [-0.5, +0.5]  (image height = 1.0)
        //   x spans [-ratio/2, +ratio/2]  (image width = ratio)
        //
        // uOutRatio  = output W/H  (portrait 9:16 → 0.5625, square → 1.0)
        // uSrcRatio  = FBO content W/H  (portrait 9:16 → 0.5625)
        // uCropScale = inscribed crop factor for rotation headroom
        // uCosSin    = (cos(roll), sin(roll)) for counter-rotation
        // -----------------------------------------------------------------
        private val PASS2_VERT = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val PASS2_FRAG = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            uniform vec2  uCosSin;
            uniform float uCropScale;
            uniform float uSrcRatio;
            uniform float uOutRatio;
            void main() {
                vec2 pos = vTexCoord - vec2(0.5);
                pos.x *= uOutRatio;
                pos *= uCropScale;

                float c = uCosSin.x;
                float s = uCosSin.y;
                vec2 rot;
                rot.x =  c * pos.x + s * pos.y;
                rot.y = -s * pos.x + c * pos.y;

                vec2 tc;
                tc.x = rot.x / uSrcRatio + 0.5;
                tc.y = rot.y + 0.5;

                gl_FragColor = texture2D(sTexture, tc);
            }
        """.trimIndent()

        private val QUAD_VERTS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEX   = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }

    // =====================================================================
    // EGL state
    // =====================================================================

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext  = EGL14.EGL_NO_CONTEXT
    private var eglConfig:  EGLConfig?  = null
    private var displayEglSurface:  EGLSurface = EGL14.EGL_NO_SURFACE
    private var recorderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // =====================================================================
    // GL — Pass 1 (OES → FBO)
    // =====================================================================

    private var pass1Program   = 0
    private var p1LocPosition  = 0
    private var p1LocTexCoord  = 0
    private var p1LocTexMatrix = 0
    private var oesTexId       = 0

    // =====================================================================
    // GL — Pass 2 (FBO → output surfaces)
    // =====================================================================

    private var pass2Program   = 0
    private var p2LocPosition  = 0
    private var p2LocTexCoord  = 0
    private var p2LocCosSin    = 0
    private var p2LocCropScale = 0
    private var p2LocSrcRatio  = 0
    private var p2LocOutRatio  = 0

    // =====================================================================
    // FBO (intermediate render target)
    // =====================================================================

    private var fboId     = 0
    private var fboTexId  = 0
    private var fboWidth  = 0
    private var fboHeight = 0
    @Volatile private var fboNeedsRecreate = true

    // =====================================================================
    // Shared
    // =====================================================================

    private val vboIds = IntArray(2)
    private val texMatrix = FloatArray(16)

    private var cameraSurfaceTexture: SurfaceTexture? = null
    var cameraSurface: Surface? = null
        private set

    var isActionMode = false
    var onFrameAvailable: (() -> Unit)? = null

    /** Source W/H after sensor orientation transform. E.g. 0.5625 for portrait 9:16. */
    var effectiveSrcRatio: Float = 0.5625f
        set(value) {
            field = value
            fboNeedsRecreate = true
        }

    /** Camera capture buffer size (sensor coordinates, e.g. 1920×1080). */
    var videoSize: Size = Size(1920, 1080)
        set(value) {
            field = value
            cameraSurfaceTexture?.setDefaultBufferSize(value.width, value.height)
            fboNeedsRecreate = true
        }

    /** Recorder output size — changes with aspect ratio selection. */
    var recordSize: Size = Size(1080, 1920)

    // =====================================================================
    // Crop scale math (rotation-only, no translation margin)
    // =====================================================================

    /**
     * Maximum safe crop scale for full 360° rotation.
     *
     * An output rectangle with aspect [outRatio] (W/H) must fit inside a
     * source rectangle with aspect [srcRatio] (W/H) at every rotation angle.
     * Checks θ = 0°, 45°, 90° (the critical angles) and returns the tightest.
     */
    private fun computeActionCropScale(srcRatio: Float, outRatio: Float): Float {
        val sw = srcRatio / 2f     // source half-width
        val sh = 0.5f              // source half-height
        val ow = outRatio / 2f     // output half-width
        val oh = 0.5f              // output half-height

        // θ = 0°
        val cs0 = min(sw / ow, sh / oh)
        // θ = 45°
        val s45 = 0.7071f
        val diag = s45 * (ow + oh)
        val cs45 = min(sw / diag, sh / diag)
        // θ = 90°
        val cs90 = min(sw / oh, sh / ow)

        return min(cs0, min(cs45, cs90))
    }

    /**
     * Aspect-only crop (no rotation). Normal mode.
     */
    private fun computeAspectCrop(srcRatio: Float, outRatio: Float): Float {
        return min(srcRatio / outRatio, 1f)
    }

    // =====================================================================
    // Init / Release
    // =====================================================================

    fun init(displaySurface: Surface) {
        setupEgl(displaySurface)
        setupPass1Program()
        setupPass2Program()
        setupVbos()
        setupCameraTexture()
    }

    fun release() {
        cameraSurfaceTexture?.release(); cameraSurfaceTexture = null
        cameraSurface?.release();        cameraSurface = null
        destroyFbo()
        if (vboIds[0] != 0) GLES20.glDeleteBuffers(2, vboIds, 0)
        if (pass1Program != 0) { GLES20.glDeleteProgram(pass1Program); pass1Program = 0 }
        if (pass2Program != 0) { GLES20.glDeleteProgram(pass2Program); pass2Program = 0 }
        if (oesTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0); oesTexId = 0
        }
        detachRecorderSurface()
        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // =====================================================================
    // Draw — two-pass pipeline
    // =====================================================================

    fun drawFrame(width: Int, height: Int) {
        cameraSurfaceTexture?.updateTexImage()
        cameraSurfaceTexture?.getTransformMatrix(texMatrix)

        if (fboNeedsRecreate) {
            recreateFbo()
            fboNeedsRecreate = false
        }
        if (fboId == 0) return

        EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)

        // Negate rollAngle to counter-rotate
        val roll = if (isActionMode) -stabilizer.rollAngle else 0f
        val cosR = cos(roll.toDouble()).toFloat()
        val sinR = sin(roll.toDouble()).toFloat()
        val src  = effectiveSrcRatio

        // === PASS 1: OES camera texture → FBO ===
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        drawPass1()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // === PASS 2: FBO → Display surface ===
        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            GLES20.glViewport(0, 0, width, height)
            val dCrop = if (isActionMode) computeActionCropScale(src, src) else 1f
            drawPass2(cosR, sinR, dCrop, src, src)
            EGL14.eglSwapBuffers(eglDisplay, displayEglSurface)
        }

        // === PASS 2: FBO → Recorder surface ===
        if (recorderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, recorderEglSurface, recorderEglSurface, eglContext)
            GLES20.glViewport(0, 0, recordSize.width, recordSize.height)
            val rOut  = recordSize.width.toFloat() / recordSize.height.toFloat()
            val rCrop = if (isActionMode) computeActionCropScale(src, rOut)
            else computeAspectCrop(src, rOut)
            drawPass2(cosR, sinR, rCrop, src, rOut)
            EGL14.eglSwapBuffers(eglDisplay, recorderEglSurface)
        }
    }

    fun attachRecorderSurface(surface: Surface) {
        recorderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEgl("attachRecorderSurface")
    }

    fun detachRecorderSurface() {
        if (recorderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, recorderEglSurface)
            recorderEglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // =====================================================================
    // Pass 1: render OES → FBO
    // =====================================================================

    private fun drawPass1() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(pass1Program)

        GLES20.glUniformMatrix4fv(p1LocTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

        GLES20.glEnableVertexAttribArray(p1LocPosition)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glVertexAttribPointer(p1LocPosition, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glEnableVertexAttribArray(p1LocTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
        GLES20.glVertexAttribPointer(p1LocTexCoord, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(p1LocPosition)
        GLES20.glDisableVertexAttribArray(p1LocTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // =====================================================================
    // Pass 2: render FBO texture → output (rotation + crop)
    // =====================================================================

    private fun drawPass2(cosR: Float, sinR: Float, cropScale: Float,
                          srcRatio: Float, outRatio: Float) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(pass2Program)

        GLES20.glUniform2f(p2LocCosSin, cosR, sinR)
        GLES20.glUniform1f(p2LocCropScale, cropScale)
        GLES20.glUniform1f(p2LocSrcRatio, srcRatio)
        GLES20.glUniform1f(p2LocOutRatio, outRatio)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)

        GLES20.glEnableVertexAttribArray(p2LocPosition)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glVertexAttribPointer(p2LocPosition, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glEnableVertexAttribArray(p2LocTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
        GLES20.glVertexAttribPointer(p2LocTexCoord, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(p2LocPosition)
        GLES20.glDisableVertexAttribArray(p2LocTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // =====================================================================
    // FBO management
    // =====================================================================

    private fun recreateFbo() {
        destroyFbo()

        val maxDim = maxOf(videoSize.width, videoSize.height)
        val minDim = minOf(videoSize.width, videoSize.height)
        if (effectiveSrcRatio <= 1f) {
            fboWidth  = minDim
            fboHeight = maxDim
        } else {
            fboWidth  = maxDim
            fboHeight = minDim
        }

        Log.d(TAG, "Creating FBO ${fboWidth}x${fboHeight} " +
                "(video=${videoSize}, srcRatio=$effectiveSrcRatio)")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTexId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            fboWidth, fboHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTexId, 0)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete: 0x${status.toString(16)}")
            destroyFbo()
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun destroyFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0)
            fboTexId = 0
        }
        fboWidth = 0; fboHeight = 0
    }

    // =====================================================================
    // EGL setup
    // =====================================================================

    private fun setupEgl(displaySurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(1), 0, IntArray(1), 0)

        val attribsWithRecordable = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val attribsBasic = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        var ok = EGL14.eglChooseConfig(
            eglDisplay, attribsWithRecordable, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0 && configs[0] != null
        if (!ok) {
            Log.w(TAG, "EGL_RECORDABLE_ANDROID not supported, falling back")
            EGL14.eglChooseConfig(eglDisplay, attribsBasic, 0, configs, 0, 1, numConfigs, 0)
        }
        eglConfig = configs[0] ?: throw RuntimeException("No suitable EGL config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        checkEgl("eglCreateContext")

        displayEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, displaySurface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEgl("eglCreateWindowSurface")

        EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)
    }

    // =====================================================================
    // GL program setup
    // =====================================================================

    private fun setupPass1Program() {
        pass1Program = buildProgram(PASS1_VERT, PASS1_FRAG)
        p1LocPosition  = GLES20.glGetAttribLocation(pass1Program, "aPosition")
        p1LocTexCoord  = GLES20.glGetAttribLocation(pass1Program, "aTexCoord")
        p1LocTexMatrix = GLES20.glGetUniformLocation(pass1Program, "uTexMatrix")
    }

    private fun setupPass2Program() {
        pass2Program = buildProgram(PASS2_VERT, PASS2_FRAG)
        p2LocPosition  = GLES20.glGetAttribLocation(pass2Program, "aPosition")
        p2LocTexCoord  = GLES20.glGetAttribLocation(pass2Program, "aTexCoord")
        p2LocCosSin    = GLES20.glGetUniformLocation(pass2Program, "uCosSin")
        p2LocCropScale = GLES20.glGetUniformLocation(pass2Program, "uCropScale")
        p2LocSrcRatio  = GLES20.glGetUniformLocation(pass2Program, "uSrcRatio")
        p2LocOutRatio  = GLES20.glGetUniformLocation(pass2Program, "uOutRatio")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    // =====================================================================
    // VBO setup
    // =====================================================================

    private fun setupVbos() {
        GLES20.glGenBuffers(2, vboIds, 0)
        val verts = ByteBuffer.allocateDirect(QUAD_VERTS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(QUAD_VERTS); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, QUAD_VERTS.size * 4, verts, GLES20.GL_STATIC_DRAW)

        val tex = ByteBuffer.allocateDirect(QUAD_TEX.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(QUAD_TEX); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, QUAD_TEX.size * 4, tex, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // =====================================================================
    // Camera texture setup
    // =====================================================================

    private fun setupCameraTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTexId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(oesTexId).also { st ->
            st.setDefaultBufferSize(videoSize.width, videoSize.height)
            st.setOnFrameAvailableListener { onFrameAvailable?.invoke() }
        }
        cameraSurface = Surface(cameraSurfaceTexture)
    }

    // =====================================================================
    // Utility
    // =====================================================================

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            val s = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, s, 0)
            if (s[0] == 0) Log.e(TAG, "Link error: ${GLES20.glGetProgramInfoLog(it)}")
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val s = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, s, 0)
            if (s[0] == 0) Log.e(TAG, "Shader error: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun checkEgl(op: String) {
        val e = EGL14.eglGetError()
        if (e != EGL14.EGL_SUCCESS) Log.e(TAG, "$op EGL error: 0x${e.toString(16)}")
    }
}