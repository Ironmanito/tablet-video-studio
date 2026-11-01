package com.tabletstudio.videoeditor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tabletstudio.videoeditor.databinding.ActivityMainBinding
import com.tabletstudio.videoeditor.server.LocalStudioServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var studioServer: LocalStudioServer
    private var activeWebView: WebView? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var isFullscreenCinema = false

    // Launcher for <input type="file"> inside WebView
    private val webFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data!!
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            data.data?.let { uris.add(it) }
        }
        callback.onReceiveValue(uris.toTypedArray())
    }

    // Launcher for Direct Native Android Gallery Importer
    private val nativeGalleryImporterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            importUrisIntoStudio(uris)
        }
    }

    // Runtime microphone permission launcher for Voiceover recording
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val req = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (req != null) {
            if (granted) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashShield()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeWebView = binding.studioWebView
        studioServer = LocalStudioServer(applicationContext)
        studioServer.onAgentCommand = { cmdJson ->
            val wv = activeWebView
            if (wv != null) {
                val payloadLiteral = cmdJson.toString()
                runOnUiThread {
                    try {
                        wv.evaluateJavascript(
                            "if(window.StudioAgent&&window.StudioAgent.runCommand){window.StudioAgent.runCommand($payloadLiteral);}",
                            null
                        )
                    } catch (_: Throwable) {}
                }
                true
            } else {
                false
            }
        }
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            cpuWakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TabletVideoStudio::AgentServerLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
        setupNativeTopBar()
        setupWebView(activeWebView!!)
        setupBackNavigation()

        lifecycleScope.launch {
            val port = withContext(Dispatchers.IO) {
                studioServer.startServer()
            }
            binding.tvEngineBadge.text = "ARM64 • Puerto $port"
            activeWebView?.loadUrl(studioServer.baseUrl)
            handleIncomingShareIntent(intent)
        }
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Si una corrutina o hilo secundario falla (ej. socket cerrado o MediaMetadataRetriever),
            // evitamos que Android cierre toda la app.
            if (thread != Looper.getMainLooper().thread) {
                android.util.Log.e("TabletVideoStudio", "Excepción capturada en hilo secundario ${thread.name}", throwable)
                try {
                    System.gc()
                } catch (_: Throwable) {}
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= TRIM_MEMORY_RUNNING_LOW) {
                activeWebView?.clearCache(false)
                System.gc()
            }
        } catch (_: Throwable) {}
    }

    private fun setupNativeTopBar() {
        binding.btnNativeImport.setOnClickListener {
            launchNativeGalleryImporter()
        }

        binding.btnExportsFolder.setOnClickListener {
            showExportedVideosDialog()
        }

        binding.btnFullscreen.setOnClickListener {
            setFullscreenCinemaMode(true)
        }

        binding.btnRestoreBar.setOnClickListener {
            setFullscreenCinemaMode(false)
        }
    }

    private fun setFullscreenCinemaMode(enable: Boolean) {
        isFullscreenCinema = enable
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (enable) {
            binding.nativeTopBar.visibility = View.GONE
            binding.topBarDivider.visibility = View.GONE
            binding.btnRestoreBar.visibility = View.VISIBLE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.nativeTopBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.VISIBLE
            binding.btnRestoreBar.visibility = View.GONE
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun recreateWebViewSafely() {
        try {
            val host = binding.webViewHost
            activeWebView?.let { old ->
                try {
                    host.removeView(old)
                    old.destroy()
                } catch (_: Throwable) {}
            }
            val freshWebView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            host.addView(freshWebView)
            activeWebView = freshWebView
            setupWebView(freshWebView)
            freshWebView.loadUrl(studioServer.baseUrl)
        } catch (t: Throwable) {
            android.util.Log.e("TabletVideoStudio", "Error recreando WebView", t)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        wv.addJavascriptInterface(AndroidStudioBridge(), "AndroidStudioBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.loadingOverlay.visibility = View.GONE
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                // Recrear la instancia de WebView limpiamente para que la app jamás se cierre
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Recuperando visor multimedia...",
                        Toast.LENGTH_SHORT
                    ).show()
                    recreateWebViewSafely()
                }
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("video/*", "audio/*", "image/*")
                    )
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    webFileChooserLauncher.launch(
                        Intent.createChooser(intent, "Seleccionar videos, audios o imágenes")
                    )
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (wantsAudio) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermissionRequest = request
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    request.grant(request.resources)
                }
            }
        }

        wv.setDownloadListener { url, _, _, _, _ ->
            if (url.contains("/api/render/download/")) {
                openOrShareRenderedVideo(url, share = false)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = activeWebView
                if (isFullscreenCinema) {
                    setFullscreenCinemaMode(false)
                } else if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun launchNativeGalleryImporter() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("video/*", "audio/*", "image/*")
            )
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            nativeGalleryImporterLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importUrisIntoStudio(uris: List<Uri>) {
        Toast.makeText(this, "Importando ${uris.size} archivo(s)...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val importedCount = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    if (studioServer.importAndroidUri(uri) != null) {
                        count++
                    }
                }
                count
            }
            if (importedCount > 0) {
                activeWebView?.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('android-media-imported'));",
                    null
                )
                Toast.makeText(
                    this@MainActivity,
                    "✅ $importedCount archivo(s) listos para usar",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo importar el archivo seleccionado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val uris = mutableListOf<Uri>()

        if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (sharedUri != null) uris.add(sharedUri)
        } else if (action == Intent.ACTION_VIEW) {
            intent.data?.let { uris.add(it) }
        }

        if (uris.isNotEmpty()) {
            importUrisIntoStudio(uris)
        }
    }

    private fun showExportedVideosDialog() {
        val exported = studioServer.outputsDir
            .listFiles { f -> f.isFile && f.name.endsWith(".mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (exported.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Mis Videos Exportados")
                .setMessage("Todavía no has exportado ningún video en esta sesión.\n\nUsa el botón 'Exportar MP4' arriba a la derecha en el editor para renderizar tu proyecto.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        val labels = exported.map { file ->
            val sizeMb = String.format("%.2f MB", file.length() / (1024f * 1024f))
            "${file.name} ($sizeMb)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Videos Renderizados (${exported.size})")
            .setItems(labels) { _, which ->
                val chosen = exported[which]
                showSingleExportActionsDialog(chosen)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showSingleExportActionsDialog(file: File) {
        val options = arrayOf(
            "▶️ Reproducir en Reproductor de Video",
            "📲 Compartir Video (TikTok, WhatsApp, Reels)",
            "🗑️ Eliminar archivo"
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, idx ->
                when (idx) {
                    0 -> openOrShareFile(file, share = false)
                    1 -> openOrShareFile(file, share = true)
                    2 -> {
                        file.delete()
                        Toast.makeText(this, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openOrShareRenderedVideo(urlOrFile: String, share: Boolean) {
        val file = studioServer.resolveExportedFile(urlOrFile)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "El archivo MP4 aún no está disponible", Toast.LENGTH_SHORT).show()
            return
        }
        openOrShareFile(file, share)
    }

    private fun openOrShareFile(file: File, share: Boolean) {
        try {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            if (share) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir video MP4"))
            } else {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(viewIntent, "Abrir video con..."))
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Video guardado en Galería/Movies: ${file.name}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
        } catch (_: Throwable) {}
        studioServer.stopServer()
    }

    inner class AndroidStudioBridge {

        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun openNativePicker() {
            runOnUiThread {
                launchNativeGalleryImporter()
            }
        }

        @JavascriptInterface
        fun shareExportedVideo(urlOrFile: String) {
            runOnUiThread {
                openOrShareRenderedVideo(urlOrFile, share = true)
            }
        }

        @JavascriptInterface
        fun openExportedVideo(urlOrFile: String) {
            runOnUiThread {
                openOrShareRenderedVideo(urlOrFile, share = false)
            }
        }
    }
}
