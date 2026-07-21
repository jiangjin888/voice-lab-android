package com.voicelab.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Sentence::class, TaskEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): AppDao
}
