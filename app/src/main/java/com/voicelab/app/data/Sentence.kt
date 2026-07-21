package com.voicelab.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sentences")
data class Sentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String,      // "voice" / "text"
    val timestamp: Long      // epoch millis
)
