package com.deepseek.pet.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.deepseek.pet.DeepSeekPetApp
import com.deepseek.pet.MainActivity
import com.deepseek.pet.R
import com.deepseek.pet.model.Mood

/** 常驻通知栏入口：点通知回到 App，通知上的按钮直接刷新 / 显示隐藏悬浮窗。 */
object PetNotification {

    const val ID = 1001

    fun build(context: Context, mood: Mood, content: String): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refresh = PendingIntent.getService(
            context,
            1,
            Intent(context, FloatingPetService::class.java)
                .setAction(FloatingPetService.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggle = PendingIntent.getService(
            context,
            2,
            Intent(context, FloatingPetService::class.java)
                .setAction(FloatingPetService.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, DeepSeekPetApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(mood.title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.action_refresh), refresh)
            .addAction(0, context.getString(R.string.action_toggle), toggle)
            .build()
    }

    fun update(context: Context, mood: Mood, content: String) {
        if (!canPost(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(ID, build(context, mood, content))
        }
    }

    fun canPost(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
