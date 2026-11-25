package id.runsense.app

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.IOException

/** Menyajikan aset web dari folder www; shim.js disisipkan setelah tag head. */
class WwwPathHandler(
    private val context: Context,
    private val shim: String
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val p = path.trimStart('/').ifEmpty { "index.html" }
        if (p.contains("..")) return notFound()
        return try {
            if (p == "index.html") {
                val html = context.assets.open("www/index.html")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val tag = "<head>"
                val i = html.indexOf(tag)
                val out = if (i >= 0)
                    html.substring(0, i + tag.length) + "<script>" + shim + "</script>" +
                        html.substring(i + tag.length)
                else "<script>$shim</script>$html"
                WebResourceResponse("text/html", "UTF-8", out.byteInputStream(Charsets.UTF_8))
            } else {
                WebResourceResponse(mimeOf(p), null, context.assets.open("www/$p"))
            }
        } catch (_: IOException) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun mimeOf(p: String): String = when (p.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}
