package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: FactEntity)

    @Query("SELECT * FROM facts ORDER BY timestamp DESC LIMIT 500")
    suspend fun getAll(): List<FactEntity>

    @Query("SELECT * FROM facts WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun search(query: String): List<FactEntity>

    @Query("DELETE FROM facts")
    suspend fun clearAll()
}
