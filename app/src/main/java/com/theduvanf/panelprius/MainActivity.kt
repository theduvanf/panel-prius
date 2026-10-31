package com.theduvanf.panelprius

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
    }

    private lateinit var web: WebView
    private lateinit var obd: ObdBle
    private var pendingScan = false

    // ---------- music from other apps ----------
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val listenerName by lazy { ComponentName(this, MediaListener::class.java) }
    private val mediaCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushMedia()
        override fun onSessionDestroyed() { controller = null; pickController(null) }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private fun hasMediaAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun startMedia() {
        if (!hasMediaAccess()) { js("window.onMedia && window.onMedia(null, false)"); return }
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            if (sessions == null) { m.addOnActiveSessionsChangedListener(sessionsListener, listenerName); sessions = m }
            pickController(m.getActiveSessions(listenerName))
        } catch (e: SecurityException) {
            js("window.onMedia && window.onMedia(null, false)")
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val all = list ?: try { sessions?.getActiveSessions(listenerName) } catch (e: Exception) { null } ?: emptyList()
        val best = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(mediaCb) } catch (_: Exception) {}
            controller = best
            best?.registerCallback(mediaCb, Handler(Looper.getMainLooper()))
        }
        pushMedia()
    }

    private var lastArtKey = ""
    private var lastArt: String? = null

    private fun pushMedia() {
        val c = controller
        if (c == null) { js("window.onMedia && window.onMedia(null, true)"); return }
        val md = c.metadata
        val o = JSONObject()
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        o.put("title", title)
        o.put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
        o.put("pkg", c.packageName)
        o.put("app", try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (e: Exception) { c.packageName })
        o.put("playing", c.playbackState?.state == PlaybackState.STATE_PLAYING)
        val key = c.packageName + "|" + title
        if (key != lastArtKey) {
            lastArtKey = key
            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            lastArt = bmp?.let {
                val small = Bitmap.createScaledBitmap(it, 160, 160, true)
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 82, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        lastArt?.let { o.put("art", it) }
        js("window.onMedia && window.onMedia($o, true)")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) startMedia()
    }

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
        // Serve the app's own files from a local https address so the page can fetch the 3D model.
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != ASSET_HOST) return null
                val path = (url.path ?: "/").removePrefix("/").ifEmpty { "index.html" }
                return try {
                    val mime = when (path.substringAfterLast('.').lowercase()) {
                        "html" -> "text/html"
                        "js" -> "application/javascript"
                        "glb" -> "model/gltf-binary"
                        "json" -> "application/json"
                        "png" -> "image/png"
                        else -> "application/octet-stream"
                    }
                    val res = WebResourceResponse(mime, if (mime.startsWith("text") || mime.endsWith("javascript")) "utf-8" else null, assets.open(path))
                    res.responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    res
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
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

        web.loadUrl("https://$ASSET_HOST/index.html")
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
                    // Open beside the panel (split screen / pop-up window) instead of covering it.
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    val dm = resources.displayMetrics
                    val opts = ActivityOptions.makeBasic()
                    opts.launchBounds = Rect(dm.widthPixels * 40 / 100, 0, dm.widthPixels, dm.heightPixels)
                    try { startActivity(launch, opts.toBundle()) } catch (e: Exception) { startActivity(launch) }
                } else {
                    obd.listener.onStatus("Esa app no está instalada en la tablet.", obd.isLive)
                }
            }
        }

        @JavascriptInterface
        fun mediaStart() = runOnUiThread { startMedia() }

        @JavascriptInterface
        fun mediaAccess() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        @JavascriptInterface
        fun media(action: String) = runOnUiThread {
            val t = controller?.transportControls ?: return@runOnUiThread
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "next" -> t.skipToNext()
                "prev" -> t.skipToPrevious()
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
