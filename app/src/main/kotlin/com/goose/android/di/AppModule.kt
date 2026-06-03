package com.goose.android.di

import android.content.Context
import androidx.room.Room
import com.goose.android.data.db.GooseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideGooseDatabase(
        @ApplicationContext context: Context,
    ): GooseDatabase =
        Room
            .databaseBuilder(context, GooseDatabase::class.java, "goose.db")
            .addMigrations(GooseDatabase.MIGRATION_1_2)
            .build()
}
