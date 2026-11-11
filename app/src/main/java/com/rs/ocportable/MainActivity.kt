package com.rs.ocportable

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * The phone surface: extraction progress, the embedded web GUI, hold-to-talk, workspace import,
 * and the small native menu Android needs that a browser does not.
 */
class MainActivity : Activity(), EngineController.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var mic: TextView
    private lateinit var menu: TextView

    private var controller: EngineController? = null
    private var loadedUrl: String? = null
    private var pendingPrompt: String? = null
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingMicStart = false
    private val voice by lazy { VoiceInput(this) }
    private val whisperVoice by lazy { WhisperVoiceInput(this) }
    private var usingWhisper = false
    private var listening = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val controller = (service as? EngineService.LocalBinder)?.controller() ?: return
            this@MainActivity.controller = controller
            controller.addListener(this@MainActivity)
            controller.takePendingPrompt()?.let { onPrompt(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            controller = null
            render(EngineController.State.Failed("The engine service disconnected. Reopen the app."))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = DARK
        window.navigationBarColor = DARK
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        buildLayout()
        setupWebView()

        val service = Intent(this, EngineService::class.java)
        startForegroundService(service)
        bindService(service, connection, Context.BIND_AUTO_CREATE)

        bumpLaunchCount()
        maybeRequestNotifications()
    }

    override fun onResume() {
        super.onResume()
        controller?.let {
            it.addListener(this)
            it.takePendingPrompt()?.let(::onPrompt)
            render(it.state)
            BatteryOptimization.maybePrompt(this, it.state is EngineController.State.Running)
        }
    }

    override fun onPause() {
        controller?.removeListener(this)
        stopMic()
        super.onPause()
    }

    override fun onDestroy() {
        controller?.removeListener(this)
        runCatching { unbindService(connection) }
        voice.destroy()
        whisperVoice.destroy()
        web.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onState(state: EngineController.State) = render(state)

    override fun onStatus(status: StatusTracker.AgentStatus) {
        // The WebView and the notification are the phone surfaces; the watch gets the same object
        // through the service. Nothing to draw here.
    }

    override fun onPrompt(text: String) {
        pendingPrompt = text
        injectCurrentPrompt()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE -> {
                val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                fileCallback?.onReceiveValue(result)
                fileCallback = null
            }

            REQ_IMPORT -> {
                val tree = data?.data
                if (resultCode == RESULT_OK && tree != null) importTree(tree)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_AUDIO -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    if (pendingMicStart) beginListening()
                } else {
                    toast("Microphone permission is required for voice input.")
                }
                pendingMicStart = false
            }

            REQ_NOTIFICATIONS -> {
                val bluetooth = permissions.indexOf(Manifest.permission.BLUETOOTH_CONNECT)
                if (bluetooth >= 0 && grantResults.getOrNull(bluetooth) == PackageManager.PERMISSION_GRANTED) {
                    controller?.refreshWatch()
                }
            }
        }
    }

    private fun buildLayout() {
        root = FrameLayout(this).apply {
            setBackgroundColor(DARK)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        web = WebView(this).apply {
            setBackgroundColor(DARK)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        detailText = TextView(this).apply {
            setTextColor(Color.parseColor("#B8B8C0"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        retry = Button(this).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { controller?.restart() }
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#F20B0B0D"))
            addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(progress, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 24; bottomMargin = 8 })
            addView(detailText, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(retry, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 24 })
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))

        mic = pill("Mic", Color.parseColor("#E6327AFF")).apply {
            visibility = View.GONE
            contentDescription = "Hold to talk"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startMic()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopMic()
                        true
                    }

                    else -> false
                }
            }
        }
        root.addView(mic, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = 24
            bottomMargin = 24
        })

        menu = pill("⋮", Color.parseColor("#CC1C1C22")).apply {
            contentDescription = "Menu"
            setOnClickListener { showMenu() }
        }
        root.addView(menu, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            rightMargin = 8
            topMargin = 8
        })

        setContentView(root)
    }

    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                openExternally(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                injectBridge()
                injectCurrentPrompt()
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "WebView error ${error.errorCode}: ${error.description}")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    toast("No file picker available.")
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val allowsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (allowsAudio) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    request.deny()
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d(TAG, "console: ${message.message()}")
                return true
            }
        }
    }

    private fun render(state: EngineController.State) {
        when (state) {
            is EngineController.State.Idle -> {
                overlay.visibility = View.VISIBLE
                statusText.text = "Starting…"
                detailText.text = ""
                progress.visibility = View.GONE
                retry.visibility = View.GONE
                mic.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }

            is EngineController.State.Preparing -> {
                overlay.visibility = View.VISIBLE
                statusText.text = state.message
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.total <= 0
                if (state.total > 0) {
                    progress.progress = (state.written.toFloat() / state.total * progress.max).toInt()
                    detailText.text = "${state.written / 1_048_576} / ${state.total / 1_048_576} MiB"
                } else {
                    detailText.text = ""
                }
                retry.visibility = View.GONE
                mic.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }

            is EngineController.State.Starting -> {
                overlay.visibility = View.VISIBLE
                statusText.text = state.message
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                detailText.text = ""
                retry.visibility = View.GONE
                mic.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }

            is EngineController.State.Running -> {
                overlay.visibility = View.GONE
                progress.visibility = View.GONE
                mic.visibility = View.VISIBLE
                menu.visibility = View.VISIBLE
                if (loadedUrl != state.url) {
                    loadedUrl = state.url
                    web.loadUrl(state.url)
                }
            }

            is EngineController.State.Failed -> {
                overlay.visibility = View.VISIBLE
                statusText.text = "Engine failed"
                detailText.text = state.message
                progress.visibility = View.GONE
                retry.visibility = View.VISIBLE
                mic.visibility = View.GONE
                menu.visibility = View.VISIBLE
            }
        }
    }

    private fun injectBridge() {
        web.evaluateJavascript(ComposerBridge.INSTALL, null)
        web.evaluateJavascript(ComposerBridge.CSS, null)
    }

    private fun injectCurrentPrompt() {
        val text = pendingPrompt ?: return
        if (!::web.isInitialized || loadedUrl == null) return
        pendingPrompt = null
        web.evaluateJavascript(ComposerBridge.setTextScript(text), null)
    }

    private fun startMic() {
        if (controller?.state !is EngineController.State.Running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        beginListening()
    }

    private fun beginListening() {
        if (listening) return
        listening = true
        mic.text = "Listening…"
        val callback = object : VoiceInput.Callback {
            override fun onPartial(text: String) {
                injectText(text)
            }

            override fun onFinal(text: String) {
                injectText(text)
                endListening()
            }

            override fun onError(message: String) {
                toast(message)
                endListening()
            }
        }
        if (voice.isAvailable()) {
            usingWhisper = false
            if (!voice.start(callback)) endListening()
        } else {
            usingWhisper = true
            if (!whisperVoice.start(callback)) endListening()
        }
    }

    private fun stopMic() {
        if (!listening) return
        if (usingWhisper) {
            mic.text = "Transcribing…"
            whisperVoice.stop()
        } else {
            voice.stop()
        }
    }

    private fun endListening() {
        listening = false
        mic.text = "Mic"
    }

    private fun injectText(text: String) {
        if (!::web.isInitialized) return
        web.evaluateJavascript(ComposerBridge.setTextScript(text), null)
    }

    private fun showMenu() {
        val items = arrayOf("Import folder", "Permissions", "Battery optimization", "Restart engine", "Quit")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> importFolder()
                    1 -> requestMissingPermissions()
                    2 -> BatteryOptimization.openSettings(this)
                    3 -> controller?.restart()
                    4 -> quit()
                }
            }
            .show()
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT < 31) return
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (missing.isEmpty()) {
            toast("All permissions are granted.")
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_NOTIFICATIONS)
    }

    private fun importFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (_: Exception) {
            toast("No folder picker available.")
        }
    }

    private fun importTree(tree: Uri) {
        toast("Importing…")
        worker.execute {
            try {
                val target = File(filesDir, "workspace")
                val count = WorkspaceImporter.copyTree(this, tree, target)
                main.post { toast("Imported $count files.") }
            } catch (e: Exception) {
                main.post { toast("Import failed: ${e.message}") }
            }
        }
    }

    private fun quit() {
        controller?.stop()
        stopService(Intent(this, EngineService::class.java))
        finish()
    }

    private fun openExternally(uri: Uri): Boolean {
        val host = uri.host
        if (uri.scheme == "http" || uri.scheme == "https") {
            if (host == "127.0.0.1" || host == "localhost") return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (_: Exception) {
                true
            }
        }
        return false
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < 31) return
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (missing.isEmpty()) return
        val prefs = getSharedPreferences("engine", Context.MODE_PRIVATE)
        if (prefs.getInt("launches", 0) < 2 || prefs.getBoolean("notifications_asked", false)) return
        prefs.edit().putBoolean("notifications_asked", true).apply()
        requestPermissions(missing.toTypedArray(), REQ_NOTIFICATIONS)
    }

    private fun bumpLaunchCount() {
        val prefs = getSharedPreferences("engine", Context.MODE_PRIVATE)
        prefs.edit().putInt("launches", prefs.getInt("launches", 0) + 1).apply()
    }

    private fun pill(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(28, 14, 28, 14)
        background = GradientDrawable().apply {
            cornerRadius = 96f
            setColor(color)
        }
        isClickable = true
        isFocusable = true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val DARK = 0xFF0B0B0D.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_FILE = 10
        const val REQ_IMPORT = 11
        const val REQ_AUDIO = 12
        const val REQ_NOTIFICATIONS = 13
    }
}
