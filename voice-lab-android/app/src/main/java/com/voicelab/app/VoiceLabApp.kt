package com.voicelab.app

import android.app.Application
import androidx.room.Room
import com.voicelab.app.data.AppDatabase

class VoiceLabApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = Room.databaseBuilder(this, AppDatabase::class.java, "voicelab.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    companion object {
        lateinit var instance: VoiceLabApp
            private set
    }
}
