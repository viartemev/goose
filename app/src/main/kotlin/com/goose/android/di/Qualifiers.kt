package com.goose.android.di

import javax.inject.Qualifier

/** Marks the application-level [kotlinx.coroutines.CoroutineScope] (SupervisorJob + Dispatchers.IO). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
