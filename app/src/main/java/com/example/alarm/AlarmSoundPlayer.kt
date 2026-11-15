package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.R

object AlarmSoundPlayer {
  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null

  fun start(context: Context, soundName: String, vibrate: Boolean) {
    stop()
    val rawRes = resolveRawRes(soundName)
    try {
      val uri = Uri.parse("android.resource://${context.packageName}/$rawRes")
      val mp = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        setDataSource(context, uri)
        isLooping = true
        prepare()
        start()
      }
      mediaPlayer = mp
    } catch (_: Exception) {
      // Fallback ignored if audio device unavailable
    }

    if (vibrate) {
      try {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
          vm?.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = vib
        val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
          @Suppress("DEPRECATION")
          vib?.vibrate(pattern, 0)
        }
      } catch (_: Exception) {
        // Ignore vibrator errors on devices without hardware vibrator
      }
    }
  }

  fun stop() {
    try {
      mediaPlayer?.apply {
        if (isPlaying) stop()
        release()
      }
    } catch (_: Exception) {
    }
    mediaPlayer = null

    try {
      vibrator?.cancel()
    } catch (_: Exception) {
    }
    vibrator = null
  }

  fun resolveRawRes(soundName: String): Int {
    val clean = soundName.lowercase().removeSuffix(".wav").removePrefix("iman_")
    return when (clean) {
      "beep" -> R.raw.beep
      "chime" -> R.raw.chime
      "soft" -> R.raw.soft
      else -> R.raw.bell
    }
  }
}
