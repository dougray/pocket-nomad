package org.nomadpocket.app.library

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import org.nomadpocket.library.BookShelf
import org.nomadpocket.library.zim.ZimArchive

/**
 * Addresses of pages inside books, like `NomadSchemeHandler` on iOS.
 *
 * The app refers to a page as `nomad://book/<book id>/<path>` (the same form as iOS).
 * The WebView loads it as `https://nomad.invalid/book/<book id>/<path>` instead:
 * Chromium only resolves relative links ("../I/x.png") against standard schemes
 * such as https, and `.invalid` is a reserved name that can never reach a real
 * server. Every request is answered from the archive in [BookWebClient]; nothing
 * goes to the network.
 */
object BookUrls {
    const val SCHEME = "nomad"
    const val HOST = "nomad.invalid"

    fun nomad(bookId: String, path: String): String = "$SCHEME://book/$bookId/${encode(path)}"
    fun web(bookId: String, path: String): String = "https://$HOST/book/$bookId/${encode(path)}"

    /** `nomad://book/<id>/<path>` or the web form, to (id, path); null for anything else. */
    fun parse(url: Uri): Pair<String, String>? {
        val raw = when {
            url.scheme == SCHEME && url.host == "book" -> url.encodedPath?.removePrefix("/")
            url.scheme == "https" && url.host == HOST -> url.encodedPath?.removePrefix("/")?.takeIf { it.startsWith("book/") }?.removePrefix("book/")
            else -> null
        } ?: return null
        // Many ZIM paths end in "/" ("www.nhs.uk/medicines/"), so the raw path is split by hand.
        val slash = raw.indexOf('/')
        if (slash <= 0) return null
        val path = ZimArchive.percentDecode(raw.substring(slash + 1)) ?: raw.substring(slash + 1)
        return raw.substring(0, slash) to path
    }

    /** Percent-encodes a ZIM path for a URL, keeping "/" so relative links work. */
    fun encode(path: String): String = Uri.encode(path, "/")
}

/** Serves book pages from their archives and keeps the WebView offline. */
class BookWebClient(
    private val shelf: BookShelf,
    private val onPdf: (bookId: String, path: String) -> Unit,
    private val onNavigated: () -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val (id, path) = BookUrls.parse(request.url) ?: return blocked()
        val content = runCatching { shelf.archive(id)?.content(path) }.getOrNull() ?: return notFound()
        val mime = content.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val charset = content.mimeType.substringAfter("charset=", "").trim().ifEmpty { if (mime.startsWith("text/") || mime.contains("javascript")) "utf-8" else null }
        return WebResourceResponse(mime, charset, 200, "OK", mapOf("Content-Length" to "${content.data.size}"), ByteArrayInputStream(content.data))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = BookUrls.parse(request.url)
        if (target != null) {
            // The WebView can't show PDFs, which some books (the zimgit collections) are made of.
            val entry = shelf.archive(target.first)?.entry(target.second)
            if (entry?.mimeType == "application/pdf") {
                onPdf(target.first, target.second)
                return true
            }
            return false
        }
        // Web links open in the browser (useful when there happens to be signal); the page stays offline.
        val scheme = request.url.scheme ?: ""
        if (scheme in setOf("http", "https", "mailto", "tel") && request.hasGesture()) {
            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
        }
        return true
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onNavigated()

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/** A book page in a WebView. The system back gesture goes back through the book's pages first. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BookReader(modifier: Modifier, bookId: String, path: String, onTitle: (String) -> Unit, onPdf: (String, String) -> Unit) {
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val url = remember(bookId, path) { BookUrls.web(bookId, path) }
    BackHandler(enabled = canGoBack) { webView?.goBack() }
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Books are web pages; the zimgit books are small web apps that keep
                    // their document list in IndexedDB. Network access is blocked above.
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // target="_blank" links and window.open (which the zimgit books use for
                    // every document) load here instead of doing nothing.
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    webViewClient = BookWebClient(BookShelf.get(ctx), onPdf) {
                        canGoBack = this.canGoBack()
                        title?.takeIf { it.isNotBlank() && !it.startsWith("https://") }?.let(onTitle)
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            title?.takeIf { it.isNotBlank() && !it.startsWith("https://") }?.let(onTitle)
                        }
                    }
                    webView = this
                }
            },
            update = {
                if (it.tag != url) {
                    it.tag = url
                    it.loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
