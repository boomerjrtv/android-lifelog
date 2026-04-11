package com.lifelog.phone.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phone_logs")
data class PhoneLogEntity(
    @PrimaryKey
    val id: Long,
    val ts: String,
    val deviceId: String,
    val kind: String,
    val text: String,
    val transcriptId: Long,
    val sourceType: String,
    val speakerId: String,
    val speakerConfidence: Double,
    val tags: String,
    val importance: Double,
    val mediaLikelihood: Double,
    val dialogDensity: Double,
    val source: String,
    val speakerClusterId: String = ""
)
