package com.horizoncam

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class GlCameraView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object { private const val TAG = "GlCameraView" }

    var renderer: GlRenderer? = null

    private var glThread: GlThread? = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val r = renderer
        if (r == null) {
            Log.w(TAG, "surfaceCreated called before renderer was assigned")
            return
        }
        Log.d(TAG, "surfaceCreated ${width}x${height}")
        glThread = GlThread(holder.surface, r, width, height).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        glThread?.updateSize(w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        glThread?.requestStop()
        glThread = null
    }

    fun requestRender() { glThread?.requestRender() }

    inner class GlThread(
        private val displaySurface: android.view.Surface,
        private val renderer: GlRenderer,
        @Volatile private var width: Int,
        @Volatile private var height: Int
    ) : Thread("GlThread") {

        private val lock = Object()
        private var renderRequested = false
        private var running = true

        override fun run() {
            renderer.init(displaySurface)
            renderer.onFrameAvailable = { requestRender() }

            while (running) {
                synchronized(lock) {
                    while (!renderRequested && running) lock.wait(200)
                    renderRequested = false
                }
                if (running) renderer.drawFrame(width, height)
            }
            renderer.release()
        }

        fun requestRender() {
            synchronized(lock) { renderRequested = true; lock.notifyAll() }
        }

        fun updateSize(w: Int, h: Int) { width = w; height = h }

        fun requestStop() {
            running = false
            synchronized(lock) { lock.notifyAll() }
            join(2000)
        }
    }
}
