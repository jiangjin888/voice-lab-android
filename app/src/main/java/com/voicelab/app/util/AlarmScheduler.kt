package com.voicelab.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.voicelab.app.data.TaskEntity
import com.voicelab.app.receiver.AlarmReceiver
import java.util.Calendar

object AlarmScheduler {

    /** 设置精确后台闹钟（到点弹出通知提醒） */
    fun schedule(context: Context, task: TaskEntity) {
        val time = task.scheduledTime ?: return
        val pi = buildPi(context, task.id, "reminder", task.content, task.id.toInt())
        setExact(context, time, pi)
    }

    /** 设置“计划日次日 09:00”的跟进闹钟，触发通知追问完成情况 */
    fun scheduleFollowup(context: Context, task: TaskEntity) {
        val base = task.scheduledTime ?: return
        val cal = Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pi = buildPi(context, task.id, "followup", task.content, (task.id + 200000).toInt())
        setExact(context, cal.timeInMillis, pi)
    }

    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf("reminder", "followup").forEachIndexed { idx, act ->
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = act }
            val req = if (idx == 0) taskId.toInt() else (taskId + 200000).toInt()
            val pi = PendingIntent.getBroadcast(
                context, req, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }

    private fun buildPi(
        context: Context, taskId: Long, action: String, content: String, reqCode: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra("taskId", taskId)
            putExtra("content", content)
        }
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setExact(context: Context, time: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setWindow(AlarmManager.RTC_WAKEUP, time, 60_000, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
    }
}
