package com.voicelab.app.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicelab.app.VoiceLabApp
import com.voicelab.app.util.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("taskId", -1L)
        when (intent.action) {
            "ACTION_DONE" -> {
                if (taskId != -1L) {
                    CoroutineScope(Dispatchers.IO).launch {
                        VoiceLabApp.instance.database.taskDao().updateStatus(taskId, "done")
                    }
                    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.cancel(taskId.toInt())
                }
            }
            else -> {
                val content = intent.getStringExtra("content") ?: "时间到了，请查看实验任务"
                NotificationUtils.showReminder(context, taskId, content)
            }
        }
    }
}
