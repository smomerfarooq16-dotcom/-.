package com.example.bridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.alarm.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AndroidCapacitorBridge(
  private val activity: Activity,
  private val webView: WebView,
  private val scope: CoroutineScope,
  private val googleAuthHelper: GoogleAuthHelper,
  private val onRequestPostNotifications: (callbackId: String) -> Unit,
  private val onRequestExactAlarms: (callbackId: String) -> Unit,
  private val onCreateDocument: (fileName: String, mimeType: String, content: String, callbackId: String) -> Unit,
  private val onThemeChange: (theme: String) -> Unit
) {

  private fun resolveJs(cbId: String, jsonPayload: String) {
    val safeJson = JSONObject.quote(jsonPayload)
    activity.runOnUiThread {
      webView.evaluateJavascript("window.__capResolve($cbId, JSON.parse($safeJson));", null)
    }
  }

  private fun rejectJs(cbId: String, errorMsg: String) {
    val safeMsg = JSONObject.quote(errorMsg)
    activity.runOnUiThread {
      webView.evaluateJavascript("window.__capReject($cbId, $safeMsg);", null)
    }
  }

  // ---- LocalNotifications ----

  @JavascriptInterface
  fun lnCreateChannel(cbId: String, channelJson: String) {
    AlarmScheduler.createChannelFromJson(activity, channelJson)
    resolveJs(cbId, "{}")
  }

  @JavascriptInterface
  fun lnGetPending(cbId: String) {
    val json = AlarmScheduler.getPendingNotificationsJson(activity)
    resolveJs(cbId, json)
  }

  @JavascriptInterface
  fun lnCancel(cbId: String, idsArrayJson: String) {
    AlarmScheduler.cancelNotifications(activity, idsArrayJson)
    resolveJs(cbId, "{}")
  }

  @JavascriptInterface
  fun lnSchedule(cbId: String, notificationsArrayJson: String) {
    AlarmScheduler.scheduleNotifications(activity, notificationsArrayJson)
    resolveJs(cbId, "{\"notifications\":[]}")
  }

  @JavascriptInterface
  fun lnCheckPermissions(cbId: String) {
    val granted = hasPostNotifications()
    resolveJs(cbId, JSONObject().put("display", if (granted) "granted" else "denied").toString())
  }

  @JavascriptInterface
  fun lnRequestPermissions(cbId: String) {
    if (hasPostNotifications()) {
      resolveJs(cbId, "{\"display\":\"granted\"}")
    } else {
      onRequestPostNotifications(cbId)
    }
  }

  @JavascriptInterface
  fun lnCheckExactSetting(cbId: String) {
    val granted = AlarmScheduler.canScheduleExactAlarms(activity)
    resolveJs(cbId, JSONObject().put("exact_alarm", if (granted) "granted" else "denied").toString())
  }

  @JavascriptInterface
  fun lnChangeExactSetting(cbId: String) {
    onRequestExactAlarms(cbId)
  }

  // ---- GoogleAuth ----

  @JavascriptInterface
  fun getDefaultWebClientId(): String {
    return googleAuthHelper.defaultWebClientId()
  }

  @JavascriptInterface
  fun gaInitialize(cbId: String, optionsJson: String) {
    try {
      val obj = JSONObject(optionsJson)
      val cid = obj.optString("clientId", "")
      val scopesArr = obj.optJSONArray("scopes") ?: JSONArray()
      val scopes = mutableListOf<String>()
      for (i in 0 until scopesArr.length()) {
        val s = scopesArr.optString(i)
        if (s.isNotBlank()) scopes.add(s)
      }
      googleAuthHelper.configure(cid, scopes)
      resolveJs(cbId, "{}")
    } catch (e: Exception) {
      rejectJs(cbId, e.message ?: "init_error")
    }
  }

  @JavascriptInterface
  fun gaSignIn(cbId: String) {
    scope.launch(Dispatchers.Main) {
      try {
        val res = googleAuthHelper.signIn()
        resolveJs(cbId, res.toString())
      } catch (e: Exception) {
        rejectJs(cbId, e.message ?: "auth_failed")
      }
    }
  }

  @JavascriptInterface
  fun gaRefresh(cbId: String) {
    scope.launch(Dispatchers.Main) {
      try {
        val res = googleAuthHelper.refresh()
        resolveJs(cbId, res.toString())
      } catch (e: Exception) {
        rejectJs(cbId, e.message ?: "refresh_failed")
      }
    }
  }

  @JavascriptInterface
  fun gaSignOut(cbId: String) {
    scope.launch(Dispatchers.Main) {
      googleAuthHelper.signOut()
      resolveJs(cbId, "{}")
    }
  }

  // ---- Filesystem & Share ----

  @JavascriptInterface
  fun fsWriteFile(cbId: String, optsJson: String) {
    try {
      val obj = JSONObject(optsJson)
      val path = obj.optString("path", "file.txt").substringAfterLast('/')
      val data = obj.optString("data", "")
      val dirName = obj.optString("directory", "CACHE").uppercase()
      val targetDir = when (dirName) {
        "DOCUMENTS", "DATA" -> File(activity.filesDir, "exports")
        else -> File(activity.cacheDir, "share")
      }
      if (!targetDir.exists()) targetDir.mkdirs()
      val outFile = File(targetDir, path)
      val textToWrite = if (path.endsWith(".json", ignoreCase = true)) data else "\uFEFF$data"
      outFile.writeText(textToWrite, Charsets.UTF_8)
      val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", outFile)
      resolveJs(cbId, JSONObject().put("uri", uri.toString()).put("path", outFile.absolutePath).toString())
    } catch (e: Exception) {
      rejectJs(cbId, e.message ?: "write_failed")
    }
  }

  @JavascriptInterface
  fun shareFile(cbId: String, optsJson: String) {
    try {
      val obj = JSONObject(optsJson)
      val title = obj.optString("title", "Share")
      val urlStr = obj.optString("url", "")
      val mime = obj.optString("mime", "*/*")
      val uri = Uri.parse(urlStr)
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      val chooser = Intent.createChooser(sendIntent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      activity.runOnUiThread {
        activity.startActivity(chooser)
      }
      resolveJs(cbId, "{}")
    } catch (e: Exception) {
      rejectJs(cbId, e.message ?: "share_failed")
    }
  }

  @JavascriptInterface
  fun saveToDownloads(cbId: String, fileName: String, data: String, mimeType: String) {
    val isDoc = !fileName.endsWith(".json", ignoreCase = true) &&
      (mimeType.contains("msword") || fileName.endsWith(".doc", ignoreCase = true))
    val payload = if (isDoc) "\uFEFF$data" else data

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val attempts = listOf(
        Pair(mimeType, Environment.DIRECTORY_DOWNLOADS + "/ImanOTaqwa"),
        Pair("application/octet-stream", Environment.DIRECTORY_DOWNLOADS)
      )
      for ((tryMime, tryRelPath) in attempts) {
        try {
          val resolver = activity.contentResolver
          val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, tryMime)
            put(MediaStore.Downloads.RELATIVE_PATH, tryRelPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
          }
          val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: continue
          resolver.openOutputStream(uri)?.use { os ->
            os.write(payload.toByteArray(Charsets.UTF_8))
          } ?: throw IllegalStateException("Could not open output stream")
          val doneValues = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
          }
          resolver.update(uri, doneValues, null, null)
          resolveJs(cbId, JSONObject().put("uri", uri.toString()).toString())
          return
        } catch (_: Exception) {
          // Try next MediaStore attempt or fall through to SAF CreateDocument
        }
      }
    }
    activity.runOnUiThread {
      onCreateDocument(fileName, mimeType, payload, cbId)
    }
  }

  // ---- Permissions & System Settings ----

  @JavascriptInterface
  fun getPermissionStatus(): String {
    val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    val fullScreenOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      nm.canUseFullScreenIntent()
    } else {
      true
    }
    val batteryOk = pm.isIgnoringBatteryOptimizations(activity.packageName)
    val dndOk = nm.isNotificationPolicyAccessGranted

    return JSONObject().apply {
      put("postNotifications", hasPostNotifications())
      put("exactAlarm", AlarmScheduler.canScheduleExactAlarms(activity))
      put("fullScreenIntent", fullScreenOk)
      put("batteryUnrestricted", batteryOk)
      put("dndAccess", dndOk)
      put("photoPicker", true)
      put("audioPicker", true)
      put("sdkInt", Build.VERSION.SDK_INT)
    }.toString()
  }

  @JavascriptInterface
  fun openFullScreenIntentSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
        data = Uri.parse("package:${activity.packageName}")
      }
      safeStartSettings(intent)
    }
  }

  @JavascriptInterface
  fun openBatteryOptimizationSettings() {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
      data = Uri.parse("package:${activity.packageName}")
    }
    if (!safeStartSettings(direct)) {
      safeStartSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
  }

  @JavascriptInterface
  fun openDndSettings() {
    safeStartSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
  }

  @JavascriptInterface
  fun setStatusBarTheme(theme: String) {
    activity.runOnUiThread {
      onThemeChange(theme)
    }
  }

  @JavascriptInterface
  fun exitApp() {
    activity.runOnUiThread {
      activity.finish()
    }
  }

  private fun hasPostNotifications(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  private fun safeStartSettings(intent: Intent): Boolean {
    return try {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      activity.startActivity(intent)
      true
    } catch (_: Exception) {
      false
    }
  }
}
