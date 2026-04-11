package com.lifelog.phone.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey
    val id: Long,
    val title: String,
    val kind: String,
    val anchorKey: String,
    val hourBucket: Int,
    val weekdays: String,
    val note: String,
    val confidence: Double,
    val source: String,
    val active: Boolean,
    val occurrences: Int,
    val firstSeenTs: String,
    val lastSeenTs: String,
    val updatedAt: String,
    val createdAt: String,
    val display: String
)
