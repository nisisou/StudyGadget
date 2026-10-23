package com.example.studycompanion.di

import com.example.studycompanion.core.AppClock
import com.example.studycompanion.core.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppClock(): AppClock = SystemClock()
}
