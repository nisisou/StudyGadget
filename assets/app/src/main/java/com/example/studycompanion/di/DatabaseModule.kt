package com.example.studycompanion.di

import android.content.Context
import androidx.room.Room
import com.example.studycompanion.data.db.AppDatabase
import com.example.studycompanion.data.db.SessionDao
import com.example.studycompanion.data.db.SubjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        subjectDaoProvider: Provider<SubjectDao>
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "study_companion.db"
        )
        .addCallback(AppDatabase.getCallback(subjectDaoProvider))
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideSessionDao(database: AppDatabase): SessionDao = database.sessionDao()

    @Provides
    fun provideSubjectDao(database: AppDatabase): SubjectDao = database.subjectDao()
}
