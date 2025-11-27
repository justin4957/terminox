package com.terminox.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.SshKeyDao
import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.data.local.database.entity.SshKeyEntity

@Database(
    entities = [ConnectionEntity::class, SshKeyEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun sshKeyDao(): SshKeyDao
}
