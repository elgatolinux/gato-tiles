package com.gatotiles.game

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SurfaceView with its own render thread paced by Choreographer, drawing through a
 * hardware-accelerated canvas, so UI-thread work (Compose overlays) never stalls the game.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val session: GameSession) :
    SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = HandlerThread("game-render", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        thread = t
        handler = Handler(t.looper)
        if (Build.VERSION.SDK_INT >= 30) {
            val rate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
            runCatching { holder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        handler?.post {
            session.onResize(width, height)
            if (!running) {
                running = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.post {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            latch.countDown()
        }
        latch.await(600, TimeUnit.MILLISECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val surface = holder.surface
        if (!surface.isValid) return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            return
        }
        try {
            session.onFrame(canvas, frameTimeNanos)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        session.onTouch(event)
        return true
    }
}
