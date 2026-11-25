package id.runsense.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Host native RunSense. UI dan mesin coach berjalan di WebView, dari salah satu sumber:
 *  - bundel di APK (https://appassets.androidplatform.net/index.html), atau
 *  - URL web milikmu bila `runsenseUrl` diisi di gradle.properties (dengan fallback ke bundel
 *    kalau halaman gagal dimuat).
 * Jembatan native ([NativeBridge]) hanya melayani halaman dari dua origin tepercaya itu.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var shim: String
    private var remoteUrl: Uri? = null
    private var usingRemote = false

    /** Dibaca dari thread JS bridge; diperbarui di main thread saat navigasi. */
    @Volatile private var trusted = false

    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var hcCont: ((Set<String>) -> Unit)? = null

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
        }

    private var notifCb: ((Boolean) -> Unit)? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            notifCb?.invoke(ok)
            notifCb = null
        }

    /** Minta izin notifikasi (Android 13+), lalu panggil [cb]. Panggil dari main thread. */
    fun requestNotifPermission(cb: (Boolean) -> Unit) {
        notifCb = cb
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val hcLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hcCont?.invoke(granted)
        hcCont = null
    }

    /** Menampilkan layar izin Health Connect dan menunggu hasilnya. Panggil dari main thread. */
    suspend fun requestHcPermissions(): Set<String> = suspendCancellableCoroutine { c ->
        hcCont = { c.resume(it) }
        hcLauncher.launch(HealthConnectWriter.PERMISSIONS)
    }

    private fun isTrusted(u: Uri): Boolean =
        u.scheme == "https" && (u.host == HOST || (remoteUrl != null && u.host == remoteUrl?.host))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        remoteUrl = BuildConfig.REMOTE_URL.trim()
            .takeIf { it.startsWith("https://") }
            ?.let { Uri.parse(it) }
            ?.takeIf { !it.host.isNullOrEmpty() }
        usingRemote = remoteUrl != null

        shim = assets.open("shim.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", WwwPathHandler(this, shim))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
                textZoom = 100
                setGeolocationEnabled(true)
            }
            addJavascriptInterface(
                NativeBridge(this@MainActivity, this) { trusted }, "RunSenseNative"
            )

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    if (isTrusted(request.url)) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    trusted = url != null && isTrusted(Uri.parse(url))
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // Cadangan bila document-start script tidak didukung WebView perangkat.
                    val host = url?.let { Uri.parse(it).host }
                    if (usingRemote && host == remoteUrl?.host &&
                        !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    ) view.evaluateJavascript(shim, null)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) fallbackToBundled(view)
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) fallbackToBundled(view)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?, callback: GeolocationPermissions.Callback?
                ) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        callback?.invoke(origin, true, false)
                    } else {
                        geoOrigin = origin
                        geoCallback = callback
                        locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
            }
        }
        setContentView(webView)

        // Halaman dari URL sendiri tidak lewat WwwPathHandler, jadi shim dipasang saat dokumen mulai.
        val r = remoteUrl
        if (r != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origin = "https://" + r.host + (if (r.port != -1) ":${r.port}" else "")
            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf(origin))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){return !!(window.__rsBack&&window.__rsBack());})()"
                ) { handled -> if (handled != "true") finish() }
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(if (usingRemote) remoteUrl.toString() else BUNDLED)
    }

    /** URL sendiri gagal dimuat (offline / server error): pakai versi bawaan APK. */
    private fun fallbackToBundled(view: WebView) {
        if (!usingRemote) return
        usingRemote = false
        Toast.makeText(this, "URL tidak terjangkau. Memakai versi bawaan.", Toast.LENGTH_LONG).show()
        view.loadUrl(BUNDLED)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val BUNDLED = "https://$HOST/index.html"
    }
}
