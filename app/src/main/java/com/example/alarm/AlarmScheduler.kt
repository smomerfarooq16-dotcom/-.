package com.example.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.example.MainActivity
import com.example.R
import org.json.JSONArray
import org.json.JSONObject

object AlarmScheduler {
  private const val PREFS_NAME = "iman_alarm_prefs"
  private const val KEY_ALARMS = "scheduled_alarms_json"
  const val ACTION_ALARM_FIRED = "com.smof.imanotaqwa.ACTION_ALARM_FIRED"
  const val ACTION_ALARM_DISMISS = "com.smof.imanotaqwa.ACTION_ALARM_DISMISS"
  const val ACTION_ALARM_SNOOZE = "com.smof.imanotaqwa.ACTION_ALARM_SNOOZE"

  const val EXTRA_ID = "alarm_id"
  const val EXTRA_TITLE = "alarm_title"
  const val EXTRA_BODY = "alarm_body"
  const val EXTRA_CHANNEL = "alarm_channel"
  const val EXTRA_SOUND = "alarm_sound"
  const val EXTRA_SNOOZE_MIN = "alarm_snooze_min"
  const val EXTRA_VIBRATE = "alarm_vibrate"

  fun ensureDefaultChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channels = listOf(
      Triple("iman_bell", context.getString(R.string.channel_bell_name), R.raw.bell),
      Triple("iman_beep", context.getString(R.string.channel_beep_name), R.raw.beep),
      Triple("iman_chime", context.getString(R.string.channel_chime_name), R.raw.chime),
      Triple("iman_soft", context.getString(R.string.channel_soft_name), R.raw.soft)
    )
    val attrs = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ALARM)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()

    channels.forEach { (id, name, rawRes) ->
      val soundUri = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$rawRes")
      val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 500, 250, 500)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
        setSound(soundUri, attrs)
      }
      nm.createNotificationChannel(ch)
    }
  }

  fun createChannelFromJson(context: Context, jsonStr: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    ensureDefaultChannels(context)
    try {
      val obj = JSONObject(jsonStr)
      val id = obj.optString("id", "iman_bell")
      val name = obj.optString("name", id)
      val soundName = obj.optString("sound", "bell.wav")
      val vibrate = obj.optBoolean("vibration", true)
      val rawRes = AlarmSoundPlayer.resolveRawRes(soundName)
      val soundUri = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$rawRes")
      val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(vibrate)
        if (vibrate) vibrationPattern = longArrayOf(0, 500, 250, 500)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDnd(true)
        setSound(soundUri, attrs)
      }
      nm.createNotificationChannel(ch)
    } catch (_: Exception) {
    }
  }

  fun scheduleNotifications(context: Context, notificationsArrayJson: String) {
    ensureDefaultChannels(context)
    val incoming = try {
      JSONArray(notificationsArrayJson)
    } catch (_: Exception) {
      return
    }

    val existingMap = loadMap(context).toMutableMap()
    val now = System.currentTimeMillis()
    for (i in 0 until incoming.length()) {
      val item = incoming.optJSONObject(i) ?: continue
      val id = item.optInt("id", (i + 1))
      val triggerAt = item.optLong("triggerAt", 0L)
      if (triggerAt <= now - 5_000L) continue
      val entry = JSONObject().apply {
        put("id", id)
        put("triggerAt", triggerAt)
        put("title", item.optString("title", context.getString(R.string.alarm_default_title)))
        put("body", item.optString("body", ""))
        put("channelId", item.optString("channelId", "iman_bell"))
        put("sound", item.optString("sound", "bell.wav"))
        put("snoozeMin", item.optInt("snoozeMin", 10))
        put("vibrate", item.optBoolean("vibrate", true))
      }
      existingMap[id] = entry
      armAlarm(context, entry)
    }
    saveMap(context, existingMap)
  }

  fun scheduleSnooze(
    context: Context,
    title: String,
    body: String,
    channelId: String,
    sound: String,
    snoozeMin: Int,
    vibrate: Boolean
  ) {
    val triggerAt = System.currentTimeMillis() + snoozeMin.coerceAtLeast(1) * 60_000L
    val id = 9000 + ((System.currentTimeMillis() / 1000L) % 900).toInt()
    val entry = JSONObject().apply {
      put("id", id)
      put("triggerAt", triggerAt)
      put("title", title)
      put("body", body)
      put("channelId", channelId)
      put("sound", sound)
      put("snoozeMin", snoozeMin)
      put("vibrate", vibrate)
    }
    val map = loadMap(context).toMutableMap()
    map[id] = entry
    saveMap(context, map)
    armAlarm(context, entry)
  }

  fun cancelNotifications(context: Context, idsArrayJson: String) {
    val arr = try {
      JSONArray(idsArrayJson)
    } catch (_: Exception) {
      return
    }
    val map = loadMap(context).toMutableMap()
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    for (i in 0 until arr.length()) {
      val opt = arr.opt(i)
      val id = when (opt) {
        is JSONObject -> opt.optInt("id", -1)
        is Number -> opt.toInt()
        else -> -1
      }
      if (id >= 0) {
        val pi = buildPendingIntent(context, id, null, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) {
          am.cancel(pi)
          pi.cancel()
        }
        map.remove(id)
      }
    }
    saveMap(context, map)
  }

  fun removeAlarmById(context: Context, id: Int) {
    val map = loadMap(context).toMutableMap()
    if (map.remove(id) != null) {
      saveMap(context, map)
    }
  }

  fun getPendingNotificationsJson(context: Context): String {
    val map = loadMap(context)
    val now = System.currentTimeMillis()
    val outArr = JSONArray()
    val cleaned = mutableMapOf<Int, JSONObject>()
    map.forEach { (id, obj) ->
      if (obj.optLong("triggerAt", 0L) > now - 60_000L) {
        cleaned[id] = obj
        outArr.put(JSONObject().apply {
          put("id", id)
          put("title", obj.optString("title"))
          put("body", obj.optString("body"))
          put("channelId", obj.optString("channelId"))
          put("triggerAt", obj.optLong("triggerAt"))
        })
      }
    }
    if (cleaned.size != map.size) {
      saveMap(context, cleaned)
    }
    return JSONObject().put("notifications", outArr).toString()
  }

  fun rescheduleAll(context: Context) {
    ensureDefaultChannels(context)
    val map = loadMap(context)
    val now = System.currentTimeMillis()
    val active = mutableMapOf<Int, JSONObject>()
    map.forEach { (id, obj) ->
      if (obj.optLong("triggerAt", 0L) > now - 30_000L) {
        active[id] = obj
        armAlarm(context, obj)
      }
    }
    saveMap(context, active)
  }

  fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
  }

  private fun armAlarm(context: Context, entry: JSONObject) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val id = entry.optInt("id", 1)
    val triggerAt = entry.optLong("triggerAt", System.currentTimeMillis() + 60_000L)
    val pi = buildPendingIntent(
      context,
      id,
      entry,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ) ?: return

    val showIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra("open_tab", "home")
    }
    val showPi = PendingIntent.getActivity(
      context,
      0,
      showIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    try {
      if (canScheduleExactAlarms(context)) {
        val info = AlarmManager.AlarmClockInfo(triggerAt, showPi)
        am.setAlarmClock(info, pi)
      } else {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
      }
    } catch (_: SecurityException) {
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
  }

  private fun buildPendingIntent(
    context: Context,
    id: Int,
    entry: JSONObject?,
    flags: Int
  ): PendingIntent? {
    val intent = Intent(context, AlarmReceiver::class.java).apply {
      action = ACTION_ALARM_FIRED
      putExtra(EXTRA_ID, id)
      if (entry != null) {
        putExtra(EXTRA_TITLE, entry.optString("title"))
        putExtra(EXTRA_BODY, entry.optString("body"))
        putExtra(EXTRA_CHANNEL, entry.optString("channelId", "iman_bell"))
        putExtra(EXTRA_SOUND, entry.optString("sound", "bell.wav"))
        putExtra(EXTRA_SNOOZE_MIN, entry.optInt("snoozeMin", 10))
        putExtra(EXTRA_VIBRATE, entry.optBoolean("vibrate", true))
      }
    }
    return PendingIntent.getBroadcast(context, id, intent, flags)
  }

  private fun loadMap(context: Context): Map<Int, JSONObject> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_ALARMS, "[]") ?: "[]"
    val arr = try {
      JSONArray(raw)
    } catch (_: Exception) {
      JSONArray()
    }
    val map = mutableMapOf<Int, JSONObject>()
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      map[obj.optInt("id", i)] = obj
    }
    return map
  }

  private fun saveMap(context: Context, map: Map<Int, JSONObject>) {
    val arr = JSONArray()
    map.values.forEach { arr.put(it) }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_ALARMS, arr.toString())
      .apply()
  }
}
