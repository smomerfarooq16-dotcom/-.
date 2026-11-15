package com.example.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.R

class AlarmRingActivity : ComponentActivity() {
  private var alarmId: Int = 1

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
    }

    alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ID, 1)
    val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: getString(R.string.alarm_default_title)
    val body = intent.getStringExtra(AlarmScheduler.EXTRA_BODY) ?: ""
    val channelId = intent.getStringExtra(AlarmScheduler.EXTRA_CHANNEL) ?: "iman_bell"
    val sound = intent.getStringExtra(AlarmScheduler.EXTRA_SOUND) ?: "bell.wav"
    val snoozeMin = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_MIN, 10)
    val vibrate = intent.getBooleanExtra(AlarmScheduler.EXTRA_VIBRATE, true)

    AlarmSoundPlayer.start(this, sound, vibrate)

    setContent {
      AlarmRingScreen(
        title = title,
        body = body,
        snoozeMin = snoozeMin,
        onDismiss = {
          stopAndClearNotification()
          finish()
        },
        onSnooze = {
          stopAndClearNotification()
          AlarmScheduler.scheduleSnooze(this, title, body, channelId, sound, snoozeMin, vibrate)
          finish()
        },
        onOpenTasks = {
          stopAndClearNotification()
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
          }
          startActivity(
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
              putExtra("open_tab", "home")
            }
          )
          finish()
        }
      )
    }
  }

  private fun stopAndClearNotification() {
    AlarmSoundPlayer.stop()
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(alarmId)
  }

  override fun onDestroy() {
    AlarmSoundPlayer.stop()
    super.onDestroy()
  }
}

@Composable
private fun AlarmRingScreen(
  title: String,
  body: String,
  snoozeMin: Int,
  onDismiss: () -> Unit,
  onSnooze: () -> Unit,
  onOpenTasks: () -> Unit
) {
  val bg = Color(0xFF0B3D2E)
  val cream = Color(0xFFFFF8E7)
  val gold = Color(0xFFC9A227)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(bg)
      .safeDrawingPadding()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Icon(
      imageVector = Icons.Filled.Alarm,
      contentDescription = title,
      tint = gold,
      modifier = Modifier.size(72.dp)
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
      text = stringResource(R.string.app_name),
      color = gold,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = title,
      color = cream,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center
    )
    if (body.isNotBlank()) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = body,
        color = cream.copy(alpha = 0.88f),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
      )
    }
    Spacer(modifier = Modifier.height(36.dp))
    Button(
      onClick = onDismiss,
      modifier = Modifier
        .fillMaxWidth()
        .height(58.dp)
        .testTag("alarm_dismiss_button"),
      shape = RoundedCornerShape(18.dp),
      colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = bg)
    ) {
      Text(
        text = stringResource(R.string.alarm_dismiss),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
      )
    }
    Spacer(modifier = Modifier.height(14.dp))
    OutlinedButton(
      onClick = onSnooze,
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .testTag("alarm_snooze_button"),
      shape = RoundedCornerShape(18.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = cream)
    ) {
      Text(
        text = "${stringResource(R.string.alarm_snooze)} · $snoozeMin min",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
    Spacer(modifier = Modifier.height(14.dp))
    OutlinedButton(
      onClick = onOpenTasks,
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .testTag("alarm_open_tasks_button"),
      shape = RoundedCornerShape(18.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = gold)
    ) {
      Text(
        text = stringResource(R.string.alarm_open_tasks),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}
