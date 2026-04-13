package com.xkeen.android.di

import android.content.Context
import androidx.room.Room
import com.xkeen.android.data.local.AppDatabase
import com.xkeen.android.data.local.RouterProfileDao
import com.xkeen.android.data.remote.VlessParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "xkeen.db")
            .build()
    }

    @Provides
    fun provideRouterProfileDao(db: AppDatabase): RouterProfileDao = db.routerProfileDao()

    @Provides
    @Singleton
    fun provideVlessParser(): VlessParser = VlessParser()
}
