package com.lifelog.phone.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey
    val id: Long,
    val source: String,
    val remoteId: String,
    val calendarId: String,
    val title: String,
    val startTs: String,
    val endTs: String,
    val timezone: String,
    val recurrenceRule: String,
    val location: String,
    val notes: String,
    val isAllDay: Boolean,
    val status: String,
    val updatedAt: String,
    val display: String
)
