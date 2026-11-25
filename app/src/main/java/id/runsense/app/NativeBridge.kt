package id.runsense.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Jembatan JS -> Kotlin (window.RunSenseNative), dipasang oleh shim.js.
 *
 * - http/cancel : fetch() ke Intervals.icu & Open-Meteo lewat HttpURLConnection (bebas CORS,
 *                 hanya host yang di-whitelist).
 * - share/copy  : navigator.share & navigator.clipboard.writeText untuk tombol workout.
 * - setTheme    : warna status/navigation bar mengikuti tema gelap/terang aplikasi.
 */
class NativeBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val trusted: () -> Boolean
) {

    private val hc by lazy { HealthConnectWriter(activity) }

    private val pool = Executors.newFixedThreadPool(4)
    private val calls = ConcurrentHashMap<Int, HttpURLConnection>()

    @JavascriptInterface
    fun http(id: Int, reqJson: String) {
        if (!trusted()) return
        pool.execute {
            try {
                val req = JSONObject(reqJson)
                val url = URL(req.getString("url"))
                if (url.protocol != "https" || !allowed(url.host)) {
                    reject(id, "Host tidak diizinkan: ${url.host}")
                    return@execute
                }
                val method = req.optString("method", "GET").uppercase()
                val c = url.openConnection() as HttpURLConnection
                calls[id] = c
                try {
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.requestMethod = method
                    c.instanceFollowRedirects = true
                    val headers = req.optJSONObject("headers")
                    if (headers != null) {
                        val it = headers.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            c.setRequestProperty(k, headers.optString(k))
                        }
                    }
                    if (!req.isNull("body") && method != "GET" && method != "HEAD") {
                        c.doOutput = true
                        c.outputStream.use { it.write(req.getString("body").toByteArray(Charsets.UTF_8)) }
                    }
                    val status = c.responseCode
                    val stream = if (status >= 400) c.errorStream else c.inputStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val res = JSONObject()
                        .put("status", status)
                        .put("statusText", c.responseMessage ?: "")
                        .put("headers", JSONObject().put("content-type", c.contentType ?: "application/json"))
                        .put("body", body)
                    resolve(id, res.toString())
                } finally {
                    calls.remove(id)
                    c.disconnect()
                }
            } catch (e: Exception) {
                reject(id, e.message ?: "Failed to fetch")
            }
        }
    }

    @JavascriptInterface
    fun cancel(id: Int) {
        if (!trusted()) return
        pool.execute { calls.remove(id)?.disconnect() }
    }

    @JavascriptInterface
    fun share(title: String, text: String) {
        if (!trusted()) return
        activity.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(send, title.ifEmpty { "Bagikan" }))
        }
    }

    @JavascriptInterface
    fun copy(text: String) {
        if (!trusted()) return
        activity.runOnUiThread {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("RunSense", text))
        }
    }

    @JavascriptInterface
    fun setTheme(theme: String) {
        if (!trusted()) return
        activity.runOnUiThread {
            val light = theme == "light"
            val color = Color.parseColor(if (light) "#F4F5F0" else "#0A0A0A")
            activity.window.statusBarColor = color
            activity.window.navigationBarColor = color
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
            webView.setBackgroundColor(color)
        }
    }


    @JavascriptInterface
    fun notify(title: String, body: String) {
        if (!trusted()) return
        activity.runOnUiThread {
            val needsAsk = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
            if (needsAsk) {
                activity.requestNotifPermission { ok -> if (ok) NotificationHelper.show(activity, title, body) }
            } else {
                NotificationHelper.show(activity, title, body)
            }
        }
    }

    /* ---------- Health Connect (tulis) ---------- */

    @JavascriptInterface
    fun hcCheck() {
        if (!trusted()) return
        activity.lifecycleScope.launch {
            js("window.__rsHcInfo(${JSONObject.quote(hcInfo().toString())})")
        }
    }

    /** json = payload dari shim.js; interactive = boleh menampilkan layar izin. */
    @JavascriptInterface
    fun hcWrite(json: String, interactive: Boolean) {
        if (!trusted()) return
        activity.lifecycleScope.launch {
            try {
                val info = hcInfo()
                when (info.getString("state")) {
                    "update" -> return@launch hcDone(false, "Perbarui Health Connect lewat Play Store dulu.")
                    "unavailable" -> return@launch hcDone(false, "Health Connect tidak tersedia di perangkat ini.")
                }
                if (!info.getBoolean("granted")) {
                    if (!interactive) return@launch hcDone(false, "Izin Health Connect belum diberikan.")
                    val granted = activity.requestHcPermissions()
                    if (granted.none { it in HealthConnectWriter.PERMISSIONS })
                        return@launch hcDone(false, "Izin ditolak. Tidak ada yang ditulis.")
                }
                val r = hc.write(JSONObject(json))
                hcDone(true, "${r.sessions} sesi dan ${r.days} hari data ditulis · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            } catch (e: Exception) {
                hcDone(false, "Gagal menulis: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    @JavascriptInterface
    fun hcOpen() {
        if (!trusted()) return
        activity.runOnUiThread {
            try {
                activity.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } catch (_: Exception) {
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun hcInfo(): JSONObject {
        val state = when (hc.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> "available"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
            else -> "unavailable"
        }
        val granted = state == "available" &&
            runCatching { hc.grantedPermissions().isNotEmpty() }.getOrDefault(false)
        return JSONObject().put("state", state).put("granted", granted)
    }

    private fun hcDone(ok: Boolean, msg: String) =
        js("window.__rsHcDone($ok,${JSONObject.quote(msg)})")

    private fun allowed(host: String) =
        host == "intervals.icu" || host.endsWith(".open-meteo.com")

    private fun js(code: String) {
        webView.post { webView.evaluateJavascript(code, null) }
    }

    private fun resolve(id: Int, json: String) =
        js("window.__rsHttp($id,true,${JSONObject.quote(json)})")

    private fun reject(id: Int, message: String) =
        js("window.__rsHttp($id,false,${JSONObject.quote(message)})")
}
