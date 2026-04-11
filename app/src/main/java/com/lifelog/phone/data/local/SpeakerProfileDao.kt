package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpeakerProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SpeakerProfileEntity)

    @Query("SELECT * FROM speaker_profiles ORDER BY updatedAtMs DESC")
    suspend fun getAll(): List<SpeakerProfileEntity>

    @Query("SELECT * FROM speaker_profiles WHERE clusterId = :clusterId LIMIT 1")
    suspend fun getByClusterId(clusterId: String): SpeakerProfileEntity?
}
