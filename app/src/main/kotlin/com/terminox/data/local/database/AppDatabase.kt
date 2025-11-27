package com.terminox.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.entity.ConnectionEntity

@Database(
    entities = [ConnectionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
}
