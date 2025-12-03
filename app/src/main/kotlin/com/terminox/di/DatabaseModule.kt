package com.terminox.di

import android.content.Context
import androidx.room.Room
import com.terminox.data.local.database.AppDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.ConnectionEventDao
import com.terminox.data.local.database.dao.SshKeyDao
import com.terminox.data.local.database.dao.TrustedHostDao
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
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "terminox.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideConnectionDao(database: AppDatabase): ConnectionDao {
        return database.connectionDao()
    }

    @Provides
    fun provideSshKeyDao(database: AppDatabase): SshKeyDao {
        return database.sshKeyDao()
    }

    @Provides
    fun provideTrustedHostDao(database: AppDatabase): TrustedHostDao {
        return database.trustedHostDao()
    }

    @Provides
    fun provideConnectionEventDao(database: AppDatabase): ConnectionEventDao {
        return database.connectionEventDao()
    }
}
