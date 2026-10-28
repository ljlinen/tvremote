package com.linen.tvremote

import org.json.JSONObject
import java.io.IOException
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-side connection to the TV. Newline-delimited JSON over TCP. */
class Controller(private val onStatus: (String, Boolean) -> Unit) {

    @Volatile private var sock: Socket? = null
    @Volatile private var out: Writer? = null
    @Volatile private var closed = false
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var px = 0f
    private var py = 0f
    private val moveQueued = AtomicBoolean(false)

    private fun status(msg: String, ok: Boolean) {
        if (!closed) onStatus(msg, ok)
    }

    fun connect(ip: String, pin: String) {
        Thread {
            status("Connecting...", false)
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, Config.PORT), 3000)
                s.soTimeout = 5000
                val w = s.getOutputStream().bufferedWriter()
                val r = s.getInputStream().bufferedReader()
                w.write(JSONObject().put("t", "hello").put("pin", pin).toString() + "\n")
                w.flush()
                val resp = JSONObject(r.readLine() ?: throw IOException("closed"))
                if (resp.optString("t") != "ok") {
                    s.close()
                    status("Wrong PIN", false)
                    return@Thread
                }
                s.soTimeout = 0
                sock = s
                out = w
                status("Connected", true)
                while (r.readLine() != null) { /* TV sends nothing; this just waits for the link to drop */ }
            } catch (e: Exception) {
            }
            status("Disconnected", false)
        }.start()
    }

    fun close() {
        closed = true
        try { sock?.close() } catch (_: Exception) {}
        exec.shutdownNow()
    }

    private fun write(line: String) {
        try {
            out?.write(line)
            out?.flush()
        } catch (e: Exception) {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    fun send(j: JSONObject) {
        val line = j.toString() + "\n"
        try { exec.execute { write(line) } } catch (_: Exception) {}
    }

    /** Mouse deltas are batched every ~8ms so a fast swipe doesn't flood the network. */
    fun move(dx: Float, dy: Float) {
        synchronized(lock) { px += dx; py += dy }
        if (moveQueued.compareAndSet(false, true)) {
            try {
                exec.schedule(Runnable {
                    moveQueued.set(false)
                    val (x, y) = synchronized(lock) {
                        val a = Pair(px, py)
                        px = 0f
                        py = 0f
                        a
                    }
                    if (x != 0f || y != 0f) {
                        write(JSONObject().put("t", "move").put("dx", r(x)).put("dy", r(y)).toString() + "\n")
                    }
                }, 8, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                moveQueued.set(false)
            }
        }
    }

    private fun r(v: Float) = Math.round(v * 10) / 10.0

    fun click() = send(JSONObject().put("t", "click"))
    fun longPress() = send(JSONObject().put("t", "long"))
    fun scroll(d: Int) = send(JSONObject().put("t", "scroll").put("d", d))
    fun key(k: String) = send(JSONObject().put("t", "key").put("k", k))
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))
}
