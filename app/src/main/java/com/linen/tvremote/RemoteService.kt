package com.linen.tvremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Draws the arrow cursor on top of everything. */
class CursorView(ctx: Context) : View(ctx) {
    var px = 0f
    var py = 0f
    private val s = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * s
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17f * s)
        lineTo(4.5f * s, 13f * s)
        lineTo(8f * s, 20f * s)
        lineTo(11f * s, 18.5f * s)
        lineTo(7.5f * s, 12f * s)
        lineTo(13f * s, 12f * s)
        close()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(px, py)
        c.drawPath(arrow, fill)
        c.drawPath(arrow, edge)
        c.restore()
    }
}

class RemoteService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteService? = null
        val clients = AtomicInteger(0)
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var wm: WindowManager? = null
    private var cursor: CursorView? = null
    private var cx = 0f
    private var cy = 0f
    private var sw = 1920
    private var sh = 1080
    private var lastScroll = 0L
    private val hide = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        cx = sw / 2f
        cy = sh / 2f

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = CursorView(this)
        v.visibility = View.GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm?.addView(v, lp)
        cursor = v
        startServer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        instance = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        main.removeCallbacks(hide)
        try { cursor?.let { wm?.removeView(it) } } catch (_: Exception) {}
        cursor = null
    }

    // ---------------------------------------------------------------- network

    private fun startServer() {
        Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(Config.PORT))
                server = s
                while (!s.isClosed) {
                    val sock = s.accept()
                    Thread { handle(sock) }.start()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 5000 // must send "hello" within 5s
            val r = sock.getInputStream().bufferedReader()
            val w = sock.getOutputStream().bufferedWriter()

            val first = JSONObject(r.readLine() ?: return)
            if (first.optString("t") != "hello" ||
                first.optString("pin") != Config.pin(applicationContext)
            ) {
                w.write("{\"t\":\"bad\"}\n"); w.flush()
                Thread.sleep(1000) // slow down PIN guessing
                return
            }
            w.write("{\"t\":\"ok\"}\n"); w.flush()
            sock.soTimeout = 0

            clients.incrementAndGet()
            try {
                while (true) {
                    val line = r.readLine() ?: break
                    val j = JSONObject(line)
                    main.post { run(j) }
                }
            } finally {
                clients.decrementAndGet()
                main.post { if (clients.get() == 0) cursor?.visibility = View.GONE }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // --------------------------------------------------------------- commands

    private fun run(j: JSONObject) {
        try {
            when (j.optString("t")) {
                "move" -> {
                    cx = (cx + j.optDouble("dx", 0.0).toFloat()).coerceIn(0f, sw - 1f)
                    cy = (cy + j.optDouble("dy", 0.0).toFloat()).coerceIn(0f, sh - 1f)
                    showCursor()
                }
                "click" -> tap(60)
                "long" -> tap(700)
                "scroll" -> scroll(j.optInt("d", 0))
                "key" -> key(j.optString("k"))
                "text" -> editFocused { it + j.optString("s") }
            }
        } catch (_: Exception) {
        }
    }

    private fun showCursor() {
        val v = cursor ?: return
        v.px = cx
        v.py = cy
        v.visibility = View.VISIBLE
        v.invalidate()
        main.removeCallbacks(hide)
        main.postDelayed(hide, 5000)
    }

    private fun tap(ms: Long) {
        val p = Path().apply { moveTo(cx, cy) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        dispatchGesture(g, null, null)
        showCursor()
    }

    /** d = +1: content moves down the page (finger swipes up). d = -1: the opposite. */
    private fun scroll(d: Int) {
        if (d == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScroll < 150) return
        lastScroll = now
        val half = sh * 0.15f
        val y1 = (cy + half * d).coerceIn(1f, sh - 2f)
        val y2 = (cy - half * d).coerceIn(1f, sh - 2f)
        val p = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 200))
            .build()
        dispatchGesture(g, null, null)
        showCursor()
    }

    private fun key(k: String) {
        when (k) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "backspace" -> editFocused { it.dropLast(1) }
            "enter" -> pressEnter()
            "up" -> dpad(GLOBAL_ACTION_DPAD_UP)
            "down" -> dpad(GLOBAL_ACTION_DPAD_DOWN)
            "left" -> dpad(GLOBAL_ACTION_DPAD_LEFT)
            "right" -> dpad(GLOBAL_ACTION_DPAD_RIGHT)
            "center" -> dpad(GLOBAL_ACTION_DPAD_CENTER)
        }
    }

    private fun dpad(action: Int) {
        if (Build.VERSION.SDK_INT >= 33) performGlobalAction(action)
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val n = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (n != null) return n
            }
        } catch (_: Exception) {
        }
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun editFocused(transform: (String) -> String) {
        val n = focusedInput() ?: return
        val hint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val cur = if (hint) "" else (n.text?.toString() ?: "")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, transform(cur))
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pressEnter() {
        val n = focusedInput() ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }
}
