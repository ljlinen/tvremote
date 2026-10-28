package com.linen.tvremote

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object Config {
    const val PORT = 8765

    private fun p(c: Context) = c.getSharedPreferences("remote", Context.MODE_PRIVATE)

    /** The TV's pairing PIN. Generated once, then stays the same so the phone can auto-reconnect. */
    fun pin(c: Context): String {
        val prefs = p(c)
        prefs.getString("pin", null)?.let { return it }
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        prefs.edit().putString("pin", pin).apply()
        return pin
    }

    fun mode(c: Context): String = p(c).getString("mode", "auto") ?: "auto"
    fun setMode(c: Context, m: String) = p(c).edit().putString("mode", m).apply()

    fun lastIp(c: Context): String = p(c).getString("ip", "") ?: ""
    fun lastPin(c: Context): String = p(c).getString("lpin", "") ?: ""
    fun saveTarget(c: Context, ip: String, pin: String) =
        p(c).edit().putString("ip", ip).putString("lpin", pin).apply()

    fun isTv(c: Context): Boolean {
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
