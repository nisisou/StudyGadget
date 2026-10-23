package com.example.studycompanion.di

import com.example.studycompanion.domain.ext.SessionSink
import com.example.studycompanion.integrations.CsvExportSink
import com.example.studycompanion.integrations.StudyplusSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SinkModule {

    @Binds
    @IntoSet
    abstract fun bindCsvExportSink(sink: CsvExportSink): SessionSink

    @Binds
    @IntoSet
    abstract fun bindStudyplusSink(sink: StudyplusSink): SessionSink
}
