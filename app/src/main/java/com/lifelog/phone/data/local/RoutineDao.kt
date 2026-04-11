package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RoutineEntity>)

    @Query("SELECT * FROM routines ORDER BY active DESC, confidence DESC, updatedAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int): List<RoutineEntity>

    @Query(
        "SELECT * FROM routines " +
            "WHERE title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%' OR kind LIKE '%' || :query || '%' OR display LIKE '%' || :query || '%' " +
            "ORDER BY active DESC, confidence DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<RoutineEntity>

    @Query("DELETE FROM routines")
    suspend fun clearAll()
}
