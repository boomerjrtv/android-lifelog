package com.lifelog.phone.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        FactEntity::class,
        PhoneLogEntity::class,
        SpeakerProfileEntity::class,
        CalendarEventEntity::class,
        RoutineEntity::class,
    ],
    version = 5
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun factDao(): FactDao
    abstract fun phoneLogDao(): PhoneLogDao
    abstract fun speakerProfileDao(): SpeakerProfileDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun routineDao(): RoutineDao
}
