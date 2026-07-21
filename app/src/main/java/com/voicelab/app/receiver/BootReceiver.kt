package com.voicelab.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voicelab.app.VoiceLabApp
import com.voicelab.app.util.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CoroutineScope(Dispatchers.IO).launch {
                val tasks = VoiceLabApp.instance.database.taskDao().getAllTasks()
                val now = System.currentTimeMillis()
                tasks.filter { (it.scheduledTime ?: 0) > now && it.status == "todo" }
                    .forEach { AlarmScheduler.schedule(context, it) }
            }
        }
    }
}
