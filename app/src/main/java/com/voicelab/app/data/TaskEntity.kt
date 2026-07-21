package com.voicelab.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val scheduledTime: Long?,   // epoch millis, null = 无计划时间
    val scheduledText: String?, // 展示用，如 "7月21日(周二) 09:00"
    var status: String,         // todo / done / fail
    val expStart: String?,      // 实验开始 HH:mm
    val expEnd: String?,        // 实验结束 HH:mm
    val source: String,         // voice / text
    val created: Long,
    val reminded: Boolean = false
)
