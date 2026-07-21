package com.voicelab.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AppDao {

    @Insert
    suspend fun insertSentence(s: Sentence)

    @Insert
    suspend fun insertTask(t: TaskEntity): Long

    @Query("SELECT * FROM tasks ORDER BY created DESC")
    suspend fun getAllTasks(): List<TaskEntity>

    @Query("SELECT * FROM sentences ORDER BY timestamp DESC")
    suspend fun getAllSentences(): List<Sentence>

    @Update
    suspend fun updateTask(t: TaskEntity)

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE tasks SET expStart = :v, expEnd = :v2 WHERE id = :id")
    suspend fun updateExp(id: Long, v: String?, v2: String?)

    @Delete
    suspend fun deleteTask(t: TaskEntity)
}
