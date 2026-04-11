package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PhoneLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PhoneLogEntity>)

    @Query("SELECT * FROM phone_logs ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PhoneLogEntity>

    @Query(
        "SELECT * FROM phone_logs " +
            "WHERE kind LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR speakerId LIKE '%' || :query || '%' OR speakerClusterId LIKE '%' || :query || '%' " +
            "ORDER BY ts DESC, id DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<PhoneLogEntity>

    @Query("SELECT * FROM phone_logs WHERE kind = :kind ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun getByKind(kind: String, limit: Int): List<PhoneLogEntity>

    @Query("SELECT * FROM phone_logs WHERE kind = :kind ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeByKind(kind: String, limit: Int): Flow<List<PhoneLogEntity>>

    @Query("UPDATE phone_logs SET speakerId = :speakerId, speakerConfidence = :speakerConfidence WHERE id = :id")
    suspend fun updateSpeakerById(id: Long, speakerId: String, speakerConfidence: Double)

    @Query("UPDATE phone_logs SET speakerId = :speakerId, speakerConfidence = :speakerConfidence, speakerClusterId = :speakerClusterId WHERE id = :id")
    suspend fun updateSpeakerAndClusterById(
        id: Long,
        speakerId: String,
        speakerConfidence: Double,
        speakerClusterId: String,
    )

    @Query("UPDATE phone_logs SET speakerId = :speakerId, speakerConfidence = :speakerConfidence WHERE speakerClusterId = :speakerClusterId")
    suspend fun updateSpeakerByClusterId(
        speakerClusterId: String,
        speakerId: String,
        speakerConfidence: Double,
    )

    @Query("UPDATE phone_logs SET speakerClusterId = :speakerClusterId, speakerConfidence = :speakerConfidence WHERE id = :id")
    suspend fun updateClusterById(id: Long, speakerClusterId: String, speakerConfidence: Double)

    @Query("UPDATE phone_logs SET speakerId = :newSpeakerId, speakerConfidence = :speakerConfidence WHERE lower(trim(speakerId)) = lower(trim(:oldSpeakerId))")
    suspend fun relabelSpeaker(oldSpeakerId: String, newSpeakerId: String, speakerConfidence: Double)

    @Query("DELETE FROM phone_logs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM phone_logs")
    suspend fun clearAll()
}
