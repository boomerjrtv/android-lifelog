package com.lifelog.phone.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey
    val clusterId: String,
    val displayName: String,
    val embedding: String = "",
    val sampleCount: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
