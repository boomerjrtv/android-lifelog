package com.lifelog.phone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CalendarEventEntity>)

    @Query("SELECT * FROM calendar_events ORDER BY startTs ASC, id ASC LIMIT :limit")
    suspend fun getAll(limit: Int): List<CalendarEventEntity>

    @Query(
        "SELECT * FROM calendar_events " +
            "WHERE title LIKE '%' || :query || '%' OR display LIKE '%' || :query || '%' OR location LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' " +
            "ORDER BY startTs ASC, id ASC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<CalendarEventEntity>

    @Query("DELETE FROM calendar_events")
    suspend fun clearAll()
}
