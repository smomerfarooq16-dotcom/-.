package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.example.alarm.AlarmScheduler
import com.example.bridge.AndroidCapacitorBridge
import com.example.bridge.GoogleAuthHelper
import org.json.JSONObject

class MainActivity : ComponentActivity() {

  private lateinit var rootLayout: FrameLayout
  private lateinit var webView: WebView
  private lateinit var googleAuthHelper: GoogleAuthHelper

  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
  private var pendingNotifCbId: String? = null
  private var pendingExactCbId: String? = null
  private var pendingDocCbId: String? = null
  private var pendingDocContent: String? = null

  private val authResolutionLauncher = registerForActivityResult(
    ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
    googleAuthHelper.onResolutionResult(result.resultCode, result.data)
  }

  private val postNotificationsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    val cbId = pendingNotifCbId ?: return@registerForActivityResult
    pendingNotifCbId = null
    val status = if (granted) "granted" else "denied"
    resolveJs(cbId, JSONObject().put("display", status).toString())
    webView.evaluateJavascript("window.imanRefreshPermissions && window.imanRefreshPermissions();", null)
  }

  private val exactAlarmSettingsLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    val cbId = pendingExactCbId
    pendingExactCbId = null
    if (cbId != null) {
      val granted = AlarmScheduler.canScheduleExactAlarms(this)
      resolveJs(cbId, JSONObject().put("exact_alarm", if (granted) "granted" else "denied").toString())
    }
    webView.evaluateJavascript("window.imanRefreshPermissions && window.imanRefreshPermissions();", null)
  }

  private val photoPickerLauncher = registerForActivityResult(
    ActivityResultContracts.PickVisualMedia()
  ) { uri ->
    val cb = fileUploadCallback
    fileUploadCallback = null
    cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
  }

  private val documentOpenLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    val cb = fileUploadCallback
    fileUploadCallback = null
    cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
  }

  private val createDocumentLauncher = registerForActivityResult(
    ActivityResultContracts.CreateDocument("*/*")
  ) { uri ->
    val cbId = pendingDocCbId
    val content = pendingDocContent
    pendingDocCbId = null
    pendingDocContent = null
    if (uri != null && content != null && cbId != null) {
      try {
        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        resolveJs(cbId, JSONObject().put("uri", uri.toString()).toString())
      } catch (e: Exception) {
        rejectJs(cbId, e.message ?: "save_failed")
      }
    } else if (cbId != null) {
      rejectJs(cbId, "cancelled")
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    AlarmScheduler.ensureDefaultChannels(this)
    googleAuthHelper = GoogleAuthHelper(this, authResolutionLauncher)

    rootLayout = FrameLayout(this)
    webView = WebView(this)
    rootLayout.addView(
      webView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )
    setContentView(rootLayout)

    ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    applyThemeBarColors("cream")

    val assetLoader = WebViewAssetLoader.Builder()
      .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
      .build()

    val bridge = AndroidCapacitorBridge(
      activity = this,
      webView = webView,
      scope = lifecycleScope,
      googleAuthHelper = googleAuthHelper,
      onRequestPostNotifications = { cbId ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pendingNotifCbId = cbId
          postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          resolveJs(cbId, "{\"display\":\"granted\"}")
        }
      },
      onRequestExactAlarms = { cbId ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          pendingExactCbId = cbId
          val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
          }
          exactAlarmSettingsLauncher.launch(intent)
        } else {
          resolveJs(cbId, "{\"exact_alarm\":\"granted\"}")
        }
      },
      onCreateDocument = { fileName, _, content, cbId ->
        pendingDocCbId = cbId
        pendingDocContent = content
        createDocumentLauncher.launch(fileName)
      },
      onThemeChange = { theme ->
        applyThemeBarColors(theme)
      }
    )

    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      allowContentAccess = true
      allowFileAccess = true
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      builtInZoomControls = false
      displayZoomControls = false
    }

    webView.addJavascriptInterface(bridge, "ImanAndroidBridge")

    webView.webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
      ): WebResourceResponse? {
        if (request.url.path == "/favicon.ico") {
          return WebResourceResponse("image/x-icon", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        return assetLoader.shouldInterceptRequest(request.url)
      }

      override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
      ): Boolean {
        val url = request.url.toString()
        if (url.startsWith("https://appassets.androidplatform.net/")) {
          return false
        }
        return try {
          startActivity(Intent(Intent.ACTION_VIEW, request.url))
          true
        } catch (_: Exception) {
          false
        }
      }

      override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail
      ): Boolean {
        rootLayout.removeView(view)
        view.destroy()
        recreate()
        return true
      }
    }

    webView.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
      ): Boolean {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = filePathCallback

        val accepts = fileChooserParams?.acceptTypes?.joinToString(",")?.lowercase() ?: ""
        return try {
          when {
            accepts.contains("image") -> {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            }
            accepts.contains("audio") -> {
              documentOpenLauncher.launch(arrayOf("audio/*"))
            }
            else -> {
              documentOpenLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
          }
          true
        } catch (_: Exception) {
          fileUploadCallback?.onReceiveValue(null)
          fileUploadCallback = null
          false
        }
      }
    }

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          webView.evaluateJavascript(
            "window.imanHandleBack ? window.imanHandleBack() : 'exit'"
          ) { result ->
            if (result == "\"exit\"" || result == "null") {
              finish()
            }
          }
        }
      }
    )

    webView.loadUrl("https://appassets.androidplatform.net/www/index.html")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.getStringExtra("open_tab") == "home") {
      webView.evaluateJavascript("window.imanOpenHome && window.imanOpenHome();", null)
    }
  }

  override fun onResume() {
    super.onResume()
    webView.evaluateJavascript("window.imanRefreshPermissions && window.imanRefreshPermissions();", null)
  }

  override fun onDestroy() {
    rootLayout.removeAllViews()
    webView.destroy()
    super.onDestroy()
  }

  private fun applyThemeBarColors(theme: String) {
    val isLight = theme == "cream"
    val bgColor = when (theme) {
      "green" -> Color.parseColor("#0B3D2E")
      "night" -> Color.parseColor("#0F211B")
      else -> Color.parseColor("#FFF8E7")
    }
    rootLayout.setBackgroundColor(bgColor)
    @Suppress("DEPRECATION")
    window.statusBarColor = bgColor
    @Suppress("DEPRECATION")
    window.navigationBarColor = bgColor
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = isLight
      isAppearanceLightNavigationBars = isLight
    }
  }

  private fun resolveJs(cbId: String, jsonPayload: String) {
    val safeJson = JSONObject.quote(jsonPayload)
    runOnUiThread {
      webView.evaluateJavascript("window.__capResolve($cbId, JSON.parse($safeJson));", null)
    }
  }

  private fun rejectJs(cbId: String, msg: String) {
    val safeMsg = JSONObject.quote(msg)
    runOnUiThread {
      webView.evaluateJavascript("window.__capReject($cbId, $safeMsg);", null)
    }
  }
}
