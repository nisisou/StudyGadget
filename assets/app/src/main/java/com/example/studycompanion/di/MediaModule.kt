package com.example.studycompanion.di

import com.example.studycompanion.domain.ext.MediaBackend
import com.example.studycompanion.media.AudioManagerMediaBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @IntoSet
    abstract fun bindAudioManagerMediaBackend(backend: AudioManagerMediaBackend): MediaBackend
}
