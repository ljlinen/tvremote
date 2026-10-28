package com.linen.tvremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/** Touchpad: drag = move, tap = click, hold = long press, two-finger drag = scroll, two-finger tap = back. */
class PadView(ctx: Context, private val ctl: () -> Controller?) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var longFired = false
    private var twoFinger = false
    private var twoMoved = false
    private var twoHandled = false
    private var twoDownT = 0L
    private var scrollAcc = 0f

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A6A78.toInt()
        textSize = 14f * d
        textAlign = Paint.Align.CENTER
    }

    private val longRun = Runnable {
        if (!moved && !twoFinger) {
            longFired = true
            ctl()?.longPress()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val lines = listOf(
            "Drag to move, tap to click",
            "Hold for long press",
            "Two fingers: scroll",
            "Two-finger tap: back"
        )
        var y = height / 2f - (lines.size - 1) * 12f * d
        for (l in lines) {
            c.drawText(l, width / 2f, y, hintPaint)
            y += 24f * d
        }
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                downT = e.eventTime
                moved = false; longFired = false
                twoFinger = false; twoMoved = false; twoHandled = false
                handler.postDelayed(longRun, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    twoFinger = true
                    twoMoved = false
                    twoHandled = false
                    twoDownT = e.eventTime
                    scrollAcc = 0f
                    lastY = avgY(e)
                    handler.removeCallbacks(longRun)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val y = avgY(e)
                    scrollAcc += y - lastY
                    lastY = y
                    if (abs(scrollAcc) > 30f * d) {
                        twoMoved = true
                        ctl()?.scroll(if (scrollAcc < 0) 1 else -1)
                        scrollAcc = 0f
                    }
                } else if (!twoFinger) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (!moved && hypot(e.x - downX, e.y - downY) > 12f * d) {
                        moved = true
                        handler.removeCallbacks(longRun)
                    }
                    if (moved) {
                        val f = 1.2f + (hypot(dx, dy) / 10f).coerceAtMost(1.8f)
                        ctl()?.move(dx * f, dy * f)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFinger && !twoMoved && !twoHandled && e.eventTime - twoDownT < 300) {
                    ctl()?.key("back")
                }
                twoHandled = true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRun)
                if (!moved && !twoFinger && !longFired && e.eventTime - downT < 300) {
                    ctl()?.click()
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRun)
        }
        return true
    }
}
