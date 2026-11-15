package com.example.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, 1)

    when (intent.action) {
      AlarmScheduler.ACTION_ALARM_DISMISS -> {
        AlarmSoundPlayer.stop()
        nm.cancel(id)
        return
      }
      AlarmScheduler.ACTION_ALARM_SNOOZE -> {
        AlarmSoundPlayer.stop()
        nm.cancel(id)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE)
          ?: context.getString(R.string.alarm_default_title)
        val body = intent.getStringExtra(AlarmScheduler.EXTRA_BODY) ?: ""
        val channel = intent.getStringExtra(AlarmScheduler.EXTRA_CHANNEL) ?: "iman_bell"
        val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: "bell.wav"
        val snoozeMin = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_MIN, 10)
        val vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true)
        AlarmScheduler.scheduleSnooze(context, title, body, channel, sound, snoozeMin, vibrate)
        return
      }
    }

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imanotaqwa:AlarmWakeLock")
    try {
      wl.acquire(10_000L)
      AlarmScheduler.ensureDefaultChannels(context)
      AlarmScheduler.removeAlarmById(context, id)

      val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE)
        ?: context.getString(R.string.alarm_default_title)
      val body = intent.getStringExtra(AlarmScheduler.EXTRA_BODY) ?: ""
      val channelId = intent.getStringExtra(AlarmScheduler.EXTRA_CHANNEL) ?: "iman_bell"
      val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: "bell.wav"
      val snoozeMin = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_MIN, 10)
      val vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true)

      val openHomeIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("open_tab", "home")
      }
      val openHomePi = PendingIntent.getActivity(
        context,
        id * 10 + 1,
        openHomeIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val fullScreenIntent = Intent(context, AlarmRingActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_TITLE, title)
        putExtra(AlarmScheduler.EXTRA_BODY, body)
        putExtra(AlarmScheduler.EXTRA_CHANNEL, channelId)
        putExtra(AlarmScheduler.EXTRA_SOUND, sound)
        putExtra(AlarmScheduler.EXTRA_SNOOZE_MIN, snoozeMin)
        putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
      }
      val fullScreenPi = PendingIntent.getActivity(
        context,
        id * 10 + 2,
        fullScreenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val dismissIntent = Intent(context, AlarmReceiver::class.java).apply {
        action = AlarmScheduler.ACTION_ALARM_DISMISS
        putExtra(AlarmScheduler.EXTRA_ID, id)
      }
      val dismissPi = PendingIntent.getBroadcast(
        context,
        id * 10 + 3,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val snoozeIntent = Intent(context, AlarmReceiver::class.java).apply {
        action = AlarmScheduler.ACTION_ALARM_SNOOZE
        putExtra(AlarmScheduler.EXTRA_ID, id)
        putExtra(AlarmScheduler.EXTRA_TITLE, title)
        putExtra(AlarmScheduler.EXTRA_BODY, body)
        putExtra(AlarmScheduler.EXTRA_CHANNEL, channelId)
        putExtra(AlarmScheduler.EXTRA_SOUND, sound)
        putExtra(AlarmScheduler.EXTRA_SNOOZE_MIN, snoozeMin)
        putExtra(AlarmScheduler.EXTRA_VIBRATE, vibrate)
      }
      val snoozePi = PendingIntent.getBroadcast(
        context,
        id * 10 + 4,
        snoozeIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )

      val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_logo_foreground)
        .setContentTitle(title)
        .setContentText(body.ifBlank { context.getString(R.string.app_name) })
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setContentIntent(openHomePi)
        .setFullScreenIntent(fullScreenPi, true)
        .addAction(0, context.getString(R.string.alarm_dismiss), dismissPi)
        .addAction(0, "${context.getString(R.string.alarm_snooze)} (${snoozeMin}m)", snoozePi)
        .build()

      nm.notify(id, notification)

      val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
      if (!pm.isInteractive || km.isKeyguardLocked) {
        try {
          context.startActivity(fullScreenIntent)
        } catch (_: Exception) {
          // Full-screen intent notification handles wake on restricted devices
        }
      }
    } finally {
      if (wl.isHeld) wl.release()
    }
  }
}
