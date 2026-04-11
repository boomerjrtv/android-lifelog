package com.lifelog.phone.data.local

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "lifelog.db"
        ).fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideFactDao(database: AppDatabase): FactDao = database.factDao()

    @Provides
    fun providePhoneLogDao(database: AppDatabase): PhoneLogDao = database.phoneLogDao()

    @Provides
    fun provideSpeakerProfileDao(database: AppDatabase): SpeakerProfileDao = database.speakerProfileDao()

    @Provides
    fun provideCalendarEventDao(database: AppDatabase): CalendarEventDao = database.calendarEventDao()

    @Provides
    fun provideRoutineDao(database: AppDatabase): RoutineDao = database.routineDao()
}
