package io.github.gohenderson.drawio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Shows a draw.io diagram: one opened from another app (a file manager, a download, Share)
 * or picked with Open. The diagram is drawn by draw.io's offline viewer in a WebView
 * (assets/viewer.html); the app has no INTERNET permission, so nothing leaves the phone.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var empty: LinearLayout
    private lateinit var message: TextView

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** The diagram shown (or being opened), and where it came from. */
    @Volatile
    private var xml: String = ""
    private var uri: Uri? = null
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
        val restored = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse)
        if (restored != null) open(restored) else handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        uri?.let { outState.putString(STATE_URI, it.toString()) }
    }

    override fun onDestroy() {
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_OPEN, 0, R.string.open)
            .setIcon(R.drawable.ic_open)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_OPEN) {
            pick()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val picked = data?.data
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && picked != null) {
            // Kept, so the diagram can be reopened after Android closes the app in the background.
            runCatching { contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            open(picked)
        }
    }

    private fun handle(intent: Intent?) {
        val target = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamOf(intent)
            else -> null
        }
        if (target != null) open(target) else if (uri == null) showEmpty(getString(R.string.empty))
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, OPEN_TYPES)
        try {
            startActivityForResult(i, REQ_OPEN)
        } catch (e: ActivityNotFoundException) {
            showEmpty(getString(R.string.no_picker))
        }
    }

    /** Reads [target] off the main thread, then shows it (or what's wrong with it). */
    internal fun open(target: Uri) {
        uri = target
        status = null
        title = displayName(target) ?: getString(R.string.app_name)
        io.execute {
            val result = try {
                contentResolver.openInputStream(target)?.use { DiagramFile.read(it) }
                    ?: DiagramFile.Result.Problem(getString(R.string.cant_read))
            } catch (e: Exception) {
                DiagramFile.Result.Problem(getString(R.string.cant_read))
            }
            main.post {
                if (isDestroyed || uri != target) return@post
                when (result) {
                    is DiagramFile.Result.Diagram -> show(result.xml)
                    is DiagramFile.Result.Problem -> {
                        status = "problem:${result.message}"
                        showEmpty(result.message)
                    }
                }
            }
        }
    }

    private fun show(diagram: String) {
        xml = diagram
        empty.visibility = View.GONE
        web.visibility = View.VISIBLE
        if (pageLoaded) web.evaluateJavascript("showDiagram()", null) else web.loadUrl(VIEWER_URL)
    }

    private fun showEmpty(text: String) {
        message.text = text
        web.visibility = View.GONE
        empty.visibility = View.VISIBLE
    }

    private fun displayName(target: Uri): String? =
        if (target.scheme == "file") target.lastPathSegment
        else runCatching {
            contentResolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    /** What the viewer page calls (on a WebView thread). */
    private inner class Bridge {
        @JavascriptInterface
        fun xml(): String = xml

        @JavascriptInterface
        fun rendered(pages: Int) {
            status = "rendered:$pages"
        }

        @JavascriptInterface
        fun failed(text: String) {
            status = "failed:$text"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun layout(): View {
        web = WebView(this).apply {
            visibility = View.GONE
            tag = "viewer"
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false // assets still load; nothing else on the phone does
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            // The page zooms and pans the diagram itself (pinch, drag, double-tap);
            // the WebView's own zoom would fight it.
            settings.setSupportZoom(false)
            addJavascriptInterface(Bridge(), "DrawioAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded = url == VIEWER_URL
                }

                // Links in a diagram open in your browser; the viewer page never navigates.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (request.isForMainFrame && target.scheme in setOf("http", "https", "mailto")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                    }
                    return true
                }

                // Only the app's own assets load. (It has no INTERNET permission anyway.)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    return if (u.startsWith(ASSETS) || u.startsWith("data:") || u.startsWith("blob:")) null
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                }
            }
        }
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            tag = "message"
        }
        val open = Button(this).apply {
            setText(R.string.open)
            tag = "open"
            setOnClickListener { pick() }
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(open, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = pad
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        return FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    companion object {
        private const val ASSETS = "file:///android_asset/"
        const val VIEWER_URL = ASSETS + "viewer.html"
        private const val STATE_URI = "uri"
        private const val REQ_OPEN = 1
        private const val MENU_OPEN = 1

        /** Offered by Open; draw.io files often have no type of their own, hence octet-stream. */
        private val OPEN_TYPES = arrayOf(
            "application/vnd.jgraph.mxfile", "application/xml", "text/xml", "text/plain",
            "image/svg+xml", "image/png", "application/octet-stream",
        )

        /**
         * What's on screen, for the UI tests: "rendered:<pages>", "failed:<why>" (the viewer
         * couldn't draw it) or "problem:<why>" (not a diagram); null while opening.
         */
        @Volatile
        var status: String? = null
            internal set
    }
}
