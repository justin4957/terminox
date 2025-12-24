package com.terminox.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.terminox.data.local.database.AppDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.ConnectionEventDao
import com.terminox.data.local.database.dao.Ec2InstanceDao
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

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create EC2 instances table
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ec2_instances (
                    instanceId TEXT PRIMARY KEY NOT NULL,
                    region TEXT NOT NULL,
                    instanceType TEXT NOT NULL,
                    publicIp TEXT,
                    state TEXT NOT NULL,
                    connectionId TEXT,
                    sshKeyId TEXT NOT NULL,
                    launchedAt INTEGER NOT NULL,
                    lastActivityAt INTEGER NOT NULL,
                    autoTerminateAfterMinutes INTEGER NOT NULL,
                    isSpotInstance INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

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
            .addMigrations(MIGRATION_6_7)
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

    @Provides
    fun provideEc2InstanceDao(database: AppDatabase): Ec2InstanceDao {
        return database.ec2InstanceDao()
    }
}
