package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, id ASC LIMIT 100")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String): Int

    @Query("UPDATE messages SET text = REPLACE(text, :wrong, :correct) WHERE text LIKE '%' || :wrong || '%'")
    suspend fun replaceTextGlobal(wrong: String, correct: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE role = :role AND text = :text AND timestamp = :timestamp LIMIT 1)")
    suspend fun existsExact(role: String, text: String, timestamp: Long): Boolean

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM messages " +
            "WHERE role = :role AND text = :text AND timestamp BETWEEN :fromTs AND :toTs LIMIT 1" +
            ")"
    )
    suspend fun existsWithinWindow(role: String, text: String, fromTs: Long, toTs: Long): Boolean

    @Query(
        """
        DELETE FROM messages
        WHERE id NOT IN (
            SELECT MIN(id)
            FROM messages
            GROUP BY role, text, timestamp
        )
        """
    )
    suspend fun deleteExactDuplicates(): Int

    @Query("DELETE FROM messages")
    suspend fun clearAll()
}
