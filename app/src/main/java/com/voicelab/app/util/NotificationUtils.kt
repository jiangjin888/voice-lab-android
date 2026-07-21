package com.voicelab.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.voicelab.app.R
import com.voicelab.app.receiver.AlarmReceiver

object NotificationUtils {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val id = context.getString(R.string.notif_channel_id)
            if (mgr.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(
                    id,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun showReminder(context: Context, taskId: Long, content: String) {
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = PendingIntent.getActivity(
            context, taskId.toInt(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val doneIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "ACTION_DONE"
            putExtra("taskId", taskId)
        }
        val donePi = PendingIntent.getBroadcast(
            context, (taskId + 100000).toInt(), doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, context.getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("⏰ 实验提醒")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_save, "完成", donePi)
            .build()
        mgr.notify(taskId.toInt(), notif)
    }
}
