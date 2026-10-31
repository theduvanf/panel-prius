package com.theduvanf.panelprius

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var obd: ObdBle
    private var pendingScan = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(0xFF000000.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        obd = ObdBle(this, object : ObdBle.Listener {
            override fun onDevice(name: String, address: String) =
                js("window.onObdDevice && window.onObdDevice(${JSONObject.quote(name)}, ${JSONObject.quote(address)})")

            override fun onStatus(text: String, live: Boolean) =
                js("window.onObdStatus && window.onObdStatus(${JSONObject.quote(text)}, $live)")

            override fun onData(data: JSONObject) =
                js("window.onObdData && window.onObdData($data)")
        })

        web.loadUrl("file:///android_asset/index.html")
        hideSystemBars()
    }

    private fun js(code: String) {
        web.post { web.evaluateJavascript(code, null) }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Works as the car's home screen: back goes to the panel's home view, never exits.
        js("document.querySelector('[data-go=\"home\"]').click()")
    }

    override fun onDestroy() {
        obd.disconnect()
        super.onDestroy()
    }

    // ---------- permissions ----------
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && pendingScan) {
            pendingScan = false
            if (hasPermissions()) obd.scan()
            else obd.listener.onStatus("Sin permiso de Bluetooth no puedo buscar el adaptador. Actívalo en Ajustes de Android → Apps → Panel Prius → Permisos.", false)
        }
    }

    // ---------- JavaScript bridge ----------
    inner class Bridge {
        @JavascriptInterface
        fun getApps(): String {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = JSONArray()
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .distinctBy { it.activityInfo.packageName }
                .forEach { info ->
                    val o = JSONObject()
                    o.put("label", info.loadLabel(pm).toString())
                    o.put("pkg", info.activityInfo.packageName)
                    try { o.put("icon", iconBase64(info.loadIcon(pm))) } catch (_: Exception) {}
                    list.put(o)
                }
            return list.toString()
        }

        @JavascriptInterface
        fun openApp(pkg: String) {
            runOnUiThread {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    obd.listener.onStatus("Esa app no está instalada en la tablet.", obd.isLive)
                }
            }
        }

        @JavascriptInterface
        fun obdScan() {
            runOnUiThread {
                if (hasPermissions()) obd.scan()
                else { pendingScan = true; requestPermissions(neededPermissions(), 7) }
            }
        }

        @JavascriptInterface
        fun obdStopScan() = obd.stopScan()

        @JavascriptInterface
        fun obdConnect(address: String) = runOnUiThread { obd.connect(address) }

        @JavascriptInterface
        fun obdDisconnect() = obd.disconnect()
    }

    private fun iconBase64(d: android.graphics.drawable.Drawable): String {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
