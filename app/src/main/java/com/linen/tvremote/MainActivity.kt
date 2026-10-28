package com.linen.tvremote

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var controller: Controller? = null
    private var resumed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        render()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        teardown()
    }

    private fun teardown() {
        ui.removeCallbacksAndMessages(null)
        controller?.close()
        controller = null
    }

    /** TV -> receiver, phone -> controller, unless the user overrode it with the mode button. */
    private fun render() {
        teardown()
        val receiver = when (Config.mode(this)) {
            "receiver" -> true
            "sender" -> false
            else -> Config.isTv(this)
        }
        if (receiver) showReceiver() else showSender()
    }

    private fun label(text: String, sp: Float, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
        }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open that here. Try Activity Launcher.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ TV side

    private fun showReceiver() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111114.toInt())
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        col.addView(label("TV receiver", 28f))
        val info = label("", 20f, 0xFFB8B8C0.toInt()).apply { setPadding(0, dp(16), 0, dp(16)) }
        col.addView(info)
        col.addView(button("Open accessibility settings") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Open app info (allow restricted settings)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("Switch to controller mode") {
            Config.setMode(this, "sender")
            render()
        })
        setContentView(ScrollView(this).apply { addView(col) })

        val tick = object : Runnable {
            override fun run() {
                val on = RemoteService.instance != null
                info.text = "Service: " +
                    (if (on) "ON" else "OFF - enable 'TV Remote' in Accessibility settings") +
                    "\nAddress: ${Config.localIp() ?: "unknown"}:${Config.PORT}" +
                    "\nPIN: ${Config.pin(this@MainActivity)}" +
                    "\nControllers connected: ${RemoteService.clients.get()}"
                ui.postDelayed(this, 2000)
            }
        }
        tick.run()
    }

    // --------------------------------------------------------------- phone side

    private fun showSender() {
        var connected = false
        var kbMode = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111114.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val status = label("Not connected", 14f, 0xFF9A9AA5.toInt())
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("TV mode") {
            Config.setMode(this, "receiver")
            render()
        })
        root.addView(top)

        // connection row
        val ip = EditText(this).apply {
            hint = "TV IP"
            setText(Config.lastIp(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
        }
        val pin = EditText(this).apply {
            hint = "PIN"
            setText(Config.lastPin(this@MainActivity))
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
        }
        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // touchpad
        val pad = PadView(this) { controller }
        pad.background = GradientDrawable().apply {
            setColor(0xFF1E1E26.toInt())
            cornerRadius = dp(20).toFloat()
        }

        // keyboard block
        fun key(k: String) = controller?.key(k)
        val kb = EditText(this).apply {
            hint = "Type here - goes to the TV"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
        }
        var clearing = false
        kb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (clearing || s.isEmpty()) return
                controller?.text(s.toString())
                clearing = true
                s.clear()
                clearing = false
            }
        })
        kb.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DEL -> { key("backspace"); true }
                KeyEvent.KEYCODE_ENTER -> { key("enter"); true }
                else -> false
            }
        }
        kb.setOnEditorActionListener { _, _, _ -> key("enter"); true }

        val dpadRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val arrows = listOf("<" to "left", "^" to "up", "v" to "down", ">" to "right", "OK" to "center")
        for ((txt, k) in arrows) {
            dpadRow.addView(button(txt) { key(k) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val kbBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dpadRow)
            addView(kb)
        }

        // bottom bar
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun applyVisibility() {
            connectRow.visibility = if (connected) View.GONE else View.VISIBLE
            pad.visibility = if (connected && !kbMode) View.VISIBLE else View.GONE
            kbBlock.visibility = if (connected && kbMode) View.VISIBLE else View.GONE
            bottom.visibility = if (connected) View.VISIBLE else View.GONE
        }

        fun startConnect() {
            val ipS = ip.text.toString().trim()
            val pinS = pin.text.toString().trim()
            if (ipS.isEmpty() || pinS.isEmpty()) return
            controller?.close()
            controller = Controller { msg, ok ->
                runOnUiThread {
                    if (!resumed) return@runOnUiThread
                    status.text = msg
                    connected = ok
                    if (ok) Config.saveTarget(this, ipS, pinS)
                    applyVisibility()
                    if (!ok && msg.startsWith("Disconnected")) {
                        ui.postDelayed({ if (resumed && !connected) startConnect() }, 3000)
                    }
                }
            }
            controller?.connect(ipS, pinS)
        }

        connectRow.addView(ip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        connectRow.addView(pin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectRow.addView(button("Connect") { startConnect() })
        root.addView(connectRow)

        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })
        root.addView(kbBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        fun w(v: View) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bottom.addView(button("Pad") {
            kbMode = false
            imm.hideSoftInputFromWindow(kb.windowToken, 0)
            applyVisibility()
        }, w(pad))
        bottom.addView(button("Keys") {
            kbMode = true
            applyVisibility()
            kb.requestFocus()
            ui.postDelayed({ imm.showSoftInput(kb, 0) }, 100)
        }, w(pad))
        bottom.addView(button("Back") { key("back") }, w(pad))
        bottom.addView(button("Home") { key("home") }, w(pad))
        bottom.addView(button("Apps") { key("recents") }, w(pad))
        root.addView(bottom)

        setContentView(root)
        applyVisibility()
        if (ip.text.isNotEmpty() && pin.text.isNotEmpty()) startConnect()
    }
}
