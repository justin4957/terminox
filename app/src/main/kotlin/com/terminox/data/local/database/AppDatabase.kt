package com.terminox.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.dao.ConnectionEventDao
import com.terminox.data.local.database.dao.PairedAgentDao
import com.terminox.data.local.database.dao.SshKeyDao
import com.terminox.data.local.database.dao.TrustedHostDao
import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.data.local.database.entity.ConnectionEventEntity
import com.terminox.data.local.database.entity.PairedAgentEntity
import com.terminox.data.local.database.entity.SshKeyEntity
import com.terminox.data.local.database.entity.TrustedHostEntity

@Database(
    entities = [
        ConnectionEntity::class,
        SshKeyEntity::class,
        TrustedHostEntity::class,
        ConnectionEventEntity::class,
        PairedAgentEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun trustedHostDao(): TrustedHostDao
    abstract fun connectionEventDao(): ConnectionEventDao
    abstract fun pairedAgentDao(): PairedAgentDao
}
